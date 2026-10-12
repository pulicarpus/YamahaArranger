#!/usr/bin/env python3
"""Small synthetic SF2 parser tests; no original binary order is asserted."""
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import sf2_extract_original_phdr as phdr

ROOT=Path(__file__).resolve().parents[1]

def chunk(tag,body):return tag+struct.pack("<I",len(body))+body+(b"\0" if len(body)&1 else b"")
def fixture(presets,terminal=None,extra=b""):
    records=b"".join(phdr.HEADER.pack(name.encode("latin-1").ljust(20,b"\0"),pc,bank,i,0,0,0) for i,(bank,pc,name) in enumerate(presets))
    records+=terminal if terminal is not None else phdr.HEADER.pack(b"EOP"+b"\0"*17,0,0,len(presets),0,0,0)
    body=b"sfbk"+extra+chunk(b"LIST",b"pdta"+chunk(b"phdr",records))
    return b"RIFF"+struct.pack("<I",len(body))+body

class OriginalPhdrTests(unittest.TestCase):
    def test_unsorted_original_order_not_metadata_sort_order(self):
        rows,detail=phdr.parse_phdr(io.BytesIO(fixture([(128,7,"Second Kit"),(127,0,"First Bank"),(0,49,"Strings")])))
        self.assertEqual([(r["original_phdr_order"],r["bank"],r["program"]) for r in rows],[(0,128,7),(1,127,0),(2,0,49)])
        self.assertEqual(detail["terminal_phdr_order"],3)

    def test_streamed_hash_multiset_and_source_no_write(self):
        data=fixture([(128,7,"Kit"),(0,1,"Piano")])+b"trailing bytes hashed"
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/"synthetic.sf2";path.write_bytes(data)
            expected=hashlib.sha256(data).hexdigest()
            rows,detail=phdr.extract_file(path,expected,[(0,1,"Piano"),(128,7,"Kit")])
            self.assertEqual([r["program"] for r in rows],[7,1])
            self.assertEqual(detail["first_drum_phdr_entry"]["program"],7)
            self.assertEqual(detail["trailing_bytes_hashed"],len(b"trailing bytes hashed"))
            self.assertEqual(path.read_bytes(),data)
            with self.assertRaisesRegex(ValueError,"SHA256"):phdr.extract_file(path,"0"*64,[(128,7,"Kit")])
            with self.assertRaisesRegex(ValueError,"multiset"):phdr.extract_file(path,expected,[(128,7,"Invented"),(0,1,"Piano")])

    def test_decoy_phdr_bytes_and_odd_chunk_padding_ignored(self):
        extra=chunk(b"JUNK",b"odd")+chunk(b"LIST",b"sdta"+chunk(b"smpl",b"phdr fake bytes"))
        rows,_=phdr.parse_phdr(io.BytesIO(fixture([(0,0,"Real")],extra=extra)))
        self.assertEqual(rows[0]["name"],"Real")

    def test_missing_and_early_eop_rejected(self):
        for terminal in (phdr.HEADER.pack(b"EOS"+b"\0"*17,0,0,1,0,0,0),phdr.HEADER.pack(b"EOP"+b"\0"*17,1,0,1,0,0,0)):
            with self.assertRaisesRegex(ValueError,"EOP"):phdr.parse_phdr(io.BytesIO(fixture([(0,0,"Piano")],terminal)))
        with self.assertRaisesRegex(ValueError,"before terminal"):phdr.parse_phdr(io.BytesIO(fixture([(0,0,"EOP")])))

    def test_duplicate_address_rejected_even_with_different_name(self):
        with self.assertRaisesRegex(ValueError,"duplicate bank/program"):
            phdr.parse_phdr(io.BytesIO(fixture([(0,0,"Piano"),(0,0,"Other")])) )

    def test_duplicate_and_missing_phdr_rejected(self):
        data=fixture([(0,0,"Piano")]);duplicate=data+data[12:]
        duplicate=duplicate[:4]+struct.pack("<I",len(duplicate)-8)+duplicate[8:]
        for bad in (duplicate,b"RIFF"+struct.pack("<I",16)+b"sfbk"+chunk(b"LIST",b"pdta")):
            with self.assertRaisesRegex(ValueError,"exactly one"):phdr.parse_phdr(io.BytesIO(bad))

    def test_truncated_parent_and_invalid_riff_rejected(self):
        data=fixture([(0,0,"Piano")])
        for bad in (data[:-1],b"RIFX"+data[4:],data[:8]+b"xxxx"+data[12:],data[:4]+struct.pack("<I",3)+data[8:]):
            with self.assertRaises(ValueError):phdr.parse_phdr(io.BytesIO(bad))

    def test_nonmultiple_record_size_rejected(self):
        body=b"sfbk"+chunk(b"LIST",b"pdta"+chunk(b"phdr",b"x"*77))
        with self.assertRaisesRegex(ValueError,"record count/size"):phdr.parse_phdr(io.BytesIO(b"RIFF"+struct.pack("<I",len(body))+body))

    def test_lossless_name_no_trim_or_ascii_ignore(self):
        rows,_=phdr.parse_phdr(io.BytesIO(fixture([(0,0,"Écho ")])))
        self.assertEqual(rows[0]["name"],"Écho ")

    def test_invalid_program_and_terminal_bag_rejected(self):
        with self.assertRaisesRegex(ValueError,"program"):phdr.parse_phdr(io.BytesIO(fixture([(0,128,"Bad")])) )
        terminal=phdr.HEADER.pack(b"EOP"+b"\0"*17,0,0,0,0,0,0)
        with self.assertRaisesRegex(ValueError,"terminal bag"):phdr.parse_phdr(io.BytesIO(fixture([(0,0,"One"),(0,1,"Two")],terminal)))

    def test_repository_fingerprints_and_metadata_counts(self):
        fp=ROOT/"tests/fixtures/percussion_inventory_fingerprints.json"
        entries=phdr.metadata_entries(ROOT/"tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv",fp)
        self.assertEqual(sum(map(len,entries.values())),1148)
        self.assertEqual(json.loads(fp.read_text()),{k:v[0] for k,v in phdr.SOURCES.items()})

    def test_no_binaries_does_not_create_order_or_output_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/"output"
            report=phdr.acquire(tmp,ROOT/"tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv",ROOT/"tests/fixtures/percussion_inventory_fingerprints.json",out)
            self.assertEqual(report["status"],"WAITING_FOR_ORIGINAL_SF2")
            self.assertEqual(report["original_order_rows_exported"],0)
            self.assertFalse(out.exists())
            self.assertFalse(report["master_export_go"])

if __name__=="__main__":unittest.main(verbosity=2)

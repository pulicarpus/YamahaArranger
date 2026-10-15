"""Actual golden payloads and independent crosschecks; corruption must fail closed."""
import csv
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools/sf2_catalog'))
from pdta_inventory import read_source,parse
FIXTURE=ROOT/'tests/fixtures/sf2_pdta/export.zip'
REVIEW=json.loads((ROOT/'tools/sf2_catalog/catalog_review.yaml').read_text())

class PdtaInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.fonts=read_source(FIXTURE,REVIEW)
    def test_all_four_originals_counts_names_and_census(self):
        self.assertEqual(len(self.fonts),4)
        self.assertEqual(sum(f['counts']['presets'] for f in self.fonts.values()),1148)
        self.assertEqual(sum(len(f['names']) for f in self.fonts.values()),12795)
        self.assertEqual(sum(len(f['zones']) for f in self.fonts.values()),66944)
        for f in self.fonts.values():
            for name in f['names']:
                self.assertEqual(len(bytes.fromhex(name['name_hex_20'])),20)
                self.assertEqual(bytes.fromhex(name['name_hex_20']).split(b'\0',1)[0].decode('latin1'),name['name_text_latin1'])
    def test_bad_original_byte_manifest_or_derived_row_is_rejected(self):
        with zipfile.ZipFile(FIXTURE) as z:original={Path(n).name:z.read(n) for n in z.namelist() if not n.endswith('/')}
        for target in ['colombo.pdta.bin','pdta_manifest.json','colombo.names.tsv','colombo.zones_attenuation.tsv']:
            changed=dict(original)
            if target.endswith('.bin'):changed[target]=changed[target][:-1]+bytes([changed[target][-1]^1])
            elif target.endswith('.json'):
                doc=json.loads(changed[target]);doc['files'][0]['pdta_size']+=1;changed[target]=json.dumps(doc).encode()
            else:
                text=changed[target].decode();header,row,rest=text.split('\n',2);cells=row.split('\t');cells[0]='999999';changed[target]=(header+'\n'+'\t'.join(cells)+'\n'+rest).encode()
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'bad.zip'
                with zipfile.ZipFile(path,'w') as z:
                    for name,data in changed.items():z.writestr('export/'+name,data)
                with self.assertRaises(ValueError,msg=target):read_source(path,REVIEW)
    def test_malformed_tables_terminal_or_link_rejected_without_sample_reads(self):
        font=next(iter(self.fonts.values()));raw=font['payload']
        for payload in [raw[:-1],b'sdta'+raw[4:],raw+b'\0']:
            with self.assertRaises(ValueError):parse(payload)
        chunks={c['id']:c for c in font['chunks']}
        bad=bytearray(raw);at=chunks['phdr']['offset_in_bin']+8;bad[at:at+2]=b'xx'
        # Terminal identity, not a normal preset name, is mandatory.
        at=chunks['phdr']['offset_in_bin']+8+chunks['phdr']['size']-38;bad[at:at+3]=b'BAD'
        with self.assertRaisesRegex(ValueError,'terminals'):parse(bytes(bad))
        bad=bytearray(raw);at=chunks['pgen']['offset_in_bin']+8
        for offset in range(at,at+chunks['pgen']['size'],4):
            if struct.unpack_from('<H',bad,offset)[0]==41:
                struct.pack_into('<h',bad,offset+2,32767);break
        with self.assertRaisesRegex(ValueError,'instrument link'):parse(bytes(bad))

if __name__=='__main__':unittest.main()

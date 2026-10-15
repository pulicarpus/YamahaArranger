"""Synthetic parser/CLI tests; these do NOT verify the four absent original payloads."""
import hashlib
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/sf2_catalog'))
from verify_golden_pdta import DRIVER,verify

class GoldenToolTest(unittest.TestCase):
    def test_missing_originals_are_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError,'original PDTA evidence unavailable'):verify(directory)

    def test_native_wrapper_hashes_exact_payload_and_rejects_bad_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cpp=root/'driver.cpp';cpp.write_text(DRIVER);exe=root/'driver'
            subprocess.run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-I'+str(ROOT/'app/src/main/cpp'),str(cpp),'-o',str(exe)],check=True)
            body=b'pdta'+b''.join(tag+struct.pack('<I',width)+bytes(width) for tag,width in [(b'phdr',38),(b'pbag',4),(b'pmod',10),(b'pgen',4),(b'inst',22),(b'ibag',4),(b'imod',10),(b'igen',4),(b'shdr',46)])
            payload=root/'synthetic.pdta';payload.write_bytes(body)
            self.assertEqual(subprocess.check_output([str(exe),str(payload)],text=True).strip(),hashlib.sha256(body).hexdigest()+' '+str(len(body)))
            payload.write_bytes(body[:-1]);bad=subprocess.run([str(exe),str(payload)],capture_output=True)
            self.assertNotEqual(bad.returncode,0)
            # Structurally valid substitutes cannot satisfy any original golden.
            for role in ['COLOMBO','PRIME_DRUM','PRIME_MELODY','TYROS4']:(root/(role+'.pdta')).write_bytes(body)
            with self.assertRaisesRegex(ValueError,'golden PDTA mismatch: COLOMBO'):verify(root)

if __name__=='__main__':unittest.main()

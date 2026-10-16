#!/usr/bin/env python3
import sys,tempfile,shutil,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import verify_r23_boundary as guard
class BoundaryTests(unittest.TestCase):
 def test_reviewed_delta_and_unauthorized_mutations(self):
  with tempfile.TemporaryDirectory(prefix='r23-boundary-') as d:
   scratch=Path(d)
   for name in ['app/src/main','app/src/test','tools','tests','catalog','.github']:
    shutil.copytree(ROOT/name,scratch/name,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
   (scratch/'.git').symlink_to(ROOT/'.git',target_is_directory=True)
   original=guard.ROOT;guard.ROOT=scratch
   try:
    guard.verify()
    mutations=[('app/src/main/cpp/voice_resolver.h','wrong_role','unreviewed_role'),
     ('app/src/main/cpp/bassmidi_player.cpp','if (bank != 127 && bank != 128) rawBanks.insert(bank);','rawBanks.insert(bank);'),
     ('app/src/main/cpp/bassmidi_player.cpp','b={font,bank,bank,pc,pdta,section,false};','b={drumFont_,bank,bank,pc,pdta,section,false};'),
     ('app/src/main/cpp/bassmidi_player.h','float soundFontVolume_ = 0.90f;','float soundFontVolume_ = 0.91f;'),
     ('tools/verify_r22_boundary.py','assert s==old(path).decode()','assert True'),
     ('app/src/test/java/com/yourapp/registration/RecallAdapterTest.kt','assertTrue(bytes.any{it==listOf(0x8a,36,0)})','assertTrue(true)')]
    for name,before,after in mutations:
     path=scratch/name;raw=path.read_bytes();text=raw.decode();self.assertIn(before,text);path.write_text(text.replace(before,after,1))
     with self.assertRaises(AssertionError,msg=name):guard.verify()
     path.write_bytes(raw)
    guard.verify()
   finally:guard.ROOT=original
if __name__=='__main__':unittest.main()

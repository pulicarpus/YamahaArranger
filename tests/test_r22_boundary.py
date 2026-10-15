#!/usr/bin/env python3
"""Positive restored-source guard plus unauthorized source mutations, entirely in scratch."""
import sys,tempfile,shutil,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import verify_r22_boundary as guard
class BoundaryTests(unittest.TestCase):
 def test_positive_and_three_negative_mutations(self):
  with tempfile.TemporaryDirectory(prefix='r22-scope-negative-') as d:
   scratch=Path(d)
   for name in ['app/src/main','tools','tests','catalog','.github']:
    shutil.copytree(ROOT/name,scratch/name,ignore=shutil.ignore_patterns('__pycache__','*.pyc'))
   (scratch/'.git').symlink_to(ROOT/'.git',target_is_directory=True)
   original=guard.ROOT;guard.ROOT=scratch
   try:
    guard.verify()
    mutations=[('app/src/main/cpp/voice_resolver.h','wrong_role','unreviewed_role'),
      ('app/src/main/cpp/bassmidi_player.h','float soundFontVolume_ = 0.90f;','float soundFontVolume_ = 0.91f;'),
      ('app/src/main/cpp/bassmidi_player.cpp','    return out.str(); // cache identity','    BASS_MIDI_StreamEvent(stream_,8,MIDI_EVENT_PROGRAM,8);\n    return out.str(); // cache identity')]
    for name,before,after in mutations:
     path=scratch/name;raw=path.read_bytes();text=raw.decode();self.assertIn(before,text);path.write_text(text.replace(before,after,1))
     with self.assertRaises(AssertionError,msg=name):guard.verify()
     path.write_bytes(raw)
    guard.verify()
   finally:guard.ROOT=original
if __name__=='__main__':unittest.main()

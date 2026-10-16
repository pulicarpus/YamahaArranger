import sys,tempfile,shutil,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import verify_r24_build as guard
class Boundary(unittest.TestCase):
 def test_reviewed_source_and_negative_mutations(self):
  with tempfile.TemporaryDirectory() as d:
   copy=Path(d)
   for name in ['app/src/main','app/src/test','tools','tests','catalog','.github','docs','scripts']:
    if (ROOT/name).exists():shutil.copytree(ROOT/name,copy/name,ignore=shutil.ignore_patterns('__pycache__'))
   # guard checks all tracked baseline files, including root configuration.
   import subprocess
   for name in subprocess.check_output(['git','ls-tree','-r','--name-only',guard.BASE],cwd=ROOT).decode().splitlines():
    if not (copy/name).exists():
     (copy/name).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,copy/name)
   (copy/'.git').symlink_to(ROOT/'.git',target_is_directory=True);saved=guard.ROOT;guard.ROOT=copy
   try:
    guard.verify()
    for name in ['app/src/main/cpp/voice_resolver.h','app/src/main/cpp/drum_punch.h','app/src/main/cpp/style_parser.cpp','app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt','.github/workflows/bassmidi-r23-drum-ab.yml']:
     p=copy/name;raw=p.read_bytes();p.write_bytes(raw+b'\n// unauthorized source drift\n')
     with self.assertRaises(AssertionError,msg=name):guard.verify()
     p.write_bytes(raw)
    guard.verify()
   finally:guard.ROOT=saved
if __name__=='__main__':unittest.main()

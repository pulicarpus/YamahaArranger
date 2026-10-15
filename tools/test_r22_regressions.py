#!/usr/bin/env python3
"""Add new pure observer compiler inputs without editing historical test assertions."""
import argparse,sys,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import test_voice_release as old
original=old.run
added=list((ROOT/'app/src/main/java/com/yourapp/yamahaarranger/arranger').glob('DrumKitShadow*.kt'))
integration=False
def augmented(command,*args,**kwargs):
 if any(str(x).endswith('/StyleSequencer.kt') for x in command):command=list(command)+[str(p) for p in added if str(p) not in map(str,command)]
 if integration and any(str(x).endswith("/StyleSequencer.kt") for x in command):command=list(command)+[str(ROOT/"tests/R22IntegrationTest.kt")]
 if integration and "org.junit.runner.JUnitCore" in command:command=list(command)+["com.yourapp.yamahaarranger.arranger.R22IntegrationTest"]
 return original(command,*args,**kwargs)
def main():
 p=argparse.ArgumentParser();p.add_argument('--suite',choices=['jvm','live','integration'],required=True);p.add_argument('--out',required=True);a=p.parse_args()
 Path(a.out).resolve().mkdir(parents=True,exist_ok=True)
 global integration
 integration=a.suite=="integration"
 old.run=augmented
 if a.suite=='integration':
  old.jvm(Path(a.out).resolve(),False)
 elif a.suite=='jvm':
  sys.argv=['test_r20_jvm.py','--out',a.out];runpy.run_path(str(ROOT/'tools/test_r20_jvm.py'),run_name='__main__')
 else:runpy.run_path(str(ROOT/'tools/test_r20_live_reg.py'),run_name='__main__')
if __name__=='__main__':main()

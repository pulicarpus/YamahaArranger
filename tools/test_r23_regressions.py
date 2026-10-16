#!/usr/bin/env python3
"""Keep all existing integration assertions and append R2-3 mode/binding contracts."""
import sys,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import test_voice_release as old
original=old.run
def augmented(command,*args,**kwargs):
 if any(str(x).endswith('/StyleSequencer.kt') for x in command):command=list(command)+[str(ROOT/'tests/R23IntegrationTest.kt')]
 if 'org.junit.runner.JUnitCore' in command:command=list(command)+['com.yourapp.yamahaarranger.arranger.R23IntegrationTest']
 return original(command,*args,**kwargs)
old.run=augmented
runpy.run_path(str(ROOT/'tools/test_r22_regressions.py'),run_name='__main__')

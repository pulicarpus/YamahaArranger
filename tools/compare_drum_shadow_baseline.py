#!/usr/bin/env python3
"""Host/mock latency observations and byte-identical traces against the #770 source.
Not an Android audio/xrun benchmark. No threshold on noisy wall-clock host timings.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
p=argparse.ArgumentParser();p.add_argument('baseline',type=Path);args=p.parse_args()
root=Path(__file__).resolve().parents[1]
compiler=os.environ.get('CXX') or shutil.which('c++') or shutil.which('g++')
with tempfile.TemporaryDirectory(prefix='shadow-baseline-') as directory:
    results={}
    for name,source,flags in [('baseline770',args.baseline,[]),('shadow',root,['-DSHADOW_OBSERVATION=1'])]:
        exe=Path(directory)/name
        subprocess.run([compiler,'-std=c++17','-O2','-pthread',*flags,'-I',str(root/'tests/mocks'),
            '-I',str(source/'app/src/main/cpp'),str(root/'tests/drum_shadow_readonly_test.cpp'),
            str(source/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(exe)],check=True)
        trace=Path(directory)/(name+'.trace')
        result=subprocess.run([str(exe),directory,str(trace)],check=True,capture_output=True,text=True)
        print(name+': '+result.stdout.strip());results[name]=trace.read_bytes()
    assert results['baseline770']==results['shadow'],'#770 production event trace changed'
    print('BASELINE #770 vs SHADOW production event traces BYTE-IDENTICAL; added NOTE hooks/allocations/mutexes=0 (structural guard); device xrun/underrun/heap/lock-contention NOT MEASURED')

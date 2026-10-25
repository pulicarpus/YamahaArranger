#!/usr/bin/env python3
"""Real BASSMIDI (Linux x86_64) on a synthetic kendang-like SF2: style notes silent, mapped target notes audible. Usage: test_drum_map_real_bass.py /path/bass /path/bassmidi --out DIR"""
import argparse, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('bass'); p.add_argument('bassmidi'); p.add_argument('--out', required=True); a = p.parse_args()
bass, midi, out = Path(a.bass).resolve(), Path(a.bassmidi).resolve(), Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=True)
exe = out / 'drum-map-real'
cmd = ['g++', '-std=c++17', '-O2', '-I', str(bass / 'c'), '-I', str(midi / 'c'), '-I', str(ROOT / 'tests'), str(ROOT / 'tests/drum_map_real_bass_test.cpp')]
for sdk in (bass, midi): cmd += ['-L', str(sdk / 'libs/x86_64'), '-Wl,-rpath,' + str(sdk / 'libs/x86_64')]
subprocess.run(cmd + ['-lbassmidi', '-lbass', '-o', str(exe)], check=True)
res = subprocess.run([str(exe), str(out)], capture_output=True, text=True)
(out / 'drum-map-real.log').write_text(res.stdout + res.stderr); print(res.stdout, res.stderr[-400:])
raise SystemExit(res.returncode)

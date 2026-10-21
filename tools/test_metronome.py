#!/usr/bin/env python3
"""Host test of the metronome click generator (pure C++)."""
import argparse, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('--out', required=True); a = p.parse_args()
out = Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=True)
exe = out / 'metronome-test'
subprocess.run(['g++', '-std=c++17', '-O2', '-Wall', '-I', str(ROOT / 'app/src/main/cpp'), str(ROOT / 'tests/metronome_engine_test.cpp'), '-o', str(exe)], check=True)
res = subprocess.run([str(exe)], capture_output=True, text=True)
(out / 'metronome.log').write_text(res.stdout + res.stderr); print(res.stdout, res.stderr[-500:])
raise SystemExit(res.returncode)

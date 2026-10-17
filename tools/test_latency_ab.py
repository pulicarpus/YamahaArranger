#!/usr/bin/env python3
"""Host tests for the onset analysis and the output-buffer A/B policy (pure headers, no BASS/Oboe)."""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    a = p.parse_args()
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    exe = out / 'latency-ab'
    subprocess.run(['g++', '-std=c++17', '-O2', '-Wall', '-Iapp/src/main/cpp', 'tests/latency_ab_test.cpp', '-o', str(exe)], cwd=ROOT, check=True)
    result = subprocess.check_output([str(exe)], cwd=ROOT, text=True)
    (out / 'latency-ab.log').write_text(result)
    print(result)


if __name__ == '__main__':
    main()

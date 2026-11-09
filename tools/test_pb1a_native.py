#!/usr/bin/env python3
"""P-B1a drum bind cache on the host: reuses the R2-3 golden fixtures and mocks (tools/test_r23_native.py), then builds
tests/pb1a_drum_bind_cache_test.cpp and tests/pb1a_kit_change_test.cpp (ROSITA kit changes) plain and with ASan+UBSan. HOST evidence only."""
import argparse, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]


def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True)
    out = Path(a.parse_args().out).resolve(); out.mkdir(parents=True, exist_ok=True)
    r23 = out / 'r23'
    subprocess.run([sys.executable, str(ROOT / 'tools/test_r23_native.py'), '--out', str(r23)], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    flags = ['-DYAMAHA_COMPATIBLE_PERCUSSION=1', '-DYAMAHA_ROLE_PCM_METERS=1', '-DYAMAHA_PCM_HEADROOM=1', '-I' + str(r23 / 'mocks'), '-Iapp/src/main/cpp']
    tests = (('tests/pb1a_drum_bind_cache_test.cpp', 'PB1A DRUM BIND CACHE PASS'), ('tests/pb1a_kit_change_test.cpp', 'PB1A KIT CHANGE PASS'))
    for src, token in tests:
        for name, extra in (('', ['-O2']), ('-asan', ['-O1', '-g', '-fno-omit-frame-pointer', '-fsanitize=address,undefined', '-fno-sanitize-recover=undefined'])):
            name = Path(src).stem + name
            exe = out / name
            subprocess.run(['g++', '-std=c++17', '-pthread', *extra, *flags, src, 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)], cwd=ROOT, check=True)
            r = subprocess.run([str(exe), str(r23 / 'golden-fixtures')], cwd=ROOT, text=True, capture_output=True)
            text = r.stdout + r.stderr; (out / f'{name}.log').write_text(text); print(f'== {name}\n{text[-600:]}', end='')
            assert r.returncode == 0 and token in r.stdout, f'{name} failed'
    print('PB1A HOST: PASS (plain + ASan/UBSan)')


if __name__ == '__main__':
    main()

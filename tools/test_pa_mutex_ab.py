#!/usr/bin/env python3
"""P-A BASSMIDI mutex diagnostic A/B on the host (production BassMidiPlayer + mock BASS). HOST evidence only.
Builds tests/pa_mutex_ab_test.cpp twice: plain -O2 and with AddressSanitizer + UndefinedBehaviorSanitizer (use-after-free / UB check) and ThreadSanitizer (data races / lock order)."""
import argparse, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]


def build_and_run(out: Path, name: str, extra: list) -> str:
    exe = out / name
    cmd = ['g++', '-std=c++17', '-Wall', '-Wextra', '-pthread', *extra, '-Itests/mocks', '-Iapp/src/main/cpp', '-Itests',
           'tests/pa_mutex_ab_test.cpp', 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)]
    subprocess.run(cmd, cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory() as d:
        r = subprocess.run([str(exe), d], cwd=ROOT, text=True, capture_output=True)
    text = r.stdout + r.stderr
    (out / f'{name}.log').write_text(text)
    print(f'== {name}\n{text}', end='')
    assert r.returncode == 0 and 'PA MUTEX AB PASS' in r.stdout, f'{name} failed'
    return text


def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True)
    out = Path(a.parse_args().out).resolve(); out.mkdir(parents=True, exist_ok=True)
    build_and_run(out, 'pa-mutex-ab', ['-O2'])
    build_and_run(out, 'pa-mutex-ab-asan', ['-O1', '-g', '-fno-omit-frame-pointer', '-fsanitize=address,undefined', '-fno-sanitize-recover=undefined'])
    build_and_run(out, 'pa-mutex-ab-tsan', ['-O1', '-g', '-fsanitize=thread'])
    print('PA MUTEX AB HOST: PASS (plain + ASan/UBSan + TSan)')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""P-B0.1 targeted probes on the host (production BassMidiPlayer + mock BASS). HOST evidence only.
Builds tests/pb01_probe_test.cpp plain (-O2), with ASan+UBSan and with TSan."""
import argparse, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]


def build_and_run(out: Path, name: str, extra: list) -> str:
    exe = out / name
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-pthread', *extra, '-Itests/mocks', '-Iapp/src/main/cpp', '-Itests',
                    'tests/pb01_probe_test.cpp', 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory() as d:
        r = subprocess.run([str(exe), d], cwd=ROOT, text=True, capture_output=True)
    text = r.stdout + r.stderr
    (out / f'{name}.log').write_text(text)
    print(f'== {name}\n{text}', end='')
    assert r.returncode == 0 and 'PB01 PROBE PASS' in r.stdout and 'WARNING: ThreadSanitizer' not in text, f'{name} failed'
    return text


def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True)
    out = Path(a.parse_args().out).resolve(); out.mkdir(parents=True, exist_ok=True)
    build_and_run(out, 'pb01', ['-O2'])
    build_and_run(out, 'pb01-asan', ['-O1', '-g', '-fno-omit-frame-pointer', '-fsanitize=address,undefined', '-fno-sanitize-recover=undefined'])
    build_and_run(out, 'pb01-tsan', ['-O1', '-g', '-fsanitize=thread'])
    print('PB01 PROBE HOST: PASS (plain + ASan/UBSan + TSan)')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Compile and run the CPV native contracts against the golden PDTA fixtures."""
import argparse
import struct
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    a = p.parse_args()
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    fixtures = out / 'golden-fixtures'
    fixtures.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / 'tests/fixtures/sf2_pdta/export.zip') as z:
        for n in z.namelist():
            if n.endswith('.pdta.bin'):
                payload = z.read(n)
                chunk = b'LIST' + struct.pack('<I', len(payload)) + payload
                (fixtures / Path(n).name.replace('.pdta.bin', '.sf2')).write_bytes(
                    b'RIFF' + struct.pack('<I', 4 + len(chunk)) + b'sfbk' + chunk)
    exe = out / 'cpv-native'
    subprocess.run(['g++', '-std=c++17', '-O2', '-pthread', '-DYAMAHA_COMPATIBLE_PERCUSSION=1',
                    '-DYAMAHA_ROLE_PCM_METERS=1', '-DYAMAHA_PCM_HEADROOM=1', '-Itests/mocks', '-Iapp/src/main/cpp',
                    'tests/cpv_native_test.cpp', 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)],
                   cwd=ROOT, check=True)
    result = subprocess.check_output([str(exe), str(fixtures)], cwd=ROOT, text=True)
    (out / 'cpv-native.log').write_text(result)
    print(result)


if __name__ == '__main__':
    main()

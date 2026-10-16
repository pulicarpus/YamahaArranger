#!/usr/bin/env python3
"""Native contract tests for the RIGHT voice A/B diagnostic (mock BASS, golden PDTA fixtures)."""
import argparse
import struct
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def chunk(tag, payload):
    return tag + struct.pack('<I', len(payload)) + payload + (b'\0' if len(payload) & 1 else b'')


def sfbk(pdta_payload, inam):
    parts = b''
    if inam is not None:
        name = inam.encode() + b'\0'
        parts += chunk(b'LIST', b'INFO' + chunk(b'INAM', name))
    parts += chunk(b'LIST', pdta_payload)
    return b'RIFF' + struct.pack('<I', 4 + len(parts)) + b'sfbk' + parts


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    a = p.parse_args()
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    fx = out / 'fixtures'
    fx.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / 'tests/fixtures/sf2_pdta/export.zip') as z:
        pdtas = {Path(n).name: z.read(n) for n in z.namelist() if n.endswith('.pdta.bin')}
    names = sorted(pdtas)
    colombo = next(n for n in names if 'colombo' in n.lower())
    tyros = next(n for n in names if 'tyros' in n.lower())
    (fx / 'colombo.sf2').write_bytes(sfbk(pdtas[colombo], 'Colombo Test Font'))
    (fx / 'tyros4.sf2').write_bytes(sfbk(pdtas[tyros], 'Tyros Test Font'))
    (fx / 'bare.sf2').write_bytes(sfbk(pdtas[colombo], None))
    exe = out / 'right-voice-diag'
    subprocess.run(['g++', '-std=c++17', '-O2', '-pthread', '-DYAMAHA_COMPATIBLE_PERCUSSION=1',
                    '-DYAMAHA_ROLE_PCM_METERS=1', '-DYAMAHA_PCM_HEADROOM=1', '-Itests/mocks', '-Iapp/src/main/cpp',
                    'tests/right_voice_diag_test.cpp', 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)],
                   cwd=ROOT, check=True)
    result = subprocess.check_output([str(exe), str(fx)], cwd=ROOT, text=True)
    (out / 'right-voice-diag.log').write_text(result)
    print(result)


if __name__ == '__main__':
    main()

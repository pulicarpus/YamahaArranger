#!/usr/bin/env python3
"""MIDI SONG V1A native contract on the host (mock BASS): separate Song stream, SysEx refused, reset isolated."""
import argparse, struct, subprocess, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True); o = Path(a.parse_args().out).resolve(); o.mkdir(parents=True, exist_ok=True)
    fx = o / 'fixtures'; fx.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / 'tests/fixtures/sf2_pdta/export.zip') as z:
        for n in z.namelist():
            if n.endswith('.pdta.bin'):
                payload = z.read(n); chunk = b'LIST' + struct.pack('<I', len(payload)) + payload
                (fx / Path(n).name.replace('.pdta.bin', '.sf2')).write_bytes(b'RIFF' + struct.pack('<I', 4 + len(chunk)) + b'sfbk' + chunk)
    exe = o / 'song-native'
    subprocess.run(['g++', '-std=c++17', '-O2', '-pthread', '-DYAMAHA_COMPATIBLE_PERCUSSION=1', '-DYAMAHA_ROLE_PCM_METERS=1', '-DYAMAHA_PCM_HEADROOM=1',
                    '-Itests/mocks', '-Iapp/src/main/cpp', 'tests/song_native_test.cpp', 'app/src/main/cpp/bassmidi_player.cpp', '-o', str(exe)], cwd=ROOT, check=True)
    r = subprocess.check_output([str(exe), str(fx)], cwd=ROOT, text=True); print(r, end='')
    assert 'SONG NATIVE PASS' in r
if __name__ == '__main__':
    main()

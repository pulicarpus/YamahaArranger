#!/usr/bin/env python3
"""DEVICE ROUTING V1 native contract on the host (mock Oboe + mock BASS). HOST evidence only: no Android audio routing is exercised."""
import argparse, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True); o = Path(a.parse_args().out).resolve(); o.mkdir(parents=True, exist_ok=True)
    exe = o / 'device-routing-native'
    subprocess.run(['g++', '-std=c++17', '-O1', '-pthread', '-Itests/mocks', '-Iapp/src/main/cpp', 'tests/device_routing_native_test.cpp',
                    'app/src/main/cpp/audio_engine.cpp', 'app/src/main/cpp/bassmidi_player.cpp', 'app/src/main/cpp/voice.cpp', '-o', str(exe)], cwd=ROOT, check=True)
    r = subprocess.check_output([str(exe)], cwd=ROOT, text=True); print(r, end='')
    (o / 'device-routing-native.log').write_text(r)
    assert 'DEVICE ROUTING NATIVE PASS' in r
if __name__ == '__main__':
    main()

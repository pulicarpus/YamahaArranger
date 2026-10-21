#!/usr/bin/env python3
"""Native realtime diagnostic: production BassMidiPlayer with MOCK BASS. A patched COPY of tests/mocks lets render() hold the synth mutex for a
configurable time (a model parameter). Nothing in tests/mocks or production code is modified."""
import argparse, re, shutil, struct, subprocess, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def main():
    p = argparse.ArgumentParser(); p.add_argument('--out', required=True); a = p.parse_args()
    out = Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=True)
    fixtures = out / 'golden-fixtures'; fixtures.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / 'tests/fixtures/sf2_pdta/export.zip') as z:
        for n in z.namelist():
            if n.endswith('.pdta.bin'):
                payload = z.read(n); chunk = b'LIST' + struct.pack('<I', len(payload)) + payload
                (fixtures / Path(n).name.replace('.pdta.bin', '.sf2')).write_bytes(b'RIFF' + struct.pack('<I', 4 + len(chunk)) + b'sfbk' + chunk)
    mocks = out / 'mocks'
    if mocks.exists(): shutil.rmtree(mocks)
    shutil.copytree(ROOT / 'tests/mocks', mocks)
    bass = (mocks / 'bass.h').read_text()
    bass = bass.replace('namespace mock_bass { inline std::vector<HSTREAM> freedStreams;', 'namespace mock_bass { inline unsigned getDataSpinUs=0; inline std::vector<HSTREAM> freedStreams;')
    bass = bass.replace('    if(stream==1) return 0;\n', '    if(stream==1) { if(mock_bass::getDataSpinUs){const auto end=std::chrono::steady_clock::now()+std::chrono::microseconds(mock_bass::getDataSpinUs);while(std::chrono::steady_clock::now()<end){}} return 0; }\n')
    bass = '#include <chrono>\n' + bass
    assert 'getDataSpinUs' in bass and bass.count('getDataSpinUs') >= 3
    (mocks / 'bass.h').write_text(bass)
    exe = out / 'rtdiag-native'
    subprocess.run(['g++', '-std=c++17', '-O2', '-pthread', '-DYAMAHA_COMPATIBLE_PERCUSSION=1', '-DYAMAHA_ROLE_PCM_METERS=1', '-DYAMAHA_PCM_HEADROOM=1',
                    '-I' + str(mocks), '-I' + str(ROOT / 'app/src/main/cpp'), str(ROOT / 'tests/rtdiag_native_test.cpp'), str(ROOT / 'app/src/main/cpp/bassmidi_player.cpp'),
                    '-o', str(exe)], cwd=ROOT, check=True)
    res = subprocess.run([str(exe), str(fixtures)], cwd=ROOT, text=True, capture_output=True)
    (out / 'rtdiag-native.log').write_text(res.stdout + res.stderr)
    print(res.stdout); print(res.stderr[-600:])
    raise SystemExit(res.returncode)

if __name__ == '__main__':
    main()

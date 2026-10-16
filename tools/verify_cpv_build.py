#!/usr/bin/env python3
"""CPV build provenance: R2-3 baseline ancestry, no R2-4 Drum Punch, APK contents."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = 'c75732f28fec58fbfbc7e626bc380b10a00722b8'  # R2-3 drum A/B, CI green
R24_ONLY = ['app/src/main/cpp/drum_punch.h']
SYMBOLS = [b'nativeChannelBinding', b'nativeSetSourceTiered', b'nativeBindDrumAB', b'nativeSetDrumABMode']


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT).decode().strip()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apk')
    p.add_argument('--out', required=True)
    a = p.parse_args()
    head = git('rev-parse', 'HEAD')
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASELINE, head], cwd=ROOT, check=True)
    for path in R24_ONLY:
        assert not (ROOT / path).exists(), 'R2-4 Drum Punch source must not be present: ' + path
    result = {'source_sha': head, 'baseline_sha': BASELINE, 'r24_drum_punch': 'ABSENT',
              'commits_since_baseline': int(git('rev-list', '--count', BASELINE + '..' + head))}
    if a.apk:
        data = Path(a.apk).read_bytes()
        result['apk_sha256'] = hashlib.sha256(data).hexdigest()
        result['apk_bytes'] = len(data)
        with zipfile.ZipFile(a.apk) as z:
            for abi in ['arm64-v8a', 'armeabi-v7a']:
                raw = z.read(f'lib/{abi}/libyamaha_arranger_native.so')
                missing = [s.decode() for s in SYMBOLS if s not in raw]
                assert not missing, f'{abi} missing {missing}'
            for name in ['kits.tsv.bin', 'kit_identity.json']:
                assert z.read('assets/r22/' + name) == (ROOT / 'app/src/main/assets/r22' / name).read_bytes()
        result['abis'] = ['arm64-v8a', 'armeabi-v7a']
    Path(a.out).write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()

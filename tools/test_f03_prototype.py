#!/usr/bin/env python3
"""Isolated P0 bookkeeping tests; no native/device evidence is generated."""
from f04_source_guard import historical_bytes
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def integrity():
    manifest = json.loads((ROOT/'tests/fixtures/f03_prototype_baseline_sha256.json').read_text())
    for name, expected in manifest['files'].items():
        actual = hashlib.sha256(historical_bytes(name)).hexdigest()
        if actual != expected:
            raise AssertionError('Baseline changed: '+name)
    return manifest

def main():
    manifest = integrity()
    result = subprocess.run(['python3', str(ROOT/'tests/test_f03_architecture_prototype.py')],
                            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout)
    integrity()
    if result.returncode or 'Ran 24 tests' not in result.stdout or '\nOK\n' not in result.stdout:
        raise AssertionError('Prototype tests failed or were not all executed')
    proof = {'bookkeeping_tests': 'PASS', 'test_count': 24,
             'baseline_commit': manifest['commit'], 'protected_files': len(manifest['files']),
             'baseline_integrity': 'PASS', 'production_dispatch': 'NOT_CONNECTED',
             'yamaha_pairing_contract': 'BLOCKED', 'native_voice_identity': 'UNKNOWN',
             'android_device': 'UNRUN', 'psr_e343': 'UNRUN',
             'original_503_corpus_rerun': 'BLOCKED',
             'release_profiles': 'SYNTHETIC_ASSUMPTIONS_ONLY'}
    output = ROOT/'build/f03-prototype'; output.mkdir(parents=True, exist_ok=True)
    (output/'proof.json').write_text(json.dumps(proof, indent=2)+'\n')
    print('F03_PROTOTYPE_PROOF '+json.dumps(proof))

if __name__ == '__main__':
    main()

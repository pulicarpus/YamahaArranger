#!/usr/bin/env python3
"""Hard bypass regression: whole production sources must equal build #776."""
import hashlib,json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'tests/fixtures/production_baseline776_sha256.json').read_text())
for path,expected in manifest.items():
    actual=hashlib.sha256((root/path).read_bytes()).hexdigest()
    assert actual==expected,'Production #776 mismatch: '+path
    if len(sys.argv)>1:
        assert (root/path).read_bytes()==(Path(sys.argv[1])/path).read_bytes(),path
for base in ['app/src/main/cpp','app/src/main/java']:
    for file in (root/base).rglob('*'):
        if file.suffix not in ['.cpp','.h','.kt']:continue
        text=file.read_text()
        for token in ['clearProductionDrum','nativeExperimentalDrum','nativePrepareExperimentalDrum','tryProductionDrumOn','endProductionDrumNote','experimental_drum_lanes.h','ProductionDrumOwners','productionDrumPlan']:
            assert token not in text,'Stage 3 reachable: '+str(file)+': '+token
print('REGRESSION_BYPASS PASS: 15 whole production files byte-identical to #776; Stage 3 absent from production source and JNI')

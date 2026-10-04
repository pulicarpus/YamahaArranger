#!/usr/bin/env python3
"""Stage 3 remains absent; protected #776 code unchanged except reviewed #781 part-presence patch."""
import hashlib,json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'tests/fixtures/production_baseline776_sha256.json').read_text())
approved={
    'app/src/main/cpp/bassmidi_player.cpp', 'app/src/main/cpp/bassmidi_player.h',
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
    'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',
    'app/src/main/java/com/yourapp/ui/MainViewModel.kt',
}
patch=json.loads((root/'tests/fixtures/accompaniment_presence781_patch.json').read_text())
assert set(patch)==approved
for path,expected in manifest.items():
    actual=hashlib.sha256((root/path).read_bytes()).hexdigest()
    if path in approved:
        assert patch[path]['baseline776']==expected,path
        assert actual==patch[path]['reviewedPresencePatch'],'Unreviewed part-presence change: '+path
    else:
        assert actual==expected,'Protected #776 mismatch: '+path
    if len(sys.argv)>1:
        assert hashlib.sha256((Path(sys.argv[1])/path).read_bytes()).hexdigest()==expected,path
for base in ['app/src/main/cpp','app/src/main/java']:
    for file in (root/base).rglob('*'):
        if file.suffix not in ['.cpp','.h','.kt']:continue
        text=file.read_text()
        for token in ['clearProductionDrum','nativeExperimentalDrum','nativePrepareExperimentalDrum','tryProductionDrumOn','endProductionDrumNote','experimental_drum_lanes.h','ProductionDrumOwners','productionDrumPlan']:
            assert token not in text,'Stage 3 reachable: '+str(file)+': '+token
print('REGRESSION_BYPASS PASS: 10 protected files byte-identical to #776 + 5 reviewed accompaniment presence patches; Stage 3 absent from production source and JNI')

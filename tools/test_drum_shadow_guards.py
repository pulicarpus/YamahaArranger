#!/usr/bin/env python3
"""Read-only shadow guard; explicit reviewed #781 accompaniment patch allowlist."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
protected={'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt': '1d8d3698a2efdbcc5273275d6a793a06cd0e5262e4c4275ad176b33c4f82cbe7', 'app/src/test/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformerTest.kt': '07bacec7ea5a9f8078c25ae704e047f853afb7d9bbb8d0f7444a71376882b951', 'app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt': '2d993aa83733ec6f9e9685b1c0eb0d47f131bd2c253df734a599b7c3cd102ebf', 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt': '2ead0dbd29ec54ebc423e4bb828618501555be6ff9055cabf189cb5859d01e76'}
protected.update({'app/src/main/cpp/bassmidi_player.cpp': '98c388e513f09b00dfa460959eb125f344d943dfea0dc484563a86dff20cf7ab', 'app/src/main/cpp/native_lib.cpp': '4b26fbd8d1af84c04f0c3686f2a31c2ce57f071ff2d423bcc763366e17b31dde'})
protected.update({'app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt': 'f024ed422f8e8c3af1cb3e3cef0d2e2f6a8e7d148dfe6b7f6340ed9a848ca5cd'})
patch=json.loads((root/'tests/fixtures/accompaniment_presence781_patch.json').read_text())
for path,expected in protected.items():
    if path in patch:
        assert patch[path]['baseline776']==expected,path
        expected=patch[path]['reviewedPresencePatch']
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==expected, "#770 production path changed: "+path
native=(root/'app/src/main/cpp/bassmidi_player.cpp').read_bytes()
marker=b"// Stage1/2 STOP-only observational snapshot."
assert marker in native, "shadow snapshot marker missing"
# Production prefix has the separately reviewed #781 accompaniment patch.
# Shadow snapshot below must remain free of synth writes.
assert hashlib.sha256(native.split(marker)[1]).hexdigest()=="2469e74f39d73b832cc1db40b15f4140139d12d462442d087609ce5a96de5420", "read-only native shadow snapshot changed"
body=native.split(marker)[1].decode()
for forbidden in ('StreamEvent(', 'StreamSetFonts(', 'FontLoad', 'FontInit(', 'setChannel', 'ensureEngine(', 'send('):
    assert forbidden not in body, "shadow snapshot writes synth state: "+forbidden
planner=(root/'app/src/main/java/com/yourapp/audio/DrumShadowPlanner.kt').read_text()
for forbidden in ('AudioEngineManager', 'NativeAudioBridge', 'MidiInputManager', 'ArrangerBrain'):
    assert forbidden not in planner, "pure planner gained production dispatch capability"
registry=(root/'app/src/main/java/com/yourapp/audio/DrumSemanticEvidenceRegistry.kt').read_text()
for forbidden in ('AudioEngineManager', 'NativeAudioBridge', 'MidiInputManager', 'ArrangerBrain'):
    assert forbidden not in registry, 'registry gained dispatch capability'
engineering=(root/'app/src/main/java/com/yourapp/audio/DrumEngineeringProof.kt').read_text()
for forbidden in ('AudioEngineManager', 'NativeAudioBridge', 'MidiInputManager', 'ArrangerBrain', 'StreamEvent(', 'StreamSetFonts('):
    assert forbidden not in engineering, 'engineering proof gained dispatch capability'
for path in ['GenericDrumResolver.kt','ShadowDrumRuntime.kt','GenericDrumShadowExport.kt','GenericDrumShadowRehearsal.kt']:
    source=(root/'app/src/main/java/com/yourapp/audio'/path).read_text()
    for forbidden in ('AudioEngineManager','NativeAudioBridge','MidiInputManager','ArrangerBrain','StreamEvent(','StreamSetFonts('):
        assert forbidden not in source, 'shadow resolver gained production dispatch capability: '+path
print('SHADOW structural guards PASS: reviewed accompaniment patch; CASM/decoder unchanged; shadow snapshot/planner have no synth writes')

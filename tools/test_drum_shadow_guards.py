#!/usr/bin/env python3
"""Stage1/2 guard against checkpoint #770: zero added production hooks/state writes."""
import hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[1]
protected={'app/src/test/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformerTest.kt': 'c8477ef5a80c3326ef13ea7f631f6a161a64435762d214e25a6681f39897523f', 'app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt': '2d993aa83733ec6f9e9685b1c0eb0d47f131bd2c253df734a599b7c3cd102ebf', 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt': '2ead0dbd29ec54ebc423e4bb828618501555be6ff9055cabf189cb5859d01e76'}
protected.update({'app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt': 'f024ed422f8e8c3af1cb3e3cef0d2e2f6a8e7d148dfe6b7f6340ed9a848ca5cd'})
for path,expected in protected.items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==expected, "#770 production path changed: "+path
native=(root/'app/src/main/cpp/bassmidi_player.cpp').read_bytes()
marker=b"// Stage1/2 STOP-only observational snapshot."
prefix=native.split(marker)[0].rstrip()
# Stage 3 is now authorized to add native routes. Protect legacy NOTE and preset
# functions exactly, and verify flag OFF event traces against all old checkpoints.


for name, expected in [('ensureEngine', '9c77e258470469fc36e877ae69afe01201e158b5a5ad87094fce96036d2ab29d'), ('noteOn', '0ce78fe5c852c82feaa2e59818ea13d2596f68d18aac501d4c780e3c266abd24'), ('noteOff', '265e2c7103983a8e44dfa070ec90d8cf11a0c23fd8c6a958f39def7a9ba443d5'), ('setChannelPreset', '8fe81be177ff4c454ba9e03de0cafc47cebc7b99a45864e925bc9694eff1c390'), ('preloadCurrentPreset', '77a7d26f6c349a32f27aeebdbe3412bd2bb6043f3e384c1e4a563c8b9e34ffae')]:
    import re
    f=re.search(r"(?:bool|void) BassMidiPlayer::"+name+r"\([^\n]*\).*?\n}",native.decode(),re.S)
    assert f and hashlib.sha256(f.group().encode()).hexdigest()==expected,"legacy native function changed: "+name

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
print('STAGE3 structural guards PASS: legacy BASS NOTE/preset/preload unchanged; melody/CASM/brain/decoder protected; shadow scanner/export remains read-only; flag OFF trace checked separately')

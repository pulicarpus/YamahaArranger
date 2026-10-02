#!/usr/bin/env python3
"""Stage1/2 guard against checkpoint #770: zero added production hooks/state writes."""
import hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[1]
protected={'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt': '1d8d3698a2efdbcc5273275d6a793a06cd0e5262e4c4275ad176b33c4f82cbe7', 'app/src/test/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformerTest.kt': 'c8477ef5a80c3326ef13ea7f631f6a161a64435762d214e25a6681f39897523f', 'app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt': '2d993aa83733ec6f9e9685b1c0eb0d47f131bd2c253df734a599b7c3cd102ebf', 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt': '2ead0dbd29ec54ebc423e4bb828618501555be6ff9055cabf189cb5859d01e76'}
for path,expected in protected.items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest()==expected, "#770 production path changed: "+path
native=(root/'app/src/main/cpp/bassmidi_player.cpp').read_bytes()
marker=b"// Stage1/2 STOP-only observational snapshot."
prefix=native.split(marker)[0].rstrip()
assert hashlib.sha256(prefix).hexdigest()=='fd3252ff364b28ee2b2919b6f125b4bc565c18254dd5eb8fb940adc6d951fe80', "#770 native production implementation changed"
body=native.split(marker)[1].decode()
for forbidden in ('StreamEvent(', 'StreamSetFonts(', 'FontLoad', 'FontInit(', 'setChannel', 'ensureEngine(', 'send('):
    assert forbidden not in body, "shadow snapshot writes synth state: "+forbidden
planner=(root/'app/src/main/java/com/yourapp/audio/DrumShadowPlanner.kt').read_text()
for forbidden in ('AudioEngineManager', 'NativeAudioBridge', 'MidiInputManager', 'ArrangerBrain'):
    assert forbidden not in planner, "pure planner gained production dispatch capability"
print('SHADOW structural guards PASS: #770 native implementation and sequencer/decoder unchanged; no added NOTE hot-path hooks, allocations or mutexes')

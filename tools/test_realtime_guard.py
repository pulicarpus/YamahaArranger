#!/usr/bin/env python3
"""Realtime audit + metronome isolation. Baseline c447a32 (Custom SF2 / Drum kit, CI-green). The Style scheduler, ACMP, CASM, MIDI OUT writer,
voice resolver and Registration stay byte-identical; the three production files that carry observer hooks may only differ in lines that
mention the observer/metronome vocabulary below."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'c447a32'
ALLOWED = {
    # metronome + observer plumbing
    'app/src/main/cpp/metronome_engine.h', 'app/src/main/cpp/latency_probe.h', 'app/src/main/cpp/audio_engine.cpp', 'app/src/main/cpp/audio_engine.h',
    'app/src/main/cpp/native_lib.cpp', 'app/src/main/cpp/bassmidi_player.cpp',
    'app/src/main/java/com/yourapp/yamahaarranger/audio/MetronomeModel.kt', 'app/src/main/java/com/yourapp/yamahaarranger/audio/MetronomeController.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt', 'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/audio/PcmSessionControls.kt',
    'app/src/main/java/com/yourapp/ui/MetronomePanel.kt', 'app/src/main/java/com/yourapp/ui/MetronomeOverlay.kt', 'app/src/main/java/com/yourapp/ui/ToolPanels.kt',
    'app/src/main/java/com/yourapp/ui/MainActivity.kt', 'app/src/main/java/com/yourapp/ui/SxMainScreen.kt', 'app/src/main/java/com/yourapp/ui/MainViewModel.kt',
    'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt', 'app/src/main/java/com/yourapp/midi/MidiInputManager.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
    # tests, tools, docs, workflows
    'tests/metronome_engine_test.cpp', 'tests/rtdiag/MetronomeFollowTest.kt', 'tests/rtdiag/RealtimeMidiStressTest.kt', 'tests/rtdiag/RtRig.kt',
    'tools/test_metronome.py', 'tools/test_rtdiag.py', 'tools/test_realtime_guard.py', 'tools/test_rtdiag_native.py', 'tools/test_rtdiag_real_bass.py',
    'tests/rtdiag_native_test.cpp', 'tests/rtdiag_real_bass_test.cpp', 'tools/test_cpv.py',
    'tools/ui_preview/build.gradle.kts', 'tools/ui_preview/src/main/kotlin/PreviewMain.kt', 'tools/ui_preview/src/main/kotlin/PreviewMetronome.kt',
    '.github/workflows/rtdiag.yml', '.github/workflows/style-voice-editor.yml', '.github/workflows/style-voice-editor-preview.yml',
    'docs/REALTIME_MIDI_STRESS_DIAGNOSTIC.md', 'docs/REALTIME_AUDIT_METRONOME_REPORT.md',
}
PROTECTED = (   # byte-identical to the baseline
    'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt',
    'app/src/main/java/com/yourapp/chord/ChordDetector.kt', 'app/src/main/java/com/yourapp/chord/AcmpChordAnalyzer.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceRequests.kt', 'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceEditorOps.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/registration/RecallPlanner.kt', 'app/src/main/java/com/yourapp/yamahaarranger/registration/Sf2ExternalMapping.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleOverrideStore.kt', 'app/src/main/cpp/bassmidi_player.h', 'app/src/main/cpp/style_parser.cpp',
)
# Files that may only gain observer/metronome lines (every added or removed line must contain one of these markers).
OBSERVER_ONLY = {
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt': ('LatencyProbe', 'METRONOME', 'loopOriginTick', 'applyVoicesProbed', 'applyVoicesFromCasm(active.section)', 'applyVoicesFromCasm(section)', 'clockRunning', 'tickToNanos', 'masterTickNow', 'retargetDecision', 'private fun applyVoicesFromCasm', 'Observer-only wrapper', 'loopOriginTickValue', 'playback job exists', 'CLOCK_MONOTONIC', 'read-only', '^\\s*}\\s*$', '^\\s*$'),
    'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt': ('LatencyProbe', 'METRONOME', 'styleClockSource', 'StyleClockSource', 'MetronomeMeter', 'MetronomeMeters', 'override fun', 'probeStart', 'sequencer.clockRunning', 'sequencer.loopOriginTick', 'sequencer.tickToNanos', 'sequencer.masterTickNow', 'sequencer.clockTempoRevision', 'read-only view', '^\\s*}\\s*$', '^\\s*$'),
    'app/src/main/java/com/yourapp/midi/MidiInputManager.kt': ('LatencyProbe',),
    'app/src/main/cpp/bassmidi_player.cpp': ('LATENCY_PROBE', 'BASS_ATTRIB_MIDI_VOICES_ACTIVE', 'host mock headers', '#ifndef', '#endif', 'probe.voices'),
    'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt': ('METRONOME', 'metronome', 'nativeMetronome', 'latencyReport', 'fun latencyReport', '^\\s*$'),
}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed - ALLOWED}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
for protected in PROTECTED:
    assert not git('diff', BASE, '--', protected).strip(), 'Protected file changed: ' + protected
for path, markers in OBSERVER_ONLY.items():
    for line in git('diff', '-U0', BASE, '--', path).splitlines():
        if line.startswith(('+++', '---', '@@', 'diff', 'index')) or not line.startswith(('+', '-')): continue
        body = line[1:]
        assert any((re.search(m, body) if m.startswith('^') else m in body) for m in markers), f'{path}: non-observer change: {line.strip()[:140]}'
print('REALTIME GUARD PASS changed=' + ','.join(sorted(changed)))

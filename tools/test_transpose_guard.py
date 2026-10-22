#!/usr/bin/env python3
"""Master Transpose / RIGHT Octave / ACMP-LEFT independence isolation. Baseline 034855a (Realtime audit + Metronome, CI-green, APK built).
Only the files that carry the new behaviour may change; the CASM transformer, chord detector/analyzer, MIDI parser/writer, native audio
(BASSMIDI/Oboe/metronome), voice resolver and Style Voice stores stay byte-identical. StyleSequencer may only change in lines that belong to the
chord-target transpose / ACMP gate vocabulary below (no scheduler, clock, CASM or voice changes)."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '034855a'
M = 'app/src/main/java/com/yourapp/'
ALLOWED = {
    M + 'arranger/ArrangerBrain.kt', M + 'yamahaarranger/arranger/StyleSequencer.kt', M + 'chord/ChordTranspose.kt',
    M + 'yamahaarranger/registration/RegistrationModel.kt', M + 'yamahaarranger/registration/RegistrationCodec.kt',
    M + 'yamahaarranger/registration/RecallPlanner.kt', M + 'yamahaarranger/registration/RegistrationLibraryController.kt',
    M + 'ui/MainViewModel.kt', M + 'ui/SxMainScreen.kt',
    'tests/rtdiag/TransposeAcmpLeftTest.kt', 'tests/cpv/TransposeRositaTest.kt', 'tests/cpv/MasterTransposeTest.kt', 'app/src/test/java/com/yourapp/audio/VoiceReleaseContractTest.kt',
    'app/src/test/java/com/yourapp/registration/TransposeOctaveAcmpRecallTest.kt',
    'tools/test_rtdiag.py', 'tools/test_cpv.py', 'tools/test_transpose_guard.py', 'tools/test_registration_recall.py',
    '.github/workflows/style-voice-editor.yml', 'docs/TRANSPOSE_OCTAVE_ACMP_LEFT_REPORT.md',
}
PROTECTED = (   # byte-identical to the baseline
    M + 'yamahaarranger/arranger/CasmNoteTransformer.kt', M + 'chord/ChordDetector.kt', M + 'chord/AcmpChordAnalyzer.kt',
    M + 'midi/MidiMixerOutput.kt', M + 'midi/MidiInputManager.kt',
    M + 'yamahaarranger/audio/AudioEngineManager.kt', M + 'yamahaarranger/audio/NativeAudioBridge.kt',
    M + 'yamahaarranger/audio/MetronomeModel.kt', M + 'yamahaarranger/audio/MetronomeController.kt',
    M + 'yamahaarranger/style/StyleVoiceRequests.kt', M + 'yamahaarranger/style/StyleVoiceEditorOps.kt', M + 'yamahaarranger/style/StyleOverrideStore.kt',
    M + 'yamahaarranger/registration/Sf2ExternalMapping.kt',
    'app/src/main/cpp',
)
SEQ_MARKERS = ('masterTranspose', 'Master Transpose', 'acmpHarmony', 'awaitChord', 'DROP_ACMP_OFF', 'masterApplied', 'setMasterTranspose',
               'val note=(transformed', 'val target=base', 'if(target==null||target==active.outputNote)', 'oldOctave', 'diagId)', 'ActiveTransposedNote(sourceChannel', 'val onId:Long',
               'CHORD-TARGET', 'ACMP gate', 'drum-only', 'diagnostic mirror', 'awaitingChord', 'Rhythm (destinations', '^\\s*\\*', '^\\s*/\\*\\*', '^\\s*}\\s*$', '^\\s*$',
               'fun setAcmpHarmony', 'fun acmpHarmonyEnabled', 'if(value!=null) awaitChord', 'if(!isDrumPart&&(!acmpHarmonyEnabled', '^\\s*return@lifecycle\\s*$', 'else { acmpHarmonyEnabled', 'if(enabled==acmpHarmonyEnabled)', 'if(enabled){ awaitChord', 'sourceHeaderPC', 'it never changes a pitch', 'chord is applied')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
for protected in PROTECTED:
    assert not git('diff', BASE, '--', protected).strip(), 'Protected path changed: ' + protected
for line in git('diff', '-U0', BASE, '--', M + 'yamahaarranger/arranger/StyleSequencer.kt').splitlines():
    if line.startswith(('+++', '---', '@@', 'diff', 'index')) or not line.startswith(('+', '-')): continue
    body = line[1:]
    assert any((re.search(m, body) if m.startswith('^') else m in body) for m in SEQ_MARKERS), f'StyleSequencer: out-of-scope change: {line.strip()[:140]}'
# scheduler / CASM / voice vocabulary must not appear in any added StyleSequencer line
for line in git('diff', '-U0', BASE, '--', M + 'yamahaarranger/arranger/StyleSequencer.kt').splitlines():
    if line.startswith('+') and not line.startswith('+++'):
        assert not re.search(r'delay\(|nanoTime|setChannelProgram|applyVoices|selectPolicy', line), 'StyleSequencer: scheduler/voice code added: ' + line[:120]
print('TRANSPOSE GUARD PASS changed=' + ','.join(sorted(changed)))

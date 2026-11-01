#!/usr/bin/env python3
"""E343 drum NOTE OFF experiment isolation (branch experiment/e343-drum-note-off). Baseline 9ae2089 (E343 audit, EnglishWaltz2 report).
Production may change ONLY: StyleSequencer (experiment object + 4 hooks), MidiMixerOutput/MidiInputManager (read-only probe + epoch),
ArrangerBrain (style name), MainViewModel (probe wiring + 4 functions), RightVoiceDiagOverlay (one row of chips).
Native/BASSMIDI, CASM, audio engine, Style parsing and every fixture except the new EnglishWaltz2 copy stay byte-identical. Default mode is LEGACY."""
import hashlib, re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '9ae2089'
J = 'app/src/main/java/com/yourapp/'
SEQ = J + 'yamahaarranger/arranger/StyleSequencer.kt'
ALLOWED = {SEQ, J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'arranger/ArrangerBrain.kt', J + 'ui/MainViewModel.kt',
           J + 'ui/RightVoiceDiagOverlay.kt', 'tests/fixtures/englishwaltz2/EnglishWaltz2.S519.prs',
           J + 'ui/E343ExperimentPanel.kt', 'tools/ui_preview/build.gradle.kts', 'tools/ui_preview/src/test/kotlin/E343ExperimentPanelTest.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'yamahaarranger/audio', J + 'audio', J + 'style', J + 'yamahaarranger/style', J + 'yamahaarranger/registration',
          J + 'chord', 'tests/fixtures/r23', 'tests/fixtures/hardrock2']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / BASSMIDI / CASM / audio / Style / fixtures changed'
for f in [J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'arranger/ArrangerBrain.kt', J + 'ui/MainViewModel.kt']:
    removed = [l for l in git('diff', '-U0', BASE, '--', f).splitlines() if l.startswith('-') and not l.startswith('---')]
    assert not removed, f + ': only additions allowed, removed ' + removed[0].strip()[:100]
removed = [l[1:].strip() for l in git('diff', '-U0', BASE, '--', SEQ).splitlines() if l.startswith('-') and not l.startswith('---')]
assert sorted(removed) == sorted(['midiInputManager.sendNoteOn(destinationChannel,midiNote,velocity)',
                   'io.stopSession(noteLifecycleLock,{ activeTransposedNotes.clear() },{ audioEngine.allNotesOff(); midiInputManager.allNotesOff() })']), removed
# RIGHT DIAG: layout was rebuilt (scrolling rows, CLOSE first, E343 page). Every original action / view-model call must still be there.
diag_old = git('show', BASE + ':' + J + 'ui/RightVoiceDiagOverlay.kt'); diag_new = (ROOT / J / 'ui/RightVoiceDiagOverlay.kt').read_text()
calls_old = set(re.findall(r'viewModel\.(\w+)\(', diag_old)) | set(re.findall(r'(RightVelocityTest\.set|ToolPanels\.diag = false)', diag_old))
calls_new = set(re.findall(r'viewModel\.(\w+)\(', diag_new)) | set(re.findall(r'(RightVelocityTest\.set|ToolPanels\.diag = false)', diag_new))
assert calls_old <= calls_new, 'RIGHT DIAG lost an action: ' + ', '.join(sorted(calls_old - calls_new))
for label in ['RESET STATS', 'LOG PROBE', 'SF2 PITCH/FX', 'MIDI OUT STATE', 'CLEAR OUT NOTES', 'CLOSE', 'TOUCH RESPONSE', 'RIGHT VELOCITY TEST', 'INTERNAL GAIN', 'E343 EXPERIMENT']:
    assert label in diag_new, 'RIGHT DIAG label missing: ' + label
panel = (ROOT / J / 'ui/E343ExperimentPanel.kt').read_text()
assert not re.search(r'^import com\.yourapp', panel, re.M) and 'viewModel' not in panel, 'the E343 page must stay portable (desktop-tested): no app imports'
seq = (ROOT / SEQ).read_text()
assert 'else midiInputManager.sendNoteOn(destinationChannel,midiNote,velocity)' in seq, 'LEGACY NOTE ON path must stay the same call'
assert '@Volatile var mode = E343DrumNoteOffMode.LEGACY; private set' in seq, 'default must be LEGACY'
assert 'const val CHANNEL = 9' in seq and 'const val NOTE = 26' in seq and 'contains("EnglishWaltz2", ignoreCase = true)' in seq, 'scope must stay EnglishWaltz2 / ch10 / note 26'
exp = seq[seq.index('open class E343DrumNoteOffExperiment'):]
assert 'audioEngine' not in exp and 'allNotesOff' not in exp and 'sendNoteOn' not in exp, 'experiment must not touch the engine or send All Notes Off'
fx = ROOT / 'tests/fixtures/englishwaltz2/EnglishWaltz2.S519.prs'
assert hashlib.sha256(fx.read_bytes()).hexdigest() == '90c9efb49ad1a7c3db4ae99b2f15cf4f4da9e2789f7e75efd6a3ab23df9e94a3', 'EnglishWaltz2 fixture must be the user file, byte-identical'
print('E343 DRUM NOTE OFF GUARD PASS changed=' + ','.join(sorted(changed)))

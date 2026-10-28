#!/usr/bin/env python3
"""RIGHT USB-MIDI velocity/touch isolation. Baseline 3550a92 (Style Editor V2, CI green with APK).
Allowed production changes: the RIGHT input probe + Touch Response module, the USB parser probe calls, the RIGHT note start in the Brain
(source tag + engine velocity only), the Virtual Keyboard probe call, the RIGHT DIAG overlay and the view-model wiring.
Style Sequencer, CASM, MIDI OUT router, native code, Style Editor V2 and the Style profile stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '3550a92'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'yamahaarranger/audio/RightUsbTouch.kt', J + 'arranger/ArrangerBrain.kt', J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt',
           J + 'ui/RightVoiceDiagOverlay.kt', J + 'yamahaarranger/audio/VirtualKeyboard.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'tools/test_', '.github/workflows/style-voice-editor.yml')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style', J + 'midi/MidiMixerOutput.kt', J + 'yamahaarranger/audio/AudioEngineManager.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / Style Sequencer / CASM / Style Editor V2 / MIDI OUT router / engine changed'
# Brain: only additions, plus the three lines that gain the source tag / engine velocity.
REMOVED_OK = ('private fun startRightNote(sourceNote: Int, velocity: Float, velocity127: Int) {', 'fun onKeyboardNoteOn(midiNote: Int, velocity: Float) {',
              'startRightNote(midiNote, velocity, velocity127)', 'engineNoteOn(channel, outputNote, RightVelocityTest.internalVelocity(velocity, velocity127))')
for path in (J + 'arranger/ArrangerBrain.kt', J + 'midi/MidiInputManager.kt', J + 'yamahaarranger/audio/VirtualKeyboard.kt'):
    for l in git('diff', '-U0', BASE, '--', path).splitlines():
        if l.startswith('-') and not l.startswith('---'):
            assert any(m in l for m in REMOVED_OK), path + ': out-of-scope removal: ' + l.strip()[:140]
# the LEFT, ACMP and chord paths must keep their original velocity vocabulary
brain = git('diff', '-U0', BASE, '--', J + 'arranger/ArrangerBrain.kt')
assert 'startLeftNote' not in ''.join(l for l in brain.splitlines() if l.startswith('+')), 'LEFT path touched'
# MIDI OUT velocity stays the raw keyboard value
src = (ROOT / J / 'arranger/ArrangerBrain.kt').read_text()
assert 'midiInputManager.sendNoteOn(channel, outputNote, RightVelocityTest.midiOutVelocity127(velocity127))' in src, 'MIDI OUT velocity changed'
print('RIGHT USB GUARD PASS changed=' + ','.join(sorted(changed)))

#!/usr/bin/env python3
"""MIDI OUT per-part octave correction isolation. Baseline 6875b39 (pitch-stability diagnostics, CI green with APK).
Production may change only in: the profile model/codec (StyleOverrideStore.kt, optional key), the MIDI OUT router + its manager pass-throughs (note write/pairing),
and the Style Editor dialog / view-model wiring. Style Sequencer, CASM, Brain, voice resolver, native code, audio engine/gain, velocity, Touch Response,
RIGHT/LEFT MIDI OUT and drum handling stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '6875b39'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'yamahaarranger/style/StyleOverrideStore.kt', J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt',
           J + 'ui/StyleVoiceEditorScreen.kt', J + 'ui/StyleVoiceEditorOverlay.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'arranger', J + 'yamahaarranger/arranger', J + 'yamahaarranger/audio', J + 'yamahaarranger/browser', J + 'yamahaarranger/registration',
          J + 'yamahaarranger/style/StyleVoiceEditorOps.kt', J + 'yamahaarranger/style/StyleVoiceRequests.kt', J + 'yamahaarranger/style/Sf2KeyCoverage.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'Sequencer / CASM / Brain / audio / voice resolver / native changed'
# Router: the only lines that may be replaced are the two note writers, the connection reset and the pending-off bookkeeping in releaseNotes.
REMOVED_OK = ('@Synchronized fun noteOn(channel: Int, note: Int, velocity: Int) {', 'if (write(byteArrayOf((0x90 or channel).toByte(), n.toByte(), velocity.coerceIn(1, 127).toByte()))) notes[channel][n]++',
              'if (!value) { notes.forEach { it.fill(0) }; pendingOff.fill(false) }', 'if (off(channel, n)) {', 'notes[channel][n] = (notes[channel][n] - 1).coerceAtLeast(0)',
              '@Synchronized fun noteOff(channel: Int, note: Int) {', 'val n = note.coerceIn(0, 127)', '// Never filter NOTE_OFF by the current mute/solo gain.',
              'val n = note.coerceIn(0, 127)', 'pendingOff[ch] = notes[ch].any { it > 0 }')
for l in git('diff', '-U0', BASE, '--', J + 'midi/MidiMixerOutput.kt').splitlines():
    if l.startswith('-') and not l.startswith('---'):
        assert any(m in l for m in REMOVED_OK), 'MidiMixerOutput: out-of-scope removal: ' + l.strip()[:140]
for path in (J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt', J + 'ui/StyleVoiceEditorOverlay.kt', J + 'ui/StyleVoiceEditorScreen.kt'):
    for l in git('diff', '-U0', BASE, '--', path).splitlines():
        if l.startswith('-') and not l.startswith('---'):
            body = l[1:].strip()
            assert body in ('fun allNotesOff() {', 'val record = currentOverrides', 'val hasExtras: Boolean = false,', 'fun editorHasExtras(): Boolean = currentOverrides?.let { it.octaveShifts.isNotEmpty() || it.drumMaps.isNotEmpty() } ?: false') \
                or 'w.octaveShifts != sv.octaveShifts' in body or 'setDrumMap = { channel, section, output, notes ->' in body or 'hasExtras = vm.editorHasExtras()' in body \
                or 'True when the working profile holds octave shifts or drum maps' in body or 'private fun executeOverrideChanges' in body or 'var confirmReset' in body \
                or 'if (confirmReset && actions != null) Dialog' in body or 'HeaderButton("SAVE PROFILE"' in body or 'Row(Modifier.fillMaxWidth().height(52.dp)' in body \
                or 'private fun SectionBar' in body or '@Composable' in body, path + ': out-of-scope removal: ' + body[:140]
# The sequencer / Brain / engine must not know about the correction
for path in (J + 'yamahaarranger/arranger/StyleSequencer.kt', J + 'arranger/ArrangerBrain.kt'):
    assert 'OctaveCorrection' not in (ROOT / path).read_text(), path + ' must not reference the MIDI OUT octave correction'
print('MIDI OUT OCTAVE GUARD PASS changed=' + ','.join(sorted(changed)))

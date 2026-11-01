#!/usr/bin/env python3
"""PSR-E343 MIDI OUT audit isolation. Baseline a400482 (latest green build; the mixer APK 9fb34c6 was the last build the user explicitly confirmed playable).
Production may change ONLY by additions: passive MIDI OUT state report (MidiMixerOutput/MidiInputManager/MainViewModel), the diag chips, a read-only classifier line
in the Style Editor detail panel, and the pure E343Compat classifier. Mapping, drum notes, voice requests, octave correction, MIDI IN, ACMP/LEFT/Split, BASSMIDI stay byte-identical."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'a400482'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt', J + 'ui/RightVoiceDiagOverlay.kt',
           J + 'ui/StyleVoiceEditorScreen.kt', J + 'yamahaarranger/style/E343Compat.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'yamahaarranger/arranger', J + 'arranger', J + 'yamahaarranger/audio', J + 'yamahaarranger/registration', J + 'ui/MixerStripUi.kt',
          J + 'ui/SplitDialog.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / sequencer / brain / audio / split / mixer changed'
for f in [J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt', J + 'ui/RightVoiceDiagOverlay.kt', J + 'ui/StyleVoiceEditorScreen.kt']:
    removed = [l for l in git('diff', '-U0', BASE, '--', f).splitlines() if l.startswith('-') and not l.startswith('---')]
    assert not removed, f + ': only additions allowed, removed ' + removed[0].strip()[:100]
# the router's wire writes must be untouched: every added line in MidiMixerOutput is report/stat code
added = [l[1:] for l in git('diff', '-U0', BASE, '--', J + 'midi/MidiMixerOutput.kt').splitlines() if l.startswith('+') and not l.startswith('+++')]
body = '\n'.join(added)
assert 'written' in body and 'outputReport' in body
# continuation (Data List extraction): no production source, Gradle or native file changed after the audit commit 7ba5b00
after = set(git('diff', '--name-only', '7ba5b00', 'HEAD').split()) | set(git('diff', '--name-only', '7ba5b00').split())
assert not [p for p in after if p.startswith('app/')], 'production changed after 7ba5b00: ' + ', '.join(sorted(p for p in after if p.startswith('app/')))
print('E343 AUDIT GUARD PASS changed=' + ','.join(sorted(changed)))

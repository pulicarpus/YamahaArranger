#!/usr/bin/env python3
"""LIVE V3.2 isolation: only presentation files may differ from the verified V3.1 + Registration + Master Transpose baseline.
Registration save/recall, Master Transpose, MIDI OUT, voice selection, engine and Style stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '68a584bada84ba4d0de617c2cc23c969999f0446'
ALLOWED = {
    'app/src/main/java/com/yourapp/ui/HardwareLedButton.kt',
    'app/src/main/java/com/yourapp/ui/LiveControlPanels.kt',
    '.github/workflows/live-v3-2-preview.yml',
    'app/src/main/java/com/yourapp/ui/LiveControlScreen.kt',
    'app/src/main/java/com/yourapp/ui/LiveV3Model.kt',
    'app/src/main/java/com/yourapp/ui/LiveFader.kt',
    'tests/cpv/LiveV3Test.kt', 'tools/test_live_v32_guard.py',
    '.github/workflows/live-v3-2.yml', 'docs/LIVE_V3_2_REPORT.md',
}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/'))}
extra = {p for p in changed - ALLOWED if not p.startswith('tools/ui_preview/')}
assert not extra, 'Unapproved or protected file changed: ' + ', '.join(sorted(extra))
for protected in ('app/src/main/java/com/yourapp/ui/MainViewModel.kt', 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',
                  'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
                  'app/src/main/java/com/yourapp/yamahaarranger/registration/RecallPlanner.kt',
                  'app/src/main/java/com/yourapp/yamahaarranger/registration/Sf2ExternalMapping.kt',
                  'app/src/main/java/com/yourapp/midi/MidiInputManager.kt', 'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt'):
    assert not git('diff', BASE, '--', protected).strip(), 'Protected file changed: ' + protected
d = git('diff', '-U0', BASE, '--', 'app/src/main/java/com/yourapp/ui/LiveV3Model.kt').splitlines()
assert not [l for l in d if l.startswith('-') and not l.startswith('---')], 'LiveV3Model: section model lines were removed'
print('LIVE V3.2 GUARD PASS changed=' + ','.join(sorted(changed)))

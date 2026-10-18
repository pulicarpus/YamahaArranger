#!/usr/bin/env python3
"""LIVE V3 isolation: only UI, presentation state, tests, docs and the V3 workflow may differ from the spec commit.
Engine, BASSMIDI/Oboe, MIDI OUT, Style/CASM/ACMP, note lifecycle and resolvers stay byte-identical."""
import subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'be75a8babb85b439d1a651cb4cb85978e97b8b3d'
ALLOWED = {
    'app/src/main/java/com/yourapp/ui/LiveControlScreen.kt',
    'app/src/main/java/com/yourapp/ui/LiveV3Model.kt',
    'app/src/main/java/com/yourapp/ui/LiveFader.kt',
    'app/src/main/java/com/yourapp/ui/MainViewModel.kt',
    'tests/cpv/LiveV3Test.kt', 'tools/test_cpv.py', 'tools/test_live_v3_guard.py',
    '.github/workflows/live-v3.yml', 'docs/LIVE_V3_REPORT.md',
}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/'))}
extra = changed - ALLOWED
assert not extra, 'Unapproved or protected file changed: ' + ', '.join(sorted(extra))
# MainViewModel: additions only, and only the read-only section inventory adapter.
diff = git('diff', BASE, '--', 'app/src/main/java/com/yourapp/ui/MainViewModel.kt').splitlines()
removed = [l for l in diff if l.startswith('-') and not l.startswith('---')]
added = [l for l in diff if l.startswith('+') and not l.startswith('+++')]
assert not removed, 'MainViewModel lines were removed: %r' % removed[:3]
joined = '\n'.join(added)
assert joined.count('LiveSectionInventory') == 3, joined
for word in ('arrangerBrain', 'audioEngine', 'midiInputManager', 'sendProgramChange', 'applyFonts'):
    assert word not in joined.replace('arrangerBrain.loadStyle', ''), 'V3 adapter touches ' + word
print('LIVE V3 GUARD PASS changed=' + ','.join(sorted(changed)))

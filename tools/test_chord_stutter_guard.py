#!/usr/bin/env python3
"""Chord-change stutter investigation = AUDIT ONLY. Baseline 16c673a (Master Transpose / RIGHT octave / ACMP-LEFT, CI SUCCESS with APK).
No production file (app/), no native code and no other test may change; only the diagnostic harness, its runner, the workflow and the report."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '16c673a'
ALLOWED = {
    'tests/rtdiag/ChordChangeStutterTest.kt', 'tests/rtdiag/RtRig.kt', 'tools/test_rtdiag.py', 'tools/test_chord_stutter_guard.py',
    '.github/workflows/rtdiag.yml', 'docs/CHORD_CHANGE_STUTTER_ROOT_CAUSE.md',
}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
assert not git('diff', BASE, '--', 'app', 'tests/cpv', 'tests/fixtures').strip(), 'production/app or other tests changed'
# RtRig may only gain the diagnostic hooks (style override + one-shot stall); every removed line is forbidden.
for line in git('diff', '-U0', BASE, '--', 'tests/rtdiag/RtRig.kt').splitlines():
    if line.startswith('-') and not line.startswith('---'):
        assert 'class RtRig' in line or 'brain.loadStyle(style(tempoBpm))' in line or 'spin(nativeCallCostUs)' in line, 'RtRig: unexpected removal: ' + line[:120]
print('CHORD STUTTER GUARD PASS changed=' + ','.join(sorted(changed)))

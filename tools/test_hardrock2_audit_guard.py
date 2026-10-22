#!/usr/bin/env python3
"""HardRock2 Main/Fill transition audit = AUDIT ONLY. Baseline 29ed56f (P1 ordered note I/O, CI green with APK). No production file, native code or other
suite may change; only the diagnostic harness, its rig hooks, the runner, the workflow, the supplied Style fixture and the reports."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '29ed56f'
ALLOWED = {'tests/rtdiag/SectionTransitionGapTest.kt', 'tests/rtdiag/RtRig.kt', 'tools/test_rtdiag.py', 'tools/test_transition_gap_guard.py',
           'tools/test_hardrock2_audit_guard.py', '.github/workflows/rtdiag.yml', 'docs/MAIN_FILL_TRANSITION_GAP_AUDIT.md',
           'docs/HARDROCK2_MAIN_FILL_TRANSITION_AUDIT.md', 'tests/fixtures/hardrock2/HardRock2.S578.prs'}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
assert not git('diff', BASE, '--', 'app', 'tests/cpv', 'tests/fixtures/r23').strip(), 'production/app or other suites changed'
for line in git('diff', '-U0', BASE, '--', 'tests/rtdiag/RtRig.kt').splitlines():
    if line.startswith('-') and not line.startswith('---'):
        assert 'stallThread' in line, 'RtRig: unexpected removal: ' + line[:120]
print('HARDROCK2 AUDIT GUARD PASS changed=' + ','.join(sorted(changed)))

#!/usr/bin/env python3
"""RIGHT audio fidelity isolation. Baseline f5f6d71 (RIGHT USB velocity probe + Touch Response, CI green with APK).
Only the Touch Response CUSTOM curve (RightUsbTouch.kt, its view-model state and overlay chips), tests, docs and CI files may change.
Native BASSMIDI code (gain, effects, envelopes), Brain, MIDI parser, Style Sequencer/CASM, MIDI OUT router and the engine stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'f5f6d71'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'yamahaarranger/audio/RightUsbTouch.kt', J + 'ui/MainViewModel.kt', J + 'ui/RightVoiceDiagOverlay.kt', 'tests/right_fidelity_real_bass_test.cpp'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'arranger', J + 'midi', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style', J + 'yamahaarranger/audio/AudioEngineManager.kt',
          J + 'yamahaarranger/audio/VirtualKeyboard.kt', J + 'yamahaarranger/audio/RightVoiceDiag.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / Brain / MIDI / Style / engine / Virtual Keyboard changed'
print('RIGHT FIDELITY GUARD PASS changed=' + ','.join(sorted(changed)))

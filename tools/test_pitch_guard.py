#!/usr/bin/env python3
"""RIGHT pitch-stability audit isolation. Baseline 702bcf3 (Internal Output Gain, CI green with APK).
Only a read-only SoundFont reader (Sf2PitchFx.kt), its RIGHT DIAG button / view-model report, tests, diagnostics, docs and CI files may change.
Native code (BASSMIDI player, engine, gain stage), Brain/velocity, Touch Response, MIDI parser/OUT, Style, Style Editor V2 and the Virtual Keyboard stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '702bcf3'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'yamahaarranger/audio/Sf2PitchFx.kt', J + 'ui/MainViewModel.kt', J + 'ui/RightVoiceDiagOverlay.kt',
           'tests/pitch_analysis.h', 'tests/pitch_analysis_test.cpp', 'tests/pitch_stability_real_bass_test.cpp'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'arranger', J + 'midi', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style', J + 'yamahaarranger/audio/AudioEngineManager.kt',
          J + 'yamahaarranger/audio/NativeAudioBridge.kt', J + 'yamahaarranger/audio/RightUsbTouch.kt', J + 'yamahaarranger/audio/InternalGain.kt',
          J + 'yamahaarranger/audio/VirtualKeyboard.kt', J + 'yamahaarranger/audio/RightVoiceDiag.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / Brain / velocity / MIDI / Style / gain / engine changed'
mvm = [l for l in git('diff', '-U0', BASE, '--', J + 'ui/MainViewModel.kt').splitlines() if l.startswith('-') and not l.startswith('---')]
assert not mvm, 'MainViewModel: only additions allowed'
print('PITCH GUARD PASS changed=' + ','.join(sorted(changed)))

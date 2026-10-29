#!/usr/bin/env python3
"""INTERNAL OUTPUT GAIN isolation. Baseline f81b286 (RIGHT fidelity audit + Touch Response Custom, CI green with APK).
Allowed: the new gain stage (internal_gain.h), its wiring in the engine (one process() call between the synth and the clamp, API/JNI), the
Kotlin bridge/manager/view-model/overlay additions, tests, docs, CI. BASSMIDI player (volumes, effects, envelopes), Brain/velocity, Touch Response,
MIDI parser/OUT router, Style Sequencer/CASM, Style Editor V2 and the Virtual Keyboard stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'f81b286'
J = 'app/src/main/java/com/yourapp/'
C = 'app/src/main/cpp/'
ALLOWED = {C + 'internal_gain.h', C + 'audio_engine.cpp', C + 'audio_engine.h', C + 'native_lib.cpp', J + 'yamahaarranger/audio/NativeAudioBridge.kt',
           J + 'yamahaarranger/audio/AudioEngineManager.kt', J + 'yamahaarranger/audio/InternalGain.kt', J + 'ui/MainViewModel.kt', J + 'ui/RightVoiceDiagOverlay.kt',
           'tests/internal_gain_test.cpp', 'tests/right_fidelity_real_bass_test.cpp'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = [C + 'bassmidi_player.cpp', C + 'bassmidi_player.h', J + 'arranger', J + 'midi', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style',
          J + 'yamahaarranger/audio/RightUsbTouch.kt', J + 'yamahaarranger/audio/VirtualKeyboard.kt', J + 'yamahaarranger/audio/RightVoiceDiag.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'BASSMIDI player / Brain / velocity / MIDI / Style / Touch Response / Virtual Keyboard changed'
# engine + manager: additions only (the single process() call and the API lines); nothing removed
for path in (C + 'audio_engine.cpp', C + 'audio_engine.h', C + 'native_lib.cpp', J + 'yamahaarranger/audio/NativeAudioBridge.kt', J + 'yamahaarranger/audio/AudioEngineManager.kt'):
    removed = [l for l in git('diff', '-U0', BASE, '--', path).splitlines() if l.startswith('-') and not l.startswith('---')]
    assert not removed, path + ': only additions allowed, removed ' + removed[0].strip()[:100]
eng = git('diff', '-U0', BASE, '--', C + 'audio_engine.cpp')
added = [l[1:] for l in eng.splitlines() if l.startswith('+') and not l.startswith('+++')]
assert any('internalGain_.process(out, sampleCount);' in l for l in added), 'gain stage call missing'
assert not any('std::max(-1.0f' in l or 'metronome' in l for l in added), 'clamp / metronome touched'
print('INTERNAL GAIN GUARD PASS changed=' + ','.join(sorted(changed)))

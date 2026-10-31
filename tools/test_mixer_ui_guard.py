#!/usr/bin/env python3
"""Mixer redesign + live MIDI keyboard mirror isolation. Baseline dfb22c4 (Style Editor BACK fix; CI green with APK).
Production may change only in: the Mixer page / strips / keyboard panel (UI), two passive observers in the MIDI input manager and the MIDI OUT router
(LiveKeyState / ChannelActivity writes), a one-store live output level in the gain stage (native, measure only) and its accessor, and view-model accessors.
BASSMIDI player, audio callback processing, Brain, Style Sequencer / CASM, MIDI OUT octave correction, drum mapping, voice resolver, registration, transpose,
profile store and every routing decision stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'dfb22c4'
J = 'app/src/main/java/com/yourapp/'
C = 'app/src/main/cpp/'
ALLOWED = {J + 'midi/LiveKeyState.kt', J + 'midi/ChannelActivity.kt', J + 'midi/MidiInputManager.kt', J + 'midi/MidiMixerOutput.kt',
           J + 'ui/MixerStripUi.kt', J + 'ui/MixerPure.kt', J + 'ui/SxMainScreen.kt', J + 'ui/VirtualKeyboardPanel.kt', J + 'ui/MainViewModel.kt',
           J + 'yamahaarranger/audio/NativeAudioBridge.kt', J + 'yamahaarranger/audio/AudioEngineManager.kt',
           C + 'internal_gain.h', C + 'audio_engine.h', C + 'native_lib.cpp', 'tests/internal_gain_test.cpp', 'tests/sff5_jvm_stubs/MidiPlatform.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = [C + 'bassmidi_player.cpp', C + 'bassmidi_player.h', C + 'audio_engine.cpp', J + 'arranger', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style',
          J + 'yamahaarranger/browser', J + 'yamahaarranger/registration', J + 'yamahaarranger/audio/InternalGain.kt', J + 'yamahaarranger/audio/RightUsbTouch.kt',
          J + 'yamahaarranger/audio/VirtualKeyboard.kt', J + 'yamahaarranger/audio/RightVoiceDiag.kt', J + 'ui/StyleVoiceEditorScreen.kt', J + 'ui/StyleVoiceEditorOverlay.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'BASSMIDI / audio callback / Brain / Style / octave correction / voice resolver changed'
# additions only (no removed line) in the router, input manager, engine glue, view-model, bridge
for path in (J + 'midi/MidiMixerOutput.kt', J + 'midi/MidiInputManager.kt', J + 'ui/MainViewModel.kt', J + 'yamahaarranger/audio/NativeAudioBridge.kt',
             J + 'yamahaarranger/audio/AudioEngineManager.kt', C + 'audio_engine.h', C + 'native_lib.cpp'):
    removed = [l for l in git('diff', '-U0', BASE, '--', path).splitlines() if l.startswith('-') and not l.startswith('---')]
    assert not removed, path + ': only additions allowed, removed ' + removed[0].strip()[:100]
gain = [l for l in git('diff', '-U0', BASE, '--', C + 'internal_gain.h').splitlines() if l.startswith('-') and not l.startswith('---')]
assert all('std::atomic<float> db_{0.0f}, lin_{1.0f}, peakPreMax_' in l for l in gain), 'internal_gain.h: only the member declaration line may be rewritten'
added = [l[1:] for l in git('diff', '-U0', BASE, '--', C + 'internal_gain.h').splitlines() if l.startswith('+') and not l.startswith('+++')]
assert not any(('x[i] =' in l or 'x[i]=' in l) for l in added), 'gain processing must not change'
# the router gained exactly one line, a passive indicator write after a successful NOTE_ON
rt = [l for l in git('diff', '-U0', BASE, '--', J + 'midi/MidiMixerOutput.kt').splitlines() if l.startswith('+') and not l.startswith('+++')]
assert len(rt) == 1 and 'ChannelActivity.noteOn(' in rt[0], 'MidiMixerOutput: exactly one passive line expected'
mgr = (ROOT / J / 'midi/MidiInputManager.kt').read_text()
assert 'mixerOutput.noteOn(channel, note, velocity)' in mgr and 'onNoteOn?.invoke(d1, d2)' in mgr and 'onNoteOff?.invoke(d1)' in mgr, 'routing calls must stay'
# nothing on the playing side may read the new state
for path in (J + 'arranger/ArrangerBrain.kt', J + 'yamahaarranger/arranger/StyleSequencer.kt', J + 'yamahaarranger/audio/AudioEngineManager.kt'):
    text = (ROOT / path).read_text()
    assert 'LiveKeyState' not in text and 'liveKeys' not in text and 'ChannelActivity' not in text, path + ' must not know the UI indicators'
print('MIXER UI GUARD PASS changed=' + ','.join(sorted(changed)))

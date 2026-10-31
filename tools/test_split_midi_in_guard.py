#!/usr/bin/env python3
"""MIDI IN left zone diagnostics + adjustable split isolation. Baseline 9fb34c6 (Mixer redesign + live key mirror; CI green with APK).
Production may change only in: the MIDI input parser's note acceptance (channel filter made configurable, default = baseline channel 1 only) with passive stats,
the key-state display layer, a non-resetting split setter in the Brain (the routing branches are untouched), view-model state/persistence, and the UI (SPLIT key, dialog,
keyboard panel). BASSMIDI, the audio callback, CASM / chord recognition, Style sequencing, MIDI OUT router and octave correction, drum mapping, voice resolver,
Internal Output Gain, registration and transpose stay byte-identical."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '9fb34c6'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'midi/MidiInputManager.kt', J + 'midi/LiveKeyState.kt', J + 'midi/MidiInStats.kt', J + 'yamahaarranger/audio/SplitPoint.kt', J + 'arranger/ArrangerBrain.kt',
           J + 'ui/MainViewModel.kt', J + 'ui/SplitDialog.kt', J + 'ui/LiveControlPanels.kt', J + 'ui/LiveControlScreen.kt', J + 'ui/MainActivity.kt', J + 'ui/VirtualKeyboardPanel.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'midi/MidiMixerOutput.kt', J + 'midi/ChannelActivity.kt', J + 'yamahaarranger/arranger', J + 'yamahaarranger/style', J + 'yamahaarranger/browser',
          J + 'yamahaarranger/registration', J + 'yamahaarranger/audio/AudioEngineManager.kt', J + 'yamahaarranger/audio/InternalGain.kt', J + 'yamahaarranger/audio/VirtualKeyboard.kt',
          J + 'yamahaarranger/audio/NativeAudioBridge.kt', J + 'ui/MixerStripUi.kt', J + 'ui/SxMainScreen.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'BASSMIDI / audio / MIDI OUT router / CASM / Style / mixer changed'
# Brain: only additions, and the routing branches stay as they were
removed = [l for l in git('diff', '-U0', BASE, '--', J + 'arranger/ArrangerBrain.kt').splitlines() if l.startswith('-') and not l.startswith('---')]
assert not removed, 'ArrangerBrain: only additions allowed, removed ' + removed[0].strip()[:100]
brain = (ROOT / J / 'arranger/ArrangerBrain.kt').read_text()
for line in ('if (midiNote > splitNote) {', 'if (!acmpEnabled && !leftVoiceEnabled) {', 'if (acmpEnabled) {', 'if (leftVoiceEnabled) startLeftNote(midiNote, velocity, velocity127)'):
    assert line in brain, 'routing contract line missing: ' + line
added = [l[1:] for l in git('diff', '-U0', BASE, '--', J + 'arranger/ArrangerBrain.kt').splitlines() if l.startswith('+') and not l.startswith('+++')]
fn = '\n'.join(added)
assert not re.search(r'chordDetector\.reset|chordHeld\.clear|appliedChord\s*=|commitTranspose', fn), 'the split setter must not reset chord context or transpose'
# Input manager: playing path (onNoteOn/onNoteOff calls, mixer output calls) still present; default accepted channel is the baseline channel 1
mgr = (ROOT / J / 'midi/MidiInputManager.kt').read_text()
for line in ('onNoteOn?.invoke(d1, d2)', 'onNoteOff?.invoke(d1)', 'mixerOutput.noteOn(channel, note, velocity)', 'var chordInputChannel: Int = 0', '@Volatile var extraInputChannels: Int = 0'):
    assert line in mgr, 'input path line missing: ' + line
rm = [l for l in git('diff', '-U0', BASE, '--', J + 'midi/MidiInputManager.kt').splitlines() if l.startswith('-') and not l.startswith('---')]
assert all(('channel == chordInputChannel' in l or 'onNoteOn?.invoke' in l or 'onNoteOff?.invoke' in l or 'liveKeys.' in l or 'LatencyProbe.noteOnParsed' in l or 'DebugLog.add("🎹 IN' in l or l[1:].strip() in ('}', 'when (type) {', 'else {', '} else {', 'if (d2 > 0) {') or '0x90 ->' in l or '0x80 ->' in l) for l in rm), 'MidiInputManager: unexpected removed line'
print('SPLIT / MIDI IN GUARD PASS changed=' + ','.join(sorted(changed)))

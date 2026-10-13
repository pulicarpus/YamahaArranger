#!/usr/bin/env python3
"""Integration contract: exact device-accepted E343 routing + UI-only polish."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
BASE = '9915a491824e4f61871daa0f621a534ac0f25d0b'
UI = 'app/src/main/java/com/yourapp/ui/SxMainScreen.kt'
LAYOUT = 'app/src/main/java/com/yourapp/ui/CompactMixerLayout.kt'
def before(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], cwd=ROOT, text=True)
# Explicit device checkpoint identity, not a token-based approximation.
for path in ['app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt', 'app/src/main/java/com/yourapp/ui/MainViewModel.kt', 'app/src/test/java/com/yourapp/audio/MidiMixerOutputTest.kt']:
    assert (ROOT / path).read_text() == before(path), f'E343 checkpoint altered: {path}'
changed = subprocess.check_output(['git', 'diff', '--name-only', BASE, '--', 'app/src/main'], cwd=ROOT, text=True).splitlines()
assert set(changed) == {UI, LAYOUT}, f'Unexpected production changes: {changed}'
s = (ROOT / UI).read_text()
b = before(UI)
assert s[s.index('@Composable'):s.index('private fun SxMixerPage')] == b[b.index('@Composable'):b.index('private fun SxMixerPage')], 'Main arranger controls changed'
def gestures(text):
    start = text.index('private fun SxVerticalFader')
    return text[start:text.index('        val pad =', start)]
assert gestures(s) == gestures(b), 'Fader gestures / accessibility / value routing changed'
page = s[s.index('private fun SxMixerPage'):s.index('private fun SxConsoleStrip')]
assert page.count('horizontalScroll(') == 1
fixed = page[page.index('// Six fixed strips'):page.index('if (voices.isEmpty())')]
assert 'horizontalScroll' not in fixed and 'state.rightVoices.forEach' in fixed
assert 'SxGreen, stripWidth) { busDetail = "STYLE" }' in fixed
assert 'SxMixerMaster, stripWidth) { busDetail = "MASTER" }' in fixed
for action in ['keyboardPicker = ch', 'voicePickerSlot = slot', 'vm.toggleMixerKeyboardMute(ch)', 'vm.toggleMixerSolo(ch)', 'onMute(slot.channel)', 'keyboardVolumeCallbacks[ch]', 'vm.setStyleChannelVolume(slot.channel, it)', 'onDismiss']:
    assert action in page, action
strip = s[s.index('private fun SxConsoleStrip'):s.index('private fun SxVerticalFader')]
assert 'SxConsoleSwitch("MUTE"' in strip and 'SxConsoleSwitch("SOLO"' in strip
assert '.height(48.dp)' in strip and 'Modifier.fillMaxWidth()' in strip
assert '.height(144.dp)' not in strip, 'Oversized bus blank header returned'
# Existing isolated-branch guards are preserved verbatim, with their original SHA.
for guard, ref in [('tools/test_e343_program_isolation.py', BASE), ('tools/test_compact_mixer_ui.py', '4ab3b81a67f812437ca9307133e31ee61a58cf54')]:
    assert (ROOT / guard).read_bytes() == subprocess.check_output(['git','show', ref + ':' + guard], cwd=ROOT)
print('PASS: exact E343 routing/recorder tests, UI-only production diff, fixed/scroll/DETAIL bindings, unchanged fader input and arranger controls; old guards intact')

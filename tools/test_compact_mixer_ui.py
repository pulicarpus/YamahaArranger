#!/usr/bin/env python3
"""Bounded UI-only change guard; existing checkpoint guards remain mandatory."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
BASE = '2704d0bb8e2aee214af9e36ebccf2f7c2a3958af'
allowed = {'app/src/main/java/com/yourapp/ui/SxMainScreen.kt',
           'app/src/main/java/com/yourapp/ui/CompactMixerLayout.kt'}
changed = subprocess.check_output(['git', 'diff', '--name-only', BASE, '--', 'app/src/main'], cwd=ROOT, text=True).splitlines()
assert set(changed) <= allowed, f'Non-UI production change: {set(changed) - allowed}'
s = (ROOT / 'app/src/main/java/com/yourapp/ui/SxMainScreen.kt').read_text()
page = s[s.index('private fun SxMixerPage'):s.index('private fun SxConsoleStrip')]
assert page.count('horizontalScroll(') == 1
fixed = page[page.index('// Six fixed strips'):page.index('if (voices.isEmpty())')]
assert 'horizontalScroll' not in fixed
assert 'state.rightVoices.forEach' in fixed and 'SxConsoleBus("STYLE"' in fixed and 'SxConsoleBus("MASTER"' in fixed
assert 'SxMixerMaster, stripWidth' in fixed
assert 'stripWidth = stripWidth' in page
for action in ['keyboardPicker = ch', 'voicePickerSlot = slot', 'vm.toggleMixerKeyboardMute(ch)', 'vm.toggleMixerSolo(ch)', 'onMute(slot.channel)', 'onDismiss']:
    assert action in page, action
strip = s[s.index('private fun SxConsoleStrip'):s.index('private fun SxVerticalFader')]
assert 'SxConsoleSwitch("MUTE"' in strip and 'SxConsoleSwitch("SOLO"' in strip
assert 'Row(Modifier.fillMaxWidth())' not in strip
assert '.height(48.dp)' in strip and 'Modifier.fillMaxWidth()' in strip
base = subprocess.check_output(['git', 'show', BASE + ':app/src/main/java/com/yourapp/ui/SxMainScreen.kt'], cwd=ROOT, text=True)
def fader(text):
    return text[text.index('private fun SxVerticalFader'):text.index('@OptIn(ExperimentalFoundationApi::class)', text.index('private fun SxVerticalFader'))]
assert fader(s) == fader(base), 'Fader gesture or volume mapping changed'
# Main arranger including RELEASE and RIGHT OFF controls must remain byte-identical.
assert s[s.index('@Composable'):s.index('private fun SxMixerPage')] == base[base.index('@Composable'):base.index('private fun SxMixerPage')]
print('PASS: UI-only production diff, fixed/scroll separation, controls and unchanged fader/arranger contracts')

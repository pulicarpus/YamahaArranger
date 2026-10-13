#!/usr/bin/env python3
"""Additive experiment gate: only keyboard auto Bank/PC routing may change."""
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = '2704d0bb8e2aee214af9e36ebccf2f7c2a3958af'
VM = 'app/src/main/java/com/yourapp/ui/MainViewModel.kt'
ROUTER = 'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt'

def before(path):
    return subprocess.check_output(['git', 'show', f'{BASE}:{path}'], cwd=ROOT).decode()

def verify():
    original = before(VM)
    start = original.index('            for (channel in 0..3) {', original.index('    fun toggleMidiOut()'))
    end = original.index('            midiInputManager.setMixerMasterVolume', start)
    assert (ROOT / VM).read_text() == original[:start] + original[end:], 'Unreviewed ViewModel change beyond automatic keyboard Bank/PC removal'
    expected = before(ROUTER).replace(
        'if (active() && channel in channels) replayChannel(channel)',
        'if (active() && channel in channels) replayChannel(channel, includeProgram = true)'
    ).replace('    private fun replayChannel(ch: Int) {\n        programs[ch]?.let { p ->',
              '    private fun replayChannel(ch: Int, includeProgram: Boolean = ch !in 0..3) {\n'
              '        // Keyboard bank/program is sent only by an explicit voice selection,\n'
              '        // never by output enable or reconnect. Style replay stays unchanged.\n'
              '        if (includeProgram) programs[ch]?.let { p ->')
    assert (ROOT / ROUTER).read_text() == expected, 'Unreviewed router change: note lifecycle / locks / mixer must remain identical'
    changed = subprocess.check_output(['git', 'diff', '--name-only', BASE, '--', 'app/src/main'], cwd=ROOT, text=True).splitlines()
    assert set(changed) == {VM, ROUTER}, 'Unexpected production change: ' + repr(changed)
    print('E343_PROGRAM_ISOLATION PASS base=2704d0b only_keyboard_auto_Bank_PC_changed=true notes_locks_style_release_rightOff_BASS_unchanged=true')

if __name__ == '__main__':
    verify()

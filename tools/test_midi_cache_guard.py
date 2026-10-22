#!/usr/bin/env python3
"""MIDI OUT voice-cache experiment isolation. Baseline 29ed56f (P1 ordered note I/O, CI green with APK).
Production may change only in MidiMixerOutput.kt (router + cache), MidiInputManager.kt (two added pass-through methods) and StyleSequencer.kt
(exactly the explicit-reapply calls `forgetSentVoice`). No native code, CASM, scheduler, P1 note I/O or Style fixture other than the supplied HardRock2 may change."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '29ed56f'
MIX = 'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt'
MGR = 'app/src/main/java/com/yourapp/midi/MidiInputManager.kt'
SEQ = 'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'
ALLOWED = {MIX, MGR, SEQ, 'app/src/test/java/com/yourapp/audio/MidiMixerOutputTest.kt',
           'tests/rtdiag/RtRig.kt', 'tests/rtdiag/SectionTransitionGapTest.kt', 'tools/test_rtdiag.py', 'tools/test_midi_cache_guard.py',
           '.github/workflows/style-voice-editor.yml', 'tests/fixtures/hardrock2/HardRock2.S578.prs',
           'docs/HARDROCK2_MAIN_FILL_TRANSITION_AUDIT.md', 'docs/MAIN_FILL_TRANSITION_GAP_AUDIT.md', 'docs/MIDI_OUT_VOICE_CACHE_REPORT.md'}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
assert not git('diff', BASE, '--', 'app/src/main/cpp', 'tests/fixtures/r23', 'tests/cpv').strip(), 'native code / other suites changed'
def lines(path, sign):
    return [l[1:] for l in git('diff', '-U0', BASE, '--', path).splitlines() if l.startswith(sign) and not l.startswith(('+++', '---'))]
assert not lines(MGR, '-'), 'MidiInputManager: only additions allowed'
assert all('forget' in l or l.strip().startswith('///') or l.strip().startswith('/**') or not l.strip() for l in lines(MGR, '+')), 'MidiInputManager: unexpected added line'
removed = lines(SEQ, '-'); added = lines(SEQ, '+')
assert len(removed) == 1 and 'midiInputManager.sendProgramChange(channel,external.program,external.msb,external.lsb)' in removed[0], 'StyleSequencer: unexpected removal'
assert all('forgetSentVoice' in l for l in added), 'StyleSequencer: only forgetSentVoice lines may be added: ' + str([l for l in added if 'forgetSentVoice' not in l])
for line in git('diff', '-U0', BASE, '--', 'tests/rtdiag/RtRig.kt').splitlines():
    if line.startswith('-') and not line.startswith('---'):
        assert 'stallThread' in line, 'RtRig: unexpected removal: ' + line[:120]
print('MIDI CACHE GUARD PASS changed=' + ','.join(sorted(changed)))

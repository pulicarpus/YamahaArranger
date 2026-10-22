#!/usr/bin/env python3
"""P1 experiment isolation. Baseline 16c673a (production baseline, CI green + APK). Only StyleSequencer.kt may change among production files, and only the
note-I/O path: removed lines must be engine/MIDI OUT/log/lifecycle/STOP lines; added lines must not touch CASM, the tempo clock, voices or the scheduler wait."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '16c673a'
SEQ = 'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'
ALLOWED = {SEQ, 'tests/rtdiag/OrderedNoteIoUnitTest.kt', 'tests/rtdiag/P1ChainTest.kt', 'tests/rtdiag/ChordChangeStutterTest.kt', 'tests/rtdiag/RtRig.kt',
           'tools/test_rtdiag.py', 'tools/test_p1_guard.py', '.github/workflows/style-voice-editor.yml', '.github/workflows/rtdiag.yml',
           'docs/CHORD_CHANGE_STUTTER_ROOT_CAUSE.md', 'docs/P1_ORDERED_NOTE_IO_REPORT.md'}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
assert not git('diff', BASE, '--', 'app/src/main/cpp', 'tests/cpv', 'tests/fixtures').strip(), 'native code / other suites changed'
REMOVED_OK = ('audioEngine.note', 'audioEngine.allNotesOff', 'midiInputManager.send', 'midiInputManager.allNotesOff', 'activeTransposedNotes.clear', 'DebugLog.add',
              'val requested=f12Timing.begin()', 'return synchronized(noteLifecycleLock)', 'val acquired=', 'try { block() } finally { f12Timing.finish', 'lifecycle {',
              'if(chordTrace.active()) audioEngine', 'else audioEngine', 'active.sourceChannel,active.sourceNote,active.sourceBank', 'sourceChannel,s.event.note,diagBank', '^\\s*}\\s*$')
ADDED_FORBIDDEN = re.compile(r'CasmNoteTransformer|tickDeadlineNanos|TempoClock|waitForClockChange|selectPolicy\(|setChannelProgram|applyVoices|musicalClockLock|delay\(')
for line in git('diff', '-U0', BASE, '--', SEQ).splitlines():
    if line.startswith(('+++', '---', '@@', 'diff', 'index')): continue
    body = line[1:]
    if line.startswith('-'):
        assert any((re.search(m, body) if m.startswith('^') else m in body) for m in REMOVED_OK), 'StyleSequencer: out-of-scope removal: ' + body.strip()[:140]
    elif line.startswith('+'):
        assert not ADDED_FORBIDDEN.search(body), 'StyleSequencer: forbidden vocabulary added: ' + body.strip()[:140]
print('P1 GUARD PASS changed=' + ','.join(sorted(changed)))

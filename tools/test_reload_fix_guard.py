#!/usr/bin/env python3
"""Style Editor reload crash fix isolation. Baseline 27d8bc6 (MIDI OUT voice cache, CI green with APK).
Production may change only in MainViewModel.kt (Style-load restore: lock-free pdta read, off the main thread, stage log), AudioEngineManager.kt
(one added lock-free reader) and StyleOverrideStore.kt (added restorer). Parser, CASM, scheduler, StyleSequencer, MIDI OUT and native code stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '27d8bc6'
VM = 'app/src/main/java/com/yourapp/ui/MainViewModel.kt'
ENG = 'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt'
STORE = 'app/src/main/java/com/yourapp/yamahaarranger/style/StyleOverrideStore.kt'
ALLOWED = {VM, ENG, STORE, 'tests/cpv/StyleReloadFlowTest.kt', 'tools/test_reload_fix_guard.py', '.github/workflows/style-voice-editor.yml', 'docs/STYLE_EDITOR_RELOAD_CRASH_REPORT.md'}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = changed - ALLOWED
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', 'app/src/main/java/com/yourapp/yamahaarranger/arranger', 'app/src/main/java/com/yourapp/midi',
          'app/src/main/java/com/yourapp/style', 'app/src/main/java/com/yourapp/arranger', 'tests/fixtures', 'tests/rtdiag', 'app/src/test']
assert not git('diff', BASE, '--', *frozen).strip(), 'parser/CASM/scheduler/MIDI OUT/native or other suites changed'
store_removed = [l for l in git('diff', '-U0', BASE, '--', STORE).splitlines() if l.startswith('-') and not l.startswith('---')]
assert not store_removed, 'StyleOverrideStore: only additions allowed'
eng_removed = [l for l in git('diff', '-U0', BASE, '--', ENG).splitlines() if l.startswith('-') and not l.startswith('---')]
assert not eng_removed, 'AudioEngineManager: only additions allowed'
print('RELOAD FIX GUARD PASS changed=' + ','.join(sorted(changed)))

#!/usr/bin/env python3
"""Current LIVE/REG functional JVM suite plus the voice-recall regression class (additive; pinned scripts untouched)."""
import runpy, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import test_voice_release as old
original = old.run
EXTRA = ['com.yourapp.yamahaarranger.registration.RegistrationVoiceRecallTest']
def augmented(command, *args, **kwargs):
    if 'org.junit.runner.JUnitCore' in command and 'com.yourapp.yamahaarranger.registration.RegistrationPersistenceTest' in command:
        command = list(command) + EXTRA
    return original(command, *args, **kwargs)
old.run = augmented
try:
    sys.argv = ['test_r22_regressions.py', '--suite', 'live', '--out', str(ROOT / 'build/reg-voice-recall')]
    runpy.run_path(str(ROOT / 'tools/test_r22_regressions.py'), run_name='__main__')
finally:
    old.run = original
print('REG VOICE RECALL SUITE PASS classes=' + ','.join(EXTRA))

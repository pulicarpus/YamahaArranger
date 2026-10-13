#!/usr/bin/env python3
"""Phase 0–1: every pre-existing production file and guard is byte-identical."""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
BASE = 'ea13199f69b4757d69e72c55cac45b9b9079aec3'
NEW_MAIN = {'app/src/main/java/com/yourapp/yamahaarranger/registration/' + f + '.kt'
            for f in ('RegistrationModel','RegistrationCodec','AtomicBankStore')}
files = subprocess.check_output(['git','ls-tree','-r','--name-only',BASE],cwd=ROOT,text=True).splitlines()
protected = files  # Stronger than the forbidden list: no existing tracked file may change.
for path in protected:
    original = subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT)
    assert (ROOT/path).is_file() and (ROOT/path).read_bytes() == original, 'Baseline file changed: '+path
actual = {str(p.relative_to(ROOT)) for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}
extra = actual - set(files)
assert extra <= NEW_MAIN, 'Unapproved production addition: '+repr(extra-NEW_MAIN)
for path in extra:
    text = (ROOT/path).read_text()
    for forbidden in ('AudioEngineManager','MidiInputManager','MidiMixerOutput','ArrangerBrain','applyFonts','sendProgramChange','android.', 'RegistrationCoordinator','RecallPlanner'):
        assert forbidden not in text, 'Runtime coupling: '+path+' '+forbidden
print(f'LIVE_REG_ISOLATION PASS base={BASE} protected_files={len(protected)} phase=0-1 runtime_coupling=none')

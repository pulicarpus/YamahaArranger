#!/usr/bin/env python3
from pathlib import Path
import subprocess, re
ROOT=Path(__file__).resolve().parents[1]
BASE='48f4c0353bb94d7340cbb61566766a3c2feda2d6'
VM='app/src/main/java/com/yourapp/ui/MainViewModel.kt'
WORKFLOW='.github/workflows/live-control-reg-phase01.yml'
files=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE],cwd=ROOT,text=True).splitlines()
for p in files:
    if p == WORKFLOW:
        before=subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT,text=True)
        expected=before.replace('python3 tools/test_live_reg.py --baseline --real --fetch-dependencies','python3 tools/test_live_reg_phase2.py --history --real --fetch-dependencies')
        assert (ROOT/p).read_text()==expected, 'Unapproved workflow change'
    elif p != VM:
        assert (ROOT/p).is_file() and (ROOT/p).read_bytes()==subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT), 'Protected file changed: '+p
s=(ROOT/VM).read_text()
start=s.index('\n    // BEGIN REG PHASE2 ADDITIONS'); end=s.index('    // END REG PHASE2 ADDITIONS',start)+len('    // END REG PHASE2 ADDITIONS\n')
block=s[start:end]
remaining=s[:start]+s[end:]
remaining=remaining.replace('// BEGIN REG PHASE2 IMPORT\nimport kotlinx.coroutines.async\n// END REG PHASE2 IMPORT\n','',1)
assert remaining==subprocess.check_output(['git','show',BASE+':'+VM],cwd=ROOT,text=True), 'Legacy ViewModel changed'
allowed={'app/src/main/java/com/yourapp/yamahaarranger/registration/'+n+'.kt' for n in ['RecallPlanner','RegistrationCoordinator']}
extra={str(p.relative_to(ROOT)) for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}-set(files)
assert extra==allowed, 'Unexpected production files: '+repr(extra)
code=re.sub(r'//[^\n]*','',block)
for word in ['startStop(', 'selectSection(', 'selectMainVariation(', 'loadStyle(', 'allNotesOff(', 'toggleMidiOut(', 'setMixerStyleChannels(', 'native', 'applyFonts(']:
    assert word not in code, 'Forbidden recall call: '+word
sf2=block[block.index('suspend fun applyKeyboardVoice'):block.index('suspend fun awaitRegistrationVoicesIdle')]
assert 'sendProgramChange' not in sf2 and 'mirrorToMidiOut || timeoutMs <= 0' in sf2
assert 'part.channel,voice.program,voice.msb,voice.lsb' in block
assert 'brain.leftVoiceEnabled' in block and 'mirrorToMidiOut=false' in block
assert 'by lazy' in block and 'init {' not in block
print(f'LIVE_REG_PHASE2_ISOLATION PASS base={BASE} protected_files={len(files)-2} legacy_VM_byte_identical=true allowed_additions=planner_coordinator_VM_block')

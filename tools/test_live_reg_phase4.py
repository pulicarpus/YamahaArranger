#!/usr/bin/env python3
"""Phase 4 tests at HEAD; immutable Phase 3 and all earlier snapshots, real PCM mandatory in CI."""
import argparse,os,sys,subprocess,tempfile,shutil
from pathlib import Path
os.environ['PYTHONDONTWRITEBYTECODE']='1';sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1];BASE='d03e7fb897ac15e07a80e02eb4bf5a4b1da9fe45'
def run(cmd,cwd=ROOT):subprocess.run(cmd,cwd=cwd,check=True)
p=argparse.ArgumentParser();p.add_argument('--history',action='store_true');p.add_argument('--real',action='store_true');p.add_argument('--fetch-dependencies',action='store_true');args=p.parse_args()
run(['python3','tools/test_live_reg_phase4_isolation.py']);run(['python3','tools/test_live_reg_phase4_guard_negative.py'])
script=ROOT/'tools/test_live_reg_phase3.py'
ns={'__file__':str(script),'__name__':'phase3_definitions'}
exec(compile(script.read_text().split('\nimport argparse\n')[0],str(script),'exec'),ns)
sys.path.insert(0,str(ROOT/'tools'));import test_voice_release as old
original=old.run
def augmented(command,**kwargs):
    if 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in command:
        fixture=ROOT/'build/live-reg-phase2/RecallViewModelHarness.kt';text=fixture.read_text()
        source=(ROOT/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text()
        block=source[source.index('    // BEGIN LIVE PHASE4 ADDITIONS'):source.index('    // END LIVE PHASE4 ADDITIONS')]
        fixture.write_text(text.replace('    // BEGIN REG PHASE2 ADDITIONS',block+'\n    // BEGIN REG PHASE2 ADDITIONS',1))
    if 'org.junit.runner.JUnitCore' in command and 'com.yourapp.yamahaarranger.registration.RegistrationPersistenceTest' in command:
        command=command+['com.yourapp.yamahaarranger.registration.RegistrationLibraryControllerTest','com.yourapp.yamahaarranger.registration.LivePhase4WiringTest']
    original(command,**kwargs)
old.run=augmented
try:ns['current_jvm'](args.fetch_dependencies)
finally:old.run=original
if args.history or args.real:
    with tempfile.TemporaryDirectory(prefix='phase4-history-') as d:
        checkout=Path(d)/'source';run(['git','worktree','add','--detach',str(checkout),BASE])
        try:
            shutil.copytree(ROOT/'build/sff1-jvm/dependencies',checkout/'build/sff1-jvm/dependencies',dirs_exist_ok=True)
            command=['python3','tools/test_live_reg_phase3.py','--history']
            if args.real:command+=['--real']
            if args.fetch_dependencies:command+=['--fetch-dependencies']
            run(command,checkout)
        finally:run(['git','worktree','remove',str(checkout)])
ns['android_compile']()
run(['python3','tools/test_live_reg_phase4_isolation.py'])

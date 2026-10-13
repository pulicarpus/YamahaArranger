#!/usr/bin/env python3
"""Current LIVE tests + unchanged Phase 0–2 snapshots; compile Android UI, never APK."""
import os,sys,subprocess,tempfile,shutil,hashlib,zipfile
from pathlib import Path
os.environ['PYTHONDONTWRITEBYTECODE']='1'
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
BASE='ea1997bd8e5c9f62566ad7ad8de0fb8a79ab20f7'
def run(cmd,cwd=ROOT):subprocess.run(cmd,cwd=cwd,check=True)
def current_jvm(fetch):
    sys.path.insert(0,str(ROOT/'tools'))
    import test_voice_release as old
    script=ROOT/'tools/test_live_reg_phase2.py'
    definitions=script.read_text().split('p=argparse.ArgumentParser();')[0]
    namespace={'__file__':str(script),'__name__':'phase2_definitions'}
    exec(compile(definitions,str(script),'exec'),namespace)
    original=old.run
    def extra(command,**kwargs):
        if 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in command:
            fixture=ROOT/'build/live-reg-phase2/RecallViewModelHarness.kt'
            text=fixture.read_text()
            s=(ROOT/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text()
            block=s[s.index('    // BEGIN LIVE PHASE3 STATE'):s.index('    // END LIVE PHASE3 STATE')]
            text=text.replace('import com.yourapp.yamahaarranger.arranger.ArrangerBrain','import com.yourapp.yamahaarranger.arranger.ArrangerBrain\nimport com.yourapp.yamahaarranger.arranger.ArrangerSection')
            text=text.replace('    // BEGIN REG PHASE2 ADDITIONS',block+'\n    // BEGIN REG PHASE2 ADDITIONS',1)
            text=text.replace('suspend fun testSnapshot()', 'var observedSectionLabel:String?=null\nfun onSectionSelected(label:String) { observedSectionLabel=label }\nsuspend fun testSnapshot()',1)
            fixture.write_text(text)
            command=command+[str(ROOT/'app/src/main/java/com/yourapp/ui/LiveControlLayout.kt'),str(ROOT/'app/src/test/java/com/yourapp/ui/LiveControlLayoutTest.kt'),str(ROOT/'app/src/test/java/com/yourapp/registration/LiveControlWiringTest.kt')]
        if 'org.junit.runner.JUnitCore' in command and 'com.yourapp.yamahaarranger.registration.RegistrationPersistenceTest' in command:
            command=command+['com.yourapp.yamahaarranger.ui.LiveControlLayoutTest','com.yourapp.yamahaarranger.registration.LiveControlWiringTest']
        original(command,**kwargs)
    old.run=extra
    try:namespace['jvm'](fetch)
    finally:old.run=original

def android_compile():
    if os.environ.get('GITHUB_ACTIONS')!='true':
        print('ANDROID_UI_COMPILE UNVERIFIED locally; required automatically in Actions',flush=True)
        return
    with tempfile.TemporaryDirectory(prefix='live-ui-gradle-') as folder:
        base=Path(folder);archive=base/'gradle.zip';checksum=base/'gradle.sha256'
        for url,dest in [('https://services.gradle.org/distributions/gradle-8.7-bin.zip',archive),('https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256',checksum)]:
            run(['curl','-fL','--retry','3',url,'-o',str(dest)])
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==checksum.read_text().strip()
        with zipfile.ZipFile(archive) as z:z.extractall(base)
        executable=base/'gradle-8.7/bin/gradle';executable.chmod(0o755)
        run([str(executable),'--no-daemon',':app:compileDebugKotlin'])
        print('ANDROID_UI_COMPILE PASS compileDebugKotlin; NO APK task invoked',flush=True)

import argparse
p=argparse.ArgumentParser();p.add_argument('--history',action='store_true');p.add_argument('--real',action='store_true');p.add_argument('--fetch-dependencies',action='store_true');args=p.parse_args()
run(['python3','tools/test_live_reg_phase3_isolation.py'])
current_jvm(args.fetch_dependencies)
if args.history or args.real:
    parent=Path(tempfile.mkdtemp(prefix='live-phase3-history-'));checkout=parent/'source'
    try:
        run(['git','worktree','add','--detach',str(checkout),BASE])
        shutil.copytree(ROOT/'build/sff1-jvm/dependencies',checkout/'build/sff1-jvm/dependencies',dirs_exist_ok=True)
        command=['python3','tools/test_live_reg_phase2.py','--history']
        if args.real:command+=['--real']
        if args.fetch_dependencies:command+=['--fetch-dependencies']
        run(command,checkout)
    finally:
        if checkout.exists():run(['git','worktree','remove',str(checkout)])
        shutil.rmtree(parent)
android_compile()
run(['python3','tools/test_live_reg_phase3_isolation.py'])

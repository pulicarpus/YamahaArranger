#!/usr/bin/env python3
"""Phase 0–1 regression gate; old tests/guards execute unchanged on pinned source."""
import argparse
import os
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
BASE='ea13199f69b4757d69e72c55cac45b9b9079aec3'
def run(args,cwd=ROOT):
    subprocess.run(args,cwd=cwd,check=True)
def registration_jvm():
    out=ROOT/'build/live-reg'; out.mkdir(parents=True,exist_ok=True)
    deps=ROOT/'build/sff1-jvm/dependencies'
    entries=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())['dependencies']
    for e in entries:
        p=deps/e['filename']
        if not p.exists():
            assert args.fetch_dependencies, 'Missing pinned dependencies'
            deps.mkdir(parents=True,exist_ok=True)
            run(['curl','--retry','3','-fsSL','--max-time','120',e['url'],'-o',str(p)])
        assert hashlib.sha256(p.read_bytes()).hexdigest()==e['sha256'], e['filename']
    cp=':'.join(str(deps/e['filename']) for e in entries)
    sources=sorted((ROOT/'app/src/main/java/com/yourapp/yamahaarranger/registration').glob('*.kt'))+sorted((ROOT/'app/src/test/java/com/yourapp/registration').glob('*.kt'))
    jar=out/'registration.jar'
    run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-jvm-target','17','-classpath',cp,'-d',str(jar),*map(str,sources)])
    run(['java','-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.registration.RegistrationPersistenceTest'])
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline',action='store_true')
parser.add_argument('--real',action='store_true')
parser.add_argument('--fetch-dependencies',action='store_true')
args=parser.parse_args()
run(['python3','tools/test_live_reg_isolation.py'])
registration_jvm()
if args.baseline or args.real:
    parent=Path(tempfile.mkdtemp(prefix='live-reg-baseline-')); checkout=parent/'source'
    try:
        run(['git','worktree','add','--detach',str(checkout),BASE])
        deps=ROOT/'build/sff1-jvm/dependencies'
        if deps.exists(): shutil.copytree(deps,checkout/'build/sff1-jvm/dependencies',dirs_exist_ok=True)
        run(['python3','tools/test_mixer_integration.py'],checkout)
        run(['python3','tools/test_right_off_style_guard.py'],checkout)
        if args.baseline:
            command=['python3','tools/test_voice_release.py','--jvm','--regressions']
            if args.fetch_dependencies: command.append('--fetch-dependencies')
            run(command,checkout)
        if args.real: run(['python3','tools/test_voice_release.py','--real'],checkout)
    finally:
        if checkout.exists(): run(['git','worktree','remove',str(checkout)])
        shutil.rmtree(parent)
run(['python3','tools/test_live_reg_isolation.py'])

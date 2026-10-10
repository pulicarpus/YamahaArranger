#!/usr/bin/env python3
"""Compile and run the actual admission model; no Android/native audio claim."""
import json,hashlib,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
base=ROOT/'build/sff1-jvm';out=ROOT/'build/readiness-host';out.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())
jars=[base/'dependencies'/x['filename'] for x in lock['dependencies']]
for j,d in zip(jars,lock['dependencies']):assert hashlib.sha256(j.read_bytes()).hexdigest()==d['sha256'],j
cp=':'.join(map(str,jars));target=out/'readiness-tests.jar'
sources=[ROOT/'app/src/main/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGate.kt',ROOT/'app/src/test/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGateTest.kt']
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(target),*map(str,sources)],check=True)
r=subprocess.run(['java','-cp',str(target)+':'+cp,'org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.lifecycle.ReadyToPlayGateTest'],capture_output=True,text=True)
(out/'junit.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr);assert r.returncode==0 and 'OK (24 tests)' in r.stdout

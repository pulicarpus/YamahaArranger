#!/usr/bin/env python3
import json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];base=ROOT/'build/sff1-jvm';out=ROOT/'build/f12-jvm';out.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text());jars=[base/'dependencies'/x['filename'] for x in lock['dependencies']]
cp=':'.join(map(str,[base/'sff1-jvm-tests.jar',*jars]));jar=out/'f12-tests.jar'
source=ROOT/'app/src/test/java/com/yourapp/yamahaarranger/arranger/F12LatencyDiagnosticTest.kt'
subprocess.run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+str(base/'sff1-jvm-tests.jar'),'-classpath',cp,'-d',str(jar),str(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/arranger/ChordChangeDiagnostic.kt'),str(source)],check=True)
r=subprocess.run(['java','-javaagent:'+str(base/'dependencies/byte-buddy-agent.jar'),'-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.arranger.F12LatencyDiagnosticTest'],text=True,capture_output=True)
(out/'junit.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr);assert r.returncode==0 and 'OK (4 tests)' in r.stdout

#!/usr/bin/env python3
"""Actual Kotlin AudioEngineManager + mocked native boundary, not native/PCM proof."""
import json,hashlib,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];base=ROOT/'build/sff1-jvm';out=ROOT/'build/readiness-engine-host';out.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text());jars=[base/'dependencies'/x['filename'] for x in lock['dependencies']]
for j,d in zip(jars,lock['dependencies']):assert hashlib.sha256(j.read_bytes()).hexdigest()==d['sha256']
cp=':'.join(map(str,[base/'sff1-jvm-tests.jar',*jars]))
raw=(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt').read_text()
methods=re.findall(r'    external fun (.*?)(?=\n    external fun|\n})',raw,re.S)
bridge=out/'NativeAudioBridge.kt';bridge.write_text('package com.yourapp.yamahaarranger.audio\nimport java.nio.ByteBuffer\nopen class NativeAudioBridge {\n'+''.join('    open fun '+x.strip()+' { error("unconfigured boundary") }\n' for x in methods)+'}\n')
log=out/'DebugLog.kt';log.write_text('package com.yourapp.yamahaarranger.ui\nobject DebugLog { fun add(s:String){}; fun traceAudio(s:String){}; fun traceError(s:String){} }\n')
sources=[bridge,log,ROOT/'tests/sff5_jvm_stubs/Inject.kt',ROOT/'app/src/main/java/com/yourapp/audio/SampleProvider.kt',ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt',ROOT/'app/src/main/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGate.kt',ROOT/'app/src/test/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayEngineTest.kt']
sources += [ROOT/'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt', ROOT/'app/src/test/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayBrainTest.kt', ROOT/'app/src/test/java/com/yourapp/yamahaarranger/arranger/PartPresenceSessionRegressionTest.kt']
jar=out/'tests.jar';subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+str(base/'sff1-jvm-tests.jar'),'-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
r=subprocess.run(['java','-javaagent:'+str(base/'dependencies/byte-buddy-agent.jar'),'-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.lifecycle.ReadyToPlayEngineTest','com.yourapp.yamahaarranger.lifecycle.ReadyToPlayBrainTest','com.yourapp.yamahaarranger.arranger.PartPresenceSessionRegressionTest'],capture_output=True,text=True);(out/'junit.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr);assert r.returncode==0 and 'OK (17 tests)' in r.stdout

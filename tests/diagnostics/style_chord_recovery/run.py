"""Isolated source-derived host proofs; no Android/JNI/PCM certification.
Support jars are the original baseline host fixtures; current Seq/Brain override
those classes. Verify dependencies using the unchanged project dependency lock.
"""
from pathlib import Path
import argparse,subprocess,json,hashlib
p=argparse.ArgumentParser();p.add_argument('--support-build',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2];OUT=a.out.resolve();OUT.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())['dependencies'];deps=[a.support_build/'sff1-jvm/dependencies'/d['filename'] for d in lock]
for f,d in zip(deps,lock):assert hashlib.sha256(f.read_bytes()).hexdigest()==d['sha256']
support=[a.support_build/'readiness-engine-host/tests.jar',a.support_build/'sff1-jvm/sff1-jvm-tests.jar'];assert all(f.is_file() for f in support)
CP=':'.join(map(str,[*support,*deps]));AGENT=a.support_build/'sff1-jvm/dependencies/byte-buddy-agent.jar'
def compile(jar,sources,cp=CP):
 subprocess.run(['java','-cp',CP,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+str(support[1]),'-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
def execute(jar,main,*args,cp=CP,expected_fail=False):
 r=subprocess.run(['java','-javaagent:'+str(AGENT),'-cp',str(jar)+':'+cp,main,*map(str,args)],capture_output=True,text=True,timeout=180)
 (OUT/(main.split('.')[-1]+('-expected-fail' if expected_fail else '')+'.log')).write_text(r.stdout+r.stderr);print(r.stdout)
 if expected_fail:assert r.returncode!=0 and 'active Fill application sequence failed' in r.stderr
 else:assert r.returncode==0,r.stderr
# Exact facade extraction; production musical/UI source not compiled as a double.
s=(ROOT/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text();facade=OUT/'Facade.kt';facade.write_text('package com.yourapp.yamahaarranger.ui\nimport java.text.SimpleDateFormat\nimport java.util.*\n'+s[s.index('object DebugLog {'):s.index('\ndata class VoiceSlot')]);jar=OUT/'logger.jar';compile(jar,[facade,HERE/'LoggerProof.kt']);execute(jar,'proof.LoggerProofKt','after',OUT/'logger.tsv')
# Explicit time/dispatcher seams for tick/bar assertions, preserving source code
# otherwise. Native engine and Android MIDI port are doubles in these fixtures.
sources=[]
for name,path in [('StyleSequencer.kt','app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'),('ArrangerBrain.kt','app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt')]:
 f=OUT/name;f.write_text((ROOT/path).read_text().replace('System.nanoTime()','audit.Clock.nanoTime()').replace('Dispatchers.Default','audit.Clock').replace('Dispatchers.Main','audit.Clock'));sources.append(f)
jar=OUT/'sections.jar';compile(jar,[*sources,HERE/'Rig.kt',HERE/'VirtualClock.kt',HERE/'DebugLogDouble.kt',HERE/'Transitions.kt',HERE/'Wake.kt',HERE/'Ending.kt',HERE/'Keyboard.kt']);execute(jar,'audit.TransitionsKt',OUT/'sections');execute(jar,'audit.TransitionsKt',OUT/'manual-fill','strict-manual',expected_fail=True);execute(jar,'audit.WakeKt','after',OUT/'wake.txt');execute(jar,'audit.EndingKt','after',OUT/'ending.txt');execute(jar,'audit.KeyboardKt')
print('HOST PROOFS PASS; manual Fill cancellation remains reproduced FAIL / Yamaha UNKNOWN; Android PCM UNRUN')

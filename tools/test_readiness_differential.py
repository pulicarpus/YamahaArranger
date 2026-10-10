#!/usr/bin/env python3
"""Compare musical source and real Kotlin facade boundary calls; not device PCM."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--baseline',type=Path,required=True);a=p.parse_args();base=a.baseline.resolve()
changed={'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt','app/src/main/java/com/yourapp/ui/MainViewModel.kt','app/src/main/java/com/yourapp/ui/MainScreen.kt','app/src/main/java/com/yourapp/ui/SxMainScreen.kt','app/src/main/java/com/yourapp/ui/ContentResolverProvider.kt','app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt'}
production=[x.relative_to(base).as_posix() for x in (base/'app/src/main').rglob('*') if x.is_file()]
for name in production:
 if name not in changed:assert (ROOT/name).read_bytes()==(base/name).read_bytes(),name
actual = {x.relative_to(ROOT).as_posix() for x in (ROOT/'app/src/main').rglob('*') if x.is_file()}
assert actual - set(production) == {'app/src/main/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGate.kt'}, 'Unexpected new production file'
# Logger stays byte-identical despite sharing a ViewModel file.
def block(s,signature):
 start=s.index(signature);brace=s.index('{',start);depth=1;i=brace+1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
vm='app/src/main/java/com/yourapp/ui/MainViewModel.kt';assert block((ROOT/vm).read_text(),'object DebugLog')==block((base/vm).read_text(),'object DebugLog')
brain='app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt'
old=(base/brain).read_text();new=(ROOT/brain).read_text()
for signature in ['private fun playSection(', 'fun selectMainVariation(', 'fun selectSection(', 'private fun applyChordNow(', 'fun loadStyle(']:assert block(old,signature)==block(new,signature),signature
# Every assertion in the existing session guard is preserved; only new resource setup.
test='app/src/test/java/com/yourapp/yamahaarranger/arranger/PartPresenceSessionRegressionTest.kt'
assert [x.strip() for x in (base/test).read_text().splitlines() if 'assert' in x]==[x.strip() for x in (ROOT/test).read_text().splitlines() if 'assert' in x]
for folder in ['tests/fixtures','app/src/test/resources']:
 for f in (base/folder).rglob('*'):
  if f.is_file():assert f.read_bytes()==(ROOT/f.relative_to(base)).read_bytes(),f
out=ROOT/'build/readiness-differential';out.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text());jars=[ROOT/'build/sff1-jvm/dependencies'/x['filename'] for x in lock['dependencies']]
for j,d in zip(jars,lock['dependencies']):assert hashlib.sha256(j.read_bytes()).hexdigest()==d['sha256']
cp=':'.join(map(str,jars));raw=(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt').read_text();methods=re.findall(r'    external fun (.*?)(?=\n    external fun|\n})',raw,re.S)
funcs=[]
for method in methods:
 name=method[:method.index('(')].strip();params=method[method.index('(')+1:method.index(')')];args=[x.split(':')[0].strip() for x in params.split(',') if x.strip()]
 returntype=method[method.index(')')+1:].strip().lstrip(':').strip() or 'Unit'
 ret={'Unit':'Unit','Boolean':'true','String':'""','ByteArray':'byteArrayOf()','Int':'0','Float':'0f','Long':'0L'}.get(returntype)
 assert ret is not None,returntype
 funcs.append('open fun '+method.strip()+' { Recorder.rows.add("'+name+'" + listOf<Any?>('+','.join(args)+').toString()); return '+ret+' }')
bridge=out/'NativeAudioBridge.kt';bridge.write_text('package com.yourapp.yamahaarranger.audio\nimport java.nio.ByteBuffer\nobject Recorder {val rows=java.util.concurrent.CopyOnWriteArrayList<String>()}\nopen class NativeAudioBridge {\n'+'\n'.join(funcs)+'\n}\n')
log=out/'DebugLog.kt';log.write_text('package com.yourapp.yamahaarranger.ui\nobject DebugLog {fun add(s:String){};fun traceAudio(s:String){};fun traceError(s:String){}}\n')
harness=out/'Probe.kt';harness.write_text('''import com.yourapp.yamahaarranger.audio.*
fun main() {
 val engine=AudioEngineManager(NativeAudioBridge(),object:SampleProvider {override fun sampleForNote(midiNote:Int):NoteSample?=null})
 engine.start();check(engine.loadSoundFontPairWithFallback("melody", "optional", "drum", "additional"))
 engine.noteOnStyleChannel(10,60,0.7f,12,60,0,0,1,false)
 engine.noteOffStyleChannel(10,60,12,60,0,24,1,0,0)
 engine.setChannelProgram(10,33,0,"Bass");engine.setChannelMixer(10,100,64,127,40,0)
 engine.setKeyboardSustain(true);engine.setKeyboardReleaseTime(64);engine.setMasterVolume(100)
 engine.noteOnChannel(9,42,0.5f);engine.noteOffChannel(9,42)
 engine.loadedSoundFontPresets()
 println(Recorder.rows.joinToString("\\n"))
}
''')
rows={}
for label,tree in [('baseline',base),('candidate',ROOT)]:
 sources=[bridge,log,harness,ROOT/'tests/sff5_jvm_stubs/Inject.kt',ROOT/'app/src/main/java/com/yourapp/audio/SampleProvider.kt',tree/'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt']
 if label=='candidate':sources.append(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGate.kt')
 jar=out/(label+'.jar');subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
 rows[label]=subprocess.check_output(['java','-cp',str(jar)+':'+cp,'ProbeKt'],text=True);(out/(label+'.trace')).write_text(rows[label])
assert rows['baseline']==rows['candidate'],'Native boundary call order/values drift'
print('READINESS_DIFFERENTIAL PASS protected production, golden, logger, Main/Fill/chord bodies, existing assertions; identical facade native call order/values. Not Android/Yamaha audio proof.')

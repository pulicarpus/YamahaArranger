#!/usr/bin/env python3
import argparse,json,hashlib,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 sys.path.insert(0,str(ROOT/'tools'));from test_voice_release import jvm
 jvm(out,False)
 jars=[]
 for entry in json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())['dependencies']:
  path=ROOT/'build/sff1-jvm/dependencies'/entry['filename'];assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256'];jars.append(str(path))
 cp=':'.join(jars);jar=out/'pcm-controls.jar'
 subprocess.run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar),str(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/PcmSessionControls.kt'),str(ROOT/'tests/PcmSessionControlsTest.kt')],check=True)
 subprocess.run(['java','-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.audio.PcmSessionControlsTest'],check=True)
if __name__=='__main__':main()

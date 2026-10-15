#!/usr/bin/env python3
import argparse,json,hashlib,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 jars=[]
 for entry in json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())['dependencies']:
  path=ROOT/'build/sff1-jvm/dependencies'/entry['filename'];assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256'];jars.append(str(path))
 cp=':'.join(jars);jar=out/'shadow.jar';sources=[ROOT/'app/src/main/java/com/yourapp/style/StyleModel.kt',ROOT/'tests/R22ShadowTest.kt']+list((ROOT/'app/src/main/java/com/yourapp/yamahaarranger/arranger').glob('DrumKitShadow*.kt'))
 subprocess.run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
 fingerprints=[]
 for trial in range(3):
  text=subprocess.check_output(['java','-cp',str(jar)+':'+cp,'R22ShadowTestKt',str(ROOT)],text=True);(out/f'run-{trial+1}.log').write_text(text);print(text,flush=True);fingerprints.append(next(l for l in text.splitlines() if l.startswith('DECISIONS_SHA256 ')))
 assert len(set(fingerprints))==1,'G3 restart determinism failed'
if __name__=='__main__':main()

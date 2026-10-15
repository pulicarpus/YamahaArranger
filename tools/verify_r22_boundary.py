#!/usr/bin/env python3
"""R22 observational delta only; existing guards, tests, Policy C and playback stay pinned."""
import argparse,subprocess,re,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE='dc746b7cabfafe365e5e15366f8172f35c7a89af'
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT)
def restore(text,marker):
 pattern=r'^[ \t]*// '+marker+'_BEGIN\n.*?^[ \t]*// '+marker+'_END\n'
 restored,n=re.subn(pattern,'',text,flags=re.S|re.M);assert n==1,marker;return restored

def verify(pin=True):
 path='app/src/main/cpp/bassmidi_player.cpp';s=(ROOT/path).read_text()
 s=restore(s,'R22_SELECTION_OBSERVER');s=restore(s,'R22_SELECTION_GETTER')
 s=s.replace('    drumSelections_={}; ++drumSelectionGeneration_; // R22 observation invalidation only\n','')
 assert s.rstrip()==old(path).decode().rstrip(),'native playback changed beyond cached observation'
 path='app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt';s=(ROOT/path).read_text()
 for marker in ['R22_RAW_SETUP','R22_OBSERVE_MUTED','R22_OBSERVE_SETUP','R22_OBSERVE_DYNAMIC']:s=restore(s,marker)
 assert s==old(path).decode(),'sequencer behavior changed beyond pure shadow hooks'
 # Every old assertion, source guard and workflow is preserved byte-for-byte.
 for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','tools','tests','.github/workflows','catalog'],cwd=ROOT,text=True).splitlines():
  assert (ROOT/path).read_bytes()==old(path),'protected tooling/catalog changed '+path
 original=set(subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main'],cwd=ROOT,text=True).splitlines())
 from generated_bass_sdk_guard import PATHS
 physical={str(p.relative_to(ROOT)):p for p in (ROOT/'app/src/main').rglob('*') if p.is_file() and str(p.relative_to(ROOT)) not in PATHS}
 changed={p for p in original|set(physical) if (physical[p].read_bytes() if p in physical else None)!=(old(p) if p in original else None)}
 hashes={p:hashlib.sha256(physical[p].read_bytes()).hexdigest() for p in sorted(changed)}
 if pin:
  manifest=json.loads((ROOT/'tests/fixtures/r22/source_manifest.json').read_text());assert manifest['baseline']==BASE;assert hashes==manifest['production_sha256'],'reviewed source scope/hash drift'
 assert (ROOT/'app/src/main/cpp/voice_resolver.h').read_bytes()==old('app/src/main/cpp/voice_resolver.h')
 return hashes
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--write-manifest',action='store_true');a=p.parse_args();hashes=verify(not a.write_manifest)
 if a.write_manifest:(ROOT/'tests/fixtures/r22/source_manifest.json').write_text(json.dumps(dict(baseline=BASE,production_sha256=hashes),indent=2,sort_keys=True)+'\n')
 print('R22 BOUNDARY PASS: native render/notes/events and original sequencer restored exactly; Policy C/old tooling/catalog unchanged; '+str(len(hashes))+' reviewed production paths')

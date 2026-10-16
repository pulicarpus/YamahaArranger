#!/usr/bin/env python3
"""R24 minimum safety: unchanged original resolved, exact keys, ownership, clipping."""
import argparse,hashlib,io,json,subprocess,sys,tarfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE='c75732f28fec58fbfbc7e626bc380b10a00722b8'
def run(cmd,**kw):return subprocess.check_output(list(map(str,cmd)),cwd=ROOT,text=True,**kw)
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 for label,flags in [('normal',[]),('android-fast-math',['-ffast-math'])]:
  exe=out/('dsp-'+label);subprocess.run(['g++','-std=c++17','-O2',*flags,'-Wall','-Wextra','-Werror','-Iapp/src/main/cpp','tests/r24_dsp_test.cpp','-o',str(exe)],cwd=ROOT,check=True);(out/('dsp-'+label+'.log')).write_text(run([exe]))
 # Existing assertions run without alteration, including native actual-binding readback.
 subprocess.run([sys.executable,'tools/test_r23_native.py','--out',str(out/'r23')],cwd=ROOT,check=True)
 base=out/'baseline';base.mkdir(exist_ok=True)
 with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git','archive',BASE,'app/src/main/cpp','tests'],cwd=ROOT))) as t:t.extractall(base,filter='data')
 results=[]
 for label,src in [('baseline',base),('candidate',ROOT)]:
  exe=out/('diff-'+label);cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1','-I'+str(out/'r23/mocks'),'-I'+str(src/'app/src/main/cpp'),'tests/r24_native_test.cpp',str(src/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(exe)]
  if label=='candidate':cmd.insert(1,'-DR24_NATIVE=1')
  subprocess.run(cmd,cwd=ROOT,check=True);text=run([exe,out/'r23/golden-fixtures',out/(label+'.pcm')]);(out/(label+'.log')).write_text(text);results.append(text)
 assert (out/'baseline.pcm').read_bytes()==(out/'candidate.pcm').read_bytes() and len((out/'baseline.pcm').read_bytes())==4096,'raw resolved PCM differs'
 assert results[0]==results[1],'R24 ORIGINAL must be identical to exact R23 RESOLVED PCM+events'
 sys.path.insert(0,str(ROOT/'tools/sf2_catalog'));from pdta_inventory import read_source
 fonts=read_source(ROOT/'tests/fixtures/sf2_pdta/export.zip',json.loads((ROOT/'tools/sf2_catalog/catalog_review.yaml').read_text()))
 col=next(v for v in fonts.values() if hashlib.sha256(v['payload']).hexdigest()=='ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506')
 catalog=json.loads((ROOT/'catalog/u01/catalog.json').read_text());proof=[]
 for pc in [0,8,16]:
  entry=next(x for x in catalog if x['font_role']=='COLOMBO' and x['bank']==127 and x['pc']==pc);assert entry['keyMapStandard']=='XG'
  for key in [35,36,38,40]:
   zones=[z['row'] for z in col['zones'] if z['eligible'] and int(z['row']['bank'])==127 and int(z['row']['program'])==pc and int(z['row']['keyLo'])<=key<=int(z['row']['keyHi'])]
   assert zones and all(z['exclusiveClass57'] in ('','0') for z in zones),'cross-key exclusive group would make isolation unsafe'
   for velocity in range(1,128):assert any(int(z['velLo'])<=velocity<=int(z['velHi']) for z in zones)
   proof.append(dict(pc=pc,preset=entry['preset_name'],key=key,group='KICK' if key in [35,36] else 'SNARE',zones=zones))
 assert proof==json.loads((ROOT/'tests/fixtures/r24/key-proof.json').read_text()),'committed key proof drift'
 (out/'key-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
 report={'source_baseline':BASE,'original_resolved_pcm_event_sha256':hashlib.sha256(results[0].encode()).hexdigest(),'raw_resolved_pcm_sha256':hashlib.sha256((out/'baseline.pcm').read_bytes()).hexdigest(),'gates':'PASS original identity, native routing/ownership/binding, metadata keys, source-only DSP clipping/nonfinite','device_audio':'UNVERIFIED','fx_isolation_equivalence':'UNVERIFIED independent FX states; compare device','no_original_sf2_or_samples_committed':True}
 (out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()

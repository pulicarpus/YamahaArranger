#!/usr/bin/env python3
"""Original ROSITA through unchanged production parser; exact audit and PDTA coverage."""
import argparse,subprocess,hashlib,json,collections,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools/sf2_catalog'))
from pdta_inventory import read_source
SHA='b8f111655656b9f9d73440f66f6c887a5f611ecd3b034119ba707819965faecb'
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 style=ROOT/'tests/fixtures/r23/ROSITA.STY';assert hashlib.sha256(style.read_bytes()).hexdigest()==SHA
 exe=out/'dump';subprocess.run(['g++','-std=c++17','-O2','-DANDROID_LOG_ERROR=6','-Itests/mocks','-Iapp/src/main/cpp','tools/dump_style_shadow_fixture.cpp','app/src/main/cpp/style_parser.cpp','app/src/main/cpp/smf_reader.cpp','-o',str(exe)],cwd=ROOT,check=True)
 raw=subprocess.check_output([str(exe),str(style)]);(out/'rosita.tsv').write_bytes(raw)
 parts=[];section=None;part=None
 for line in raw.decode().splitlines():
  f=line.split('\t')
  if f[0]=='S':section=f[1]
  elif f[0]=='P':part={'section':section,'policies':[],'events':[]};parts.append(part)
  elif f[0]=='C':part['policies'].append((int(f[1]),int(f[2])))
  elif f[0]=='E':part['events'].append(tuple(map(int,f[1:5])))
 fonts=read_source(ROOT/'tests/fixtures/sf2_pdta/export.zip',json.loads((ROOT/'tools/sf2_catalog/catalog_review.yaml').read_text()))
 col=next(f for f in fonts.values() if f['pdta_sha256']=='ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506') if any('pdta_sha256' in f for f in fonts.values()) else next(f for f in fonts.values() if hashlib.sha256(f['payload']).hexdigest()=='ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506')
 results=[]
 for row in json.loads((ROOT/'tests/fixtures/r22/audit_ranges.json').read_text())['rows']:
  if row['kind']!='COL-XG':continue
  events=[e for part in parts if part['section']==row['section'] for e in part['events'] if any(src==e[1]%16 and dst==row['channel'] for src,dst in part['policies'])]
  msb=lsb=pc=None
  for tick,status,key,vel in events:
   if tick!=0:continue
   if status>>4==11 and key==0:msb=vel
   if status>>4==11 and key==32:lsb=vel
   if status>>4==12:pc=key
  assert (msb,lsb,pc)==(127,0,row['requestPc']),(row,msb,lsb,pc)
  hits=[e for e in events if e[1]>>4==9 and e[3]>0]
  assert len(hits)==row['total']
  assert collections.Counter(e[2] for e in hits)=={k['key']:k['count'] for k in row['keys']}
  for k in row['keys']:
   velocities=[e[3] for e in hits if e[2]==k['key']];assert (min(velocities),max(velocities))==(k['lo'],k['hi'])
  zones=[z['row'] for z in col['zones'] if z['eligible'] and int(z['row']['bank'])==127 and int(z['row']['program'])==pc]
  for e in hits:assert any(int(z['keyLo'])<=e[2]<=int(z['keyHi']) and int(z['velLo'])<=e[3]<=int(z['velHi']) for z in zones),(row,e)
  results.append({'section':row['section'],'channel':row['channel'],'request':[msb,lsb,pc],'hits':len(hits),'covered':len(hits)})
 assert len(results)==8
 result={'gate':'ROSITA_BYTE_REPLAY_PASS','styleSha256':SHA,'parserOutputSha256':hashlib.sha256(raw).hexdigest(),'rows':results,'samplePCM':'not present; no device audio claim'}
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()

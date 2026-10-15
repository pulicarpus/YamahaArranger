#!/usr/bin/env python3
"""Read verified U1 PDTA only. No SF2 audio; exact raw names, deterministic output."""
import argparse,hashlib,json,gzip,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/sf2_catalog'))
from pdta_inventory import export_inputs,read_source

def generate(source):
 review=json.loads((ROOT/'tools/sf2_catalog/catalog_review.yaml').read_text());found=read_source(source,review);assert found is not None
 roles={f['file_sha256']:f['role'] for f in review['font_provenance']};lines=['R22_KIT_IDENTITY\t1\tsf2-list-pdta-payload-v2'];identities=[]
 for sha,font in sorted(found.items(),key=lambda x:roles[x[0]]):
  role=roles[sha];pdta=font['pdta_sha256']
  for pi,p in sorted(font['presets'].items()):
   bank=p['bank'];ns='YAMAHA_XG' if role=='COLOMBO' and bank==127 else 'GS' if role in ('COLOMBO','TYROS4') and bank==128 else 'CUSTOM' if role=='PRIME_DRUM' or bank==126 else 'UNKNOWN'
   if ns=='UNKNOWN':continue
   zones=[z for z in font['zones'] if z['row']['presetIndex']==str(pi) and z['eligible']]
   key=f'{pdta}:{bank}:{p["pc"]}:{hashlib.sha256(bytes.fromhex(p["nameHex"])).hexdigest()}'
   lines.append('\t'.join(map(str,['K',key,role,pdta,bank,p['pc'],p['nameHex'],ns])))
   identities.append(dict(id=key,font=role,pdtaSha256=pdta,bank=bank,pc=p['pc'],nameHex=p['nameHex'],namespace=ns,provenance='U1 verified original PDTA + SF2_BINARY_AUDIT_CATALOG_CORRECTIONS; R2_2 audit §6.3'))
   for z in zones:
    q=z['row'];pg=z['pg'];ig=z['ig']
    # Additive sends are retained raw, never interpreted as rendered FX/audio gain.
    lines.append('\t'.join(map(str,['Z',q['keyLo'],q['keyHi'],q['velLo'],q['velHi'],q['sampleIndex'],q['total48_raw_cB'],pg.get(16,0)+ig.get(16,0),pg.get(15,0)+ig.get(15,0),ig.get(57,0)])))
 raw=('\n'.join(lines)+'\n').encode();manifest=dict(schema=1,identityVersion='r22-kit-identity-v1',catalogUse='OFF_PLAYBACK_SHADOW_READ_ONLY',pdtaDomain='sf2-list-pdta-payload-v2',tsvSha256=hashlib.sha256(raw).hexdigest(),kits=identities)
 return raw,manifest
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--pdta',required=True);p.add_argument('--out',required=True);a=p.parse_args();raw,manifest=generate(a.pdta);out=Path(a.out);out.mkdir(parents=True,exist_ok=True);(out/'kits.tsv.bin').write_bytes(gzip.compress(raw,mtime=0));(out/'kit_identity.json').write_text(json.dumps(manifest,sort_keys=True,indent=2)+'\n');print(json.dumps({'kits':len(manifest['kits']),'bytes':len(raw),'sha256':manifest['tsvSha256']}))

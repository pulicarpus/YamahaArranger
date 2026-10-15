"""Bounded, read-only PDTA inventory; no sample PCM or active synth dependency."""
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct

WIDTHS={'phdr':38,'pbag':4,'pmod':10,'pgen':4,'inst':22,'ibag':4,'imod':10,'igen':4,'shdr':46}
MAX_PDTA=32*1024*1024

def text(raw):return raw.split(b'\0',1)[0].decode('latin1')
def records(raw,fmt):return list(struct.iter_unpack(fmt,raw))

def parse(payload):
    if not 4<=len(payload)<=MAX_PDTA or payload[:4]!=b'pdta':raise ValueError('invalid PDTA payload/domain')
    tables={};chunks=[];at=4
    for tag,width in WIDTHS.items():
        if len(payload)-at<8 or payload[at:at+4]!=tag.encode():raise ValueError('PDTA table order/missing')
        size=struct.unpack_from('<I',payload,at+4)[0];end=at+8+size
        if size<width or size%width or end+(size&1)>len(payload):raise ValueError('PDTA table extent')
        chunks.append({'id':tag,'offset_in_bin':at,'size':size});tables[tag]=payload[at+8:end];at=end+(size&1)
    if at!=len(payload):raise ValueError('unexpected PDTA trailing bytes')
    ph=records(tables['phdr'],'<20sHHHIII');inst=records(tables['inst'],'<20sH');sh=records(tables['shdr'],'<20sIIIIIBbHH')
    if (text(ph[-1][0]),text(inst[-1][0]),text(sh[-1][0]))!=('EOP','EOI','EOS'):raise ValueError('missing PDTA terminals')
    pb=records(tables['pbag'],'<HH');ib=records(tables['ibag'],'<HH');pg=records(tables['pgen'],'<Hh');ig=records(tables['igen'],'<Hh')
    names=[]
    for kind,rows in [('phdr',ph),('inst',inst),('shdr',sh)]:
        for index,row in enumerate(rows):names.append({'table':kind,'index':str(index),'bank':str(row[2]) if kind=='phdr' else '',
            'program':str(row[1]) if kind=='phdr' else '', 'name_text_latin1':text(row[0]),'name_hex_20':row[0].hex()})
    def gens(bags,gs,index):
        if not 0<=index<len(bags)-1:raise ValueError('invalid bag index')
        lo,hi=bags[index][0],bags[index+1][0]
        if not 0<=lo<=hi<len(gs):raise ValueError('invalid generator index')
        return dict(gs[lo:hi])
    def bounds(g,op):
        packed=g.get(op,0x7f00)&0xffff;lo,hi=packed&255,packed>>8
        if not 0<=lo<=127 or not 0<=hi<=127:raise ValueError('invalid MIDI range')
        return lo,hi
    zones=[];presets={};instruments={};samples={i:row for i,row in enumerate(sh[:-1])}
    for index,row in enumerate(inst[:-1]):
        lo,hi=row[1],inst[index+1][1]
        if not 0<=lo<=hi<len(ib):raise ValueError('invalid instrument bags')
        glob={};local=[]
        for bag in range(lo,hi):
            g=gens(ib,ig,bag)
            if 53 not in g:
                if bag!=lo:raise ValueError('nonleading instrument global')
                glob=g;continue
            merged={**glob,**g};si=merged[53]&0xffff
            if si not in samples:raise ValueError('invalid sample index')
            local.append((bag,merged,si))
        instruments[index]=local
    for pi,p in enumerate(ph[:-1]):
        name,pc,bank,lo=p[:4];hi=ph[pi+1][3]
        if not 0<=pc<=127 or not 0<=bank<=16383 or not 0<=lo<=hi<len(pb):raise ValueError('invalid preset identity/bags')
        presets[pi]={'bank':bank,'pc':pc,'name':text(name),'nameHex':name.hex()};glob={}
        for bag in range(lo,hi):
            g=gens(pb,pg,bag)
            if 41 not in g:
                if bag!=lo:raise ValueError('nonleading preset global')
                glob=g;continue
            merged={**glob,**g};ii=merged[41]&0xffff
            if ii not in instruments:raise ValueError('invalid instrument link')
            for ibag,igen,si in instruments[ii]:
                pk,ik=bounds(merged,43),bounds(igen,43);pv,iv=bounds(merged,44),bounds(igen,44)
                kl,kh=max(pk[0],ik[0]),min(pk[1],ik[1]);vl,vh=max(pv[0],iv[0]),min(pv[1],iv[1])
                a,b=merged.get(48,0),igen.get(48,0);sample=sh[si]
                row={'presetIndex':pi,'bank':bank,'program':pc,'presetNameHex':name.hex(),'instIndex':ii,'instNameHex':inst[ii][0].hex(),
                     'sampleIndex':si,'sampleNameHex':sample[0].hex(),'keyLo':kl,'keyHi':kh,'velLo':vl,'velHi':vh,
                     'pgen48_cB':a,'igen48_cB':b,'total48_raw_cB':a+b,'total48_clamped_0_1440_cB':min(1440,max(0,a+b)),
                     'negative':int(a+b<0),'sampleType':sample[9],'sampleRate':sample[5],'originalKey':sample[6],'pitchCorrection':sample[7],
                     'overridingRootKey58':igen.get(58,''),'exclusiveClass57':igen.get(57,''),'sampleModes54':igen.get(54,'')}
                zones.append({'relation':f'{pi}:{bag}:{ii}:{ibag}:{si}','row':{k:str(v) for k,v in row.items()},'eligible':kl<=kh and vl<=vh,'pg':merged,'ig':igen})
                if len(zones)>200000:raise ValueError('zone count limit')
    return {'presets':presets,'names':names,'zones':zones,'chunks':chunks,
            'headers':{'phdr':ph,'inst':inst,'shdr':sh},
            'counts':{'presets':len(ph)-1,'instruments':len(inst)-1,'samples':len(sh)-1}}

def export_inputs(directory,review):
    directory=Path(directory);manifest=json.loads((directory/'pdta_manifest.json').read_text())
    if manifest['format']!='YamahaArranger SF2 PDTA export v1':raise ValueError('unknown PDTA export schema')
    declarations={f['file_sha256']:f for f in review['font_provenance']};result={}
    from binary_audit import load
    audited=load(review)
    expected_files={'README.txt','pdta_manifest.json'} | {f[key] for f in manifest['files'] for key in ['pdta_file','names_file','zones_attenuation_file']}
    if {p.name for p in directory.iterdir() if p.is_file()}!=expected_files:raise ValueError('unexpected PDTA export member')
    for f in manifest['files']:
        sha=f['source_sf2_sha256'];decl=declarations[sha]
        if f['source_sf2_size']!=audited[sha]['structure']['size']:raise ValueError('original source-size provenance mismatch')
        def child(name):
            if Path(name).name!=name:raise ValueError('unsafe PDTA export filename')
            return directory/name
        payload=child(f['pdta_file']).read_bytes();digest=hashlib.sha256(payload).hexdigest()
        if (digest,len(payload))!=(decl['expected_pdta_sha256'],decl['pdta_bytes']):raise ValueError('original PDTA golden mismatch')
        if (f['pdta_sha256'],f['golden_pdta_sha256'],f['pdta_size'])!=(digest,digest,len(payload)):raise ValueError('export manifest hash mismatch')
        parsed=parse(payload)
        if parsed['counts']!=f['counts_excluding_terminal'] or parsed['chunks']!=f['pdta_subchunks']:raise ValueError('PDTA manifest tables mismatch')
        for filename,expected in [(f['names_file'],parsed['names']),(f['zones_attenuation_file'],[z['row'] for z in parsed['zones']])]:
            with child(filename).open(encoding='utf-8',newline='') as stream:actual=list(csv.DictReader(stream,delimiter='\t'))
            if actual!=expected:
                for i,(a,b) in enumerate(zip(actual,expected)):
                    if a!=b:raise ValueError(f'PDTA derived TSV mismatch {filename} row {i+2}: {a} != {b}')
                raise ValueError('PDTA derived TSV length mismatch '+filename)
        if len(parsed['zones'])!=f['zones'] or sum(int(z['row']['negative']) for z in parsed['zones'])!=f['zones_negative_total_attenuation']:raise ValueError('PDTA zone census mismatch')
        if sha in result:raise ValueError('duplicate PDTA export')
        result[sha]={**parsed,'payload':payload,'pdta_sha256':digest,'provenance':f}
    if set(result)!=set(declarations):raise ValueError('required PDTA font missing')
    return result

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--export-dir',required=True);a=p.parse_args()
    review=json.loads(Path(__file__).with_name('catalog_review.yaml').read_text())
    found=export_inputs(a.export_dir,review)
    print(json.dumps([{'role':next(f['role'] for f in review['font_provenance'] if f['file_sha256']==sha),'pdtaSha256':v['pdta_sha256'],'counts':v['counts'],'zones':len(v['zones'])} for sha,v in found.items()],indent=2))

def read_source(source,review):
    """Read a strict metadata-only export directory/ZIP, returning verified original tables."""
    import tempfile
    import zipfile
    source=Path(source)
    if source.is_dir():return export_inputs(source,review) if (source/'pdta_manifest.json').exists() else None
    if not zipfile.is_zipfile(source):return None
    with zipfile.ZipFile(source) as archive:
        files=[f for f in archive.infolist() if not f.is_dir()]
        if not any(Path(f.filename).name=='pdta_manifest.json' for f in files):return None
        if len(files)!=14 or sum(f.file_size for f in files)>64*1024*1024:raise ValueError('PDTA archive bounds')
        with tempfile.TemporaryDirectory(prefix='pdta-source-') as temp:
            seen=set()
            for f in files:
                parts=Path(f.filename).parts
                if Path(f.filename).is_absolute() or '..' in parts or len(parts)>2:raise ValueError('unsafe PDTA archive path')
                name=Path(f.filename).name
                if name in seen:raise ValueError('duplicate PDTA archive member')
                seen.add(name);(Path(temp)/name).write_bytes(archive.read(f))
            return export_inputs(temp,review)

def metadata_from_inventory(found):
    """Lossless names and effective generators in the existing catalog input grammar.

This is a derived catalog input, not a pretend device export. No PCM is generated.
    """
    result={}
    for index,(sha,font) in enumerate(sorted(found.items()),1):
        provenance=font['provenance'];ph=font['presets'];names=font['names']
        def quote(value):return value.replace('\\','\\\\').replace("'","\\'").replace('\n','\\n').replace('\r','\\r').replace('\t','\\t')
        lines=[f"SF2 METADATA v1 file='{quote(provenance['source_sf2'])}' stableIdentity='pdta:{font['pdta_sha256']}'",
               f"TABLES presets={font['counts']['presets']} instruments={font['counts']['instruments']} samples={font['counts']['samples']}"]
        for kind,prefix in [('shdr','S'),('inst','I'),('phdr','P')]:
            records=[r for r in names if r['table']==kind]
            for row in records[:-1]:
                i=int(row['index']);nm=quote(row['name_text_latin1']);hexname=row['name_hex_20'];headers=font['headers'][kind]
                if prefix=='P':line=f"P id={i} bank={row['bank']} rawPC={row['program']} displayPC={int(row['program'])+1} name='{nm}' bags={headers[i][3]}:{headers[i+1][3]}"
                else:line=f"{prefix} id={i} name='{nm}' "+(f'frames={headers[i][1]}:{headers[i][2]}' if prefix=='S' else f'bags={headers[i][1]}:{headers[i+1][1]}')
                lines.append(line+' nameHex='+hexname)
        for z in font['zones']:
            r=z['row'];pg=','.join(f'{op}:{value}' for op,value in sorted(z['pg'].items()));ig=','.join(f'{op}:{value}' for op,value in sorted(z['ig'].items()));p=int(r['presetIndex'])
            lines.append(f"Z relation={z['relation']} bank={r['bank']} rawPC={r['program']} displayPC={int(r['program'])+1} preset='{quote(ph[p]['name'])}' instrument={r['instIndex']}:'{quote(text(bytes.fromhex(r['instNameHex'])))}' sample={r['sampleIndex']}:'{quote(text(bytes.fromhex(r['sampleNameHex'])))}' keys={r['keyLo']}:{r['keyHi']} velocities={r['velLo']}:{r['velHi']} eligible={str(z['eligible']).lower()} PG={pg}; IG={ig};")
        lines.append(f"END sha256={sha} bytes={provenance['source_sf2_size']} complete=true reason=derived_catalog_projection_from_verified_pdta relations={len(font['zones'])}")
        result[f'font_{index}.txt']=('\n'.join(lines)+'\n').encode()
    return result

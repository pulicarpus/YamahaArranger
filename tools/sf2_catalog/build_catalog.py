#!/usr/bin/env python3
"""Deterministic U1 inventory. Generated data is never wired to the active resolver.

JSON-only YAML 1.2 review avoids executable YAML constructors. Raw file SHA is
metadata provenance, NEVER a substitute FontId or PDTA digest.
"""
import argparse
import gzip
import io
import hashlib
import json
from pathlib import Path
import re
import zipfile

from binary_audit import load as load_audit, attenuation, corrected_preset, annotations

VERSION = 'u01-catalog-v2-audit'
DOMAIN = 'sf2-list-pdta-payload-v2'
HEX = re.compile(r'^[0-9a-f]{64}$')

def sha(data): return hashlib.sha256(data).hexdigest()
def raw_name(line):
    m=re.search(r'\bnameHex=([0-9a-fA-F]+)(?: |$)',line)
    if not m:
        if 'nameHex=' in line:raise ValueError('invalid raw nameHex')
        return None
    if len(m[1])!=40:raise ValueError('invalid raw SF2 nameHex width')
    return bytes.fromhex(m[1])

def canonical(data): return (json.dumps(data, sort_keys=True, indent=2, ensure_ascii=True)+'\n').encode()

def family(name):
    n=re.sub('[^a-z0-9]', '', name.lower())
    if n.startswith(('satriomusik','sf2')) or not any(c.isalpha() for c in n): return 'UNKNOWN'
    groups=[('PERCUSSION',('drum','kendang','percussion','melodictom')),
            ('WOODWIND',('bassoon','flute','piccolo','oboe','clarinet','recorder','harmonica','shakuhachi')),
            ('SAX',('sax',)),('BRASS',('trumpet','trombone','tuba','brass','horn')),
            ('GUITAR',('guitar','gtr')),('ACCORDION',('accordion','bandoneon')),
            ('PIANO',('piano','grand','rhodes','harpsichord','honkytonk')),
            ('CHOIR',('choir','ooh','aah','vocal','voice')),
            ('STRINGS',('strings','violin','viola','cello','contrabass')),
            ('BASS',('bass',)),('ORGAN',('organ','hammond','drawbar')),
            ('PAD',('pad',)),('SYNTH',('synth','lead')),
            ('CHROMATIC',('glock','vibraphone','marimba','xylophone','celesta'))]
    return next((f for f,words in groups if any(w in n for w in words)), 'UNKNOWN')

def metadata_inputs(source):
    source=Path(source)
    if source.is_dir(): return {p.name:p.read_bytes() for p in sorted(source.glob('font_*.txt'))}
    with zipfile.ZipFile(source) as z:
        out={}
        for entry in z.infolist():
            if not re.fullmatch(r'font_\d+\.txt',Path(entry.filename).name):continue
            if entry.file_size>64*1024*1024:raise ValueError('metadata size limit')
            key=Path(entry.filename).name
            if key in out:raise ValueError('duplicate metadata member')
            out[key]=z.read(entry)
        return out

def build(source, review_path, out, fingerprint_manifest=None):
    review_bytes=Path(review_path).read_bytes();review=json.loads(review_bytes)
    if review['schema']!=1 or review['pdta_domain']!=DOMAIN:raise ValueError('review schema/domain')
    audits=load_audit(review)
    provenance={f['file_sha256']:f for f in review['font_provenance']}
    if len(provenance)!=len(review['font_provenance']):raise ValueError('duplicate font provenance')
    for digest,f in provenance.items():
        if not HEX.fullmatch(digest):raise ValueError('invalid provenance hash')
        expected=f['expected_pdta_sha256']
        if expected is not None and not HEX.fullmatch(expected):raise ValueError('invalid reviewed PDTA')
    reviews={}
    for entry in review['reviews']:
        key=(entry['file_sha256'],entry['bank'],entry['pc'],entry['preset_name'])
        if key in reviews:raise ValueError('duplicate preset review')
        reviews[key]=entry
    observed={}
    if fingerprint_manifest:
        for line in Path(fingerprint_manifest).read_text().splitlines():
            if not line.startswith('SF2_FINGERPRINT '):continue
            fields=dict(re.findall(r'(\w+)=([^ ]+)',line))
            file_sha,pdta=fields.get('fileSha256',''),fields.get('pdtaSha256','')
            if file_sha not in provenance or not HEX.fullmatch(pdta):raise ValueError('unidentified font/invalid device PDTA')
            if file_sha in observed and observed[file_sha]!=pdta:raise ValueError('conflicting PDTA fingerprints')
            observed[file_sha]=pdta
    entries=[];fonts=[];seen=set();review_seen=set();input_hashes={};raw_names=[]
    for filename,raw in sorted(metadata_inputs(source).items()):
        input_hashes[filename]=sha(raw);lines=raw.decode('utf-8',errors='strict').splitlines()
        header=re.fullmatch(r"SF2 METADATA v1 file='([^']*)' stableIdentity='[^']*'",lines[0])
        ends=[l for l in lines if l.startswith('END ')]
        if not header or len(ends)!=1 or 'complete=true' not in ends[0]:raise ValueError('incomplete metadata')
        digest=re.search(r'sha256=([0-9a-f]{64})',ends[0]).group(1)
        if digest in seen:raise ValueError('duplicate font identity')
        seen.add(digest);decl=provenance.get(digest);role=decl['role'] if decl else 'UNKNOWN'
        expected=decl['expected_pdta_sha256'] if decl else None;actual=observed.get(digest)
        if expected is not None and actual is not None and expected!=actual:raise ValueError('PDTA catalog mismatch')
        verified=expected is not None and actual==expected
        identity={'metadata_file_sha256':digest,'pdta_sha256':expected,'observed_pdta_sha256':actual,
                  'status':'VERIFIED_PDTA' if verified else 'UNVERIFIED','role':role,'display_name':header[1]}
        audit=audits[digest]
        presets={};zones={};names={};atten={};sample_names={};name_sources={}
        instrument_names={};samples={};preset_hex={};preset_name_verified={}
        for line in lines:
            if line.startswith(('I ','S ')):
                m=re.match(r"([IS]) id=(\d+) name='(.*?)' (bags|frames)=",line)
                if not m:raise ValueError('invalid raw name record')
                kind,number,name=m[1],int(m[2]),m[3]
                raw_name_bytes=raw_name(line)
                if raw_name_bytes is not None:name=raw_name_bytes.split(b'\0')[0].decode('latin1')
                (instrument_names if kind=='I' else samples)[number]=name
                # Legacy exporter is lossy. Never label re-encoded legacy text as raw bytes.
                raw_names.append({'metadata_file_sha256':digest,'kind':kind,'id':number,'legacy_text':name,
                                  'nameHex':raw_name_bytes.hex() if raw_name_bytes is not None else None,
                                  'status':'RAW_20_BYTES' if raw_name_bytes is not None else 'LEGACY_LOSSY_PENDING_PDTA'})
            if line.startswith('P '):
                m=re.fullmatch(r"P id=(\d+) bank=(\d+) rawPC=(\d+) displayPC=(\d+) name='(.*)' bags=(\d+):(\d+)(?: nameHex=[0-9a-fA-F]+)?",line)
                if not m:raise ValueError('malformed preset')
                pid,b,pc,display,name,_,_=m.groups();pid,b,pc,display=map(int,(pid,b,pc,display))
                if pid in presets or display!=pc+1 or not 0<=pc<=127 or not 0<=b<=16383:raise ValueError('invalid preset identity')
                raw_name_bytes=raw_name(line)
                if raw_name_bytes is not None:name=raw_name_bytes.split(b'\0')[0].decode('latin1')
                preset_hex[pid]=raw_name_bytes.hex() if raw_name_bytes is not None else None
                name,name_source,name_verified=corrected_preset(audit,pid,b,pc,name)
                preset_name_verified[pid]=name_verified or raw_name_bytes is not None
                presets[pid]=(b,pc,name);zones[pid]=set();names[pid]=set();sample_names[pid]=set();atten[pid]=[];name_sources[pid]=name_source
                raw_names.append({'metadata_file_sha256':digest,'kind':'P','id':pid,'text':name,
                                  'nameHex':preset_hex[pid] or (name.encode('latin1').hex() if preset_name_verified[pid] else None),'hex_scope':'raw_20_bytes' if preset_hex[pid] is not None else 'audit_decoded_name_content_excludes_NUL_and_unused_padding',
                                  'status':'AUDIT_CROSSCHECKED_TEXT' if preset_name_verified[pid] else 'LEGACY_LOSSY_PENDING_PDTA','provenance':name_source})
            elif line.startswith('Z '):
                m=re.search(r'relation=(\d+):',line);pid=int(m[1])
                if pid not in presets:raise ValueError('zone without preset')
                inst=re.search(r"instrument=(\d+):",line);sample=re.search(r"sample=(\d+):",line)
                if not inst or not sample:raise ValueError('missing zone identity')
                names[pid].add(instrument_names[int(inst[1])]);sample_names[pid].add(samples[int(sample[1])])
                atten[pid].append(attenuation(line))
                k=re.search(r'keys=(\d+):(\d+) velocities=(\d+):(\d+) eligible=(true|false)',line)
                if not k:raise ValueError('malformed zone')
                lo,hi,vl,vh=map(int,k.groups()[:4])
                if not all(0<=v<=127 for v in (lo,hi,vl,vh)):raise ValueError('zone range outside MIDI')
                if k[5]=='true':
                    if lo>hi or vl>vh:raise ValueError('contradictory eligible zone')
                    zones[pid].update(range(lo,hi+1))
        count=re.search(r'TABLES presets=(\d+)',raw.decode())[1]
        if set(presets)!=set(range(int(count))):raise ValueError('preset count/id completeness')
        if (len(presets),len(instrument_names),len(samples))!=(audit['structure']['presets'],audit['structure']['instruments'],audit['structure']['samples']):raise ValueError('audit table count mismatch')
        fonts.append({**identity,'presets':len(presets)})
        for pid,(bank,pc,name) in sorted(presets.items()):
            key=(digest,bank,pc,name);override=reviews.get(key)
            if override:review_seen.add(key)
            f=family(name);metadata_families={family(n) for n in (names[pid] | (sample_names[pid] if audit['sample_weight'] else set()))}-{'UNKNOWN'}
            all_families=metadata_families|({f} if f!='UNKNOWN' else set())
            evidence='CONFLICT' if len(all_families)>1 else 'METADATA_CONSISTENT' if metadata_families else 'NAME_ONLY'
            if f=='UNKNOWN' and len(metadata_families)==1:f=next(iter(metadata_families))
            if override:f=override['family'];evidence=override['evidence']
            # U1 does not invent EXACT Yamaha identities or listened verification.
            if evidence=='LISTENED_VERIFIED' and not override:raise ValueError('unreviewed listened verification')
            entry={'metadata_file_sha256':digest,'pdta_sha256':expected,'font_role':role,
                'preset_id':pid,'bank':bank,'pc':pc,'preset_name':name,'nameHex':preset_hex[pid] or (name.encode('latin1').hex() if preset_name_verified[pid] else None),
                'nameHexScope':'raw_20_bytes' if preset_hex[pid] is not None else 'audit_decoded_name_content_excludes_NUL_and_unused_padding','name_provenance':name_sources[pid],
                'family':f,'evidence':evidence,
                'kind':'DRUM' if bank in (127,128) or role=='PRIME_DRUM' else 'VOICE',
                'standard':'CUSTOM' if role=='PRIME_DRUM' else 'UNREVIEWED',
                'keys_present':sorted(zones[pid]),'coverage_scope':'union_of_eligible_key_velocity_zones_not_all_velocities_or_PCM',
                'metadata_names':sorted(names[pid]),'sample_names_legacy':sorted(sample_names[pid]),'gmCanonical':False,
                'yamahaMatch':'PENDING_REVIEWED_PDTA' if override and override.get('yamaha_exact_requested') else 'NONE',
                'identity_status':identity['status'],'runtimeEligible':False}
            annotations(entry,audit,atten[pid])
            entry['rule_provenance']['family']=('tools/sf2_catalog/catalog_review.yaml#'+name+'; sha256='+sha(review_bytes)) if override else 'tools/sf2_catalog/build_catalog.py#family; conservative-name-evidence-v1, no candidate PC'
            entry['rule_provenance']['keys_present']='owner metadata eligible Z key/velocity range union; input digests in catalog_manifest.json'
            entries.append(entry)
    if review_seen!=set(reviews):raise ValueError('review entry absent or preset runtime metadata name differs')
    if set(provenance)!=seen:raise ValueError('required font missing')
    entries.sort(key=lambda e:(e['metadata_file_sha256'],e['bank'],e['pc'],e['preset_name'],e['preset_id']))
    manifest={'schema':1,'generator':VERSION,'pdta_domain':DOMAIN,'status':'PROVISIONAL','catalogUse':'OFF_U0_U1',
              'dictionary_version':'conservative-name-evidence-v1; GM canonical dictionary NOT supplied',
              'review_sha256':sha(review_bytes),'metadata_sha256':input_hashes,'fonts':sorted(fonts,key=lambda f:f['metadata_file_sha256']),
              'raw_name_status':'PARTIAL; original instrument/sample bytes and uncrosschecked Colombo preset names pending PDTA',
              'audit_provenance_sha256':sha((Path(__file__).resolve().parents[2]/'tests/fixtures/sf2_binary_audit/provenance.json').read_bytes()),
              'presets':len(entries),'voices':sum(e['kind']=='VOICE' for e in entries),'drums':sum(e['kind']=='DRUM' for e in entries)}
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    (out/'catalog_manifest.json').write_bytes(canonical(manifest));(out/'catalog.json').write_bytes(canonical(entries))
    compressed=io.BytesIO()
    with gzip.GzipFile(filename='',fileobj=compressed,mode='wb',mtime=0) as archive:archive.write(canonical(entries))
    (out/'catalog.json.gz').write_bytes(compressed.getvalue())
    raw_names.sort(key=lambda n:(n['metadata_file_sha256'],n['kind'],n['id']))
    compressed_names=io.BytesIO()
    with gzip.GzipFile(filename='',fileobj=compressed_names,mode='wb',mtime=0) as archive:archive.write(canonical(raw_names))
    (out/'catalog_names.json.gz').write_bytes(compressed_names.getvalue())
    for kind,filename in [('VOICE','voice_catalog_generated.h'),('DRUM','drum_catalog_generated.h')]:
        content=['#pragma once','#include <cstddef>','namespace u01_'+kind.lower()+' {',
                 'struct Entry {const char* metadataFileSha256; const char* pdtaSha256; int bank, pc, presetId; const char* name; const char* family; const char* evidence; bool runtimeEligible; const char* nameHex; const char* standard; const char* keyMapStandard; bool levelOutlier;};',
                 'inline constexpr Entry entries[] = {']
        for e in entries:
            if e['kind']!=kind:continue
            strings=[json.dumps(e['metadata_file_sha256']),json.dumps(e['pdta_sha256']) if e['pdta_sha256'] else 'nullptr',str(e['bank']),str(e['pc']),str(e['preset_id']),json.dumps(e['preset_name']),json.dumps(e['family']),json.dumps(e['evidence']),'false',json.dumps(e['nameHex']) if e['nameHex'] else 'nullptr',json.dumps(e['standard']),json.dumps(e['keyMapStandard']),'true' if 'LEVEL_OUTLIER' in e['flags'] else 'false']
            content.append('    {'+', '.join(strings)+'},')
        content+=['};','inline constexpr std::size_t count=sizeof(entries)/sizeof(entries[0]);','}']
        (out/filename).write_text('\n'.join(content)+'\n')
    report=['# U1 provisional catalog','', 'Runtime use OFF. File hashes are metadata provenance, NOT PDTA identity.','',
            f'Presets: {len(entries)}; voice: {manifest["voices"]}; drum: {manifest["drums"]}.','',
            '## Review queue / conflicts','']
    for e in entries:
        if e['evidence'] in ('CONFLICT','NAME_ONLY') or e['yamahaMatch']!='NONE':
            report.append(f"- {e['font_role']} {e['bank']}/{e['pc']} {e['preset_name']}: {e['family']} / {e['evidence']} / {e['yamahaMatch']}")
    report+=['','## Binary audit integration','',
        'Golden PDTA references: LIST payload INCLUDING pdta fourcc. Original bytes/device validation pending.',
        'Colombo 127=XG, 128=GS; Tyros 128=GS. Prime Drum=CUSTOM with separate keyMapStandard.',
        'Prime sample-name evidence weight=0, derived from audited ambiguity ratio >10%.',
        'Raw instrument/sample nameHex remains null pending PDTA; legacy text is retained, never relabeled raw.',
        'crosscheck.py caps preset mismatches at 5. Colombo reaches that cap; 887 unlisted Colombo preset names remain unverified, with nameHex=null.',
        'Attenuation is inherited PG48+IG48, not a measurement of BASSMIDI clamping.',
        'Model levels A/B do not include controllers/envelopes/modulators and never change playback.','']
    for e in entries:
        if e['flags']:report.append(f"- {e['font_role']} {e['bank']}/{e['pc']} {e['preset_name']}: {','.join(e['flags'])}; A={e['levelEstimateA_dB']}, B={e['levelEstimateB_dB']} dB; review only")
    (out/'catalog_report.md').write_text('\n'.join(report)+'\n')
    return manifest

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--metadata',required=True);p.add_argument('--out',required=True)
    p.add_argument('--review',default=str(Path(__file__).with_name('catalog_review.yaml')));p.add_argument('--fingerprints')
    a=p.parse_args();print(json.dumps(build(a.metadata,a.review,a.out,a.fingerprints),sort_keys=True))

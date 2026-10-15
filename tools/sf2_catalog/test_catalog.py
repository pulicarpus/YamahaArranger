#!/usr/bin/env python3
"""Requires the owner's metadata export. Never generates invented real-font identities."""
import argparse
import hashlib
import json
import statistics
import tempfile
import time
from pathlib import Path
from build_catalog import build, metadata_inputs

def main():
    p=argparse.ArgumentParser();p.add_argument('--metadata',required=True);a=p.parse_args()
    review=Path(__file__).with_name('catalog_review.yaml')
    with tempfile.TemporaryDirectory(prefix='u01-catalog-') as directory:
        root=Path(directory);one=root/'one';two=root/'two'
        manifest=build(a.metadata,review,one);build(a.metadata,review,two)
        assert manifest['presets']==1148 and len(manifest['fonts'])==4
        assert {f['role'] for f in manifest['fonts']}=={'COLOMBO','TYROS4','PRIME_MELODY','PRIME_DRUM'}
        assert all(p.read_bytes()==(two/p.name).read_bytes() for p in one.iterdir())
        entries=json.loads((one/'catalog.json').read_text())
        assert all(not e['runtimeEligible'] and e['identity_status']=='HOST_PDTA_VERIFIED_DEVICE_PENDING' and len(e['pdta_sha256'])==64 for e in entries)
        for pc,name in [(52,'Suling S770'),(53,'Shakuhachi'),(47,'Melodic Tom1'),(84,'SARUNE')]:
            e=next(e for e in entries if e['font_role']=='PRIME_MELODY' and e['pc']==pc and e['preset_name']==name)
            assert e['evidence']=='CONFLICT'
        assert all(e['standard']=='CUSTOM' for e in entries if e['font_role']=='PRIME_DRUM')
        # Imported golden references do not establish local-byte/device verification.
        for font in manifest['fonts']:
            fake=root/'observations.txt';provenance=font['metadata_file_sha256']
            fake.write_text('SF2_FINGERPRINT fileSha256='+provenance+' pdtaSha256='+'1'*64+'\n')
            try:build(a.metadata,review,root/'mismatch',fake)
            except ValueError as e:assert 'PDTA catalog mismatch' in str(e)
            else:raise AssertionError('mismatched device PDTA accepted')
            fake.write_text('SF2_FINGERPRINT fileSha256='+provenance+' pdtaSha256='+font['pdta_sha256']+'\n')
            seen=root/'seen';observed=build(a.metadata,review,seen,fake)
            assert sum(f['status']=='VERIFIED_PDTA' for f in observed['fonts'])==1
        expected={'COLOMBO':(761,81,-175),'TYROS4':(567,31,-367),'PRIME_MELODY':(2230,28,-1440),'PRIME_DRUM':(4393,16,-1440)}
        for role,counts in expected.items():
            selected=[e for e in entries if e['font_role']==role]
            assert (sum(e['negAttenZones'] for e in selected),sum(e['negAttenZones']>0 for e in selected),min(e['attenRange_cB'][0] for e in selected if e['attenRange_cB']))==counts
        for e in entries:
            if e['font_role']=='COLOMBO' and e['bank']==127:assert e['standard']==e['keyMapStandard']=='XG'
            if e['font_role'] in ('COLOMBO','TYROS4') and e['bank']==128:assert e['standard']==e['keyMapStandard']=='GS'
            if e['font_role'].startswith('PRIME'):assert e['sampleNameEvidenceWeight']==0
        honky=next(e for e in entries if e['font_role']=='PRIME_MELODY' and e['bank']==0 and e['pc']==3)
        assert honky['flags']==['LEVEL_OUTLIER'] and (honky['levelEstimateA_dB'],honky['levelEstimateB_dB'])==(-43.4,-26.6)
        import gzip
        names=json.loads(gzip.decompress((one/'catalog_names.json.gz').read_bytes()))
        assert len(names)==12783 and all(n['status']=='RAW_20_BYTES' and len(n['nameHex'])==40 for n in names)
        assert len([e for e in entries if "'" in e['preset_name']])==14
        for e in entries:
            if e['nameHex'] is not None:assert bytes.fromhex(e['nameHex']).split(b'\0',1)[0].decode('latin1')==e['preset_name']
        assert all(e['nameHex'] is not None and len(e['nameHex'])==40 for e in entries)
        # Sample names from Prime are retained as data, with ZERO family influence.
        # Mutate every Prime sample name, not the instrument/preset names or PC.
        changed_samples=root/'changed-samples';changed_samples.mkdir()
        for filename,raw in metadata_inputs(a.metadata).items():
            text=raw.decode()
            if any(f['role'].startswith('PRIME') and f['metadata_file_sha256'] in text for f in manifest['fonts']):
                import re
                text='\n'.join(re.sub(r'nameHex=[0-9a-f]+', 'nameHex='+b'Grand Piano'.ljust(20,b'\0').hex(),line) if line.startswith('S ') else line for line in text.split('\n'))
            (changed_samples/filename).write_text(text)
        changed_out=root/'changed-out';build(changed_samples,review,changed_out)
        altered=json.loads((changed_out/'catalog.json').read_text())
        def semantic(rows):return {(e['metadata_file_sha256'],e['preset_id']):(e['family'],e['evidence']) for e in rows}
        assert semantic(altered)==semantic(entries)
        # PC permutation of a neutral, unreviewed melodic candidate cannot add a family.
        changed_pc=root/'changed-pc';changed_pc.mkdir()
        import re
        target=next(e for e in entries if e['font_role']=='PRIME_MELODY' and e['family']=='UNKNOWN' and e['evidence']!='CONFLICT')
        for filename,raw in metadata_inputs(a.metadata).items():
            text=raw.decode()
            if target['metadata_file_sha256'] in text:
                text=re.sub(r'(?m)^(P id='+str(target['preset_id'])+r' bank=0 rawPC=)\d+ displayPC=\d+',r'\g<1>126 displayPC=127',text)
            (changed_pc/filename).write_text(text)
        pc_out=root/'pc-out';build(changed_pc,review,pc_out)
        assert semantic(json.loads((pc_out/'catalog.json').read_text()))==semantic(entries)
        # Rename all PDTA archive members and claimed source filenames. Identity stays hash-based.
        import zipfile,io
        renamed=root/'renamed.zip'
        with zipfile.ZipFile(a.metadata) as z:members={Path(n).name:z.read(n) for n in z.namelist() if not n.endswith('/')}
        exported=json.loads(members['pdta_manifest.json']);renames={}
        for index,f in enumerate(exported['files']):
            f['source_sf2']='UNRELATED_'+str(index)+'.sf2'
            for key in ['pdta_file','names_file','zones_attenuation_file']:
                old=f[key];f[key]='renamed_'+old;renames[old]=f[key]
        members['pdta_manifest.json']=json.dumps(exported).encode()
        with zipfile.ZipFile(renamed,'w') as z:
            for name,data in members.items():z.writestr('unrelated-directory/'+renames.get(name,name),data)
        renamed_out=root/'renamed-out';renamed_manifest=build(renamed,review,renamed_out)
        assert {f['role'] for f in renamed_manifest['fonts']}=={'COLOMBO','TYROS4','PRIME_MELODY','PRIME_DRUM'}
        assert json.loads((renamed_out/'catalog.json').read_text())==entries
        # Role is keyed by provenance fingerprint: metadata display names are not identity.
        values=json.loads(review.read_text());assert all(len(f['expected_pdta_sha256'])==64 for f in values['font_provenance'])
        timing=[]
        for _ in range(31):
            start=time.perf_counter_ns();loaded=json.loads((one/'catalog.json').read_bytes());timing.append(time.perf_counter_ns()-start);assert len(loaded)==1148
        print(json.dumps({'status':'PASS','deterministic_files':len(list(one.iterdir())),'presets':1148,'voices':manifest['voices'],'drums':manifest['drums'],'catalog_json_bytes':(one/'catalog.json').stat().st_size,'host_load_median_ms':statistics.median(timing)/1e6,'catalog_use':'OFF','android_hashing':'UNVERIFIED'},sort_keys=True))

if __name__=='__main__':main()

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
        assert all(not e['runtimeEligible'] and e['identity_status']=='UNVERIFIED' and e['pdta_sha256'] is None for e in entries)
        for pc,name in [(52,'Suling S770'),(53,'Shakuhachi'),(47,'Melodic Tom1'),(84,'SARUNE')]:
            e=next(e for e in entries if e['font_role']=='PRIME_MELODY' and e['pc']==pc and e['preset_name']==name)
            assert e['evidence']=='CONFLICT'
        assert all(e['standard']=='CUSTOM' for e in entries if e['font_role']=='PRIME_DRUM')
        # Device values cannot silently establish reviewed identities.
        fake=root/'observations.txt';provenance=manifest['fonts'][0]['metadata_file_sha256']
        fake.write_text('SF2_FINGERPRINT fileSha256='+provenance+' pdtaSha256='+'1'*64+'\n')
        seen=root/'seen';build(a.metadata,review,seen,fake)
        assert all(f['status']=='UNVERIFIED' for f in json.loads((seen/'catalog_manifest.json').read_text())['fonts'])
        approved=json.loads(review.read_text())
        next(f for f in approved['font_provenance'] if f['file_sha256']==provenance)['expected_pdta_sha256']='2'*64
        bad=root/'bad-review.yaml';bad.write_text(json.dumps(approved))
        try:build(a.metadata,bad,root/'mismatch',fake)
        except ValueError as e:assert 'PDTA catalog mismatch' in str(e)
        else:raise AssertionError('mismatched device PDTA accepted')
        renamed=root/'renamed';renamed.mkdir()
        for index,raw in enumerate(metadata_inputs(a.metadata).values()):
            header,rest=raw.split(b'\n',1)
            header=__import__('re').sub(rb"file='[^']*'", b"file='UNRELATED_NAME.sf2'",header,count=1)
            (renamed/f'font_{99-index}.txt').write_bytes(header+b'\n'+rest)
        renamed_out=root/'renamed-out';renamed_manifest=build(renamed,review,renamed_out)
        assert {f['role'] for f in renamed_manifest['fonts']}=={'COLOMBO','TYROS4','PRIME_MELODY','PRIME_DRUM'}
        assert json.loads((renamed_out/'catalog.json').read_text())==entries
        # Role is keyed by provenance fingerprint: metadata display names are not identity.
        values=json.loads(review.read_text());assert all(f['expected_pdta_sha256'] is None for f in values['font_provenance'])
        timing=[]
        for _ in range(31):
            start=time.perf_counter_ns();loaded=json.loads((one/'catalog.json').read_bytes());timing.append(time.perf_counter_ns()-start);assert len(loaded)==1148
        print(json.dumps({'status':'PASS','deterministic_files':len(list(one.iterdir())),'presets':1148,'voices':manifest['voices'],'drums':manifest['drums'],'catalog_json_bytes':(one/'catalog.json').stat().st_size,'host_load_median_ms':statistics.median(timing)/1e6,'catalog_use':'OFF','android_hashing':'UNVERIFIED'},sort_keys=True))

if __name__=='__main__':main()

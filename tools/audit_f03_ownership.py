#!/usr/bin/env python3
"""Read-only F03 raw overlap/projection inventory; no musical pairing oracle.

FIFO pairing is an explicitly labeled observer convention, not SMF/Yamaha
instance identity. Raw bytes, ordinals, all native policies and event order are
verified against frozen S1. Generated JVM cases isolate one source/key window;
omitted other sources are disclosed, so these are reduced real counterexamples.
"""
import argparse
from collections import Counter, defaultdict, deque
import csv
import io
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from audit_sff1_corpus import (ROOT, ARCHIVE_SHA, read_smf, expected_projection,
    raw_note_evidence, read_native, compile_driver, file_sha, digest, require)
from test_sff_root_selection import portable_production_identity

REF=ROOT/'tests/fixtures/f03_ownership_reference_s5.json'
CASES=ROOT/'app/src/test/resources/sff5/overlap_cases.tsv'
CSV=ROOT/'tests/fixtures/f03_overlap_inventory_s5.csv'

def build(corpus, output, manifest):
    portable_production_identity()
    require(file_sha(corpus)==ARCHIVE_SHA,'Original corpus SHA mismatch')
    pinned=json.loads((ROOT/'tests/fixtures/sff1_reference.json').read_text())
    require(file_sha(manifest)=='7701f47cf7cf7e8166cd0ba6ffdc5a4792af7ba2743e8b386fa1237fb086f56b','Verified S4 full manifest required')
    full=json.loads(manifest.read_text())
    driver=compile_driver(output)
    rows=[];cases=[];by_style=Counter();by_section=Counter();cross=0
    with zipfile.ZipFile(corpus) as archive, tempfile.TemporaryDirectory(prefix='f03-') as temp:
        names=sorted(n for n in archive.namelist() if not n.endswith('/'))
        require(len(names)==503,'503 original style files required')
        for name in names:
            relative=name.removeprefix('sff1/');data=archive.read(name)
            require(digest_bytes(data)==pinned['styles'][relative]['file_sha256'],'Style SHA mismatch '+relative)
            _,raw,offsets,_,_=read_smf(data);projection=expected_projection(raw)
            overlaps,maximum=raw_note_evidence(raw,offsets)
            require(maximum<=2,'Corpus multiplicity drift')
            if not overlaps:continue
            path=Path(temp)/'input.style';path.write_bytes(data)
            native=read_native(subprocess.check_output([str(driver),str(path)],text=True))
            require(native['raw']==raw,'Native raw event drift')
            for sec,actual in native['sections'].items():
                require(digest(actual)==full['styles'][relative]['sections'][sec]['native_section_sha256'],'Native section drift')
            pending=defaultdict(deque);pairs={}
            for ordinal,e in enumerate(raw):
                key=(e[2],e[3]);high=e[1]&240
                if high==144 and e[4]>0:pending[key].append(ordinal)
                elif high==128 or(high==144 and e[4]==0):
                    if pending[key]:pairs[pending[key].popleft()]=ordinal
            def section_of(ordinal):
                tick=raw[ordinal][0]
                return next((s for s,p in projection.items() if p['start']<=tick<p['end']),'PRE_SECTION')
            for overlap in overlaps:
                ordinal=overlap['raw_event_ordinal'];prior=overlap['prior_on_ordinals'][0]
                section=section_of(ordinal);first_off=pairs.get(prior);second_off=pairs.get(ordinal)
                src,note=overlap['source'],overlap['note'];part=next(p for p in native['sections'][section]['parts'] if p['source']==src)
                start=projection[section]['start'];end=projection[section]['end']
                ordinals=[prior,ordinal]+[o for o in (first_off,second_off) if o is not None]
                outside=[o for o in ordinals if section_of(o)!=section]
                cross+=bool(outside)
                # Full same-key stream from prior ON through paired last OFF,
                # clipped to authored section. Keeps retriggers/extra OFFs/order.
                last=max(ordinals)
                selected=[i for i in range(prior,last+1) if raw[i][2:4]==[src,note]
                          and ((raw[i][1]&240) in (128,144)) and start<=raw[i][0]<end]
                case_id=f'{len(rows):03d}'
                event_rows=[{'ordinal':i,'byte_offset':offsets[i],'tick':raw[i][0],
                    'relative_tick':raw[i][0]-start,'status':raw[i][1],'velocity':raw[i][4],
                    'source':src,'note':note,'section':section_of(i)} for i in ordinals]
                row={'id':case_id,'style':relative,'style_sha256':digest_bytes(data),'section':section,
                     'source':src,'note':note,'ownership_key':f'{src}:{note}',
                     'second_on_ordinal':ordinal,'prior_on_ordinal':prior,
                     'first_off_fifo_ordinal':first_off,'second_off_fifo_ordinal':second_off,
                     'cross_section':bool(outside),'events':event_rows,
                     'policy_candidates':part['policies'],'native_section_sha256':digest(native['sections'][section]),
                     'classification':'SOURCE_OVERLAP','audible_failure':'UNKNOWN',
                     'pairing':'FIFO_OBSERVER_CONVENTION_NOT_YAMAHA_ORACLE',
                     'reduction':'REAL_AUTHORED_SAME_KEY_WINDOW_OTHER_SOURCES_OMITTED',
                     'selected_raw_ordinals':selected}
                rows.append(row);by_style[relative]+=1;by_section[section]+=1
                cases.append('\t'.join(['CASE',case_id,relative,section,str(src),str(note),str(end-start),row['style_sha256']]))
                for policy in part['policies']:
                    cases.append('POLICY\t'+' '.join(map(str,policy)))
                for i in selected:
                    e=raw[i];cases.append('\t'.join(map(str,['EVENT',i,offsets[i],e[0]-start,e[1],src,note,e[4]])))
                cases.append('END')
    require(len(rows)==523 and len(by_style)==47,'Frozen overlap523/47 mismatch')
    case_text='\n'.join(cases)+'\n'
    stream=io.StringIO();writer=csv.writer(stream,lineterminator='\n')
    writer.writerow(['id','style','section','source','note','ownership_key','on1_ordinal','on2_ordinal','off1_fifo_ordinal','off2_fifo_ordinal','cross_section','raw_event_trace','policy_candidates'])
    for r in rows:writer.writerow([r['id'],r['style'],r['section'],r['source'],r['note'],r['ownership_key'],r['prior_on_ordinal'],r['second_on_ordinal'],r['first_off_fifo_ordinal'],r['second_off_fifo_ordinal'],r['cross_section'],json.dumps(r['events'],separators=(',',':')),json.dumps(r['policy_candidates'],separators=(',',':'))])
    result={'schema':1,'S4_baseline':'98a9d2a79b6226bf14b0a4be624f494a878b2ac5',
            'corpus_sha256':ARCHIVE_SHA,'styles':503,'overlapping_on':523,'overlap_styles':47,
            'maximum_multiplicity':2,'cross_section_windows':cross,
            'by_style':dict(sorted(by_style.items())),'by_section':dict(sorted(by_section.items())),
            'all_cases':rows,'JVM_fixture_sha256':digest_bytes(case_text.encode()),
            'inventory_csv_sha256':digest_bytes(stream.getvalue().encode()),
            'behavior_oracle':'NONE; BASELINE OBSERVATION ONLY'}
    result['evidence_sha256']=digest(result)
    portable_production_identity()
    return result,case_text,stream.getvalue()

def digest_bytes(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus',required=True,type=Path)
    parser.add_argument('--output',type=Path,default=ROOT/'build/s5-ownership')
    parser.add_argument('--manifest',type=Path,default=ROOT/'build/s5-before/observed_manifest.json',help='Freshly regenerated, SHA-pinned S4 manifest, never committed')
    parser.add_argument('--record',action='store_true',help='initial S5 artifacts only; refuses overwrite')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    result,cases,csv_text=build(args.corpus,args.output,args.manifest)
    payload=json.dumps(result,sort_keys=True,indent=2)+'\n'
    for path,text in [(REF,payload),(CASES,cases),(CSV,csv_text)]:
        if args.record:
            require(not path.exists(),'Refuse overwrite '+str(path));path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
        else:require(path.read_text()==text,'S5 evidence drift '+str(path))
    (args.output/'observed.json').write_text(payload)
    print(f"F03_CORPUS PASS 503 styles; overlaps=523/47; crossSectionWindows={result['cross_section_windows']} evidence={result['evidence_sha256']} audibleFailure=UNKNOWN")

if __name__=='__main__':main()

#!/usr/bin/env python3
"""Execute pinned baseline and physical F04 candidate, allow only proven routing drops.
No historical golden write; no audio/native/device success inference.
"""
import hashlib
import json
import struct
import re
import subprocess
from pathlib import Path
from f04_source_guard import ROOT, verify_profile, pipeline_expected

BASE=ROOT/'build/f04-baseline'
CANDIDATE=ROOT/'build/sff1-jvm'
OUT=ROOT/'build/f04-differential'

def declarations():
    result={};name=None;source=None
    for line in (ROOT/'app/src/test/resources/sff1_main_d_native.tsv').read_text().splitlines():
        f=line.split('\t')
        if f[0]=='STYLE':name=f[1];result[name]={}
        elif f[0]=='PART':source=int(f[2]);result[name][source]=[]
        elif f[0]=='POLICY':result[name][source].append((int(f[2]),int(f[3])))
    return result

def approved_projection(text,decls):
    rows=text.splitlines();kept=[];dropped=[];i=0
    while i<len(rows):
        f=rows[i].split('\t')
        if f[:2]==['AUDIO','noteOnStyleChannel']:
            dst,src=int(f[2]),int(f[5]);policies=decls.get(src,[])
            eligible=dst==src and src in (8,9) and policies and all(s==src and d in range(10,16) for s,d in policies)
            if eligible:
                assert int(f[3])==int(f[6]), 'Phantom fallback must retain raw key'
                assert i+1<len(rows), 'Missing external paired dispatch'
                packet=rows[i+1].split('\t')
                velocity=round(struct.unpack('!f',struct.pack('!I',int(f[4].split(':')[1])))[0]*127)
                assert packet==['MIDI','sendNoteOn',str(dst),f[3],str(velocity)], 'Different or unpaired MIDI event'
                dropped.append({'source':src,'destination':dst,'key':int(f[6]),'velocity':velocity,'tick':int(f[8])})
                i+=2;continue
        kept.append(rows[i]);i+=1
    return '\n'.join(kept)+'\n',dropped

def compare_traces():
    decls=declarations();results={};changed=[]
    baseline=list((BASE/'traces').glob('*.txt'));candidate=list((CANDIDATE/'traces').glob('*.txt'))
    assert len(baseline)==len(candidate)==20,'All six synthetic + fourteen real captures required'
    assert {p.name for p in baseline}=={p.name for p in candidate}
    for path in baseline:
        label=path.stem
        style=next((s for s in decls if label.startswith('GOLDEN_'+s.replace('/','_')+'_')),None)
        before=path.read_text();after=(CANDIDATE/'traces'/path.name).read_text()
        expected,drops=approved_projection(before,decls[style]) if style else (before,[])
        assert after==expected,'NON-F04 DIFFERENTIAL: '+label
        if drops:changed.append(label)
        results[label]={'audio_on_removed':len(drops),'midi_on_removed':len(drops),'removed_events':drops,
                        'baseline_trace_sha256':hashlib.sha256(before.encode()).hexdigest(),
                        'candidate_trace_sha256':hashlib.sha256(after.encode()).hexdigest(),
                        'retained_trace_exact':True}
        # Negative controls: extra OFF, losing a retained call, or changing setup/velocity cannot pass.
        assert after+'MIDI\tsendNoteOff\t8\t60\n'!=expected
        assert '\n'.join(after.splitlines()[1:])+'\n'!=expected
        assert after.replace('AUDIO\t','CORRUPTED\t',1)!=expected
    assert len(changed)==4
    for style,count in [('Movie&Show/BaroqueAir1.S145.sst',44),('Pop&Rock/Unplugged2.T151.prs',256)]:
        for chord in ('C_MAJOR','F_MAJOR'):
            assert results['GOLDEN_'+style.replace('/','_')+'_'+chord]['audio_on_removed']==count
    assert sum(v['audio_on_removed'] for v in results.values())==600
    return results

def reduced_projection(text):
    blocks=re.findall(r'CASE .*?(?=CASE |\Z)',text,re.S)
    assert len(blocks)==1046
    policies={}
    for block in (ROOT/'app/src/test/resources/sff5/overlap_cases.tsv').read_text().split('END\n'):
        if not block.strip():continue
        lines=block.strip().splitlines();head=lines[0].split('\t')
        policies[int(head[1])]=[tuple(map(int,row.split('\t')[1].split()[:2])) for row in lines if row.startswith('POLICY\t')]
    changed=[];output=[]
    for block in blocks:
        head=block.splitlines()[0];case=int(head.split()[1])
        if case not in (386,387):output.append(block);continue
        assert head.startswith(f"CASE {case:03d} Pop&Rock/6-8Rock.S115.bcs/FillCC root=")
        assert head.endswith(('root=0','root=5'))
        assert policies[case] and all(src==9 and dst in range(10,16) for src,dst in policies[case])
        tick=0 if case==386 else 1920
        assert 'AUDIO [ON 9 60, ON 9 60]\nMIDI [ON 9 60, ON 9 60]\n' in block
        dispatch=f'DISPATCH [src=9 note=60 tick={tick} dst=9 output=60 velocity=71, src=9 note=60 tick={tick} dst=9 output=60 velocity=71]'
        assert dispatch in block and 'OWNER {}' in block
        forward=f'FORWARD src=9 note=60 dst=11 output=60 tick={tick} origin=-'
        assert block.count(forward)==2
        candidate=block.replace('AUDIO [ON 9 60, ON 9 60]\nMIDI [ON 9 60, ON 9 60]\n','AUDIO []\nMIDI []\n')
        candidate=candidate.replace(dispatch,'DISPATCH []').replace(forward,f'DROP_NO_POLICY_WITH_CHORD src=9 note=60 dst=11 output=-1 tick={tick} origin=-')
        # Existing OFF_NO_ACTIVE_LEDGER and OWNER remain byte-identical.
        output.append(candidate);changed.append(head)
    assert len(changed)==4
    return ''.join(output),changed


def main():
    verify_profile();OUT.mkdir(parents=True,exist_ok=True)
    from generated_bass_sdk_guard import PATHS
    if any((ROOT/path).exists() for path in PATHS):
        subprocess.run(['python3','-B',str(ROOT/'tools/test_generated_bass_sdk_guard.py')],cwd=ROOT,check=True)
    common=['--existing-regressions','--s2','--s3','--s4','--s5']
    for profile,extra,output in [('baseline',['--f04-baseline'],BASE),('candidate',['--f04'],CANDIDATE)]:
        log=OUT/(profile+'-pipeline.log')
        with log.open('w') as stream:
            result=subprocess.run(['python3',str(ROOT/'tools/test_sff1_pipeline.py'),*common,*extra,'--fetch-dependencies','--output',str(output)],cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text());raise AssertionError(profile+' pipeline failed')
        count=83 if profile=='baseline' else 90
        assert 'OK ('+str(count)+' tests)' in log.read_text(),profile+' test count drift'
        print('F04_'+profile.upper()+'_REGRESSIONS PASS tests='+str(count))
    assert (BASE/'sff1_pipeline_observed.tsv').read_bytes()==(ROOT/'app/src/test/resources/sff1_pipeline_digests.tsv').read_bytes()
    assert (CANDIDATE/'sff1_pipeline_observed.tsv').read_bytes()==pipeline_expected()
    results=compare_traces();verify_profile()
    reduced_base=(BASE/'ownership-traces.txt').read_text()
    assert reduced_base==(ROOT/'tests/fixtures/f03_dispatch_trace_s5.txt').read_text()
    reduced_expected,reduced_changed=reduced_projection(reduced_base)
    assert (CANDIDATE/'ownership-traces.txt').read_text()==reduced_expected,'NON-F04 REDUCED OVERLAP DIFFERENTIAL'
    proof={'baseline_commit':verify_profile()['baseline_commit'],'differential':'PASS','captured_runs':20,
           'changed_runs':4,'audio_on_removed':600,'midi_on_removed':600,'retained_calls':'BYTE_IDENTICAL',
           'historical_goldens':'UNCHANGED','production_files_changed':1,
           'native_pcm':'UNRUN','android_device':'UNRUN','yamaha_timbre':'UNKNOWN','original_503_corpus':'BLOCKED',
           's5_reduced_runs':1046,'s5_changed_runs':reduced_changed,'s5_audio_on_removed':8,'s5_midi_on_removed':8,
           's5_retained_off_and_owner':'BYTE_IDENTICAL','results':results}
    (OUT/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    print('F04_DIFFERENTIAL PASS Baroque=44 Unplugged=256 eachChord=C/F; no other event delta; dispatch != device audio')

if __name__=='__main__':main()

#!/usr/bin/env python3
"""Join raw provenance to frozen real production transcripts, not PCM claims."""
from collections import Counter
import json
from pathlib import Path
from audit_sff1_corpus import ROOT,digest,require

def summarize():
    inventory=json.loads((ROOT/'tests/fixtures/f03_ownership_reference_s5.json').read_text())
    cases={r['id']:r for r in inventory['all_cases']};rows=[];counts=Counter()
    text=(ROOT/'tests/fixtures/f03_dispatch_trace_s5.txt').read_text()
    for block in text.split('CASE ')[1:]:
        lines=[line for line in block.splitlines() if line];cid=lines[0].split()[0];root=int(lines[0].rsplit('root=',1)[1]);case=cases[cid]
        stages=[line.split()[0] for line in lines[5:]]
        replacement='SCHEDULED_REPLACE_OFF' in stages;orphan='OFF_NO_ACTIVE_LEDGER' in stages
        events={e['ordinal']:e for e in case['events']}
        first=events.get(case['first_off_fifo_ordinal']);last=events.get(case['second_off_fifo_ordinal'])
        prior=events[case['prior_on_ordinal']];second=events[case['second_on_ordinal']]
        strict_gap=bool(replacement and not case['cross_section'] and first and last
            and prior['tick']<second['tick']<first['tick']<last['tick'])
        classifications=['SOURCE_OVERLAP']
        if replacement:classifications+=['IDENTITY_COLLISION','OWNER_REPLACEMENT']
        if orphan:classifications+=['ORPHAN_NOTE_OFF_AT_SEQUENCER']
        if strict_gap:classifications+=['PREMATURE_NOTE_OFF_RELATIVE_TO_FIFO_OBSERVER_MODEL']
        if root==0:
            counts['cases']+=1;counts['owner_replacement_cases']+=replacement;counts['orphan_cases']+=orphan
            counts['strict_fifo_duration_counterexamples']+=strict_gap
            counts['policy_rejection_cases']+='DROP_NO_POLICY_WITH_CHORD' in stages
            counts['no_owner_replacement_cases']+=not replacement
        rows.append({'case_id':cid,'style':case['style'],'section':case['section'],'root':root,
             'source':case['source'],'note':case['note'],'identity':case['ownership_key'],
             'classifications':classifications,'strict_fifo_duration_gap':strict_gap,
             'first_F03_divergence':{'stage':'SCHEDULED_REPLACE_OFF','original_second_on_ordinal':case['second_on_ordinal'],
                 'original_second_on_offset':second['byte_offset'],'raw_tick':second['tick'],
                 'code':'StyleSequencer.kt:969-975'} if replacement else 'NOT_REACHED_IN_REDUCED_REPLAY',
             'dispatched':lines[1:4],'final_owner_state':lines[4],
             'lifecycle':lines[5:],'audible_failure':'UNKNOWN'})
    require(len(rows)==1046 and counts['cases']==523,'All real windows/roots required')
    result={'schema':1,'counts_C_major':dict(counts),'same_results_for_F_major_classifications':all(rows[i]['classifications']==rows[i+1]['classifications'] for i in range(0,len(rows),2)),
            'rows':rows,'limits':'Reduced same-key windows; FIFO assumption explicit; no hardware/native acceptance/PCM oracle'}
    result['summary_sha256']=digest(result)
    return result

def main():
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--record',action='store_true');args=p.parse_args()
    path=ROOT/'tests/fixtures/f03_dispatch_summary_s5.json';payload=json.dumps(summarize(),sort_keys=True,indent=2)+'\n'
    if args.record:require(not path.exists(),'Refuse overwrite S5 summary');path.write_text(payload)
    else:require(path.read_text()==payload,'S5 dispatch summary drift')
    print('S5_DISPATCH_SUMMARY '+json.dumps(summarize()['counts_C_major'],sort_keys=True))

if __name__=='__main__':main()

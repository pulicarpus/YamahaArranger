"""Evidence integration only; does not interpret audit predictions as engine measurements."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2] / 'tests/fixtures/sf2_binary_audit'
SOURCE = 'tests/fixtures/sf2_binary_audit/'

def load(review):
    manifest = json.loads((ROOT/'provenance.json').read_text())
    for path, digest in manifest['files'].items():
        if hashlib.sha256((ROOT/path).read_bytes()).hexdigest() != digest:
            raise ValueError('audit evidence integrity: '+path)
    structure = json.loads((ROOT/'structure.json').read_text())
    crosscheck = json.loads((ROOT/'crosscheck.json').read_text())
    clamp = json.loads((ROOT/'levels_clamp.json').read_text())
    emu = json.loads((ROOT/'levels_emu04.json').read_text())
    by_sha = {}
    for f in review['font_provenance']:
        s = structure[f['audit_filename']]
        if (f['expected_pdta_sha256'], f['pdta_bytes']) != (s['pdta_sha256'], s['pdta_bytes']):
            raise ValueError('review does not match Claude golden')
        c = crosscheck[f['audit_filename']]
        ratio = c['names_with_multiple_distinct_data']/c['sample_names']
        by_sha[f['file_sha256']] = dict(structure=s, crosscheck=c, sample_weight=0 if ratio>0.10 else 1,
            ambiguity_ratio=ratio, clamp=clamp, emu=emu)
    return by_sha

def attenuation(line):
    values=[]
    for prefix in ('PG','IG'):
        match = re.search(r'\b'+prefix+r'=([^;]*);',line)
        if not match: raise ValueError('missing inherited zone generators')
        generators=dict((int(op),int(value)) for op,value in re.findall(r'(\d+):(-?\d+)',match[1]))
        values.append(generators.get(48,0))
    return sum(values)

def corrected_preset(audit, pid, bank, pc, legacy):
    for actual, exported in audit['crosscheck']['preset_mismatches']:
        if actual[0]==pid:
            if [pid,bank,pc,legacy] not in (exported,actual) or actual[1:3]!=[bank,pc]:
                raise ValueError('name correction input mismatch')
            return actual[3], SOURCE+'crosscheck.json#preset_mismatches', True
    complete=audit['crosscheck']['presets_match'] or len(audit['crosscheck']['preset_mismatches'])<5
    return legacy, (SOURCE+'crosscheck.json#presets_match_or_uncapped_mismatches' if complete else 'owner_legacy_export; audit mismatch list capped at 5'), complete

def annotations(entry,audit,atten):
    e=entry;role=e['font_role'];bank=e['bank'];pc=e['pc']
    standard='CUSTOM' if role=='PRIME_DRUM' else ('XG' if role=='COLOMBO' and bank==127 else 'GS' if role in ('COLOMBO','TYROS4') and bank==128 else 'UNREVIEWED')
    keymap=standard
    if role=='PRIME_DRUM':keymap='XG_PSR_LIKE' if pc in (0,1,2) else 'PSR_CUSTOM'
    e.update(standard=standard,keyMapStandard=keymap,keyMapReviewRequired=role=='PRIME_DRUM',
        zoneCount=len(atten),negAttenZones=sum(v<0 for v in atten),attenRange_cB=[min(atten),max(atten)] if atten else None,
        attenuation_scope='all_exported_inherited_PG_plus_IG_zones_not_engine_clamp',
        sampleNameEvidenceWeight=audit['sample_weight'],sampleNameAmbiguityRatio=audit['ambiguity_ratio'],
        levelEstimateA_dB=None,levelEstimateB_dB=None,level_estimate_scope='sample_model_not_BASSMIDI',flags=[],
        rule_provenance={'keyMapStandard':SOURCE+'SF2_BINARY_AUDIT_CATALOG_CORRECTIONS.md#5.2',
            'sampleNameEvidenceWeight':SOURCE+'crosscheck.json',
            'attenuation':'owner_metadata_Z_inherited_PG48_plus_IG48; '+SOURCE+'negative_attenuation.txt',
            'family':'conservative-name-evidence-v1; review override if specified',
            'runtimeEligible':'owner_U0_U1_only_catalog_OFF'})
    code={'COLOMBO':'COL','TYROS4':'TYR','PRIME_MELODY':'PRM','PRIME_DRUM':'DRM'}[role]
    rows=[row for row in audit['clamp']['melodic'] if row['font']==code and row['preset']==e['preset_name']]
    if rows:
        if len(rows)!=1:raise ValueError('ambiguous level model')
        row=rows[0];other=next(r for r in audit['emu']['melodic'] if (r['font'],r['preset'],r['group'])==(code,row['preset'],row['group']))
        e['levelEstimateA_dB']=row['mean_db'];e['levelEstimateB_dB']=other['mean_db']
        e['level_model_keys']=row['keys'];e['level_model_velocity']=row['vel']
        e['rule_provenance']['levelEstimateA_dB']=SOURCE+'levels_clamp.json#'+row['group']
        e['rule_provenance']['levelEstimateB_dB']=SOURCE+'levels_emu04.json#'+row['group']
        peers=[r['mean_db'] for r in audit['clamp']['melodic'] if r['group']==row['group']]
        # Threshold is review-only and never changes preset selection/gain.
        import statistics
        if peers and statistics.median(peers)-row['mean_db']>12:e['flags'].append('LEVEL_OUTLIER')
        e['rule_provenance']['LEVEL_OUTLIER']='audit §6; >12 dB below median of all available role candidates, model A; engine model UNVERIFIED'
    if role=='PRIME_DRUM' and pc in (8,16):
        if any(k in e['keys_present'] for k in (33,34,35)):raise ValueError('audit missing-key contradiction')
        e['coverage_note']='no zones for keys 33–35; CUSTOM, not an XG Room/Rock identity'
    return e

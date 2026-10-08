"""Exact F04 candidate profile; recover historical bytes only after physical pins.

Recovery is for historical guards, never a claim candidate playback is unchanged.
The differential runner executes both physical sequencer sources independently.
"""
import hashlib
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT/'tests/fixtures/f04_source_profile.json'
SOURCE = 'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'

def sha(data): return hashlib.sha256(data).hexdigest()
def profile(): return json.loads(MANIFEST.read_text())
def historical_bytes(path, data=None):
    path = str(path)
    if path.startswith(str(ROOT)+'/'): path = path[len(str(ROOT))+1:]
    if data is None: data = (ROOT/path).read_bytes()
    rule = profile()['changes'].get(path)
    if rule is None: return data
    if sha(data) == rule['baseline_sha256']: return data
    assert sha(data) == rule['candidate_sha256'], 'Unreviewed F04 file: '+path
    for edit in reversed(rule['edits']):
        before, after = bytes.fromhex(edit['before_hex']), bytes.fromhex(edit['after_hex'])
        offset = edit['candidate_offset']
        assert data[offset:offset+len(after)] == after, 'F04 edit drift: '+path
        data = data[:offset]+before+data[offset+len(after):]
    assert sha(data) == rule['baseline_sha256'], 'F04 recovery drift: '+path
    return data

def historical_sha(path): return sha(historical_bytes(path))
def verify_profile():
    p=profile()
    assert p['baseline_commit']=='0b8536a018eb2661bd6e06e8864a638b31d57226'
    production=[f for f in p['changes'] if f.startswith('app/src/main/')]
    assert production==[SOURCE], 'F04 must change exactly one production source'
    for path,expected in p['baseline_files'].items():
        assert sha(historical_bytes(path))==expected, 'Non-F04 baseline drift: '+path
    assert sha((ROOT/SOURCE).read_bytes())==p['changes'][SOURCE]['candidate_sha256'], 'Expected candidate profile'
    return p

def pipeline_expected():
    rows={r.split('\t')[0]:r for r in (ROOT/'app/src/test/resources/sff1_pipeline_digests.tsv').read_text().splitlines()}
    updates=(ROOT/'app/src/test/resources/f04/pipeline_candidate_digests.tsv').read_text().splitlines()
    assert len(updates)==4 and {r.split('\t')[0] for r in updates}=={
        'GOLDEN_'+name+'_'+chord for name in ['Movie&Show/BaroqueAir1.S145.sst','Pop&Rock/Unplugged2.T151.prs'] for chord in ['C_MAJOR','F_MAJOR']}
    rows.update({r.split('\t')[0]:r for r in updates})
    return ('\n'.join(rows[k] for k in sorted(rows))+'\n').encode()

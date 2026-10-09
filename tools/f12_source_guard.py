"""Exact reversible diagnostic overlay; never normalizes an unpinned mutation."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'tests/fixtures/f12_diagnostic_source_profile.json'
def sha(data):return hashlib.sha256(data).hexdigest()
def baseline_bytes(path,data):
    path=str(path)
    if path.startswith(str(ROOT)+'/'):path=path[len(str(ROOT))+1:]
    profile=json.loads(MANIFEST.read_text());rules=profile['changes'];rule=rules.get(path)
    if rule is None:return data
    if sha(data)==rule['before_sha256']:return data
    old=profile.get('historical_inputs',{}).get(path)
    if old and sha(data)==old['sha256']: return data  # still checked by the original historical guard
    assert sha(data)==rule['after_sha256'],'Unreviewed F12 file: '+path
    for e in reversed(rule['edits']):
        offset=e['offset'];before=bytes.fromhex(e['before']);after=bytes.fromhex(e['after'])
        assert data[offset:offset+len(after)]==after,'F12 edit mismatch: '+path
        data=data[:offset]+before+data[offset+len(after):]
    assert sha(data)==rule['before_sha256'],'F12 baseline recovery mismatch: '+path
    return data

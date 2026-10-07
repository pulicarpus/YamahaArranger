"""Independent S3 source pin; stripping checked insertions recovers exact S2."""
import hashlib
import json
import os
import re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FIXTURE=ROOT/'tests/fixtures/sff_casm_source_identity_s3.json'
PATTERN=rb'/\* SFF_CASM_METADATA_BEGIN \*/.*?/\* SFF_CASM_METADATA_END \*/'
def source_rules():
    return json.loads(FIXTURE.read_text())['files'] if FIXTURE.exists() else {}
def validate_source(path,data):
    rule=source_rules()[path]
    actual=hashlib.sha256(data).hexdigest()
    assert actual in (rule['old_sha256'],rule['new_sha256']), 'S3 source identity drift: '+path
    blocks=re.findall(PATTERN,data,re.S)
    if actual==rule['new_sha256']:
        assert [hashlib.sha256(b).hexdigest() for b in blocks]==rule['blocks_sha256'],path
        old=re.sub(PATTERN,b'',data,flags=re.S)
    else:
        assert not blocks,path
        old=data
    assert hashlib.sha256(old).hexdigest()==rule['old_sha256'],path
    return old

def normalize_s3(path,data):
    blocks=re.findall(PATTERN,data,re.S)
    if not blocks:return data  # Already stripped/baseline input; physical profiles checked separately.
    assert path in source_rules(), 'Unapproved S3 metadata: '+path
    return validate_source(path,data)

def verify_metadata_sources():
    rules=source_rules()
    profiles=set()
    for path,rule in rules.items():
        data=(ROOT/path).read_bytes()
        validate_source(path,data)
        profiles.add('S3' if hashlib.sha256(data).hexdigest()==rule['new_sha256'] else 'S2')
    expected=os.environ.get('SFF_CASM_SOURCE_PROFILE','S3')
    assert expected in ('S3','S2_BASELINE'),'Unknown S3 source profile'
    assert profiles==({'S2'} if expected=='S2_BASELINE' else {'S3'}),'Unexpected S3 source profile: '+str(profiles)
    return rules if profiles=={'S3'} else {}
if __name__=='__main__':
    assert len(verify_metadata_sources())==6
    print('S3_SOURCE_IDENTITY PASS: six exact physical hashes; every old S2 byte recovered')

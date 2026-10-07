"""Exact S2 metadata/source pin; removal must recover original source bytes.

This never normalizes musical code. Both the complete new file and each
enumerated metadata insertion are hash-pinned. Historical musical guards see
the exact old bytes after those checked insertions are removed.
"""
import hashlib
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tests/fixtures/sff_dialect_source_identity_s2.json"
PATTERN = r'/\* SFF_DIALECT_METADATA_BEGIN \*/.*?/\* SFF_DIALECT_METADATA_END \*/'


def source_rules():
    return json.loads(FIXTURE.read_text())["files"] if FIXTURE.exists() else {}


def validate_source(path, data):
    rule=source_rules()[path]
    actual=hashlib.sha256(data).hexdigest()
    assert actual in (rule["old_sha256"],rule["new_sha256"]), "S2 source identity drift: " + path
    text=data.decode()
    blocks=re.findall(PATTERN,text,re.S)
    if actual==rule["new_sha256"]:
        assert [hashlib.sha256(b.encode()).hexdigest() for b in blocks]==rule["blocks_sha256"],path
        text=re.sub(PATTERN,'',text,flags=re.S)
    else:
        assert not blocks,path
    assert hashlib.sha256(text.encode()).hexdigest()==rule["old_sha256"],path
    return actual==rule["new_sha256"]


def strip_metadata(path, text):
    rules = source_rules()
    blocks = re.findall(PATTERN, text, re.S)
    if path not in rules:
        assert not blocks, "Unapproved dialect metadata: " + path
        return text
    rule = rules[path]
    validate_source(path,(ROOT/path).read_bytes())
    if blocks:
        assert [hashlib.sha256(b.encode()).hexdigest() for b in blocks] == rule["blocks_sha256"], "S2 metadata block drift: " + path
        return re.sub(PATTERN, '', text, flags=re.S)
    return text  # Already stripped by an earlier historical guard layer.


def verify_metadata_sources():
    profiles=set()
    for path, rule in source_rules().items():
        actual=hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
        profiles.add("S2" if actual==rule["new_sha256"] else "S1")
        old = strip_metadata(path, (ROOT / path).read_text())
        assert hashlib.sha256(old.encode()).hexdigest() == rule["old_sha256"], "S2 changed non-metadata bytes: " + path
    assert len(profiles)<=1, "Mixed baseline/S2 metadata source profile"
    expected = os.environ.get("SFF_DIALECT_SOURCE_PROFILE", "S2")
    assert expected in ("S2", "S1_BASELINE"), "Unrecognized source profile"
    assert profiles == ({"S1"} if expected == "S1_BASELINE" else {"S2"}), "Unexpected source profile: " + str(profiles)
    return source_rules() if profiles=={"S2"} else {}


if __name__ == "__main__":
    rules = verify_metadata_sources()
    assert len(rules) == 6, "Exactly six authorized S2 production files required"
    print("S2_SOURCE_IDENTITY PASS: six exact new hashes; checked metadata removal recovers every old byte")

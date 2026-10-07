"""Enumerated read-only hooks; stripping must recover exact #791 musical source."""
import hashlib,json,re
from pathlib import Path
from sff_dialect_source_guard import strip_metadata
ROOT=Path(__file__).resolve().parents[1]
PATTERN=r'\n// HEADROOM_OBSERVER_BEGIN\n.*?// HEADROOM_OBSERVER_END\n'
def strip_headroom(path,text):
    text=strip_metadata(path,text)
    blocks=re.findall(PATTERN,text,re.S)
    if blocks:
        fixture=json.loads((ROOT/'tests/fixtures/pcm_headroom_blocks.json').read_text())
        assert [hashlib.sha256(b.encode()).hexdigest() for b in blocks]==fixture[path]['blocks'],path
        text=re.sub(PATTERN,'',text,flags=re.S)
        assert hashlib.sha256(text.encode()).hexdigest()==fixture[path]['baseline791'],path
    return text

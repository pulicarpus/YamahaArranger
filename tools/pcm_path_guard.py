"""Allow only enumerated, reviewed observer blocks; retain every playback line."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PATTERN=r'\n// PCM_PATH_OBSERVER_BEGIN\n.*?// PCM_PATH_OBSERVER_END\n'
def strip_observers(path,text):
    blocks=re.findall(PATTERN,text,re.S)
    if blocks:
        expected=json.loads((ROOT/'tests/fixtures/pcm_path_observer_blocks.json').read_text())[path]
        assert [hashlib.sha256(b.encode()).hexdigest() for b in blocks]==expected,path
        text=re.sub(PATTERN,'',text,flags=re.S)
        if path.endswith('audio_engine.cpp'):
            text=text.replace('std::string AudioEngine::sfNoteZoneReport() const {\n    return soundFont_.noteZoneReport();\n}',
                              'std::string AudioEngine::sfNoteZoneReport() const { return soundFont_.noteZoneReport(); }')
    return text

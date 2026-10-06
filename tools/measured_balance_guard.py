"""Only enumerated response trim integration; retain all original MIDI/state logic."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PATTERN=r'\n// MEASURED_BALANCE_BEGIN\n.*?// MEASURED_BALANCE_END\n'
def strip_balance(path,text):
    blocks=re.findall(PATTERN,text,re.S)
    if blocks:
        expected=json.loads((ROOT/'tests/fixtures/measured_balance_blocks.json').read_text())[path]
        assert [hashlib.sha256(b.encode()).hexdigest() for b in blocks]==expected,path
        text=re.sub(PATTERN,'',text,flags=re.S)
        text=text.replace('        if(!meter.dsp){meter.error=BASS_ErrorGetCode();            BASS_StreamFree(meter.stream);meter.stream=0;return;}',
                          '        if(!meter.dsp){meter.error=BASS_ErrorGetCode();BASS_StreamFree(meter.stream);meter.stream=0;return;}')
        text=text.replace('        if(!meter.dsp){meter.error=BASS_ErrorGetCode();\n            BASS_StreamFree(meter.stream);meter.stream=0;return;}',
                          '        if(!meter.dsp){meter.error=BASS_ErrorGetCode();BASS_StreamFree(meter.stream);meter.stream=0;return;}')
    return text

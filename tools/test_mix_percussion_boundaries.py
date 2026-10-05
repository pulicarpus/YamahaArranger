#!/usr/bin/env python3
"""Freeze the successful #784 routing/scheduler. Only mix math + isolated percussion dispatch changed."""
from pathlib import Path
import hashlib,json,re
root=Path(__file__).resolve().parents[1]
def method(text,name):
    start=text.index('{',text.index(name));depth=1;end=start+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
for record in json.loads((root/'tests/fixtures/mix_percussion784_protected_methods.json').read_text()):
    actual=method((root/record['path']).read_text(),record['method'])
    for addition in record.get('strip',[]):
        assert actual.count(addition)==1,record['method']
        actual=actual.replace(addition,'')
    assert hashlib.sha256(actual.encode()).hexdigest()==record['sha256'],record['method']
# Original read-only diagnostic code remains separately guarded. New audio paths
# never call the old Stage 3 adapter or production clear/arranger reset.
cpp=(root/'app/src/main/cpp/bassmidi_player.cpp').read_text()
for name in ['bool BassMidiPlayer::percussionOn','bool BassMidiPlayer::percussionOff','void BassMidiPlayer::renderPercussion']:
    body=method(cpp,name)
    for forbidden in ['catalog(', 'sha256(', 'ifstream', 'preparePercussionLane(', 'loadRole(', 'applyFonts(', 'FontInit(', 'FontLoad']:
        assert forbidden not in body,(name,forbidden)
for name in ['void BassMidiPlayer::retirePercussionStreams','void BassMidiPlayer::preparePercussionLane']:
    body=method(cpp,name)
    assert 'send(' not in body and 'BASS_StreamFree(stream_)' not in body,name
seq=(root/'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt').read_text()
assert 'maxOf(state.volume, 100)' not in seq and 'maxOf(state.expression, 100)' not in seq
assert 'audioEngine.setChannelVolume(' not in seq
assert 'setStyleBusTrim(v)' in (root/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text()
# Compare generated audition observations to the immutable registry; UNKNOWN rows stay excluded.
data=(root/'app/src/main/resources/drum_shadow_audit770_v1.tsv').read_text()
seed=(root/'app/src/main/cpp/percussion_audition_evidence.h').read_text()
for row in data.splitlines():
    v=row.split('|')
    if v[0]=='CANDIDATE' and v[9]=='COMPATIBLE':assert f'{{"{v[3]}",{v[4]},{v[5]},{v[6]},' in seed
assert seed.count('    {')==4
assert 'YAMAHA_COMPATIBLE_PERCUSSION "' in (root/'app/src/main/cpp/CMakeLists.txt').read_text()
print('MIX_PERCUSSION boundaries PASS: #784 fonts/normalization/melodic routing/CASM/scheduler intact; no heavy NOTE_ON/OFF/render work; old Stage 3 absent')

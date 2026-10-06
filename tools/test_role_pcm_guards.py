from pathlib import Path
import hashlib,json
from pcm_path_guard import strip_observers
from measured_balance_guard import strip_balance
root=Path(__file__).resolve().parents[1]
cpp=strip_observers('app/src/main/cpp/bassmidi_player.cpp',strip_balance('app/src/main/cpp/bassmidi_player.cpp',(root/'app/src/main/cpp/bassmidi_player.cpp').read_text()))
def body(name):
 s=cpp.index('{',cpp.index(name));e=s+1;d=1
 while d:d+=(cpp[e]=='{')-(cpp[e]=='}');e+=1
 return cpp[s:e]
for row in json.loads((root/'tests/fixtures/role_pcm786_protected_methods.json').read_text()):
 actual=body(row['method'])
 for call in row['strip']:
  assert actual.count(call)==1,row['method'];actual=actual.replace(call,'')
 assert hashlib.sha256(actual.encode()).hexdigest()==row['sha256'],row['method']
for file,h in json.loads((root/'tests/fixtures/role_pcm786_protected_sources.json').read_text()).items():
 assert hashlib.sha256(strip_observers(file,strip_balance(file,(root/file).read_text())).encode()).hexdigest()==h,file
for method in ['void CALLBACK BassMidiPlayer::rolePcmTap(','void BassMidiPlayer::observeRolePcmNote(','void BassMidiPlayer::observeRolePcmController(']:
 t=body(method)
 for forbidden in ['LOG','ifstream','catalog(','match(','parse(','BASS_MIDI_StreamEvent','BASS_ChannelGetData','BASS_MIDI_StreamGetChannel','updateRolePcmMeter(']:assert forbidden not in t,(method,forbidden)
assert 'channel<10 || channel>15' in body('void BassMidiPlayer::updateRolePcmMeter(')
assert 'BASS_DSP_READONLY' in body('void BassMidiPlayer::updateRolePcmMeter(')
assert 'static_cast<const float*>' in body('void CALLBACK BassMidiPlayer::rolePcmTap(')
for name in ['std::string BassMidiPlayer::rolePcmReport(','void BassMidiPlayer::updateRolePcmMeter(']:
 t=body(name)
 for bad in ['BASS_MIDI_StreamEvent','BASS_MIDI_StreamSetFonts','BASS_MIDI_FontSetVolume','BASS_ChannelSetAttribute']:assert bad not in t,(name,bad)
print('ROLE_PCM_GUARDS PASS: #786 routing/controller/CASM/ACMP/scheduler/drum intact; measured response DSP only; taps only melodic10..15; no gain, MIDI, font-map writes')

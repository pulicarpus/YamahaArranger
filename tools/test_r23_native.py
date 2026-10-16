#!/usr/bin/env python3
import argparse,io,struct,zipfile,subprocess,json,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 # Retain every old identity assertion and compare exactly against mandatory R2-2.
 source=(ROOT/'tools/test_r22_native.py').read_text().replace("BASE='dc746b7cabfafe365e5e15366f8172f35c7a89af'","BASE='d456d733c788b46ae06fe09c8b687eeb4915d554'")
 source=source.replace('dc746b7 vs shadow getter OFF vs ON','d456d733 R2-2 vs R2-3 LEGACY getter OFF vs ON')
 code=compile(source,str(ROOT/'tools/test_r22_native.py'),'exec');namespace={'__file__':str(ROOT/'tools/test_r22_native.py'),'__name__':'r23_legacy'};exec(code,namespace);namespace['main']()
 fixtures=out/'golden-fixtures';fixtures.mkdir(exist_ok=True)
 with zipfile.ZipFile(ROOT/'tests/fixtures/sf2_pdta/export.zip') as z:
  for n in z.namelist():
   if n.endswith('.pdta.bin'):
    payload=z.read(n);chunk=b'LIST'+struct.pack('<I',len(payload))+payload
    (fixtures/(Path(n).name.replace('.pdta.bin','.sf2'))).write_bytes(b'RIFF'+struct.pack('<I',4+len(chunk))+b'sfbk'+chunk)
 mocks=out/'mocks';bass=mocks/'bass.h';text=bass.read_text()
 text=text.replace('namespace mock_bass { inline std::vector<HSTREAM> freedStreams;', 'namespace mock_bass { inline std::vector<HSTREAM> r23DecodedStreams; inline std::vector<HSTREAM> freedStreams;')
 text=text.replace('inline DWORD BASS_ChannelGetData(HSTREAM stream, void* data, DWORD bytes) {','inline DWORD BASS_ChannelGetData(HSTREAM stream, void* data, DWORD bytes) { mock_bass::r23DecodedStreams.push_back(stream);')
 bass.write_text(text)
 exe=out/'r23-native';subprocess.run(['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1','-I'+str(mocks),'-Iapp/src/main/cpp','tests/r23_native_ab_test.cpp','app/src/main/cpp/bassmidi_player.cpp','-o',str(exe)],cwd=ROOT,check=True)
 result=subprocess.check_output([str(exe),str(fixtures)],cwd=ROOT,text=True);(out/'ab.log').write_text(result);print(result)
if __name__=='__main__':main()

#!/usr/bin/env python3
"""Exact R2-0 baseline vs R2-2; native cached getter ON/OFF + nonzero PCM/event bytes."""
import argparse,subprocess,tempfile,tarfile,io,shutil,json,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE='dc746b7cabfafe365e5e15366f8172f35c7a89af'
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='r22-native-') as directory:
  base=Path(directory)/'baseline';base.mkdir()
  with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git','archive',BASE,'app/src/main/cpp','tests'],cwd=ROOT))) as archive:archive.extractall(base,filter='data')
  mocks=out/'mocks';shutil.copytree(ROOT/'tests/mocks',mocks,dirs_exist_ok=True);f=mocks/'bass.h';s=f.read_text().replace('    if(stream==1) return 0;','    (void)stream;').replace('    std::fill_n(static_cast<float*>(data),bytes/sizeof(float),0.0f); return bytes;', '    for(unsigned i=0;i<bytes/sizeof(float);++i)static_cast<float*>(data)[i]=float(int(i%41)-20)/13.f; return bytes;');f.write_text(s)
  driver=(ROOT/'tests/native_resolver_test.cpp').read_text();needle='    std::cout << "PASS: " << checks'
  extra=r'''
#ifdef R22_GETTER
    const auto eventsBeforeGetter=mock_bass::events;const auto historyBeforeGetter=mock_bass::history;const auto tapsBeforeGetter=mock_bass::roleChannelRequests;
    const auto r22Before=player.drumChannelSelection(8);
    check(r22Before==player.drumChannelSelection(8),"snapshot deterministic");
    check(player.drumChannelSelection(-1)=="UNOBSERVED","invalid snapshot channel");
    check(mock_bass::events==eventsBeforeGetter && mock_bass::history==historyBeforeGetter && mock_bass::roleChannelRequests==tapsBeforeGetter,"readonly getter adds no BASS event/tap");
#endif
    player.setChannelPreset(8,128,0);player.setChannelPreset(9,128,8);
    for(int ch=0;ch<16;++ch)player.noteOn(ch,60,0.7f);
    float block[256];std::ofstream pcm(dir+"/r22-pcm.bin",std::ios::binary);
    for(int n=0;n<4;++n){
#ifdef R22_GETTER
       player.drumChannelSelection(8);player.drumChannelSelection(9);
#endif
       player.render(block,128);pcm.write(reinterpret_cast<const char*>(block),sizeof(block));
    }
    pcm.close();
    for(const auto& e:mock_bass::history)std::cout<<"TRACE "<<std::get<0>(e)<<" "<<std::get<1>(e)<<" "<<std::get<2>(e)<<" "<<std::get<3>(e)<<"\n";
    for(const auto& m:mock_bass::mappings)std::cout<<"MAP "<<m.font<<" "<<m.spreset<<" "<<m.sbank<<" "<<m.dpreset<<" "<<m.dbank<<" "<<m.dbanklsb<<" "<<m.minchan<<" "<<m.numchan<<"\n";
'''
  assert needle in driver;path=out/'driver.cpp';path.write_text(driver.replace(needle,extra+needle));fixture=out/'fixture';fixture.mkdir(exist_ok=True);traces=[];pcms=[]
  for label,source,getter in [('baseline',base,False),('off',ROOT,False),('on',ROOT,True)]:
   exe=out/label;cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1','-I'+str(mocks),'-I'+str(source/'app/src/main/cpp'),'-I'+str(source/'tests'),str(path),str(source/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(exe)]
   if getter:cmd.insert(1,'-DR22_GETTER=1')
   subprocess.run(cmd,cwd=ROOT,check=True);text=subprocess.check_output([str(exe),str(fixture)],text=True);(out/(label+'.log')).write_text(text);traces.append([l for l in text.splitlines() if l.startswith(('TRACE ','MAP '))]);pcms.append((fixture/'r22-pcm.bin').read_bytes())
  assert len(pcms[0])==4096 and any(pcms[0]) and pcms[0]==pcms[1]==pcms[2]
  assert traces[0] and traces[0]==traces[1]==traces[2]
  result=dict(gate='G1_HOST_PASS',pcmBytes=4096,pcmSha256=hashlib.sha256(pcms[0]).hexdigest(),traceRows=len(traces[0]),comparison='dc746b7 vs shadow getter OFF vs ON',scope='same_mock_decoder_not_device');(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()

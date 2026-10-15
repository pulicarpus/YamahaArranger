#!/usr/bin/env python3
"""R2-0 native baseline/candidate comparison and host overhead estimate. No device claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tarfile
import io
import tempfile
ROOT=Path(__file__).resolve().parents[1];BASE='163f769bf056f0dd0593f78ab20fbbe700dcb56f'
def run(args,**kwargs):return subprocess.check_output(list(map(str,args)),cwd=ROOT,text=True,**kwargs)
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
 exe=out/'session-test';subprocess.run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-Iapp/src/main/cpp','tests/pcm_session_test.cpp','-o',str(exe)],cwd=ROOT,check=True);print(run([exe]),flush=True)
 with tempfile.TemporaryDirectory(prefix='r20-baseline-') as directory:
  base=Path(directory);archive=subprocess.check_output(['git','archive',BASE,'app/src/main/cpp','tests'],cwd=ROOT)
  with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(base,filter='data')
  mocks=out/'mocks';shutil.copytree(ROOT/'tests/mocks',mocks,dirs_exist_ok=True)
  f=mocks/'bass.h';s=f.read_text().replace('    if(stream==1) return 0;','    (void)stream;')
  s=s.replace('    std::fill_n(static_cast<float*>(data),bytes/sizeof(float),0.0f); return bytes;', '    for(unsigned i=0;i<bytes/sizeof(float);++i)static_cast<float*>(data)[i]=float(int(i%41)-20)/13.f; return bytes;')
  f.write_text(s)
  driver=(ROOT/'tests/native_resolver_test.cpp').read_text();needle='    std::cout << "PASS: " << checks'
  extra=r'''
    const auto r20Events=mock_bass::events;const auto r20History=mock_bass::history;const auto r20Taps=mock_bass::roleChannelRequests;
#ifdef R20_CANDIDATE
    check(player.markPcmSession({})==1,"first mark");
    const auto unplayed=player.pcmSessionReport({});
    check(unplayed.find("frames=0")!=std::string::npos,"zero frames at mark");
    check(mock_bass::events==r20Events && mock_bass::history==r20History && mock_bass::roleChannelRequests==r20Taps,"mark/report no events/taps");
#endif
    for(int ch=0;ch<16;++ch)player.noteOn(ch,60,0.7f);
    uint64_t checksum=1469598103934665603ull;float block[256];
    std::ofstream r20Pcm(dir+"/r20-pcm.bin",std::ios::binary);
    for(int n=0;n<4;++n){player.render(block,128);r20Pcm.write(reinterpret_cast<const char*>(block),sizeof(block));for(unsigned i=0;i<sizeof(block);++i){checksum^=reinterpret_cast<unsigned char*>(block)[i];checksum*=1099511628211ull;}}
    r20Pcm.close();
    const auto r20After=mock_bass::history;
#ifdef R20_CANDIDATE
    const auto session=player.pcmSessionReport({});
    check(session.find("frames=512")!=std::string::npos,"session counts successful frames");
    check(session.find("noteOnAttemptsDelta=1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1")!=std::string::npos,"all sixteen attempts");
    check(session.find("keyboardNoteOnDelta=4")!=std::string::npos,"keyboard sent delta");
    check(player.markPcmSession({})==2,"second mark starts a new session");
    const auto second=player.pcmSessionReport({});
    check(second.find("frames=0")!=std::string::npos && second.find("peakSinceMark=0 clippedSinceMark=0")!=std::string::npos,"second mark clears PCM metrics");
    check(mock_bass::history==r20After,"export and second mark add no events");
#endif
    std::cout<<"PCM_BYTES_FNV "<<checksum<<"\n";
    for(const auto& e:mock_bass::history)std::cout<<"TRACE "<<std::get<0>(e)<<" "<<std::get<1>(e)<<" "<<std::get<2>(e)<<" "<<std::get<3>(e)<<"\n";
    for(const auto& m:mock_bass::mappings)std::cout<<"MAP "<<m.font<<" "<<m.spreset<<" "<<m.sbank<<" "<<m.dpreset<<" "<<m.dbank<<" "<<m.dbanklsb<<" "<<m.minchan<<" "<<m.numchan<<"\n";
    if(std::getenv("R20_BENCH")){
        const auto start=std::chrono::steady_clock::now();
        for(unsigned i=0;i<150000;++i)player.render(block,128);
        const auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
        std::cout<<"BENCH_NS "<<ns<<"\n";
    }
'''
  assert needle in driver;driver=driver.replace(needle,extra+needle);path=out/'native-driver.cpp';path.write_text(driver)
  fixture=out/'fixture';fixture.mkdir(exist_ok=True);results=[];executables=[];pcm=[]
  for label,source in [('before',base),('after',ROOT)]:
   exe=out/('native-'+label);cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM=1','-I'+str(mocks),'-I'+str(source/'app/src/main/cpp'),'-I'+str(source/'tests'),str(path),str(source/'app/src/main/cpp/bassmidi_player.cpp'),'-o',str(exe)]
   if label=='after':cmd.insert(1,'-DR20_CANDIDATE=1')
   subprocess.run(cmd,cwd=ROOT,check=True);text=run([exe,fixture]);(out/(label+'.log')).write_text(text);raw=(fixture/'r20-pcm.bin').read_bytes();pcm.append(raw);(out/('pcm-'+label+'.bin')).write_bytes(raw)
   results.append([l for l in text.splitlines() if l.startswith(('PCM_BYTES_FNV','TRACE ','MAP '))]);executables.append(exe)
  assert len(pcm[0])==4096 and any(pcm[0]) and pcm[0]==pcm[1],'decoded PCM bytes changed'
  assert results[0] and results[0]==results[1],'PCM/MIDI/mapping changed'
  print('R20 PCM/MIDI/MAPPING DIFFERENTIAL PASS rows='+str(len(results[0]))+'; nonzero synthetic decode bytes identical; mark/export no event/tap additions',flush=True)
  timings=[[],[]]
  for trial in range(7):
   for i in ([0,1] if trial%2==0 else [1,0]):
    text=run([executables[i],fixture],env={**os.environ,'R20_BENCH':'1'});ns=int(next(l.split()[1] for l in text.splitlines() if l.startswith('BENCH_NS')));timings[i].append(ns)
  overhead=(statistics.median(timings[1])/statistics.median(timings[0])-1)*100
  report={'baseline_ns':timings[0],'candidate_ns':timings[1],'median_overhead_percent':overhead,'scope':'HOST_MOCK_DECODE_NOT_ANDROID_CALLBACK','device_G5':'UNVERIFIED','gate_lt_1_percent':overhead<1}
  (out/'overhead.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
  assert overhead<1,'host render overhead gate failed; do not loosen threshold'
if __name__=='__main__':main()

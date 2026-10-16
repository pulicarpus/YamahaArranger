#include "bassmidi_player.h"
#include <chrono>
#include <ctime>
#include <iostream>
#include <stdexcept>
static void must(bool value,const char* text){if(!value)throw std::runtime_error(text);}
static uint64_t cpu(){timespec t{};clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;}
// Post-EOF summary defaults when the persistent harness replaces the batch timer.
static constexpr long long wall=0,cn=0;
int main(int argc,char**argv){
 must(argc==3,"fixture dir and mode");const std::string d=argv[1],mode=argv[2];BassMidiPlayer p;
 must(p.loadMelody(d+"/prime_melodi.sf2") && p.loadDrum(d+"/prime_drum.sf2"),"pair");
 must(p.loadMelodyFallback(d+"/colombo.sf2") && p.loadMelodyFallback(d+"/tyros4.sf2"),"pool");
 for(int ch=10;ch<16;++ch)p.setChannelPreset(ch,0,0,"Piano");
 p.setChannelPreset(8,128,0);p.setChannelPreset(9,128,8);
#ifdef R23
 if(mode=="resolved"){
  must(p.setDrumABMode(true),"resolved mode");
  const std::string fp="ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";
  must(p.bindDrumAB(8,fp,127,0,"MainA") && p.bindDrumAB(9,fp,127,8,"MainA"),"resolved real stream binding");
 }
#else
 must(mode=="legacy","baseline legacy only");
#endif
 p.markPcmSession({});float block[256];

    auto cpuNs=[](){timespec t{};clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;};
    for(int warm=0;warm<10000;++warm)p.render(block,128);
    std::cout<<"READY"<<std::endl;
    unsigned count;
    while(std::cin>>count){
      const auto start=std::chrono::steady_clock::now();const auto cpuStart=cpuNs();
      for(unsigned i=0;i<count;++i)p.render(block,128);
      const auto used=cpuNs()-cpuStart;
      const auto wall=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
      std::cout<<"EPOCH "<<wall<<" "<<used<<std::endl;
    }
}

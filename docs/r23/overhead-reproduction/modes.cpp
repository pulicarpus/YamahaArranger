#include "bassmidi_player.h"
#include <chrono>
#include <ctime>
#include <iostream>
#include <stdexcept>
static void must(bool value,const char* text){if(!value)throw std::runtime_error(text);}
static uint64_t cpu(){timespec t{};clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t);return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;}
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
 p.markPcmSession({});float block[256];for(int i=0;i<10000;++i)p.render(block,128);
 const auto begin=std::chrono::steady_clock::now();const auto c=cpu();
 for(int i=0;i<150000;++i)p.render(block,128);
 const auto cn=cpu()-c;const auto wall=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count();
 // NOTE dispatch cost is measured separately; JNI calls the synth directly,
 // independently of AudioEngine's render callback. Keep the callback input fixed.
 const auto noteStart=std::chrono::steady_clock::now();
 for(int i=0;i<2000;++i){p.noteOn(9,53,1);p.noteOff(9,53);}
 const auto notes=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-noteStart).count();
 const auto snapshotStart=std::chrono::steady_clock::now();
 for(int i=0;i<1000;++i)p.drumChannelSelection(9);
 const auto snapshots=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-snapshotStart).count();
 std::cout<<"MODE "<<mode<<" WALL "<<wall<<" CPU "<<cn<<" NOTE_PAIRS_NS "<<notes<<" OBSERVATIONS_NS "<<snapshots<<"\n";
}

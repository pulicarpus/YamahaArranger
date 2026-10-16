#include "bassmidi_player.h"
#include <iostream>
#include <fstream>
#include <stdexcept>
static void check(bool v,const char* m){if(!v)throw std::runtime_error(m);}
int main(int argc,char**argv){
 check(argc==3,"fixtures and PCM output");std::string d=argv[1],col="ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";BassMidiPlayer p;
 check(p.loadMelody(d+"/prime_melodi.sf2")&&p.loadDrum(d+"/prime_drum.sf2")&&p.loadMelodyFallback(d+"/colombo.sf2"),"fonts");

#ifdef R24_NATIVE
 check(!p.setDrumPunch(1),"LEGACY rejects punch");
#endif
 check(p.setDrumABMode(true),"resolved");
 check(p.bindDrumAB(9,col,127,0,"MainA"),"bind");
#ifdef R24_NATIVE
 for(int mode:{1,2}){
  check(p.setDrumPunch(mode),"mode admission");
  for(int pc:{0,8,16}){
   check(p.bindDrumAB(9,col,127,pc,"Main"),"actual kit");p.refreshDrumPunch(9);
   for(int key:{35,36,38,40,42,46,49}){
    auto n=mock_bass::history.size();p.noteOn(9,key,0.75f);p.noteOff(9,key);
    unsigned ons=0,offs=0;HSTREAM on=0,off=0;
    for(size_t i=n;i<mock_bass::history.size();++i){const auto& e=mock_bass::history[i];if(std::get<2>(e)==MIDI_EVENT_NOTE){check((std::get<3>(e)&255)==unsigned(key),"same key");if(std::get<3>(e)>>8){++ons;on=std::get<0>(e);check((std::get<3>(e)>>8)==95,"velocity unchanged");}else{++offs;off=std::get<0>(e);}}}
    check(ons==1&&offs==1&&on==off,"exactly one ON/OFF to same stream");
    check((on!=1)==(drum_punch::group(key)>=0),"target-only routing");
    if(on!=1){BASS_MIDI_FONT live{};check(BASS_MIDI_StreamGetPreset(on,9,&live)&&live.bank==127&&live.preset==pc,"stem actual preset after NOTE_ON");}
   }
   float pcm[2048];p.render(pcm,1024); // chunk boundary safety
  }
  // Active ownership remains associated with original stream across a preset transition.
  p.noteOn(9,36,.75f);auto first=std::get<0>(mock_bass::history.back());
  check(p.bindDrumAB(9,col,128,0,"GS"),"unreviewed punch identity remains valid R23 GS");p.refreshDrumPunch(9);p.noteOn(9,36,.75f);
  p.noteOff(9,36);check(std::get<0>(mock_bass::history.back())==first,"old OFF owner");p.noteOff(9,36);check(std::get<0>(mock_bass::history.back())==1,"fallback OFF owner");
  check(p.drumPunchReport().find("parent_unreviewed_or_prepare_failed")!=std::string::npos,"fallback explicit");
 }
 check(p.setDrumPunch(0),"return original without restart");auto n=mock_bass::history.size();check(p.setDrumPunch(0),"idempotent original");check(n==mock_bass::history.size(),"original no events");
 check(p.bindDrumAB(9,col,127,0,"MainA"),"restore");
#endif
 // Differential against exact R2-3 RESOLVED, ORIGINAL only.
 mock_bass::history.clear();p.markPcmSession({});
 for(int key:{35,36,38,40,42,46,49}){p.noteOn(9,key,.75f);p.noteOff(9,key);}
 float pcm[1024];p.render(pcm,512);
 std::ofstream(argv[2],std::ios::binary).write(reinterpret_cast<char*>(pcm),sizeof(pcm));
 uint64_t h=1469598103934665603ull;for(auto b:std::string(reinterpret_cast<char*>(pcm),sizeof(pcm))){h^=uint8_t(b);h*=1099511628211ull;}
 std::cout<<"PCM "<<h<<'\n';for(auto e:mock_bass::history)std::cout<<"EVENT "<<std::get<0>(e)<<' '<<std::get<1>(e)<<' '<<std::get<2>(e)<<' '<<std::get<3>(e)<<'\n';
}

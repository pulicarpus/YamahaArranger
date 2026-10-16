#include "bassmidi_player.h"
#include <iostream>
#include <stdexcept>
static void check(bool v,const char* m){if(!v)throw std::runtime_error(m);}
int main(int argc,char**argv){
 check(argc==2,"fixture directory");const std::string d=argv[1];BassMidiPlayer p;
 check(p.loadMelody(d+"/prime_melodi.sf2") && p.loadDrum(d+"/prime_drum.sf2"),"load pair");
 check(p.loadMelodyFallback(d+"/colombo.sf2") && p.loadMelodyFallback(d+"/tyros4.sf2"),"load exact golden pool");
 const std::string col="ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";
 const std::string prime="338385762217a9ecd9ecb8707fa71202a115da4eaad81441c27d714acbf61283";
 check(p.setDrumABMode(true),"enter resolved at STOP");
 for(int pc:{0,8,16})for(int ch:{8,9}){
   const auto ons=mock_bass::noteOns;
   check(p.bindDrumAB(ch,col,127,pc,"Main"),"Colombo exact binding");
   check(mock_bass::noteOns==ons,"binding must not audition notes");
   BASS_MIDI_FONT live{};check(BASS_MIDI_StreamGetPreset(1,ch,&live),"independent binding readback");
   check(live.bank==127 && live.preset==pc && mock_bass::fonts[live.font].find("colombo")!=std::string::npos,"actual selected font/PC");
   p.noteOn(ch,53,1);check(mock_bass::noteOns==ons+1,"one real ON through bound stream");p.noteOff(ch,53);
   BASS_MIDI_FONT after{};check(BASS_MIDI_StreamGetPreset(1,ch,&after) && after.font==live.font && after.bank==127 && after.preset==pc,"actual binding survives real note dispatch");
   const auto& event=mock_bass::history[mock_bass::history.size()-2];
   check(std::get<0>(event)==1 && std::get<1>(event)==unsigned(ch) && std::get<2>(event)==MIDI_EVENT_NOTE,"real NOTE_ON on bound main stream");
   mock_bass::r23DecodedStreams.clear();float pcm[32];p.render(pcm,16);
   check(!mock_bass::r23DecodedStreams.empty() && mock_bass::r23DecodedStreams.front()==1,"bound instance is audio decode instance");
 }
 const std::string tyros="16bb60deafdf426cdc7e269b6c5f434cd5b7db6e14a83f86d17f54720176217a";
 for(const auto& fp:{col,tyros}) {
   check(p.bindDrumAB(9,fp,128,0,"GS"),"reserved GS bank128 exact binding");
   BASS_MIDI_FONT gs{};check(BASS_MIDI_StreamGetPreset(1,9,&gs) && gs.bank==128 && gs.preset==0,"GS raw bank retained");
   const std::string name=fp==col?"colombo":"tyros4";
   check(mock_bass::fonts[gs.font].find(name)!=std::string::npos,"GS fingerprint font bound");
 }
 check(!p.bindDrumAB(9,prime,128,8,"unknown"),"automatic Prime must be rejected");
 auto ons=mock_bass::noteOns;p.noteOn(9,37,1);check(mock_bass::noteOns==ons,"invalid identity cannot use stale/Prime map");
 check(!p.bindDrumAB(8,col,128,999,"invalid"),"invalid PC rejected");
 mock_bass::mismatchPreset=true;
 check(!p.bindDrumAB(8,col,127,0,"mismatch"),"wrong BASS readback rejected");
 mock_bass::mismatchPreset=false;ons=mock_bass::noteOns;p.noteOn(8,36,1);check(mock_bass::noteOns==ons,"failed binding cannot revive on note");
 check(p.setDrumABMode(false),"return legacy");
 p.setChannelPreset(9,128,8);BASS_MIDI_FONT legacy{};check(BASS_MIDI_StreamGetPreset(1,9,&legacy),"legacy binding restored");
 check(mock_bass::fonts[legacy.font].find("prime_drum")!=std::string::npos && legacy.preset==8,"legacy Prime restored");
 check(p.setDrumABMode(true),"second session");ons=mock_bass::noteOns;p.noteOn(9,37,1);check(mock_bass::noteOns==ons,"no cross-session stale binding");
 check(p.bindDrumAB(9,col,127,8,"MainA"),"second session bind");
 const auto history=mock_bass::history;const auto taps=mock_bass::roleChannelRequests;
 std::cout<<p.drumABReport();check(mock_bass::history==history && mock_bass::roleChannelRequests==taps,"report read only, no GetChannel tap");
 std::cout<<"R23 NATIVE PASS\n";
}

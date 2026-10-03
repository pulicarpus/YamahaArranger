#include "bassmidi_player.h"
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <fstream>
#include <iostream>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool yes,const char* why) {++checks;if(!yes){std::cerr<<"FAIL "<<why<<'\n';std::exit(1);}}
static std::string fixture(const std::string& dir,const char* filename,int bank,int pc) {
    std::vector<unsigned char> b(108);auto w=[&](int n,int v){b[n]=v&255;b[n+1]=v>>8;};
    auto t=[&](int n,const char* s){std::memcpy(b.data()+n,s,4);};
    t(0,"RIFF");w(4,100);t(8,"sfbk");t(12,"LIST");w(16,88);t(20,"pdta");t(24,"phdr");w(28,76);
    t(32,"Kit ");w(52,pc);w(54,bank);t(70,"EOP ");
    const auto path=dir+"/"+filename;std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<char*>(b.data()),b.size());return path;
}
static uint64_t generation(BassMidiPlayer& p) {const auto s=p.shadowDrumSnapshot();return std::stoull(s.substr(s.find("GEN value=")+10));}
int main(int argc,char** argv) {
    check(argc==2,"fixture directory");const std::string dir=argv[1],sha(64,'a');
    const auto kit=fixture(dir,"legacy.sf2",128,0),candidate=fixture(dir,"candidate.sf2",126,35);
    BassMidiPlayer player;check(player.loadDrum(kit),"legacy kit loads");
    player.setChannelPreset(8,128,73);player.setChannelPreset(9,128,73);
    player.setChannelMixer(9,81,71,43,12,5);const auto legacyMapping=mock_bass::mappings;
    const auto legacyControllers=mock_bass::events;const int before=mock_bass::noteOns;
    const auto gen=generation(player);
    check(!player.experimentalDrumOn(1,9,110,73),"OFF defaults to legacy");
    const auto start=mock_bass::history.size();
    const int r9=player.prepareExperimentalDrum(candidate,sha,126,35,91,9,gen);
    const int r8=player.prepareExperimentalDrum(candidate,sha,126,35,91,8,gen);
    check(r9==1 && r8==2,"prepared independent Rhythm1/Rhythm2 resources");
    for(size_t i=start;i<mock_bass::history.size();++i)check(std::get<2>(mock_bass::history[i])!=MIDI_EVENT_NOTE,"preflight never sends NOTE_ON/OFF");
    check(mock_bass::events==legacyControllers && mock_bass::noteOns==before,"preflight preserves arranger controls and notes");
    check(mock_bass::mappings.size()==legacyMapping.size(),"active arranger FONTEX2 mappings preserved");
    check(mock_bass::preloadFlags==0 && mock_bass::preloadProgram==35,"dedicated key preload synchronous, outside ON");
    check(player.enableExperimentalDrum(true),"explicit activation after preflight");
    auto a=player.experimentalDrumOn(r9,9,110,73),b=player.experimentalDrumOn(r9,9,69,73),c=player.experimentalDrumOn(r8,8,120,73);
    check(a && b && c && a!=b,"simultaneous repeated owners have distinct tokens");
    check(mock_bass::noteOns==before,"mapped ON never enters production stream");
    const auto onA=mock_bass::history[mock_bass::history.size()-3],onB=mock_bass::history[mock_bass::history.size()-2];
    check(std::get<0>(onA)==std::get<0>(onB) && std::get<1>(onA)!=std::get<1>(onB),"many-to-one voices use independent physical lanes");
    check((std::get<3>(onA)&127)==91 && (std::get<3>(onA)>>8)==110,"cross-key note, original velocity byte");
    check((std::get<3>(onB)>>8)==69,"velocity behavior not replaced with audition velocity");
    check(player.experimentalDrumOff(b),"out-of-order OFF supported by captured lane");
    const auto offB=mock_bass::history.back();check(std::get<0>(offB)==std::get<0>(onB) && std::get<1>(offB)==std::get<1>(onB) && std::get<3>(offB)==91,"OFF same stream/lane/key as its ON");
    check(!player.experimentalDrumOff(b),"duplicate OFF cannot release another note");
    check(player.enableExperimentalDrum(false) && player.experimentalDrumOff(a),"flag changes do not lose held owner binding");
    player.setChannelPreset(9,128,0);check(player.experimentalDrumOff(c),"preset change leaves captured private owner valid");
    check(player.enableExperimentalDrum(true),"reenable");
    check(!player.experimentalDrumOn(r9,9,110,0),"native-present kit protected");
    player.setChannelPreset(9,128,73);
    check(!player.experimentalDrumOn(r8,9,110,73),"rhythm lane mismatch abstains");
    mock_bass::failPrivateNote=true;
    check(!player.experimentalDrumOn(r9,9,110,73),"failed private ON returns no owner");
    player.noteOn(9,31,110/127.f);check(mock_bass::noteOns==before+1,"failed adapter ON falls back once to unchanged legacy path");
    mock_bass::failPrivateNote=false;
    std::vector<uint64_t> owners;for(int i=0;i<32;++i)owners.push_back(player.experimentalDrumOn(r9,9,1+i,73));
    check(owners.back()!=0 && !player.experimentalDrumOn(r9,9,127,73),"capacity rejected before extra ON, legacy caller can proceed");
    for(auto t:owners)check(player.experimentalDrumOff(t),"all individual repeated owners release");
    const auto held=player.experimentalDrumOn(r9,9,110,73);
    player.setChannelExpression(9,0);
    const HSTREAM lane=std::get<0>(onA);
    check(BASS_MIDI_StreamGetEvent(lane,0,MIDI_EVENT_EXPRESSION)==0,"CC11 mirrors source without resending CC7");
    player.setMasterGain(0.5f);
    player.allNotesOff();check(!player.experimentalDrumOff(held),"STOP flushes only existing experimental owners, duplicate ignored");
    player.clearExperimentalDrum();check(player.experimentalDrumReport().find("resources=0")!=std::string::npos,"clear frees prepared streams/fonts");
    check(!player.prepareExperimentalDrum(candidate,sha,126,35,91,9,gen+1),"stale generation rejected at preparation");
    mock_bass::mismatchPreset=true;check(!player.prepareExperimentalDrum(candidate,sha,126,35,91,9,gen),"actual native preset mismatch blocks resource");mock_bass::mismatchPreset=false;
    mock_bass::failPreload=true;
    check(!player.prepareExperimentalDrum(candidate,sha,126,35,91,9,gen),"unready key preload abstains");
    mock_bass::failPreload=false;
    check(!player.prepareExperimentalDrum(candidate,sha,126,36,91,9,gen),"missing requested preset cannot silently play another kit");
    check(!player.prepareExperimentalDrum(candidate,sha,126,35,91,10,gen),"melody/ACMP routes rejected");
    // Private engine contract independently tests generation pinning/render errors.
    ExperimentalDrumLanes lanes;const int id=lanes.prepare(candidate,sha,126,35,91,9,gen,1,48000,0.9f);
    check(id==1,"standalone native lane contract prepares");lanes.enabled=true;
    auto pinned=lanes.on(id,9,110,gen);check(pinned && !lanes.on(id,9,110,gen+1),"generation closes new admission, held owner pinned");
    check(lanes.off(pinned),"old-generation owner OFF uses captured resource");
    std::array<float,5000*2> buffer{};lanes.renderAdd(buffer.data(),5000);check(!lanes.decodeFailures,"render bounded blocks without allocation");
    mock_bass::failDecode=true;lanes.renderAdd(buffer.data(),64);check(!lanes.enabled && lanes.decodeFailures,"decode failure disables new routes");mock_bass::failDecode=false;
    lanes.clear();check(!lanes.hasOwners() && !lanes.count,"resources cleaned after failure");
    std::cout<<"PRODUCTION_DRUM_ADAPTER "<<checks<<" checks PASS; actual BassMidiPlayer with mock BASS; device PCM/latency remains device verification\n";
}

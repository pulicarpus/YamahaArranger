#include "bassmidi_player.h"
#include "sf2_rich_fixture.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <cstdlib>

JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool ok,const char* message) { ++checks; if(!ok) { std::cerr<<"FAIL: "<<message<<'\n';std::exit(1); } }
static std::string fixture(const std::string& dir) {
    using namespace rich_fixture;
    // Real bank normalization/cache parsing, all role families in the primary.
    const char* names[]={"ConcertGrand","Acoustic Bass","Steel Guitar","Strings","Warm Pad","Flute"};
    const int pcs[]={0,33,24,49,89,73};
    Bytes ph(38*7),pdta={'p','d','t','a'};
    for(int i=0;i<6;++i) { std::memcpy(ph.data()+38*i,names[i],std::strlen(names[i]));word(ph,38*i+20,pcs[i]);word(ph,38*i+22,8); }
    std::memcpy(ph.data()+38*6,"EOP",3);chunk(pdta,"phdr",ph);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    const auto path=dir+"/Yamaha Presence.sf2";std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
int main(int argc,char**argv) {
    check(argc==2,"fixture directory provided");BassMidiPlayer player;check(player.loadMelody(fixture(argv[1])),"production font loader succeeds");
    const char* voices[]={"Bass","Piano","A.Guitar","Strings1","Warm Pad","Flute"};
    const int pc[]={33,0,24,49,89,73};
    for(int ch=10;ch<16;++ch) { player.setChannelPreset(ch,8*128,pc[ch-10],voices[ch-10]);player.setChannelMixer(ch,80,64,100,40,0); }
    auto on=[&](int ch) { const auto before=mock_bass::noteOns;player.noteOn(ch,60,0.5f,AudioPathOrigin{ch,60,1024,100,1,false});return mock_bass::noteOns==before+1; };
    for(int ch=10;ch<16;++ch) {
        check(on(ch),"all six admitted accompaniment roles reach actual production BASS dispatch");
        BASS_MIDI_FONT live{};check(BASS_MIDI_StreamGetPreset(1,ch,&live) && live.preset==pc[ch-10],"native selected preset readback matches each production melodic route");
    }
    const auto table=mock_bass::mappings;const auto controllers=mock_bass::events;
    mock_bass::failMapping=true;player.setChannelPreset(12,8*128,24,"A.Guitar");mock_bass::failMapping=false;
    check(mock_bass::mappings.size()==table.size(),"failed installation retains previous native table");
    check(!on(12),"failing destination remains fail-closed");
    for(int ch:{10,11,13,14,15}) check(on(ch),"one map failure cannot invalidate another established accompaniment route");
    for(int ch:{10,11,13,14,15}) check(mock_bass::events[{ch,MIDI_EVENT_VOLUME}]==controllers.at({ch,MIDI_EVENT_VOLUME}) && mock_bass::events[{ch,MIDI_EVENT_EXPRESSION}]==controllers.at({ch,MIDI_EVENT_EXPRESSION}),"other role controller state unchanged");
    player.noteOff(12,60);check(mock_bass::events[{12,MIDI_EVENT_NOTE}]==60,"OFF available even for a rejected new ON");
    player.setChannelPreset(12,8*128,24,"A.Guitar");check(on(12),"destination recovers after its own successful map retry");
    const auto history=mock_bass::history;const auto report=player.noteZoneReport();check(mock_bass::history==history,"presence export emits no MIDI events");
    check(report.find("NATIVE ch=10 midiChannel=11 attempts=2 BASS_NOTE_ON_SENT=2")!=std::string::npos,"presence reports accepted native count rather than scheduler guesses");
    check(report.find("familyOrMapRejected=1")!=std::string::npos && report.find("presetMapFailures=1")!=std::string::npos,"native gate losses have counters");
    mock_bass::failMapping=true;mock_bass::dropMappingsOnFailure=true;player.setChannelPreset(12,1024,24,"A.Guitar");
    mock_bass::failMapping=false;mock_bass::dropMappingsOnFailure=false;
    check(!on(10) && !on(13),"unverified native table retains global fail-closed guard");
    std::cout<<"ACCOMPANIMENT_NATIVE checks="<<checks<<" production_player=true no_PCM_claim=true\n";
}

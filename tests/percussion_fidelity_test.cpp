#include "bassmidi_player.h"
#include "percussion_fidelity_fixture.h"
#include "sf2_fingerprint.h"
#include <jni.h>
#include <iostream>
#include <cstdlib>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool b,const char* s){++checks;if(!b){std::cerr<<"FAIL "<<s<<'\n';std::exit(1);}}
static AudioPathOrigin origin(int ch,int key){return {ch,key,127*128,0,1,false};}
static size_t eventCount(HSTREAM stream,int lane,DWORD event,int value=-1){size_t n=0;for(const auto& e:mock_bass::history)if(std::get<0>(e)==stream && int(std::get<1>(e))==lane && std::get<2>(e)==event && (value<0 || int(std::get<3>(e))==value))++n;return n;}
int main(int argc,char** argv){
    using namespace percussion_fidelity;check(argc==2,"dir");const std::string dir=argv[1];
    check(family("Hi-Hat Edge 10 PD")==Family::Unknown,"edge remains UNKNOWN");
    check(family("Pedal HiHat")==Family::HatPedalClosed && family("Hi-Hat Pedal Splash")==Family::Unknown,"pedal != splash");
    check(family("808 claves")==Family::Unknown && family("Metro Bell")==Family::Unknown,"wrong family/electronic is not promoted");
    check(sourceIdentity(127,0,73,16)->group==64 && sourceIdentity(127,0,73,17)->group==96,"Yamaha directional groups explicit");
    check(family(sourceIdentity(127,0,73,31)->identity)==Family::SnareHit,"Yamaha snare source identity, not GM key31");
    check(family(sourceIdentity(127,0,73,33)->identity)==Family::Kick,"Yamaha key33 is kick, not metronome");
    check(family(sourceIdentity(127,0,73,80)->identity)==Family::TriangleMute,"triangle muted articulation");
    check(chokes(64,96) && !chokes(96,64) && !chokes(96,96),"Yamaha asymmetric choke");
    check(sf2_fingerprint::sha256({})=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","SHA256 empty");
    check(sf2_fingerprint::sha256({'a','b','c'})=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","SHA256 abc");
    std::vector<unsigned char> million(1000000,'a');check(sf2_fingerprint::sha256(million)=="cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0","SHA256 multiblock");
    const auto fixturePath=percussion_fixture::write(dir,"policy-hazards.sf2",128,5);
    std::ifstream fixtureFile(fixturePath,std::ios::binary);
    std::vector<unsigned char> fixtureBytes((std::istreambuf_iterator<char>(fixtureFile)),std::istreambuf_iterator<char>());
    const auto coherent=sf2_zones::parse(fixtureBytes);
    check(choose(catalog(coherent,1,"fixture"),Family::Claves,75)!=nullptr,"coherent cross-key family accepted");
    for(int hazard=0;hazard<7;++hazard) {
        auto invalid=coherent;
        for(auto& z:invalid.presets[{128,5}])if(z.keyLow==58) {
            if(hazard==0)z.instrumentGenerators.amount[58]=60;
            if(hazard==1)z.pitchCorrection=1;
            if(hazard==2)z.velLow=64;
            if(hazard==3)z.sampleType=2;
            if(hazard==4)z.instrumentGenerators.mods.push_back({});
            if(hazard==5){z.instrumentGenerators.present|=uint64_t(1)<<54;z.instrumentGenerators.amount[54]=1;}
            if(hazard==6){z.instrumentGenerators.present|=uint64_t(1)<<52;z.instrumentGenerators.amount[52]=3;}
        }
        check(choose(catalog(invalid,1,"fixture"),Family::Claves,75)==nullptr,"unproved root/velocity/stereo/modulator/loop/tuning must ABSTAIN");
    }
    mock_bass::deferAuxReadbackUntilNote=true;
    BassMidiPlayer player;
    check(player.loadMelody(percussion_fixture::write(dir,"melody.sf2",0,24,true)),"melody native loader");
    check(player.loadDrum(percussion_fixture::write(dir,"legacy.sf2",128,0,true)),"opaque legacy native loader");
    check(player.loadMelodyFallback(percussion_fixture::write(dir,"compatible.sf2",128,5)),"independent compatible SF2");
    const auto before=mock_bass::history.size();const auto fonts=mock_bass::fonts;
    player.setChannelPreset(8,128,73,"PopDrumKit");player.setChannelPreset(9,128,73,"PopDrumKit");
    for(size_t i=before;i<mock_bass::history.size();++i)check(std::get<2>(mock_bass::history[i])!=MIDI_EVENT_NOTE,"catalog/prepare emits NO NOTE_ON/OFF");
    const HSTREAM a=2,b=3;check(mock_bass::otherMappings.count(a) && mock_bass::otherMappings.count(b),"two isolated rhythm streams");
    player.setChannelPreset(12,0,24,"Guitar");player.setChannelMixer(12,80,54,90,26,15);
    const auto productionTable=mock_bass::mappings;const auto production12=mock_bass::events;
    player.setChannelMixer(8,74,25,91,22,15);player.setChannelExpression(8,63);
    check(BASS_MIDI_StreamGetEvent(a,82,MIDI_EVENT_VOLUME)==74 && BASS_MIDI_StreamGetEvent(a,82,MIDI_EVENT_EXPRESSION)==63,"exact current CC7/11 mirrored");
    check(BASS_MIDI_StreamGetEvent(a,82,MIDI_EVENT_PAN)==25,"pan preserved");
    auto on=[&](int ch,int key,int v=42){player.noteOn(ch,key,v/127.f,origin(ch,key));};
    auto off=[&](int ch,int key){player.noteOff(ch,key,origin(ch,key));};
    on(8,82,42);on(8,82,110);off(8,82);off(8,82);
    check(eventCount(a,82,MIDI_EVENT_NOTE,82|(42<<8))==1 && eventCount(a,82,MIDI_EVENT_NOTE,82|(110<<8))==1,"velocity layers retain original velocity");
    check(eventCount(a,82,MIDI_EVENT_NOTE,82)==2,"repeated-note oldest ownership");
    on(8,31,110);on(8,40,110);off(8,40);
    check(eventCount(a,31,MIDI_EVENT_NOTE,40|(110<<8))==1 && eventCount(a,40,MIDI_EVENT_NOTE,40)==1 && eventCount(a,31,MIDI_EVENT_NOTE,40)==0,"many-to-one has distinct owner lanes");
    off(8,31);check(eventCount(a,31,MIDI_EVENT_NOTE,40)==1,"cross-key OFF uses ON key and lane");
    on(8,75);off(8,75);check(eventCount(a,75,MIDI_EVENT_NOTE,58|(42<<8))==1 && eventCount(a,75,MIDI_EVENT_NOTE,58)==1,"claves cross-key58, not bell75");
    on(9,31);off(9,31);check(eventCount(b,31,MIDI_EVENT_NOTE,40)==1,"Rhythm2 independence");
    on(8,81);on(8,80);const auto triangleOffs=eventCount(a,81,MIDI_EVENT_NOTE,81);off(8,81);off(8,80);
    check(eventCount(a,81,MIDI_EVENT_SOUNDOFF)>0 && eventCount(a,81,MIDI_EVENT_NOTE,81)==triangleOffs,"triangle choke and tombstone OFF");
    on(8,16);on(8,21);check(eventCount(1,8,MIDI_EVENT_NOTE,16|(255<<8))==1,"mapped pedal chokes legacy Yamaha64 group");
    off(8,16);off(8,21);check(eventCount(a,21,MIDI_EVENT_NOTE,44)==1,"pedal OFF source44");
    on(8,21);on(8,17);check(eventCount(a,21,MIDI_EVENT_SOUNDOFF)==0,"source96 does not choke64");
    off(8,17);off(8,21);
    check(eventCount(1,8,MIDI_EVENT_NOTE,16|(42<<8))==1,"UNKNOWN edge stays legacy");
    check(mock_bass::fonts==fonts,"no active arranger font replaced by routing");
    check(mock_bass::mappings.size()==productionTable.size(),"production FONTEX2 unchanged");
    for(int cc:{MIDI_EVENT_VOLUME,MIDI_EVENT_PAN,MIDI_EVENT_EXPRESSION,MIDI_EVENT_REVERB,MIDI_EVENT_CHORUS})
        check(mock_bass::events[{12,DWORD(cc)}]==production12.at({12,DWORD(cc)}),"melodic controller lane untouched");
    player.noteOn(12,60,0.5f);check(eventCount(1,12,MIDI_EVENT_NOTE,60|(64<<8))==1,"melodic accompaniment still reaches original stream");
    auto snapshot=mock_bass::history;auto report=player.noteZoneReport();check(mock_bass::history==snapshot,"existing presence export remains read-only");
    check(report.find("candidateKey=58")!=std::string::npos && report.find("semantic=COMPATIBLE_not_EXACT")!=std::string::npos,"measured routes explicitly reported");
    // All named transitions have stable same-kit routing, no production NOTESOFF.
    for(const char* transition:{"Main-Fill","Fill-Main","Intro-Main","Ending","Loop","Rapid"}) {
        (void)transition;on(8,82);on(9,31);player.setChannelPreset(8,128,73,"PopDrumKit");player.setChannelPreset(9,128,73,"PopDrumKit");off(8,82);off(9,31);
        check(mock_bass::nextStream==4,"same kit transition neither resets nor reallocates auxiliary lanes");
    }
    const auto notesOffBefore=eventCount(1,12,MIDI_EVENT_NOTESOFF);on(8,82);player.setChannelPreset(8,128,72,"SchlagerKit");
    const auto oldOnCount=eventCount(a,82,MIDI_EVENT_NOTE,82|(42<<8));on(8,82);off(8,82);off(8,82);
    check(eventCount(a,82,MIDI_EVENT_NOTE,82|(42<<8))==oldOnCount,"pending program transition new ON is legacy");
    check(eventCount(a,82,MIDI_EVENT_NOTE,82)>0 && eventCount(1,8,MIDI_EVENT_NOTE,82)>0,"mixed old/new program owners stay distinct");
    check(eventCount(1,12,MIDI_EVENT_NOTESOFF)==notesOffBefore,"program changes do not clear ACMP/melodic stream");
    player.setChannelPreset(8,128,73,"PopDrumKit");const auto current=mock_bass::nextStream-1;
    on(8,82);check(player.loadDrum(percussion_fixture::write(dir,"replacement.sf2",128,0,true)),"font generation reload");
    const auto offHistory=mock_bass::history.size();off(8,82);check(mock_bass::history.size()==offHistory,"retired owner OFF never goes to replacement generation");
    check(std::find(mock_bass::freedStreams.begin(),mock_bass::freedStreams.end(),current)!=mock_bass::freedStreams.end(),"retired aux freed");
    player.setChannelPreset(8,128,73,"PopDrumKit");mock_bass::failNote=true;on(8,82);mock_bass::failNote=false;
    const auto failedOff=mock_bass::history.size();off(8,82);check(mock_bass::history.size()==failedOff,"failed substituted ON OFF consumed safely");
    for(int i=0;i<40;++i) {if(i%2)player.noteOn(8,82,42/127.f);else on(8,82);}
    for(int i=0;i<40;++i)off(8,82);
    check(player.noteZoneReport().find("overflowDropped=8")!=std::string::npos,"bounded owner exhaustion fail-closed");
    player.allNotesOff();check(eventCount(1,12,MIDI_EVENT_NOTESOFF)==notesOffBefore+1,"explicit stop retains legacy all-off once");
    // The actual production rhythm scheduler intentionally never emits scheduled OFFs.
    // Long shaker playback must not run out of owner slots or force a reset.
    const auto longStream=mock_bass::nextStream-1;const auto longBefore=eventCount(longStream,82,MIDI_EVENT_NOTE,82|(42<<8));
    for(int i=0;i<2000;++i)on(8,82);
    check(eventCount(longStream,82,MIDI_EVENT_NOTE,82|(42<<8))==longBefore+2000,"one-shot rhythm remains audible beyond32 hits without OFF");
    std::vector<float> pcm(1024);for(int i=0;i<600;++i)player.render(pcm.data(),512);
    const auto oldStreams=mock_bass::nextStream;player.setChannelPreset(8,128,72,"SchlagerKit");
    check(mock_bass::nextStream>oldStreams,"finite one-shot owners drain from rendered frames, not a missing scheduled OFF");
    player.allNotesOff();
    player.setChannelPreset(8,128,73,"PopDrumKit");
    for(int i=0;i<2000;++i)on(8,21);
    check(player.noteZoneReport().find("overflowDropped=8")!=std::string::npos,"choked one-shots also keep bounded bookkeeping");player.allNotesOff();
    // Auxiliary preparation/controller/resource failures cannot mutate melodic routes.
    auto mapsBefore=mock_bass::mappings;auto melodyBefore=mock_bass::events;
    player.setChannelPreset(8,128,72,"SchlagerKit");mock_bass::failAuxMapping=true;
    player.setChannelPreset(8,128,73,"PopDrumKit");mock_bass::failAuxMapping=false;
    const auto legacyBefore=mock_bass::noteOns;on(8,82);off(8,82);
    check(mock_bass::noteOns==legacyBefore+1,"aux FONTEX2 failure uses legacy ON");
    check(mock_bass::mappings.size()==mapsBefore.size(),"aux failed preparation keeps production maps");
    player.setChannelPreset(8,128,72,"SchlagerKit");player.setChannelPreset(8,128,73,"PopDrumKit");
    const auto loaded=mock_bass::nextStream-1;on(8,82);mock_bass::failAuxExpression=true;
    player.setChannelExpression(8,40);mock_bass::failAuxExpression=false;
    const auto nativeBefore=mock_bass::noteOns;on(8,82);off(8,82);off(8,82);
    check(mock_bass::noteOns==nativeBefore+1 && eventCount(loaded,82,MIDI_EVENT_NOTE,82)>0,"controller failure falls back but retains existing owner OFF");
    for(int cc:{MIDI_EVENT_VOLUME,MIDI_EVENT_EXPRESSION,MIDI_EVENT_PAN})check(mock_bass::events[{12,DWORD(cc)}]==melodyBefore.at({12,DWORD(cc)}),"failure never resets ACMP controllers");
    player.allNotesOff();
    player.setChannelPreset(8,128,72,"SchlagerKit");player.setChannelPreset(8,128,73,"PopDrumKit");
    const auto unverified=mock_bass::nextStream-1;const auto mismatchBefore=mock_bass::noteOns;
    mock_bass::changeDiagnosticPresetOnNote=true;on(8,75);mock_bass::changeDiagnosticPresetOnNote=false;
    check(eventCount(unverified,75,MIDI_EVENT_SOUNDOFF)==1 && mock_bass::noteOns==mismatchBefore+1,"actual first-note mismatch stopped before render and retains legacy ON");
    off(8,75);check(eventCount(1,8,MIDI_EVENT_NOTE,75)>0,"mismatched auxiliary route OFF follows legacy owner");
    player.allNotesOff();player.setChannelPreset(8,128,72,"SchlagerKit");player.setChannelPreset(8,128,73,"PopDrumKit");
    const auto isolated=mock_bass::nextStream-1;on(8,75);const auto offBefore=mock_bass::history.size();
    player.noteOff(8,75);check(mock_bass::history.size()==offBefore,"manual orphan OFF cannot release style-owned cross-key note");
    off(8,75);check(eventCount(isolated,75,MIDI_EVENT_NOTE,58)==1,"matching style OFF releases original donor owner");
    player.allNotesOff();mock_bass::failPreload=true;
    check(player.loadDrum(percussion_fixture::write(dir,"not-ready.sf2",128,0,true)),"legacy loader unaffected by optional preload failure");
    mock_bass::failPreload=false;player.setChannelPreset(8,128,73,"PopDrumKit");
    const auto refused=mock_bass::noteOns;on(8,31);off(8,31);check(mock_bass::noteOns==refused+1,"unready candidate never steals a legacy event");
    player.setChannelPreset(8,128,0,"native exact kit");on(8,31);off(8,31);check(mock_bass::noteOns==refused+2,"requested native preset is retained intact");
    std::cout<<"PERCUSSION_NATIVE checks="<<checks<<" production_adapter=true mock_API_not_original_PCM\n";
}

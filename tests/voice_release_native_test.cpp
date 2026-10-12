#include "bassmidi_player.h"
#include "voice_release_fixture.h"
#include <jni.h>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <set>
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
class VoiceReleaseTestAccess { public:
    static HSTREAM stream(const BassMidiPlayer& p) { return p.stream_; }
    static bool matches(const BassMidiPlayer& p,uint64_t id,int bank,int pc,const BASS_MIDI_FONT& actual) {
        const auto* expected=p.keyboardPreset({id,bank,pc,false});
        return expected && expected->font==actual.font && expected->nativeBank==actual.bank && expected->program==actual.preset;
    }
};
static int checks=0;
static void check(bool ok,const char* message) { ++checks;if(!ok){std::cerr<<"FAIL: "<<message<<'\n';std::exit(1);} }
struct Preset {std::string role,file,name;uint64_t id;int bank,pc;};
static std::vector<Preset> parse(const std::string& text) {
    std::vector<Preset> result;std::istringstream records(text);std::string record;
    while(std::getline(records,record,'\x1e')) {
        std::istringstream fields(record);std::vector<std::string> f;std::string field;
        while(std::getline(fields,field,'\x1f')) f.push_back(field);
        check(f.size()==6,"identity-bearing catalog framing");
        result.push_back({f[0],f[2],f[5],std::stoull(f[1]),std::stoi(f[3]),std::stoi(f[4])});
    }
    return result;
}
static std::vector<float> render(BassMidiPlayer& p,int frames) {
    std::vector<float> pcm(frames*2);for(int i=0;i<frames;i+=256)p.render(pcm.data()+i*2,std::min(256,frames-i));return pcm;
}
static double rms(const std::vector<float>& pcm) {double e=0;for(auto v:pcm)e+=v*v;return std::sqrt(e/pcm.size());}
static double tone(const std::vector<float>& pcm,int frequency) {
    double re=0,im=0;for(size_t i=0;i<pcm.size()/2;++i){double t=2*3.141592653589793*frequency*i/48000;re+=pcm[2*i]*std::cos(t);im+=pcm[2*i]*std::sin(t);}return std::sqrt(re*re+im*im);
}
int main(int argc,char** argv) {
    check(argc==2,"fixture directory");const std::string dir=argv[1];BassMidiPlayer player;
    const auto primary=voice_release_fixture::write(dir,"voice-primary.sf2",375);
    const auto secondary=voice_release_fixture::write(dir,"voice-secondary.sf2",750);
    const auto third=voice_release_fixture::write(dir,"voice-third.sf2",1125);
    const auto drum=voice_release_fixture::write(dir,"voice-drum.sf2",1500);
    check(player.loadMelody(primary),"load primary");check(player.loadDrum(drum),"load drum");
    const auto initial=parse(player.presetList()).front();
    check(player.setKeyboardPreset(0,initial.id,initial.bank,initial.pc),"select before attaching another SF2");
    check(player.loadMelodyFallback(secondary),"load secondary");check(player.loadMelodyFallback(third),"load third");
    BASS_MIDI_FONT retained{};
    check(BASS_MIDI_StreamGetPreset(VoiceReleaseTestAccess::stream(player),0,&retained) &&
          VoiceReleaseTestAccess::matches(player,initial.id,initial.bank,initial.pc,retained),"adding SF2s preserves exact source selection");
    const auto catalog=parse(player.presetList());std::set<uint64_t> ids;std::set<std::pair<int,int>> addresses;
    for(const auto& p:catalog){ids.insert(p.id);addresses.emplace(p.bank,p.pc);}
    check(ids.size()==4,"all four SF2 identities visible");
    // Dedicated DRUM is unnormalized: BASSMIDI only exposes its legal banks.
    for(auto id:ids){int count=0;std::string file;for(const auto& p:catalog)if(p.id==id){++count;file=p.file;}
        std::cout<<"VOICE_COUNT sf2="<<file<<" id="<<id<<" presets="<<count<<'\n';}
    check(catalog.size()>=18,"normalized high/variation banks and drum kits are visible");
    check(addresses.count({1025,24})&&addresses.count({65535,127}),"original non-GM bank identities preserved");
    std::cout<<"VOICE_DUPLICATES addressGroups="<<addresses.size()<<" extraEntries="<<catalog.size()-addresses.size()<<'\n';
    auto stream=VoiceReleaseTestAccess::stream(player);
    for(int ch=10;ch<16;++ch)player.setChannelPreset(ch,0,24,"Acoustic Guitar");
    player.setChannelPreset(8,128,0,"Drums");player.setChannelPreset(9,128,0,"Drums");
    BASS_MIDI_FONT style{},rhythm{};check(BASS_MIDI_StreamGetPreset(stream,10,&style),"style readback");check(BASS_MIDI_StreamGetPreset(stream,9,&rhythm),"rhythm readback");
    auto find=[&](const std::string& file,int bank,int pc) {
        for(const auto& p:catalog)if(p.file==file&&p.bank==bank&&p.pc==pc)return p;
        std::cerr<<"missing controlled fixture preset\n";std::exit(1);
    };
    std::array<Preset,4> independent={find("voice-primary.sf2",0,24),find("voice-secondary.sf2",0,24),
        find("voice-third.sf2",1025,24),find("voice-drum.sf2",0,24)};
    for(int ch=0;ch<4;++ch) {
        const auto& p=independent[ch];check(player.setKeyboardPreset(ch,p.id,p.bank,p.pc),"assign simultaneous independent parts");
        player.setChannelMixer(ch,127,64,127,0,0);
    }
    check(player.setKeyboardReleaseTime(64),"simultaneous test normal release");
    for(int ch=0;ch<4;++ch) player.noteOn(ch,ch==3?40:60,95/127.f);
    for(int ch=0;ch<4;++ch) {
        BASS_MIDI_FONT actual{};const auto& p=independent[ch];
        check(BASS_MIDI_StreamGetPreset(stream,ch,&actual)&&VoiceReleaseTestAccess::matches(player,p.id,p.bank,p.pc,actual),"all four sources verified after simultaneous NOTE_ON");
    }
#ifdef VOICE_RELEASE_REAL_BASS
    render(player,4096);const auto simultaneous=render(player,4096);
    for(int frequency:{375,750,1125,472})check(tone(simultaneous,frequency)>tone(simultaneous,2500)*10,"all four SF2 signatures are present in the simultaneous PCM mix");
#endif
    const auto sameSourceVariation=find("voice-primary.sf2",1025,24);
#ifndef VOICE_RELEASE_REAL_BASS
    mock_bass::history.clear();
#endif
    check(player.setKeyboardPreset(1,sameSourceVariation.id,sameSourceVariation.bank,sameSourceVariation.pc),"change RIGHT2 to a different preset from RIGHT1's SF2");
#ifndef VOICE_RELEASE_REAL_BASS
    for(const auto& event:mock_bass::history)if(std::get<0>(event)==stream)
        check(std::get<1>(event)==1,"RIGHT2 selection sends no MIDI controller or sound-off to RIGHT1/RIGHT3/LEFT/style");
#endif
    independent[1]=sameSourceVariation;
    for(int ch=0;ch<4;++ch) {
        BASS_MIDI_FONT actual{};const auto& p=independent[ch];
        check(BASS_MIDI_StreamGetPreset(stream,ch,&actual)&&VoiceReleaseTestAccess::matches(player,p.id,p.bank,p.pc,actual),"changing RIGHT2 preserves every other part's source");
    }
#ifdef VOICE_RELEASE_REAL_BASS
    const auto unchangedHeld=render(player,4096);
    for(int frequency:{375,1125,472})check(tone(unchangedHeld,frequency)>tone(simultaneous,frequency)*0.75,"RIGHT1/RIGHT3/LEFT held PCM survives RIGHT2 voice change");
#endif
    std::cout<<"VOICE_SIMULTANEOUS fourIndependentSources=true sameSf2DifferentBanks=true nonTargetMidiUnchanged=true PASS\n";
    for(int ch=0;ch<4;++ch)BASS_MIDI_StreamEvent(stream,ch,MIDI_EVENT_SOUNDOFF,0);
    const auto beforeProbe=player.keyboardVoiceSnapshot();
#ifndef VOICE_RELEASE_REAL_BASS
    mock_bass::history.clear();
#endif
    const auto probe=player.diagnosticKeyboardReleaseWav(0,60,100);
    check(probe.size()==44+48000*33,"offline probe produces stereo WAV for three 2.75-second segments");
    check(player.keyboardVoiceSnapshot()==beforeProbe,"offline RELEASE probe preserves all production presets/controllers/note evidence");
#ifndef VOICE_RELEASE_REAL_BASS
    for(const auto& event:mock_bass::history)check(std::get<0>(event)!=stream,"offline probe sends no production-stream MIDI");
#else
    check(player.keyboardReleaseProbeReport().find("classification=RESPONDS")!=std::string::npos,"same diagnostic shipped in APK measures real release PCM response");
#endif
    std::cout<<player.keyboardReleaseProbeReport();
    for(int ch=0;ch<4;++ch) {
        for(const auto& preset:catalog) {
            check(player.setKeyboardPreset(ch,preset.id,preset.bank,preset.pc),"exact keyboard selection accepted/readback");
            BASS_MIDI_FONT actual{};check(BASS_MIDI_StreamGetPreset(stream,ch,&actual),"selected source readback");
            check(BASS_MIDI_FontGetPreset(actual.font,actual.preset,actual.bank)!=nullptr,"selected preset really exists");
            check(VoiceReleaseTestAccess::matches(player,preset.id,preset.bank,preset.pc,actual),"actual SF2 handle/native bank/program match exact browser identity");
            BASS_MIDI_FONT after{};check(BASS_MIDI_StreamGetPreset(stream,10,&after)&&after.font==style.font&&after.bank==style.bank&&after.preset==style.preset,"style mapping unchanged");
            for(int acmp=8;acmp<16;++acmp) {
                const auto& expected=acmp<10?rhythm:style;
                check(BASS_MIDI_StreamGetPreset(stream,acmp,&after)&&after.font==expected.font&&after.bank==expected.bank&&after.preset==expected.preset,"every ACMP channel mapping remains unchanged");
                check(BASS_MIDI_StreamGetEvent(stream,acmp,MIDI_EVENT_RELEASE)==64,"every ACMP channel release remains normal");
            }
            player.setChannelMixer(ch,127,64,127,0,0);player.noteOn(ch,60,95/127.f);
#ifdef VOICE_RELEASE_REAL_BASS
            render(player,512);const auto pcm=render(player,4096);check(rms(pcm)>0.005,"selected voice has PCM");
            const int expected=preset.file=="voice-primary.sf2"?375:preset.file=="voice-secondary.sf2"?750:preset.file=="voice-third.sf2"?1125:1500;
            const auto wanted=tone(pcm,expected);for(int f:{375,750,1125,1500})if(f!=expected)check(wanted>tone(pcm,f)*3,"PCM matches selected SF2 signature");
#else
            check(mock_bass::noteOns>0,"NOTE_ON sent for exact keyboard selection");
#endif
            player.noteOff(ch,60);player.setKeyboardReleaseTime(0);render(player,4096);player.allNotesOff();
            for(int keyboard=0;keyboard<4;++keyboard)BASS_MIDI_StreamEvent(stream,keyboard,MIDI_EVENT_SOUNDOFF,0);
        }
        std::cout<<"VOICE_CHANNEL channel="<<ch<<" role="<<(ch==3?"LEFT":"RIGHT")<<" selected="<<catalog.size()<<" PASS\n";
    }
    const auto selected=catalog.front();check(!player.setKeyboardPreset(4,selected.id,selected.bank,selected.pc),"style channel cannot use keyboard API");
    check(!player.setKeyboardPreset(0,selected.id,1234,1234),"absent preset rejected without fallback");
    for(int value:{0,64,127}) {
        check(player.setKeyboardReleaseTime(value),"RELEASE send and readback");
        for(int ch=0;ch<3;++ch)check(BASS_MIDI_StreamGetEvent(stream,ch,MIDI_EVENT_RELEASE)==DWORD(value),"RIGHT RELEASE readback equals UI value");
        check(BASS_MIDI_StreamGetEvent(stream,3,MIDI_EVENT_RELEASE)==64,"LEFT release remains normal");
        check(BASS_MIDI_StreamGetEvent(stream,10,MIDI_EVENT_RELEASE)==64,"ACMP release remains normal");
    }
    check(player.setKeyboardReleaseTime(127),"retain high release");
    check(player.setKeyboardPreset(0,selected.id,selected.bank,selected.pc),"voice change preserves release");
    check(BASS_MIDI_StreamGetEvent(stream,0,MIDI_EVENT_RELEASE)==127,"release after voice change");
    check(player.loadDrum(drum),"replace SF2");check(BASS_MIDI_StreamGetEvent(stream,0,MIDI_EVENT_RELEASE)==127,"release after SF2 change");
    check(!player.setKeyboardPreset(0,catalog.back().id,catalog.back().bank,catalog.back().pc),"removed SF2 identity rejected");
#ifndef VOICE_RELEASE_REAL_BASS
    mock_bass::failRelease=true;check(!player.setKeyboardReleaseTime(0),"parameter send failure is reported");mock_bass::failRelease=false;
    const auto current=parse(player.presetList()).front();mock_bass::failMapping=true;
    check(!player.setKeyboardPreset(0,current.id,current.bank,current.pc),"mapping failure rejected");
    const int before=mock_bass::noteOns;player.noteOn(0,60,1);check(mock_bass::noteOns==before+1,"failed mapping preserves the previous verified voice without fallback");mock_bass::failMapping=false;
#endif
    check(player.setKeyboardReleaseTime(127),"setting before restart");player.unload();check(player.presetList().empty(),"unload clears catalog");
    check(player.loadMelody(primary),"decoder restart");stream=VoiceReleaseTestAccess::stream(player);
    check(BASS_MIDI_StreamGetEvent(stream,0,MIDI_EVENT_RELEASE)==127,"release restored on new decoder");
    check(!player.setKeyboardPreset(0,selected.id,selected.bank,selected.pc),"stale ID never resolves to reused BASS handle");
#ifdef VOICE_RELEASE_REAL_BASS
    const auto fresh=parse(player.presetList()).front();check(player.setKeyboardPreset(0,fresh.id,0,24),"controlled release preset");
    player.setChannelMixer(0,127,64,127,0,0);double late[3];int i=0;
    for(int value:{0,64,127}) {
        check(player.setKeyboardReleaseTime(value),"release experiment value");player.noteOn(0,60,95/127.f);
        const auto held=render(player,24000);check(rms(held)>0.005,"held test tone audible");player.noteOff(0,60);
        render(player,24000);late[i++]=rms(render(player,24000));
        std::cout<<"RELEASE_PCM value="<<value<<" heldRms="<<rms(held)<<" tailRms500to1000ms="<<late[i-1]<<'\n';
        // Stop only the test part's tails before the next measurement.
        check(player.setKeyboardPreset(0,fresh.id,0,24),"reset prior test voice");
    }
    check(late[0]<late[1]*0.1&&late[1]<late[2]*0.8,"release low/normal/high have distinct note-off envelopes");
    const auto oneShot=voice_release_fixture::write(dir,"voice-one-shot.sf2",375,0,true);
    check(player.loadMelody(oneShot),"finite one-shot limitation fixture");
    const auto finite=parse(player.presetList()).front();check(player.setKeyboardPreset(0,finite.id,0,24),"finite fixture selected exactly");
    for(int value:{0,64,127}) {
        check(player.setKeyboardReleaseTime(value),"one-shot RELEASE controller accepted/readback");
        player.noteOn(0,60,95/127.f);render(player,24000);
        const auto beforeOff=render(player,4096);player.noteOff(0,60);const auto afterOff=render(player,4096);
        check(rms(beforeOff)<0.00001 && rms(afterOff)<0.00001,"finite sample already ended before key-up; not parameter delivery failure");
        std::cout<<"RELEASE_LIMITATION value="<<value<<" acceptedAndReadback=1 cause=sample_exhausted_before_keyup tailRms="<<rms(afterOff)<<'\n';
    }
#endif
    std::cout<<"VOICE_RELEASE_NATIVE checks="<<checks<<" PASS PCM="
#ifdef VOICE_RELEASE_REAL_BASS
        <<"real_linux_sdk_synthetic_sf2"
#else
        <<"NOT_MEASURED_api_recorder"
#endif
        <<" deviceAudio=UNVERIFIED\n";
}

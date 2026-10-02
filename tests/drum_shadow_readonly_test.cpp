#include "bassmidi_player.h"
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
JavaVM* g_jvm=nullptr;
jclass g_debugLogClass=nullptr;
jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool ok,const char* why) {++checks;if(!ok){std::cerr<<"FAIL: "<<why<<'\n';std::exit(1);}}
static std::string fixture(const std::string& dir,const char* file,int bank,int pc) {
    std::vector<unsigned char> data(108,0);
    auto word=[&](int n,int v){data[n]=v&255;data[n+1]=(v>>8)&255;};
    auto tag=[&](int n,const char* t){std::memcpy(data.data()+n,t,4);};
    tag(0,"RIFF");word(4,100);tag(8,"sfbk");tag(12,"LIST");word(16,88);tag(20,"pdta");tag(24,"phdr");word(28,76);
    std::memcpy(data.data()+32,"Opaque fixture",14);word(52,pc);word(54,bank);std::memcpy(data.data()+70,"EOP",3);
    const auto path=dir+"/"+file;std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(data.data()),data.size());return path;
}
static std::string mappings() {
    std::string out;for(const auto& m:mock_bass::mappings) out+=std::to_string(m.font)+":"+std::to_string(m.spreset)+":"+std::to_string(m.sbank)+":"+std::to_string(m.dpreset)+":"+std::to_string(m.dbank)+":"+std::to_string(m.dbanklsb)+":"+std::to_string(m.minchan)+":"+std::to_string(m.numchan)+";";return out;
}
int main(int argc,char** argv) {
    check(argc==3,"fixture and trace output paths");
    BassMidiPlayer player;
    check(player.loadMelody(fixture(argv[1],"shadow-primary.sf2",999,12)),"primary loads");
    check(player.loadDrum(fixture(argv[1],"shadow-drums.sf2",128,5)),"dedicated loads");
    player.setChannelPreset(9,121*128+3,12,"Opaque request");
    player.setChannelMixer(9,83,61,49,12,7);
    player.setKeyboardSustain(true);player.setKeyboardReleaseTime(31);player.setMasterGain(0.73f);
    auto observe=[&] {
#ifdef SHADOW_OBSERVATION
        const auto history=mock_bass::history;const auto events=mock_bass::events;
        const auto fonts=mock_bass::fonts;const auto maps=mappings();const auto ons=mock_bass::noteOns;
        const auto preload=std::make_tuple(mock_bass::preloadedFont,mock_bass::preloadProgram,mock_bass::preloadBank,mock_bass::preloadFlags);
        const auto snapshot=player.shadowDrumSnapshot();
        check(snapshot.find("inputBank=15491 inputPC=12")!=std::string::npos,"snapshot retains pre-native-coercion identity");
        check(snapshot.find("raw=999 virtual=")!=std::string::npos,"raw/virtual binding is observational");
        check(history==mock_bass::history && events==mock_bass::events && ons==mock_bass::noteOns,"snapshot adds zero events and leaves CC/program unchanged");
        check(fonts==mock_bass::fonts && maps==mappings(),"snapshot does not activate fonts or rebuild FONTEX2");
        check(preload==std::make_tuple(mock_bass::preloadedFont,mock_bass::preloadProgram,mock_bass::preloadBank,mock_bass::preloadFlags),"snapshot does not preload or change NOWAIT");
#endif
    };
    auto sequence=[&](bool read) {
        mock_bass::history.clear();
        for(int i=0;i<8;++i) {
            const int key=60+i;player.noteOn(9,key,(21+i*13)/127.0f);
            if(read)observe();
            player.noteOff(9,key);
        }
        player.allNotesOff();return mock_bass::history;
    };
    const auto before=sequence(false);const auto after=sequence(true);
    check(before==after,"complete production event trace unchanged with shadow reads");
    check(mock_bass::events[{9,MIDI_EVENT_VOLUME}]==83 && mock_bass::events[{9,MIDI_EVENT_EXPRESSION}]==49,"mixer and CC11 unchanged");
    check((mock_bass::streamFlags & BASS_MIDI_NOTEOFF1)!=0,"NOTEOFF1 unchanged");
    std::ofstream trace(argv[2]);for(const auto& e:after)trace<<std::get<0>(e)<<' '<<std::get<1>(e)<<' '<<std::get<2>(e)<<' '<<std::get<3>(e)<<'\n';
    std::vector<long long> ns;ns.reserve(3000);
    for(int i=0;i<3000;++i) {
        const auto start=std::chrono::steady_clock::now();player.noteOn(9,60,0.4f);
        ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count());
        player.noteOff(9,60);
    }
    std::sort(ns.begin(),ns.end());
    std::cout<<"SHADOW HOST "<<checks<<" checks PASS NOTE_ON p50="<<ns[1500]<<"ns p95="<<ns[2850]<<"ns p99="<<ns[2970]<<"ns samples="<<ns.size()<<"; mock synth, no device xrun measurement\n";
}

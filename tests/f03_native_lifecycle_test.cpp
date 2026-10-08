// Unchanged BassMidiPlayer + API recorder. No synth voices/PCM oracle.
#include "bassmidi_player.h"
#include "sf2_rich_fixture.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <cstdlib>

JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool ok,const char* message) { ++checks;if(!ok){std::cerr<<message<<'\n';std::exit(1);} }
static std::string fixture(const std::string& dir) {
    using namespace rich_fixture;
    Bytes ph(38*2),pdta={'p','d','t','a'};
    std::memcpy(ph.data(),"ConcertGrand",12);word(ph,20,0);word(ph,22,8);
    std::memcpy(ph.data()+38,"EOP",3);chunk(pdta,"phdr",ph);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    auto path=dir+"/F03 Synthetic.sf2";std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
static std::vector<DWORD> notes() {
    std::vector<DWORD> result;
    for(auto [stream,channel,event,value]:mock_bass::history)if(stream==1&&channel==11&&event==MIDI_EVENT_NOTE)result.push_back(value);
    return result;
}
int main(int argc,char** argv) {
    check(argc==2,"temporary fixture directory");BassMidiPlayer player;
    check(player.loadMelody(fixture(argv[1])),"real production loader");
    player.setChannelPreset(11,1024,0,"Piano");
    check((mock_bass::streamFlags&BASS_MIDI_NOTEOFF1)!=0,"production oldest-note flag retained");
    mock_bass::history.clear();
    AudioPathOrigin a{4,60,1024,0,101,false}, b{5,60,1024,1,102,false};
    player.noteOn(11,60,100/127.f,a);player.noteOn(11,60,90/127.f,b);
    player.noteOff(11,60,b);
    check(notes()==std::vector<DWORD>{DWORD(60|(100<<8)),DWORD(60|(90<<8)),60},"melodic off forwards destination pitch without source owner token");
    player.noteOff(11,60,a);
    check(notes().size()==4 && notes().back()==60,"second off still forwarded at native layer");
    // Actual received F03 replacement transcript, not desired musical fix.
    mock_bass::history.clear();
    player.noteOn(11,60,100/127.f,a);player.noteOff(11,60,a);
    player.noteOn(11,60,90/127.f,a);player.noteOff(11,60,a);
    check(notes()==std::vector<DWORD>{DWORD(60|(100<<8)),60,DWORD(60|(90<<8)),60},"sequencer replacement trace reaches production BASS calls unchanged");
    player.allNotesOff();
    unsigned allOff=0;for(auto [stream,ch,event,value]:mock_bass::history)if(stream==1&&event==MIDI_EVENT_NOTESOFF)++allOff;
    check(allOff==16,"stop dispatch releases all sixteen internal channels");
    mock_bass::history.clear();mock_bass::failNote=true;
    player.noteOn(11,60,.5f,a);player.noteOff(11,60,a);mock_bass::failNote=false;
    check(notes().size()==2,"failed backend still records attempts; API recorder is not audible evidence");
    std::cout<<"S5_NATIVE_LIFECYCLE checks="<<checks<<" productionPlayer=true BASS_NOTEOFF1=true API_mock=true nativeSDK_acceptance=NOT_MEASURED PCM=NOT_MEASURED\n";
}

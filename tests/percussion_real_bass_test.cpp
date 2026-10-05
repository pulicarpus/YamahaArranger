#include "bassmidi_player.h"
#include "percussion_fidelity_fixture.h"
#include <jni.h>
#include <iostream>
#include <vector>
#include <cmath>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
std::vector<HSTREAM> created;unsigned productionOns=0;
extern "C" BOOL __real_BASS_MIDI_StreamEvent(HSTREAM,DWORD,DWORD,DWORD);
extern "C" BOOL __wrap_BASS_MIDI_StreamEvent(HSTREAM h,DWORD c,DWORD e,DWORD v){if(!created.empty() && h==created.front() && e==MIDI_EVENT_NOTE && (v>>8)>0 && (v>>8)<128)++productionOns;return __real_BASS_MIDI_StreamEvent(h,c,e,v);}
extern "C" HSTREAM __real_BASS_MIDI_StreamCreate(DWORD,DWORD,DWORD);
extern "C" HSTREAM __wrap_BASS_MIDI_StreamCreate(DWORD c,DWORD f,DWORD r){auto h=__real_BASS_MIDI_StreamCreate(c,f,r);if(h)created.push_back(h);return h;}
int main(int argc,char** argv){if(argc!=2)return 2;BassMidiPlayer player;const std::string dir=argv[1];
if(!player.loadMelody(percussion_fixture::write(dir,"real-melody.sf2",0,24,true)) ||
 !player.loadDrum(percussion_fixture::write(dir,"real-legacy.sf2",128,0,true)) ||
 !player.loadMelodyFallback(percussion_fixture::write(dir,"real-compatible.sf2",62,5))){std::cerr<<"load failure="<<BASS_ErrorGetCode()<<'\n';return 1;}
player.setChannelPreset(12,0,24,"Guitar");player.setChannelPreset(8,128,73,"PopDrumKit");player.setChannelMixer(8,80,64,100,40,0);
if(created.size()!=2){std::cerr<<"streams="<<created.size()<<'\n';return 1;}
BASS_MIDI_FONT physical{};
player.noteOn(8,75,0.75f,AudioPathOrigin{8,75,127*128,0,1});
if(!BASS_MIDI_StreamGetPreset(created.back(),75,&physical)||physical.bank!=0||physical.preset!=5){std::cerr<<"normalized bank not applied after real note\n"<<player.noteZoneReport();return 1;}
std::vector<float> pcm(2048);double energy=0;for(int i=0;i<10;++i){player.render(pcm.data(),1024);for(float v:pcm)energy+=v*v;}
if(energy<=0){std::cerr<<"no auxiliary PCM\n";return 1;}
if(productionOns!=0){std::cerr<<"production stream duplicated NOTE_ON\n";return 1;}
player.noteOff(8,75,AudioPathOrigin{8,75,127*128,0,2});
for(int i=0;i<10000;++i)player.noteOn(8,82,0.4f,AudioPathOrigin{8,82,127*128,i,i+3});
if(player.noteZoneReport().find("overflowDropped=0")==std::string::npos){std::cerr<<"one-shot owner exhaustion\n";return 1;}
player.noteOn(12,60,0.5f);BASS_MIDI_FONT melodic{};
if(!BASS_MIDI_StreamGetPreset(created.front(),12,&melodic)||melodic.preset!=24){std::cerr<<"melodic binding regression\n";return 1;}
std::cout<<"REAL_BASSMIDI compatible cross-key75->58 normalized sourceBank="<<physical.bank<<" preset="<<physical.preset<<" PCM_energy="<<energy<<" repeatedOneShots=10000 ownerOverflow=0 melodicBindingPreserved=true synthetic_fixture_not_tester_timbre\n";
}

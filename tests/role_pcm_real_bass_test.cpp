#include "bassmidi_player.h"
#include "mix_response_fixture.h"
#include "percussion_fidelity_fixture.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <vector>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
unsigned midiEvents=0,notes=0,taps=0;
struct RoutedEvent {DWORD channel,event,value;};std::vector<RoutedEvent> eventTrace;
extern "C" BOOL __real_BASS_MIDI_StreamEvent(HSTREAM,DWORD,DWORD,DWORD);
extern "C" BOOL __wrap_BASS_MIDI_StreamEvent(HSTREAM h,DWORD ch,DWORD e,DWORD v){eventTrace.push_back({ch,e,v});++midiEvents;if(e==MIDI_EVENT_NOTE&&(v>>8)>0&&(v>>8)<128)++notes;return __real_BASS_MIDI_StreamEvent(h,ch,e,v);}
extern "C" HSTREAM __real_BASS_MIDI_StreamGetChannel(HSTREAM,DWORD);
extern "C" HSTREAM __wrap_BASS_MIDI_StreamGetChannel(HSTREAM h,DWORD ch){if(ch<10||ch>15)std::exit(9);++taps;return __real_BASS_MIDI_StreamGetChannel(h,ch);}
std::string font(const std::string& dir,int bank,int pc,const std::string& name,int fixed) {
 auto path=mix_response_fixture::write(dir,0,fixed);std::ifstream f(path,std::ios::binary);std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(f)),{});
 for(size_t i=12;i+46<bytes.size();++i)if(std::string(reinterpret_cast<char*>(bytes.data()+i),4)=="phdr") {
  std::fill(bytes.begin()+i+8,bytes.begin()+i+28,0);std::copy(name.begin(),name.end(),bytes.begin()+i+8);
  bytes[i+28]=pc;bytes[i+29]=pc>>8;bytes[i+30]=bank;bytes[i+31]=bank>>8;break;
 }
 path=dir+"/"+name+".sf2";std::ofstream out(path,std::ios::binary);out.write(reinterpret_cast<char*>(bytes.data()),bytes.size());return path;
}
int main(int argc,char** argv){if(argc!=2)return 2;const std::string dir=argv[1];BassMidiPlayer p;
 if(!p.loadMelody(font(dir,0,24,"Guitar",-1)) || !p.loadMelodyFallback(font(dir,8,49,"Strings",127)) ||
    !p.loadDrum(percussion_fixture::write(dir,"role-drums.sf2",128,0,true)))return 3;
 p.setMasterGain(.8f);p.setChannelPreset(0,0,24,"Guitar");p.setChannelPreset(8,128,0,"StandardKit");p.setChannelPreset(9,128,0,"StandardKit");
 for(int ch=10;ch<16;++ch){p.setChannelPreset(ch,ch<13?0:8*128,ch<13?24:49,ch<13?"Guitar":"Strings");p.setChannelMixer(ch,50+ch*2,40+ch,85+ch,60,30);}
 std::ofstream pcm(dir+"/pcm.bin",std::ios::binary);std::vector<float> block(512);
 for(int i=0;i<100;++i){
  if(i%10==0){for(int ch=10;ch<16;++ch)p.noteOn(ch,60+(i/10)%3,(35+ch*3)/127.0f,AudioPathOrigin{ch,60,0,i,i*16+ch});p.noteOn(8,33,.5f,AudioPathOrigin{8,33,16384,i,i});}
  if(i%10==3)for(int ch=10;ch<16;++ch)p.noteOff(ch,60+(i/10)%3,AudioPathOrigin{ch,60,0,i,i*16+ch});
  if(i==40)for(int ch=10;ch<16;++ch)p.setChannelExpression(ch,35);
  if(i==70)for(int ch=10;ch<16;++ch)p.setChannelMixer(ch,80,64,117,60,30);
  p.render(block.data(),256);pcm.write(reinterpret_cast<const char*>(block.data()),block.size()*sizeof(float));
 }
 auto report=p.noteZoneReport();std::ofstream(dir+"/role-report.txt")<<report;
#if YAMAHA_ROLE_PCM_METERS
 if(taps!=6)return 4;
 for(int ch=10;ch<16;++ch){auto marker="ROLE_PCM ch="+std::to_string(ch)+" ";auto pos=report.find(marker);if(pos==std::string::npos)return 5;auto line=report.substr(pos,report.find('\n',pos)-pos);
  if(line.find("bindingVerified=1")==std::string::npos || line.find("callbackSamples=0")!=std::string::npos || line.find("styleOns=10")==std::string::npos)return 6;
  if(ch>=13 && (line.find("selectedRawBank=8 nativeBank=0 rawPC=49")==std::string::npos))return 7;
 }
#else
 if(taps!=0)return 8;
#endif
 p.unload();std::ofstream trace(dir+"/midi.bin",std::ios::binary);trace.write(reinterpret_cast<const char*>(eventTrace.data()),eventTrace.size()*sizeof(RoutedEvent));std::cout<<"ROLE_REAL taps="<<taps<<" notes="<<notes<<" midiEvents="<<midiEvents<<" synthetic_multi_SF2=true controllers_preserved=true\n";
}

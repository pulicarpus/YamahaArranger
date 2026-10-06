// Real production AudioEngine callback + real BASSMIDI; only Oboe platform is simulated.
#include "audio_engine.h"
#define main roleFixtureMain
#include "role_pcm_real_bass_test.cpp"
#undef main
#include <cassert>
bool failNextDecode=false;
extern "C" DWORD __real_BASS_ChannelGetData(DWORD,void*,DWORD);
extern "C" DWORD __wrap_BASS_ChannelGetData(DWORD stream,void* out,DWORD length) {
 if(failNextDecode){failNextDecode=false;return __real_BASS_ChannelGetData(0,out,length);}
 return __real_BASS_ChannelGetData(stream,out,length);
}
int main(int argc,char** argv) {
 if(argc!=2)return 2;std::string dir=argv[1];AudioEngine engine;
 std::vector<float> block(512,123);engine.onAudioReady(nullptr,block.data(),256);
 assert(std::all_of(block.begin(),block.end(),[](float x){return x==0;}));
 assert(engine.loadMelodySoundFont(font(dir,0,24,"Guitar",-1)));
 assert(engine.loadMelodyFallbackSoundFont(font(dir,8,49,"Strings",127)));
 assert(engine.loadDrumSoundFont(percussion_fixture::write(dir,"role-drums.sf2",128,0,true)));
 assert(engine.start());
 for(int i=0;i<20127;++i)engine.onAudioReady(nullptr,block.data(),256);
 for(int ch=10;ch<16;++ch){engine.sfSetChannelPresetWithName(ch,ch<13?0:8*128,ch<13?24:49,ch<13?"Guitar":"Strings");engine.sfSetChannelMixer(ch,70,64,110,0,0);}
 // Internal style sends progress independently of callbacks: this must not invent PCM.
 for(int ch=10;ch<16;++ch)engine.sfNoteOnStyleChannel(ch,60,.6f,AudioPathOrigin{ch,60,0,1,ch});
 auto stalled=engine.sfNoteZoneReport();std::ofstream(dir+"/stalled-report.txt")<<stalled;
#if YAMAHA_ROLE_PCM_METERS
 assert(stalled.find("renderedSpanSamples=0 callbackSamples=0")!=std::string::npos);
 assert(stalled.find("PCM_DECODE calls=20127 success=20127 failed=0")!=std::string::npos);
 assert(stalled.find("decodeCallsAtStart=20127 decodeCallsAtLastNote=20127")!=std::string::npos);
 assert(stalled.find("synthCallbacks=20127 fallbackCallbacks=1")!=std::string::npos);
#endif
 std::ofstream pcm(dir+"/pcm.bin",std::ios::binary);
 for(int i=0;i<100;++i){engine.onAudioReady(nullptr,block.data(),256);pcm.write(reinterpret_cast<char*>(block.data()),block.size()*sizeof(float));}
 auto audible=engine.sfNoteZoneReport();std::ofstream(dir+"/audible-report.txt")<<audible;
#if YAMAHA_ROLE_PCM_METERS
 for(int ch=10;ch<16;++ch){auto p=audible.find("ROLE_PCM ch="+std::to_string(ch)+" ");assert(p!=std::string::npos);auto line=audible.substr(p,audible.find('\n',p)-p);
 assert(line.find("renderedSpanSamples=51200 callbackSamples=51200")!=std::string::npos);
 assert(line.find("peak=0 ")==std::string::npos);assert(line.find("dryRmsSpan=0 ")==std::string::npos);}
#endif
 failNextDecode=true;engine.onAudioReady(nullptr,block.data(),256);
 assert(std::all_of(block.begin(),block.end(),[](float x){return x==0;}));
 auto failed=engine.sfNoteZoneReport();std::ofstream(dir+"/failed-report.txt")<<failed;
#if YAMAHA_ROLE_PCM_METERS
 assert(failed.find("PCM_DECODE calls=20228 success=20227 failed=1 noStream=0")!=std::string::npos);
 assert(failed.find("lastError=5")!=std::string::npos);
#endif
 engine.stop();assert(engine.start());engine.onAudioReady(nullptr,block.data(),256);
 engine.stop();oboe::failStart=true;assert(!engine.start());oboe::failStart=false;
 oboe::failOpen=true;assert(!engine.start());oboe::failOpen=false;
 auto report=engine.sfNoteZoneReport();std::ofstream(dir+"/lifecycle-report.txt")<<report;
#if YAMAHA_ROLE_PCM_METERS
 assert(report.find("AUDIO_OUTPUT starts=4 started=2 stops=2 lastStartResult=-1")!=std::string::npos);
#else
 assert(report.find("AUDIO_OUTPUT")==std::string::npos);assert(report.find("PCM_DECODE")==std::string::npos);assert(report.find("ROLE_PCM_PATH")==std::string::npos);
#endif
 // Export cannot produce another note/render/output lifecycle operation.
 assert(engine.sfNoteZoneReport().find("PCM_MIX samples=10356736")!=std::string::npos);
 std::ofstream trace(dir+"/midi.bin",std::ios::binary);trace.write(reinterpret_cast<char*>(eventTrace.data()),eventTrace.size()*sizeof(RoutedEvent));
 std::cout<<"PCM_PATH_REAL PASS: stalled callbacks, successful decode/role PCM, failed decode, fallback, stop/restart/open/start failure; production callback tested\n";
}

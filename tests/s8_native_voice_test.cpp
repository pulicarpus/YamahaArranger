// Diagnostic only: real SDK headers required. No recorder is PCM evidence.
#include "bassmidi_player.h"
#include "s8_signature_fixture.h"
#include <jni.h>
#include <iostream>
#include <iomanip>
#include <cmath>
#include <cstdlib>
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static HSTREAM stream;static bool one=true,reject=false;static unsigned ons,offs,rejected;static DWORD flags;
static void ck(bool b,const char* m){if(!b){std::cerr<<m<<" error="<<BASS_ErrorGetCode()<<'\n';std::exit(1);}}
extern "C" HSTREAM __real_BASS_MIDI_StreamCreate(DWORD,DWORD,DWORD);
extern "C" HSTREAM __wrap_BASS_MIDI_StreamCreate(DWORD c,DWORD f,DWORD r){f=one?(f|BASS_MIDI_NOTEOFF1):(f&~BASS_MIDI_NOTEOFF1);flags=f;return stream=__real_BASS_MIDI_StreamCreate(c,f,r);}
extern "C" BOOL __real_BASS_MIDI_StreamEvent(HSTREAM,DWORD,DWORD,DWORD);
extern "C" BOOL __wrap_BASS_MIDI_StreamEvent(HSTREAM h,DWORD c,DWORD e,DWORD v){if(e==MIDI_EVENT_NOTE && v>>8 && reject){reject=false;++rejected;return FALSE;}auto ok=__real_BASS_MIDI_StreamEvent(h,c,e,v);if(e==MIDI_EVENT_NOTE){ck(ok,"native NOTE acceptance");if(v>>8)++ons;else ++offs;}return ok;}
struct Metric {double a,b,energy,residual;};
static Metric sample(BassMidiPlayer& p){
 std::vector<float> pcm(4096);p.render(pcm.data(),2048);double re[2]={},im[2]={},e=0;
 // Integer-bin, phase-invariant projection; stereo folded to mono.
 for(int j=0;j<2048;++j){double x=(pcm[j*2]+pcm[j*2+1])/2;ck(std::isfinite(x),"finite PCM");e+=x*x;for(int k=0;k<2;++k){double t=2*3.141592653589793*(k?1125:375)*j/48000;re[k]+=x*std::cos(t);im[k]+=x*std::sin(t);}}
 double a=2*(re[0]*re[0]+im[0]*im[0])/2048,b=2*(re[1]*re[1]+im[1]*im[1])/2048;
 return {a,b,e,std::max(0.,e-a-b)/std::max(e,1e-30)};
}
static Metric run(const std::string& path,int mode,bool same,bool offB,bool sustain,bool rejectA, bool flag){
 one=flag;BassMidiPlayer p;ck(p.loadMelody(path),"signature font load");p.setChannelPreset(11,0,24,"Guitar");p.setChannelMixer(11,127,64,127,0,0);ck(bool(flags&BASS_MIDI_NOTEOFF1)==flag,"actual SDK flag");
 AudioPathOrigin a{4,60,0,0,101,false},b{same?4:5,60,0,2048,102,false};
 if(sustain)ck(BASS_MIDI_StreamEvent(stream,11,MIDI_EVENT_SUSTAIN,127),"sustain on");
 if(mode!=1){reject=rejectA;p.noteOn(11,60,48/127.f,a);}sample(p);
 if(mode!=0)p.noteOn(11,60,112/127.f,b);sample(p);
 if(mode>=3)p.noteOff(11,60,offB?b:a);
 // Release tail explicitly allowed 8192 frames (170.7ms), then window43ms.
 Metric m{};for(int i=0;i<5;++i)m=sample(p);
 if(mode>=3){p.noteOff(11,60,offB?a:b);if(sustain)ck(BASS_MIDI_StreamEvent(stream,11,MIDI_EVENT_SUSTAIN,0),"sustain off");for(int i=0;i<30;++i)sample(p);auto end=sample(p);std::cout<<"S8_FINAL energy="<<end.energy<<" residual="<<end.residual<<'\n';}
 return m;
}
int main(int argc,char**argv){ck(argc==2,"fixture directory");std::cout<<std::setprecision(12)<<"S8_SDK bass="<<BASS_GetVersion()<<" bassmidi="<<BASS_MIDI_GetVersion()<<" NOTEOFF1="<<BASS_MIDI_NOTEOFF1<<'\n';
 auto path=s8_fixture::write(argv[1],-12000);auto a=run(path,0,false,false,false,false,true),b=run(path,1,false,false,false,false,true),both=run(path,2,false,false,false,false,true);
 ck(a.energy>1e-12&&b.energy>1e-12,"nonzero reference");ck(a.a>100*a.b&&b.b>100*b.a,"orthogonal reference separation");
 auto emit=[&](const char* name,Metric m){double x=m.a/a.a,y=m.b/b.b;bool valid=m.residual<.05;const char* id=!valid?"UNKNOWN":x>.5&&y<.01?"A":y>.5&&x<.01?"B":x>.5&&y>.5?"BOTH":x<.01&&y<.01?"SILENT":"UNKNOWN";std::cout<<"S8_PCM case="<<name<<" powerA="<<m.a<<" powerB="<<m.b<<" energy="<<m.energy<<" residual="<<m.residual<<" ratioA="<<x<<" ratioB="<<y<<" signature="<<id<<'\n';};
 emit("referenceA",a);emit("referenceB",b);emit("overlap",both);
 for(bool same:{false,true})for(bool offB:{false,true}){std::string n=(same?"same":"cross")+std::string(offB?"_offB_first":"_offA_first");emit(n.c_str(),run(path,3,same,offB,false,false,true));}
 emit("no_NOTEOFF1",run(path,3,false,false,false,false,false));
 emit("sustain",run(path,3,false,false,true,false,true));emit("injected_rejected_A",run(path,3,false,false,false,true,true));
 auto longPath=s8_fixture::write(argv[1],0);emit("long_release_170ms",run(longPath,3,false,false,false,false,true));
 ck(rejected==1,"one explicit injected rejection");
 std::cout<<"S8_API acceptedOns="<<ons<<" acceptedOffs="<<offs<<" injectedRejectedOns="<<rejected<<" voiceHandle=NOT_OBSERVED Android=UNRUN PSRE343=UNRUN\n";
}

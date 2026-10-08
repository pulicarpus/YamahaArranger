// S7 diagnostic: unchanged player. Real mode uses real SDK + synthetic SF2 PCM;
// recorder mode checks request shape only. Neither is PSR-E343 evidence.
#include "bassmidi_player.h"
#include "percussion_fidelity_fixture.h"
#include <jni.h>
#include <iostream>
#include <vector>
#include <tuple>
#include <cmath>
#include <cstdlib>
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool b,const char* label){++checks;if(!b){std::cerr<<label<<'\n';std::exit(1);}}
static AudioPathOrigin a{4,60,0,0,101,false}, b{5,60,0,512,102,false};
#ifdef S7_REAL_BASS
static bool rejectNextOn=false;
static unsigned acceptedOns=0,acceptedOffs=0,rejectedOns=0;
static DWORD streamFlags=0;
extern "C" BOOL __real_BASS_MIDI_StreamEvent(HSTREAM,DWORD,DWORD,DWORD);
extern "C" BOOL __wrap_BASS_MIDI_StreamEvent(HSTREAM h,DWORD c,DWORD e,DWORD v){
    if(e==MIDI_EVENT_NOTE && (v>>8)>0 && (v>>8)<128 && rejectNextOn){rejectNextOn=false;++rejectedOns;return FALSE;}
    const auto result=__real_BASS_MIDI_StreamEvent(h,c,e,v);
    if(result && e==MIDI_EVENT_NOTE){if(v>>8)++acceptedOns;else ++acceptedOffs;}
    return result;
}
extern "C" HSTREAM __real_BASS_MIDI_StreamCreate(DWORD,DWORD,DWORD);
extern "C" HSTREAM __wrap_BASS_MIDI_StreamCreate(DWORD c,DWORD f,DWORD r){streamFlags=f;return __real_BASS_MIDI_StreamCreate(c,f,r);}
static std::vector<float> block(BassMidiPlayer& p){std::vector<float> pcm(1024);p.render(pcm.data(),512);for(float f:pcm)check(std::isfinite(f),"finite real PCM");return pcm;}
// Discard initial 256 frames of OFF block: this synthetic SF2 has no reverb,
// chorus or extended release generator. No hardware voice identifier is read.
static double energy(const std::vector<float>& v){double s=0;for(size_t i=512;i<v.size();++i)s+=v[i]*v[i];return s;}
static double difference(const std::vector<float>& x,const std::vector<float>& y){double s=0;for(size_t i=512;i<x.size();++i){double d=x[i]-y[i];s+=d*d;}return s/std::max(1e-30,energy(y));}
static std::vector<float> probe(const std::string& path,int kind){
    BassMidiPlayer p;check(p.loadMelody(path),"real font load");p.setChannelPreset(11,0,24,"Guitar");p.setChannelMixer(11,127,64,127,0,0);
    check((streamFlags&BASS_MIDI_NOTEOFF1)!=0,"production NOTEOFF1 retained");
    if(kind!=1){if(kind==4)rejectNextOn=true;p.noteOn(11,60,96/127.f,a);}block(p);
    if(kind!=0){auto origin=b;if(kind==3)origin.sourceChannel=4;p.noteOn(11,60,32/127.f,origin);}block(p);
    if(kind==2)p.noteOff(11,60,b); // source B attribution only in diagnostic origin
    if(kind==4)p.noteOff(11,60,a); // explicit old/rejected A OFF, not admitted B OFF
    if(kind==3){auto sameSource=b;sameSource.sourceChannel=4;p.noteOff(11,60,sameSource);}
    if(kind==5)p.allNotesOff();
    return block(p);
}
int main(int argc,char** argv){
    check(argc==2,"fixture directory");const auto path=percussion_fixture::write(argv[1],"s7-contract.sf2",0,24,true);
    const auto first=probe(path,0),second=probe(path,1),cross=probe(path,2),same=probe(path,3),rejected=probe(path,4),stopped=probe(path,5);
    check(energy(first)>0 && energy(second)>0,"isolated real reference PCM nonzero");
    check(difference(first,second)>0.01,"velocity reference signatures distinguishable");
    check(rejectedOns==1,"explicit test-wrapper rejection exercised");
    auto classify=[&](const std::vector<float>& x){const auto da=difference(x,first),db=difference(x,second);if(db<1e-5&&da>0.01)return "SECOND_SIGNATURE";if(da<1e-5&&db>0.01)return "FIRST_SIGNATURE";return "UNKNOWN";};
    std::cout<<"S7_REAL_BASS checks="<<checks<<" version="<<BASS_GetVersion()<<" midiVersion="<<BASS_MIDI_GetVersion()<<" acceptedOns="<<acceptedOns<<" acceptedOffs="<<acceptedOffs<<" injectedRejectedOns="<<rejectedOns
      <<" crossSourceSurvivor="<<classify(cross)<<" sameSourceSurvivor="<<classify(same)<<" crossErrorFirst="<<difference(cross,first)<<" crossErrorSecond="<<difference(cross,second)
      <<" sameErrorFirst="<<difference(same,first)<<" sameErrorSecond="<<difference(same,second)
      <<" referenceEnergyFirst="<<energy(first)<<" referenceEnergySecond="<<energy(second)<<" rejectedThenOffEnergy="<<energy(rejected)<<" stopEnergy="<<energy(stopped)
      <<" LinuxSDK=true syntheticSF2=true nativeVoiceHandle=NOT_OBSERVED PSRE343=NOT_MEASURED\n";
}
#else
static std::vector<DWORD> notes(){std::vector<DWORD> v;for(auto [s,c,e,p]:mock_bass::history)if(s==1&&c==11&&e==MIDI_EVENT_NOTE)v.push_back(p);return v;}
int main(int argc,char** argv){
    check(argc==2,"fixture directory");BassMidiPlayer p;check(p.loadMelody(percussion_fixture::write(argv[1],"s7-recorder.sf2",0,24,true)),"production loader with API recorder");
    p.setChannelPreset(11,0,24,"Guitar");check((mock_bass::streamFlags&BASS_MIDI_NOTEOFF1)!=0,"flag preserved, not runtime semantics");
    mock_bass::history.clear();p.noteOn(11,60,96/127.f,a);p.noteOn(11,60,32/127.f,b);p.noteOff(11,60,b);p.noteOff(11,60,a);
    auto reversed=notes();check(reversed==std::vector<DWORD>{DWORD(60|(96<<8)),DWORD(60|(32<<8)),60,60},"OFF B then A wire transcript");
    mock_bass::history.clear();p.noteOn(11,60,96/127.f,a);p.noteOn(11,60,32/127.f,b);p.noteOff(11,60,a);p.noteOff(11,60,b);
    check(notes()==reversed,"source origin/off attribution does not change native event value");
    mock_bass::history.clear();mock_bass::failNote=true;p.noteOn(11,60,96/127.f,a);mock_bass::failNote=false;p.noteOn(11,60,32/127.f,b);p.noteOff(11,60,a);
    check(notes()==std::vector<DWORD>{DWORD(60|(96<<8)),DWORD(60|(32<<8)),60},"rejected ON attempt has no native OFF token");
    std::cout<<"S7_API_RECORDER checks="<<checks<<" productionPlayer=true logicalOriginsDifferent=true nativeEventValuesIdentical=true runtimeVoicePairing=UNKNOWN PCM=NOT_MEASURED\n";
}
#endif

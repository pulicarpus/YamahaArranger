// P1 isolated real Linux SDK controls. No production changes or device claims.
#include "bassmidi_player.h"
#include "s8_signature_fixture.h"
#include <jni.h>
#include <iostream>
#include <iomanip>
#include <cmath>
#include <cstdlib>
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static HSTREAM stream;static bool one=true;static unsigned ons,offs;static DWORD flags;
static void ck(bool b,const char* m){if(!b){std::cerr<<m<<" error="<<BASS_ErrorGetCode()<<'\n';std::exit(1);}}
extern "C" HSTREAM __real_BASS_MIDI_StreamCreate(DWORD,DWORD,DWORD);
extern "C" HSTREAM __wrap_BASS_MIDI_StreamCreate(DWORD c,DWORD f,DWORD r){f=one?(f|BASS_MIDI_NOTEOFF1):(f&~BASS_MIDI_NOTEOFF1);flags=f;return stream=__real_BASS_MIDI_StreamCreate(c,f,r);}
extern "C" BOOL __real_BASS_MIDI_StreamEvent(HSTREAM,DWORD,DWORD,DWORD);
extern "C" BOOL __wrap_BASS_MIDI_StreamEvent(HSTREAM h,DWORD c,DWORD e,DWORD v){auto ok=__real_BASS_MIDI_StreamEvent(h,c,e,v);if(e==MIDI_EVENT_NOTE){ck(ok,"native NOTE acceptance");if(v>>8)++ons;else ++offs;}return ok;}
struct Metric {double a,b,energy,residual;};
static Metric sample(BassMidiPlayer& p){
 std::vector<float> pcm(4096);p.render(pcm.data(),2048);double re[2]={},im[2]={},e=0;
 // Integer-bin, phase-invariant projection; stereo folded to mono.
 for(int j=0;j<2048;++j){double x=(pcm[j*2]+pcm[j*2+1])/2;ck(std::isfinite(x),"finite PCM");e+=x*x;for(int k=0;k<2;++k){double t=2*3.141592653589793*(k?1125:375)*j/48000;re[k]+=x*std::cos(t);im[k]+=x*std::sin(t);}}
 double a=2*(re[0]*re[0]+im[0]*im[0])/2048,b=2*(re[1]*re[1]+im[1]*im[1])/2048;
 return {a,b,e,std::max(0.,e-a-b)/std::max(e,1e-30)};
}

// Reuse immutable S8 bytes, changing only velocity ranges of a NEW generated font.
// Swap which velocity admits the A/B frequency; preset/family naming stays valid.
static std::string fixture(const std::string& dir,bool swapped) {
 auto input=s8_fixture::write(dir,-12000);std::ifstream f(input,std::ios::binary);
 std::vector<unsigned char> data((std::istreambuf_iterator<char>(f)),{});
 auto read32=[&](size_t p){return unsigned(data[p])|(unsigned(data[p+1])<<8)|(unsigned(data[p+2])<<16)|(unsigned(data[p+3])<<24);};
 size_t start=0,end=0;
 for(size_t p=12;p+8<=data.size();) {
  unsigned n=read32(p+4);ck(p+8+n<=data.size(),"RIFF bounds");
  if(!std::memcmp(data.data()+p,"LIST",4) && !std::memcmp(data.data()+p+8,"pdta",4)) {
   for(size_t q=p+12;q+8<=p+8+n;) {unsigned m=read32(q+4);ck(q+8+m<=p+8+n,"pdta bounds");
    if(!std::memcmp(data.data()+q,"igen",4)){start=q+8;end=start+m;}q+=8+m+(m&1);}
  }p+=8+n+(n&1);
 }
 ck(start && end,"igen found");unsigned ranges=0;
 for(size_t p=start;p+4<=end;p+=4)if((data[p]|(data[p+1]<<8))==44) {
  if(swapped){unsigned lo=data[p+2],hi=data[p+3];data[p+2]=lo==1?64:1;data[p+3]=hi==63?127:63;}
  ++ranges;
 }
 ck(ranges==2,"two velocity zones");std::string output=dir+(swapped?"/p1-swapped.sf2":"/p1-normal.sf2");
 std::ofstream out(output,std::ios::binary);out.write((char*)data.data(),data.size());return output;
}
// kind=0 A reference,1 B reference,2 overlap,3 first OFF,4 two OFFs same frame.
static Metric run(const std::string& path,bool swap,bool reverse,bool same,bool offB,int kind) {
 BassMidiPlayer p;ck(p.loadMelody(path),"font load");p.setChannelPreset(11,0,24,"Guitar");p.setChannelMixer(11,127,64,127,0,0);
 ck(flags&BASS_MIDI_NOTEOFF1,"original SDK NOTEOFF1 flag");
 AudioPathOrigin a{4,60,0,reverse?2048:0,101,false},b{same?4:5,60,0,reverse?0:2048,102,false};
 auto on=[&](bool B){if((kind!=0||!B)&&(kind!=1||B))p.noteOn(11,60,(B?(swap?48:112):(swap?112:48))/127.f,B?b:a);};
 on(reverse);sample(p);on(!reverse);sample(p); // first frame=0; second=2048; OFF=4096
 if(kind>=3)p.noteOff(11,60,offB?b:a);
 if(kind==4)p.noteOff(11,60,offB?a:b); // no render/time advance between the OFFs
 Metric m{};for(int i=0;i<5;++i)m=sample(p); // measurement frames12288..14335
 if(kind==3)p.noteOff(11,60,offB?a:b);
 if(kind>=3){for(int i=0;i<30;++i)sample(p);auto end=sample(p);ck(end.energy<1e-12,"final finite silence control");}
 return m;
}
int main(int argc,char** argv) {
 ck(argc==2,"directory");std::cout<<std::setprecision(12);
 std::cout<<"P1_SDK bass="<<BASS_GetVersion()<<" bassmidi="<<BASS_MIDI_GetVersion()<<" NOTEOFF1="<<BASS_MIDI_NOTEOFF1<<'\n';
 unsigned identified=0,unknown=0,mismatch=0,trials=0,doubleOff=0;
 double maxResidual=0,maxLeak=0,minRetained=1e30,minSeparation=1e30;
 for(int repeat=0;repeat<3;++repeat)for(bool swap:{false,true})for(bool reverse:{false,true}) {
  auto path=fixture(argv[1],swap);
  auto a=run(path,swap,reverse,false,false,0),b=run(path,swap,reverse,false,false,1);
  ck(a.energy>1e-12&&b.energy>1e-12,"nonzero references");ck(a.a>100*a.b&&b.b>100*b.a,"orthogonal signature references");
  minSeparation=std::min(minSeparation,std::min(a.a/std::max(a.b,1e-30),b.b/std::max(b.a,1e-30)));
  auto emit=[&](const char* kind,Metric m,bool same,bool offB){
   double x=m.a/a.a,y=m.b/b.b;bool valid=m.residual<.05;
   const char* id=!valid?"UNKNOWN":x>.5&&y<.01?"A":y>.5&&x<.01?"B":x>.5&&y>.5?"BOTH":x<.01&&y<.01?"SILENT":"UNKNOWN";
   std::cout<<"P1_PCM repeat="<<repeat<<" swapped="<<swap<<" reverse="<<reverse<<" sameSource="<<same<<" offBFirst="<<offB
            <<" kind="<<kind<<" noteAOnFrame="<<(std::string(kind)=="referenceB"?-1:(reverse?2048:0))
            <<" noteBOnFrame="<<(std::string(kind)=="referenceA"?-1:(reverse?0:2048))
            <<" firstOffFrame="<<((std::string(kind)=="first_off" || std::string(kind)=="same_frame_two_offs")?4096:-1)
            <<" secondOffFrame="<<(std::string(kind)=="first_off"?14336:std::string(kind)=="same_frame_two_offs"?4096:-1)
            <<" measurementStart=12288 measurementEnd=14336"
            <<" velocityA="<<(swap?112:48)<<" velocityB="<<(swap?48:112)<<" powerA="<<m.a<<" powerB="<<m.b
            <<" energy="<<m.energy<<" residual="<<m.residual<<" ratioA="<<x<<" ratioB="<<y<<" signature="<<id<<'\n';
   if(std::string(kind)=="first_off"){
    ++trials;if(std::string(id)=="UNKNOWN")++unknown;
    else if(std::string(id)==(reverse?"A":"B")){++identified;maxResidual=std::max(maxResidual,m.residual);
     maxLeak=std::max(maxLeak,reverse?y:x);minRetained=std::min(minRetained,reverse?x:y);}
    else ++mismatch;
   }
   if(std::string(kind)=="same_frame_two_offs"){++doubleOff;ck(std::string(id)=="SILENT","two OFF silence");}
   return std::string(id);
  };
  emit("referenceA",a,false,false);emit("referenceB",b,false,false);
  auto both=run(path,swap,reverse,false,false,2);ck(emit("overlap",both,false,false)=="BOTH","audible overlap control");
  for(bool same:{false,true})for(bool offB:{false,true}) {
   emit("first_off",run(path,swap,reverse,same,offB,3),same,offB);
   emit("same_frame_two_offs",run(path,swap,reverse,same,offB,4),same,offB);
  }
 }
 std::cout<<"P1_SUMMARY trials="<<trials<<" identifiedYounger="<<identified<<" unknown="<<unknown<<" contradicted="<<mismatch
          <<" doubleOffTrials="<<doubleOff<<" maxResidual="<<maxResidual<<" maxAbsentRatio="<<maxLeak
          <<" minRetainedRatio="<<minRetained<<" minReferenceSeparation="<<minSeparation
          <<" acceptedOns="<<ons<<" acceptedOffs="<<offs<<" voiceHandle=NOT_OBSERVED Android=UNRUN PSRE343=UNRUN\n";
 ck(!mismatch,"observed vendor oldest-release contract contradicted");
}

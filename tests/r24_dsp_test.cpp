#include "drum_punch.h"
#include <cassert>
#include <chrono>
#include <iostream>
#include <vector>
int main(){
 for(int key=0;key<128;++key)assert((drum_punch::group(key)>=0)==(key==35||key==36||key==38||key==40));
 drum_punch::OwnerQueue q;for(int i=0;i<128;++i)assert(q.push(i+1));assert(!q.push(9));for(int i=0;i<128;++i)assert(q.pop()==unsigned(i+1));assert(q.pop()==0);
 std::array<std::array<float,1024>,4> stems{};float out[1024];uint64_t oldClips=0,newClips=0;float maxDelta=0;
 for(int mode:{1,2}){
  drum_punch::Processor p;p.init(48000);p.hit(0);p.hit(1);
  for(int block=0;block<1000;++block){
   float baseline[1024];
   for(int i=0;i<1024;++i){out[i]=0.7f*std::sin(float(block*1024+i)*0.003f);
    for(int g=0;g<4;++g)stems[g][i]=0.18f*std::sin(float(i+block*5)*(0.01f+g*.02f));
    baseline[i]=out[i];for(auto& s:stems)baseline[i]+=s[i];}
   p.mix(out,stems,512,mode);
   for(int i=0;i<1024;++i){assert(drum_punch::finite(out[i]));assert(std::abs(out[i])<=std::max(1.f,std::abs(baseline[i])));oldClips+=std::abs(baseline[i])>1;newClips+=std::abs(out[i])>1;maxDelta=std::max(maxDelta,std::abs(out[i]-baseline[i]));}
  }
  std::cerr<<"mode="<<mode<<" guarded="<<p.guarded<<" maxExtra="<<p.maxExtra<<" frames="<<p.frames<<" oldClips="<<oldClips<<" newClips="<<newClips<<std::endl;
  assert(p.guarded>0 && p.maxExtra>0 && p.maxExtra<=.181f);
 }
 assert(newClips<=oldClips && maxDelta>0);
 // Full real-valued headroom: level and transient differ, hats/parent unaffected.
 for(auto& s:stems){s.fill(0);}
 stems[0].fill(.2f);
 drum_punch::Processor level,punch;level.init(48000);punch.init(48000);punch.hit(0);
 float a[1024]{},b[1024]{};level.budgetAlpha=punch.budgetAlpha=1;level.mix(a,stems,512,1);punch.mix(b,stems,512,2);
 assert(b[0]>a[0] && b[0]>b[1000]);
 // With no targets the non-target parent is bit-identical.
 for(auto& s:stems){s.fill(0);}
 for(int i=0;i<1024;++i){a[i]=b[i]=std::sin(i*.01f);}
 punch.mix(a,stems,512,2);for(int i=0;i<1024;++i)assert(a[i]==b[i]);
 // Nonfinite target never contaminates parent; retained in diagnostics.
 stems[0][0]=NAN;stems[1][1]=INFINITY;punch.mix(a,stems,512,2);assert(punch.bad==2);for(float f:a)assert(drum_punch::finite(f));
 std::vector<double> times;
 for(auto& s:stems){s.fill(.01f);}
 for(int mode:{1,2}){times.clear();drum_punch::Processor perf;perf.init(48000);
  for(int trial=0;trial<1000;++trial){for(auto& f:a){f=.1f;}auto t=std::chrono::steady_clock::now();perf.mix(a,stems,512,mode);times.push_back(std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-t).count());}
  std::sort(times.begin(),times.end());std::cout<<"DSP_TIMING mode="<<mode<<" frames=512 p50_us="<<times[500]<<" p99_us="<<times[990]<<" scope=host_DSP_only_not_four_BASS_decoders\n";
 }
 std::cout<<"R24 DSP PASS baselineClipped="<<oldClips<<" processedClipped="<<newClips<<" maxChange="<<maxDelta<<" synthetic_not_device\n";
}

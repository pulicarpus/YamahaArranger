#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
// Diagnostic-only source gain. This never scales the parent mix or limits output.
namespace drum_punch {
constexpr int blockFrames=512;
inline bool finite(float x){uint32_t bits;std::memcpy(&bits,&x,4);return (bits&0x7f800000u)!=0x7f800000u;}
inline int group(int key){return key==35||key==36?0:key==38||key==40?1:-1;}
struct OwnerQueue {
 std::array<uint32_t,128> stream{};unsigned head=0,size=0;
 bool push(uint32_t s){if(size==stream.size())return false;stream[(head+size++)%stream.size()]=s;return true;}
 uint32_t pop(){if(!size)return 0;auto s=stream[head];head=(head+1)%stream.size();--size;return s;}
};
struct Processor {
 float attack[4]{},decay=1,recovery=0,budgetAlpha=0;uint64_t frames=0,guarded=0,bad=0;double energy=0;float peak=0,maxExtra=0;
 void init(int rate){*this={};decay=std::exp(-1.f/(0.012f*std::max(1,rate)));recovery=1-std::exp(-1.f/(0.020f*std::max(1,rate)));}
 void hit(int lane){attack[lane]=1;}
 // Conservative experiment ceilings, not fixed applied gains: real PCM budget
 // chooses alpha each stereo frame. 12% level + 6% transient is tested before publication.
 void mix(float* parent,const std::array<std::array<float,1024>,4>& stems,int n,int mode){
  bool protectedFrame=false;
  for(int frame=0;frame<n;++frame){
   float base[2]{},delta[2]{},safeAlpha=1;
   for(int side=0;side<2;++side){int i=frame*2+side;float b=parent[i],d=0;
    for(int lane=0;lane<4;++lane){float s=stems[lane][i];if(!finite(s)){s=0;++bad;}
     b+=s;d+=s*(0.12f+(mode==2?0.06f*attack[lane]:0));}
    base[side]=b;delta[side]=d;
    if(!finite(b)||!finite(d)){safeAlpha=0;continue;}
    const float limit=std::max(0.98f,std::abs(b));
    if(d>0)safeAlpha=std::min(safeAlpha,std::max(0.f,(limit-b)/d));
    if(d<0)safeAlpha=std::min(safeAlpha,std::max(0.f,(-limit-b)/d));
   }
   // Instant restriction, 20ms recovery; one alpha for both stereo sides.
   budgetAlpha=safeAlpha<budgetAlpha?safeAlpha:budgetAlpha+recovery*(safeAlpha-budgetAlpha);
   protectedFrame=protectedFrame||safeAlpha<1;
   for(int side=0;side<2;++side){float y=base[side]+budgetAlpha*delta[side];
    // Roundoff fail-safe on the added source contribution; never clamp base.
    if(!finite(y)||std::abs(y)>std::max(1.f,std::abs(base[side])))y=base[side];
    parent[frame*2+side]=y;if(finite(y)){peak=std::max(peak,std::abs(y));energy+=double(y)*y;}
   }
   float transient=0;for(float a:attack)transient=std::max(transient,a);
   maxExtra=std::max(maxExtra,budgetAlpha*(0.12f+(mode==2?0.06f*transient:0)));
   for(auto& a:attack)a*=decay;
  }
  if(protectedFrame)++guarded;
  frames+=n;
 }
};
}

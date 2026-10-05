#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <algorithm>
// Read-only measurements, not calibration/gain/floors. Callback never writes PCM.
namespace role_pcm {
struct Level {
    uint64_t samples=0,activeSamples=0,blocks=0,activeBlocks=0,clipped=0,nonfinite=0;
    double energy=0;float peak=0;
    void consume(const float* data,unsigned count) {
        ++blocks;double blockEnergy=0;
        for(unsigned i=0;i<count;++i) {
            const float v=data[i];++samples;
            if(!std::isfinite(v)){++nonfinite;continue;}
            blockEnergy+=double(v)*v;peak=std::max(peak,std::abs(v));clipped+=(std::abs(v)>1);
        }
        energy+=blockEnergy;
        if(blockEnergy>0){++activeBlocks;activeSamples+=count;}
    }
    double rms(uint64_t denominator) const {return denominator?std::sqrt(energy/denominator):0;}
};
struct Input {
    uint64_t sent=0,rejected=0,other=0,sum=0;
    std::array<uint64_t,128> velocities{};
    int key=-1,velocity=-1,cc7Min=128,cc7Max=-1,cc11Min=128,cc11Max=-1;
    void control(int cc,int value) {
        if(cc==7){cc7Min=std::min(cc7Min,value);cc7Max=std::max(cc7Max,value);}
        if(cc==11){cc11Min=std::min(cc11Min,value);cc11Max=std::max(cc11Max,value);}
    }
    void note(int k,int v,bool accepted,bool style,int cc7,int cc11) {
        if(accepted){control(7,cc7);control(11,cc11);}
        if(!style){++other;return;}
        if(!accepted){++rejected;return;}
        ++sent;sum+=v;++velocities[std::clamp(v,0,127)];key=k;velocity=v;
    }
    int percentile(int percent) const {
        if(!sent)return -1;
        uint64_t seen=0,goal=(sent*percent+99)/100;
        for(int i=0;i<128;++i){seen+=velocities[i];if(seen>=goal)return i;}return 127;
    }
};
inline double db(double amplitude) {return amplitude>0?20*std::log10(amplitude):-INFINITY;}
}

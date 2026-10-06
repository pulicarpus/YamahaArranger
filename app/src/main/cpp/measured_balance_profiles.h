#pragma once
#include <string>
#include <cmath>

// Empirical accompaniment mix trims, not Yamaha-exact response calibration.
// Identity uses full content hash/source bank/PC; names/channels never select it.
// #790 device reports 20261006_164624 and _164656 establish the imbalance.
namespace measured_balance {
struct Profile { float db=0;const char* evidence="NONE"; };
inline Profile lookup(const std::string& fingerprint,int bank,int pc) {
    if(fingerprint=="e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82") {
        if(bank==0 && pc==0)return {-6,"DEVICE790_PIANO"};
        if(bank==8 && pc==1)return {-6,"DEVICE790_GUITAR"};
    }
    if(fingerprint=="de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce" && bank==0 && pc==49)
        return {12,"DEVICE790_SLOW_STRINGS"};
    return {};
}
inline float gain(float db) {return std::pow(10.0f,db/20.0f);}
struct Ramp {
    float current=1,target=1,step=0;unsigned remaining=0;
    // Control changes only at existing preset/resource boundaries. MIDI controls
    // never call this; therefore articulation/expression remain authored.
    void set(float value,bool immediate=false) {
        if(value==target)return;
        target=value;
        if(immediate){current=value;remaining=0;step=0;return;}
        remaining=960;step=(target-current)/remaining; // 20 ms at the fixed 48 kHz engine
    }
    void process(float* pcm,unsigned count) {
        for(unsigned i=0;i+1<count;i+=2) {
            if(remaining){current+=step;if(!--remaining)current=target;}
            pcm[i]*=current;pcm[i+1]*=current;
        }
    }
};
}

#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <sstream>
#ifndef YAMAHA_PCM_HEADROOM
#define YAMAHA_PCM_HEADROOM YAMAHA_ROLE_PCM_METERS
#endif
// Observers only. No buffers retained, allocations, SDK calls, or PCM writes in consume/finish.
namespace pcm_headroom {
struct Level {
    uint64_t n=0,over=0,bad=0;double energy=0;float peak=0;
    void consume(const float* p,unsigned count,bool clampModel=false) {
        n+=count;
        for(unsigned i=0;i<count;++i){float x=p[i];uint32_t bits;std::memcpy(&bits,&x,4);
            // Works with Android -ffast-math (std::isfinite can be optimized away).
            if((bits&0x7f800000u)==0x7f800000u){++bad;continue;}
            over+=std::abs(x)>1;
            if(clampModel)x=std::max(-1.f,std::min(1.f,x));
            energy+=double(x)*x;peak=std::max(peak,std::abs(x));
        }
    }
    void add(const Level& b){n+=b.n;over+=b.over;bad+=b.bad;energy+=b.energy;peak=std::max(peak,b.peak);}
    double rms()const{return n?std::sqrt(energy/n):0;}
};
struct Role {Level pre,post;bool trimmed=false,missingPre=false;float minGain=1,maxGain=1;};
struct Block {
    uint64_t decode=0,frame=0,generation=0;
    std::array<Role,6> roles{};Level parent,mix,clamp;std::array<Level,2> lanes{};
};
struct Meter {
    Block current,totals,worstMix;std::array<Block,6> worstRole{};
    uint64_t renders=0;
    void begin(uint64_t decode,uint64_t frame,uint64_t generation){current={};current.decode=decode;current.frame=frame;current.generation=generation;}
    void role(unsigned i,const Level& pre,const Level& post,bool trim,float low,float high){
        auto& r=current.roles[i];r.post=post;r.pre=trim?pre:post;r.trimmed=trim;
        r.missingPre=trim && pre.n!=post.n;r.minGain=trim?low:1;r.maxGain=trim?high:1;
    }
    void finish(const float* out,unsigned n){
        current.mix.consume(out,n);current.clamp.consume(out,n,true);++renders;
        totals.parent.add(current.parent);totals.mix.add(current.mix);totals.clamp.add(current.clamp);
        for(unsigned i=0;i<2;++i)totals.lanes[i].add(current.lanes[i]);
        for(unsigned i=0;i<6;++i){const auto& r=current.roles[i];auto& t=totals.roles[i];
            if(!t.post.n){t.minGain=r.minGain;t.maxGain=r.maxGain;}
            else {t.minGain=std::min(t.minGain,r.minGain);t.maxGain=std::max(t.maxGain,r.maxGain);}
            t.pre.add(r.pre);t.post.add(r.post);t.trimmed|=r.trimmed;t.missingPre|=r.missingPre;
            if(r.post.peak>worstRole[i].roles[i].post.peak)worstRole[i]=current;
        }
        if(current.mix.peak>worstMix.mix.peak)worstMix=current;
    }
    static void level(std::ostringstream& s,const Level& v){s<<" samples="<<v.n<<" rms="<<v.rms()<<" peak="<<v.peak<<" over="<<v.over<<" nonfinite="<<v.bad;}
    static void worst(std::ostringstream& s,const char* label,int ch,const Block& b){
        s<<"HEADROOM_WORST trigger="<<label<<" ch="<<ch<<" decode="<<b.decode<<" frame="<<b.frame<<" generation="<<b.generation
         <<" parentPeak="<<b.parent.peak<<" lane8Peak="<<b.lanes[0].peak<<" lane9Peak="<<b.lanes[1].peak<<" sumPeak="<<b.mix.peak<<" clampModelPeak="<<b.clamp.peak<<" rolesPrePost=";
        for(unsigned i=0;i<6;++i){if(i)s<<',';s<<i+10<<':'<<b.roles[i].pre.peak<<'/'<<b.roles[i].post.peak;}s<<'\n';
    }
    std::string report(float snapshotMaster,bool masterOk)const {
        std::ostringstream s;s<<"HEADROOM_SCOPE renders="<<renders<<" clock=successful_parent_decode_process_lifetime dry=pre_shared_FX stages=parent_post_FX,lanes,sum,existing_clamp_model clampModel=finite_input_only snapshotMaster="<<snapshotMaster<<" masterReadbackOK="<<masterOk<<" snapshotMaster_not_peak_time_value observations_only_no_gain_write\n";
        const char* names[]={"parent","lane8","lane9","sum","clamp_model"};
        const Level* levels[]={&totals.parent,&totals.lanes[0],&totals.lanes[1],&totals.mix,&totals.clamp};
        for(unsigned i=0;i<5;++i){s<<"HEADROOM_STAGE name="<<names[i];level(s,*levels[i]);s<<'\n';}
        for(unsigned i=0;i<6;++i){const auto& r=totals.roles[i];s<<"HEADROOM_ROLE ch="<<i+10<<" preSamples="<<r.pre.n<<" postSamples="<<r.post.n<<" preRms="<<r.pre.rms()<<" postRms="<<r.post.rms()<<" prePeak="<<r.pre.peak<<" postPeak="<<r.post.peak<<" preOver="<<r.pre.over<<" postOver="<<r.post.over<<" nonfinite="<<r.pre.bad+r.post.bad<<" gainRange="<<r.minGain<<':'<<r.maxGain<<" trimSeen="<<r.trimmed<<" missingPre="<<r.missingPre<<'\n';}
        worst(s,"sum",-1,worstMix);
        for(unsigned i=0;i<6;++i)if(worstRole[i].roles[i].post.peak>1)worst(s,"role",i+10,worstRole[i]);
        return s.str();
    }
};
}

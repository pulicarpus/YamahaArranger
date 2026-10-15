#pragma once
#include "pcm_headroom.h"
// Caller holds BassMidiPlayer::mutex_. No allocations, PCM writes or SDK calls in observe().
namespace pcm_session {
struct Counters {std::array<uint64_t,16> attempts{},sent{},styleVelocity{};};
struct Controls {int muteMask=0,knownMask=65535;uint64_t changes=0,lastChangeNs=0;};
struct Meter {
    uint64_t id=0,markFrame=0,markNs=0,worstFrame=0,worstGeneration=0,markGeneration=0;
    float peak=0;pcm_headroom::Block baseline{};Counters notes{};Controls controls{};
    std::array<int,16> reverb{},chorus{};
    void mark(uint64_t frame,uint64_t ns,uint64_t generation,const pcm_headroom::Block& totals,const Counters& counts,Controls state){
        ++id;markFrame=frame;markNs=ns;markGeneration=generation;baseline=totals;notes=counts;controls=state;
        peak=0;worstFrame=frame;worstGeneration=generation;
    }
    void observe(const pcm_headroom::Level& block,uint64_t frame,uint64_t generation){
        if(id && block.peak>peak){peak=block.peak;worstFrame=frame;worstGeneration=generation;}
    }
    static pcm_headroom::Level delta(const pcm_headroom::Level& now,const pcm_headroom::Level& then){
        pcm_headroom::Level d;d.n=now.n-then.n;d.over=now.over-then.over;d.bad=now.bad-then.bad;d.energy=std::max(0.,now.energy-then.energy);return d;
    }
    template<class T>static void array(std::ostringstream& out,const T& values){for(unsigned ch=0;ch<16;++ch){if(ch)out<<',';out<<values[ch];}}
    std::string report(const pcm_headroom::Block& totals,const Counters& now,Controls state,const std::array<int,16>& r,const std::array<int,16>& c,int rate,uint64_t generation,uint64_t atNs)const{
        std::ostringstream out;
        if(!id)return "PCM_SESSION status=UNMARKED action=MARK_PCM_SESSION counters=process_lifetime\n";
        const auto mix=delta(totals.mix,baseline.mix);uint64_t keys=0;
        out<<"=== PCM_SESSION v1 ===\nPCM_SESSION id="<<id<<" markFrame="<<markFrame<<" frames="<<mix.n/2<<" samples="<<mix.n
           <<" seconds="<<(rate>0?double(mix.n)/2/rate:0)<<" sampleRate="<<rate<<" peakSinceMark="<<peak<<" clippedSinceMark="<<mix.over<<" rms="<<mix.rms()<<" nonfinite="<<mix.bad
           <<" markMonotonicNs="<<markNs<<" snapshotMonotonicNs="<<atNs<<" wallSeconds="<<double(atNs-markNs)/1e9
           <<" worstFrame="<<worstFrame<<" worstGeneration="<<worstGeneration<<" worstFrameScope=absolute_render_block_start_before_output_clamp"
           <<" mappingGenerationAtMark="<<markGeneration<<" mappingGenerationNow="<<generation<<" scope=successful_parent_decode_sum_before_existing_output_clamp\n";
        std::array<uint64_t,16> attempts{},sent{},vel{};
        for(unsigned ch=0;ch<16;++ch){attempts[ch]=now.attempts[ch]-notes.attempts[ch];sent[ch]=now.sent[ch]-notes.sent[ch];vel[ch]=now.styleVelocity[ch]-notes.styleVelocity[ch];if(ch<4)keys+=sent[ch];}
        out<<"PCM_SESSION_NOTES channels=0..15 scope=internal_BASSMIDI_events_not_external_MIDI_OUT noteOnAttemptsDelta=";array(out,attempts);out<<" noteOnSentDelta=";array(out,sent);out<<" keyboardNoteOnDelta="<<keys<<" styleVelocityCountDelta=";array(out,vel);out<<'\n';
        out<<"PCM_SESSION_MUTE muteAtMarkMask="<<controls.muteMask<<" muteNowMask="<<state.muteMask<<" knownAtMarkMask="<<controls.knownMask<<" knownNowMask="<<state.knownMask
           <<" muteChangesSinceMark="<<state.changes-controls.changes<<" lastMuteChangeMonotonicNs="<<state.lastChangeNs<<" settledAtMarkSeconds="<<(markNs>=controls.lastChangeNs?double(markNs-controls.lastChangeNs)/1e9:0)<<" source=explicit_UI_and_style_override_state_not_CC7_heuristic\n";
        out<<"PCM_SESSION_MUTE_CHANNELS muteAtMark=";for(unsigned ch=0;ch<16;++ch)out<<((controls.muteMask>>ch)&1)<<',';out<<" muteNow=";for(unsigned ch=0;ch<16;++ch)out<<((state.muteMask>>ch)&1)<<',';out<<'\n';
        out<<"PCM_SESSION_FX reverbAtMark=";array(out,reverb);out<<" chorusAtMark=";array(out,chorus);out<<" reverbNow=";array(out,r);out<<" chorusNow=";array(out,c);out<<" fxSendNonZeroChannels=";
        for(unsigned ch=0;ch<16;++ch)if(r[ch]>0||c[ch]>0)out<<ch<<',';
        out<<" readback=CC91_CC93_only no_FX_tap no_drum_or_keyboard_GetChannel\n";
        const pcm_headroom::Level* a[]={&totals.parent,&totals.lanes[0],&totals.lanes[1],&totals.mix,&totals.clamp};
        const pcm_headroom::Level* b[]={&baseline.parent,&baseline.lanes[0],&baseline.lanes[1],&baseline.mix,&baseline.clamp};
        for(unsigned i=0;i<5;++i){const auto d=delta(*a[i],*b[i]);out<<"PCM_SESSION_STAGE index="<<i<<" samples="<<d.n<<" energy="<<d.energy<<" rms="<<d.rms()<<" clipped="<<d.over<<" nonfinite="<<d.bad<<'\n';}
        for(unsigned i=0;i<6;++i){auto pre=delta(totals.roles[i].pre,baseline.roles[i].pre),post=delta(totals.roles[i].post,baseline.roles[i].post);out<<"PCM_SESSION_ROLE ch="<<i+10<<" preSamples="<<pre.n<<" postSamples="<<post.n<<" preEnergy="<<pre.energy<<" postEnergy="<<post.energy<<" preRms="<<pre.rms()<<" postRms="<<post.rms()<<" preClipped="<<pre.over<<" postClipped="<<post.over<<'\n';}
        return out.str();
    }
};
}

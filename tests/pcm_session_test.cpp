#include "pcm_session.h"
#include <cassert>
#include <cstring>
#include <iostream>
int main(){
    pcm_headroom::Meter headroom;pcm_session::Meter session;pcm_session::Counters counters;pcm_session::Controls controls;
    float before[]={5,0,-4,0};headroom.begin(1,0,7);headroom.finish(before,4);
    session.mark(2,10000000000,7,headroom.totals,counters,controls);
    float after[]={.5f,-.25f,1.5f,-1.25f};float copy[4];std::memcpy(copy,after,sizeof after);
    headroom.begin(2,2,8);headroom.finish(after,4);session.observe(headroom.current.mix,2,8);
    assert(std::memcmp(copy,after,sizeof after)==0);assert(session.peak==1.5f && session.worstFrame==2 && session.worstGeneration==8);
    auto delta=session.delta(headroom.totals.mix,session.baseline.mix);assert(delta.n==4 && delta.over==2);assert(std::abs(delta.energy-4.125)<1e-12);
    counters.attempts[0]=2;counters.sent[0]=1;counters.attempts[9]=3;counters.sent[9]=3;
    std::array<int,16> fx{};const auto report=session.report(headroom.totals,counters,controls,fx,fx,48000,8,11000000000);
    assert(report.find("keyboardNoteOnDelta=1")!=std::string::npos && report.find("clippedSinceMark=2")!=std::string::npos);
    session.mark(4,12000000000,8,headroom.totals,counters,controls);assert(session.id==2 && session.peak==0);
    float quiet[]={.1f,-.1f};headroom.begin(3,4,9);headroom.finish(quiet,2);session.observe(headroom.current.mix,4,9);
    delta=session.delta(headroom.totals.mix,session.baseline.mix);assert(delta.over==0 && delta.n==2 && session.peak==.1f);
    controls.muteMask=1<<9;controls.changes=1;const auto second=session.report(headroom.totals,counters,controls,fx,fx,48000,9,13000000000);
    assert(second.find("keyboardNoteOnDelta=0")!=std::string::npos && second.find("muteChangesSinceMark=1")!=std::string::npos);
    assert(second.find("clippedSinceMark=0")!=std::string::npos);
    std::cout<<"PCM_SESSION G1 PASS reset/peak/clipping/RMS/frames/worst/notes/mute; input bytes unchanged\n";
}

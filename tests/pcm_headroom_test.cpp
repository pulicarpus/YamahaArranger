#include "pcm_headroom.h"
#include <cassert>
#include <limits>
#include <iostream>
int main(){
 pcm_headroom::Meter m;const float pre[]={2,-4,.5f,-.5f},post[]={1,-2,.25f,-.25f},lane[]={.5f,.5f,.5f,.5f},sum[]={1.5f,-1.5f,.75f,.25f};
 pcm_headroom::Level a,b;a.consume(pre,4);b.consume(post,4);
 m.begin(9,1024,7);m.role(1,a,b,true,.5f,.5f);m.current.parent.consume(post,4);m.current.lanes[0].consume(lane,4);m.finish(sum,4);
 assert(m.totals.roles[1].pre.peak==4 && m.totals.roles[1].post.peak==2);
 assert(m.totals.roles[1].pre.over==2 && m.totals.roles[1].post.over==1);
 assert(m.worstMix.decode==9 && m.worstMix.frame==1024 && m.worstMix.roles[1].pre.peak==4);
 assert(m.totals.mix.peak==1.5f && m.totals.clamp.peak==1 && m.totals.clamp.energy<m.totals.mix.energy);
 assert(pre[0]==2 && sum[0]==1.5f); // observation did not clamp or scale input
 m.begin(10,1026,8);m.finish(lane,4);assert(m.worstMix.decode==9 && m.current.roles[1].pre.n==0);
 const float bad[]={std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()};pcm_headroom::Level level;level.consume(bad,2);assert(level.bad==2 && level.energy==0);
 assert(m.report(1,true).find("HEADROOM_WORST trigger=role ch=11 decode=9")!=std::string::npos);
 std::cout<<"HEADROOM_UNIT PASS pre_post_gain stage_summing clamp_model same_decode reset_nonfinite_fastmath read_only\n";
}

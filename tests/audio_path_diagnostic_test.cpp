#include "audio_path_diagnostic.h"
#include "chord_change_diagnostic.h"
#include <iostream>
#include <cstdlib>
static int checks=0;
static void check(bool ok,const char* what) { ++checks; if(!ok){std::cerr<<what<<'\n';std::exit(1);} }
int main() {
    audio_path::Channel c;
    c.on(60,40,true,100,0,1000);
    c.on(60,70,true,100,90,1020);
    c.on(61,100,false,100,90,1030);
    check(c.stats.attempts==3&&c.stats.sent==2&&c.stats.rejected==1,"actual attempts vs accepted events");
    check(c.stats.ccZero==1&&c.stats.velocityMin==40&&c.stats.velocityMax==100,"controller/velocity evidence");
    check(c.off(60,true,1040)==40,"NOTEOFF1-style oldest instance diagnostic duration");
    check(c.off(60,true,1150)==130,"second instance duration preserved");
    check(c.stats.shortOffs==1&&c.pending()==0,"short strings durations counted without changing events");
    check(c.off(61,true,1160)==-1&&c.stats.orphanOffs==1,"rejected on cannot appear active");
    c.on(38,80,true,127,127,1200,true); c.on(40,30,false,0,127,1210,true);
    check(c.stats.snare38==1&&c.stats.snare38Sent==1&&c.stats.snare40==1&&c.stats.snare40Sent==0,"snare attempts vs actual send");
    c.on(38,80,true,127,127,1200,false);
    check(c.stats.snare38==1,"Bass note38 must not count as snare");
    audio_path::Channel budget;
    for(int i=0;i<4;++i) check(budget.sample(1000,false),"first bounded note sample");
    check(!budget.sample(1001,false),"normal log budget enforced");
    for(int i=0;i<4;++i) check(budget.sample(1002,true),"independent snare budget");
    check(!budget.sample(1003,true)&&budget.sample(3000,false),"budget resets at two seconds");
    check(!budget.summaryDue(1000)&&!budget.summaryDue(2999)&&budget.summaryDue(3000),"summary interval");
    c.resetSummary(); check(c.stats.attempts==0&&c.pending()==2,"summary reset retains observation ledger");
    c.allOff(); check(c.pending()==0,"all notes off observation clears pending only");
    for(int i=0;i<50;++i) c.on(50,80,true,127,127,4000+i);
    check(c.pending()==16&&c.stats.ledgerOverflow==34,"diagnostic ledger bounded for one-shot drums");
    chord_diagnostic::Capture capture;
    check(!capture.active(1),"chord capture defaults off");
    capture.arm(100);
    check(capture.active(59999999999ULL) && !capture.active(60000000100ULL),"capture expires at sixty seconds");
    chord_diagnostic::Row row; row.mono=101; row.origin={5,60,1029,10,42,false,41,1};
    for(size_t n=0;n<chord_diagnostic::Capture::cap+2;++n) capture.append(row);
    check(capture.rows.size()==4096 && capture.dropped==2 && capture.rows.front().captureOrder==1,
          "bounded capture retains original event order and explicitly counts overflow");
    check(capture.rows.back().origin.id==42 && capture.rows.back().origin.chordId==41,"event and chord identities survive capture");
    capture.stop(); capture.append(row);
    check(capture.rows.size()==4096 && !capture.active(102),"stop retains evidence and prevents additional rows");
    capture.arm(200);
    check(capture.rows.empty() && capture.dropped==0,"explicit rearm clears only diagnostic evidence");
    row.mono=60000000200ULL; capture.append(row);
    check(capture.rows.empty(),"late record is rejected after deadline");
    std::cout<<"PASS: "<<checks<<" audio-path diagnostic checks (observation only)\n";
}


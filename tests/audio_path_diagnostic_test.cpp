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
    chord_diagnostic::Capture window; window.arm(1);
    chord_diagnostic::Row before; before.channel=11; before.stage="NOTE_POST"; before.mono=100000000;
    window.append(before);
    check(window.rows.empty() && window.recentPiano.size()==1,"ordinary ch11 notes stay in short pending context, not permanent capture");
    check(!window.interested(9,{},false,100000001),"unrelated drum notes are filtered before readback");
    window.mark(800,200000000);
    check(window.rows.size()==1 && window.rows[0].windowChordId==800,"mark flushes preceding250ms ch11 evidence under its window ID");
    before.mono=250000000; window.append(before);
    check(window.rows.size()==2,"nearby scheduled Piano is captured after change");
    before.mono=1200000000;window.append(before);
    check(window.rows.size()==2 && window.recentPiano.size()==1,"late ordinary Piano notes are not retained in report");
    window.mark(801,1600000000);
    check(window.rows.size()==2,"stale preceding context is excluded from next change");
    check(window.interested(13,AudioPathOrigin{13,60,1029,0,10,false,801,1},false,1600000001),"all actual retarget destinations remain eligible");
    chord_diagnostic::Capture large;large.arm(1);
    chord_diagnostic::Row huge;huge.stage="NOTE_POST";huge.channel=13;huge.mappingMatch=1;
    huge.liveName=std::string(100,'a');huge.origin.chordId=9;huge.origin.operation=1;huge.mono=2;
    for(size_t n=0;n<4000;++n)large.append(huge);
    huge.channel=11;huge.liveName="ConcertGrand";huge.origin.id=999;large.append(huge);
    const auto compact=chord_diagnostic::compactReport(large);
    check(compact.size()<=30*1024 && compact.find("exportOmittedRows=0 ")==std::string::npos,"compact native report hard byte cap exposes omissions");
    check(compact.find("id=999")<compact.find("dst=13"),"ch11 survives report overflow ahead of other parts");
    check(compact.find("family=PIANO")!=std::string::npos && compact.find("...")!=std::string::npos,"readback family and explicit name truncation remain available");
    chord_diagnostic::Capture ledger;ledger.arm(1);ledger.mark(50,2);
    chord_diagnostic::Row piano; piano.channel=11;piano.key=65;piano.mono=1000000;piano.stage="NOTE_POST";piano.sent=1;piano.origin.id=10;
    ledger.append(piano);piano.mono=4000000;piano.origin.id=11;piano.origin.operation=1;ledger.append(piano);
    check(ledger.rows.back().keyBefore==1 && ledger.rows.back().keyAfter==2 && ledger.rows.back().previousOnId==10 && ledger.rows.back().previousOnAgeMs==3,
          "distinct accepted normal/retarget ons to one key expose overlap and elapsed time");
    check(!ledger.rows.back().sameEventRepeat,"different event identities must not be labelled duplicate event");
    piano.mono=5000000;ledger.append(piano);
    check(ledger.rows.back().sameEventRepeat==1,"same key/event accepted twice is explicitly flagged, without suppressing send");
    piano.stage="OFF_POST";piano.sent=0;piano.mono=6000000;ledger.append(piano);
    check(ledger.rows.back().keyBefore==3 && ledger.rows.back().keyAfter==3,"rejected off does not remove accepted MIDI request");
    piano.sent=1;piano.mono=7000000;ledger.append(piano);
    check(ledger.rows.back().keyAfter==2 && ledger.rows.back().oldestOnAgeMs==6,"accepted off updates FIFO observer only");
    chord_diagnostic::Capture pair;pair.arm(1);pair.mark(90,2);
    piano.stage="NOTE_PRE";piano.mono=3;piano.sent=-1;piano.liveName="Strings";piano.livePc=49;piano.mappingMatch=1;piano.origin.chordId=90;
    pair.append(piano);piano.stage="NOTE_POST";piano.mono=4;piano.sent=1;piano.liveName="ConcertGrand";piano.livePc=0;piano.mappingMatch=0;pair.append(piano);
    auto paired=chord_diagnostic::compactReport(pair);
    check(paired.find("b=1 a=2")!=std::string::npos && paired.find("S s=1")!=std::string::npos && paired.find("S s=2")!=std::string::npos,
          "state dictionary preserves changed PRE/POST independently in one whole event unit");
    pair.rows[1].origin.id=999;
    auto unmatched=chord_diagnostic::compactReport(pair);
    check(unmatched.find("b=-1")!=std::string::npos && unmatched.find("NOTE_PRE/POST dst=")==std::string::npos,
          "different identities never form a synthetic paired event");
    chord_diagnostic::Capture contextFlood;contextFlood.arm(1);contextFlood.mark(91,2);
    piano.stage="NOTE_POST";piano.origin.operation=0;piano.liveName="ConcertGrand";piano.livePc=0;piano.mappingMatch=1;piano.mono=3;
    for(int n=0;n<1000;++n) {piano.origin.id=n;contextFlood.append(piano);}
    piano.origin.operation=1;piano.origin.chordId=91;piano.origin.id=99999;contextFlood.append(piano);
    auto focused=chord_diagnostic::compactReport(contextFlood);
    check(focused.find("id=99999")<focused.find("id=0 "),"late actual ch11 retarget has priority above earlier normal Piano context");
    check(focused.size()<=30*1024 && focused.find("not voice/PCM counts")!=std::string::npos,"hard report cap and MIDI-ledger limitation are explicit");
    std::cout<<"PASS: "<<checks<<" audio-path diagnostic checks (observation only)\n";
}

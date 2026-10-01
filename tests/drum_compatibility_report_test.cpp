#include "drum_compatibility_report.h"
#include <cstdlib>
#include <fstream>
#include <iostream>
static int checks=0;
static void check(bool ok,const char* why) { ++checks; if(!ok) { std::cerr<<"FAIL "<<why<<'\n';std::exit(1); } }
static sf2_zones::Zone zone(int key,int low,int high,int sample) {
    sf2_zones::Zone z; z.keyLow=z.keyHigh=key; z.velLow=low; z.velHigh=high;
    z.sampleId=sample; z.sample="Sample "+std::to_string(sample);z.instrument="Synthetic";
    z.start=10;z.end=100;z.sampleRate=44100;z.presetBag=2;z.instrumentBag=4;return z;
}
int main(int argc,char** argv) {
    sf2_zones::Inventory inv;inv.valid=true;inv.reason="ok";
    for(int pc=0;pc<21;++pc) {
        inv.presetNames[{128,pc}]="Synthetic Kit "+std::to_string(pc);
        inv.presets[{128,pc}]={zone(60,1,127,pc)};
        if(pc) inv.presets[{128,pc}].push_back(zone(31,110,110,100+pc));
        if(pc==2) { inv.presets[{128,pc}].push_back(zone(21,42,127,201));inv.presets[{128,pc}].push_back(zone(31,100,120,202)); }
    }
    inv.presets[{127,2}]={zone(21,28,28,301)};inv.presetNames[{127,2}]="Other bank same PC";
    std::vector<drum_compat::Demand> demand={{0,9,21,28,16},{0,9,21,42,16},{0,9,31,110,8},{0,8,60,26,3},{1,9,31,109,1},{1,9,31,111,1}};
    drum_compat::Live live;live.channel=9;live.initialized=live.verified=live.inCandidateSource=true;
    live.requestBank=128;live.requestPc=73;live.effectivePc=live.pc=0;live.bank=128;live.reason="requested_PC_absent_existing_first_available";
    live.source="Synthetic Drum.sf2";live.name="Synthetic Kit 0";
    const auto report=drum_compat::report(inv,live.source,demand,{live});
    check(report.size()<=drum_compat::REPORT_BYTES,"native bound leaves 8KiB for Kotlin");
    check(report.find("kits=22")!=std::string::npos,"21 kits plus same-PC other bank are distinct");
    check(report.find("nativeRequestBank=128 requestPC=73 effectivePC=0 actualKnown=1 actualBank=128 actualPC=0")!=std::string::npos,"requested vs actual preserved");
    check(report.find("fallbackReason=requested_PC_absent_existing_first_available")!=std::string::npos,"fallback explanation is explicit");
    check(report.find("KIT bank=128 PC=0 name='Synthetic Kit 0' metadataKnown=1 coveredHits=3/45")!=std::string::npos,"weighted actual key and velocity demand");
    check(report.find("LIVE_COVERAGE ch=9 coveredHits=0/42 unknownHits=0")!=std::string::npos,"active channel coverage uses its bins only");
    check(report.find("MATCH bank=128 PC=2 ch=9 key=21 vel=28 activeGap=1 status=MISSING_ZONE")!=std::string::npos,"21 low velocity has no layer");
    check(report.find("MATCH bank=128 PC=2 ch=9 key=21 vel=42 activeGap=1 status=COVERED")!=std::string::npos,"21 high velocity has layer");
    check(report.find("MATCH bank=128 PC=2 ch=9 key=31 vel=110 activeGap=1 status=COVERED eligibleLayers=2")!=std::string::npos,"layered note counts once, all layers exposed");
    check(report.find("MATCH bank=128 PC=1 ch=9 key=31 vel=109 activeGap=1 status=MISSING_ZONE")!=std::string::npos,"velocity lower boundary");
    check(report.find("MATCH bank=128 PC=1 ch=9 key=31 vel=111 activeGap=1 status=MISSING_ZONE")!=std::string::npos,"velocity upper boundary");
    check(report.find("MATCH bank=127 PC=2 ch=9 key=21 vel=28 activeGap=1 status=COVERED")!=std::string::npos,"source banks are not merged");
    check(report.find("sampleId=202 sample='Sample 202'")!=std::string::npos && report.find("bags=2:4")!=std::string::npos,"candidate sample and bag identity exposed");
    check(report.find("SECTION id=1 ch=9 key=31 vel=109 count=1")!=std::string::npos,"fill section demand retained");
    check(report.find("musicalIdentity=UNKNOWN")!=std::string::npos && report.find("no_candidate_recommendation")!=std::string::npos,"coverage is not musical endorsement");
    check(report.find("NOT_runtime_sent_counts")!=std::string::npos && report.find("BASS_voice_sample_ID=unavailable")!=std::string::npos,"raw source and layer evidence limits");
    auto duplicate=demand;duplicate.push_back({0,8,60,26,4});
    check(drum_compat::report(inv,"x",duplicate,{}).find("SECTION id=0 ch=8 key=60 vel=26 count=7")!=std::string::npos,"duplicate bins canonicalized across parts");
    auto invalid=demand;invalid.push_back({0,9,31,0,9});invalid.push_back({0,11,60,100,1});invalid.push_back({-1,9,31,1,1});
    check(drum_compat::report(inv,"x",invalid,{}).find("invalidInputRows=3")!=std::string::npos,"zero velocity/melody/invalid section never counted");
    auto unknown=inv;unknown.valid=false;unknown.reason="invalid_pdta";
    const auto u=drum_compat::report(unknown,"x",demand,{live});
    check(u.find("coverage=UNKNOWN (not zero)")!=std::string::npos && u.find("coveredHits=UNKNOWN/45")!=std::string::npos,"invalid metadata does not claim missing/zero coverage");
    check(u.find("status=MISSING_ZONE")==std::string::npos && u.find("ZONE bank=")==std::string::npos,"unknown metadata is not trusted for samples");
    auto notVerified=live;notVerified.verified=false;
    check(drum_compat::report(inv,"x",demand,{notVerified}).find("unknownHits=42")!=std::string::npos,"actual preset getter failure remains unknown");
    // Ordinary arbitrary key demonstrates the diagnostic is independent of historical 21/31 regression examples.
    auto arbitrary=inv;arbitrary.presets[{128,0}].push_back(zone(7,17,17,888));
    check(drum_compat::report(arbitrary,"x",{{0,8,7,17,1}},{}).find("MATCH bank=128 PC=0 ch=8 key=7 vel=17 activeGap=0 status=COVERED")!=std::string::npos,"arbitrary actual key/velocity, no hardcoded rule");
    const auto same=drum_compat::report(inv,live.source,demand,{live});
    check(same==report,"deterministic output, no inventory mutation");
    auto rename=inv;rename.presetNames[{128,0}]="Best Yamaha Snare kit";
    const auto named=drum_compat::report(rename,"x",demand,{});
    check(named.find("coveredHits=3/45")!=std::string::npos && named.find("musicalIdentity=UNKNOWN")!=std::string::npos,"preset name cannot confer coverage or semantic validity");
    std::vector<drum_compat::Demand> large;
    for(int s=0;s<100;++s) for(int k=0;k<128;++k) large.push_back({s,9,k,64,1});
    const auto big=drum_compat::report(inv,std::string(50000,'x'),large,{live});
    check(big.size()<=drum_compat::REPORT_BYTES,"large all-section export hard bounded");
    check(big.find("BLOCK demand omittedRows=0")==std::string::npos && big.find("BLOCK zones omittedRows=0")==std::string::npos,"bounded sections report omitted evidence separately");
    check(big.find("KIT bank=128 PC=20")!=std::string::npos && big.find("BLOCK kits omittedRows=0")!=std::string::npos,"all 21 kit summaries survive a large demand histogram");
    check(big.back()=='\n' && big.find("BLOCK zones omittedRows=")!=std::string::npos,"final omission footer retained, whole records only");
    check(drum_compat::label("bad\nname\x1e'\xff")=="bad?name???","binary names and record framing sanitized");
    auto hugeCounts=demand;hugeCounts.push_back({0,9,21,28,2147483647});hugeCounts.push_back({0,9,21,28,2147483647});
    check(drum_compat::report(inv,"x",hugeCounts,{}).find("key=21 vel=28 count=4294967310")!=std::string::npos,"hit aggregation cannot overflow int32");
    if(argc==2) {std::ofstream out(argv[1]);out<<"EXAMPLE ONLY — SYNTHETIC FIXTURE, NOT ANDROID RUNTIME\n"<<report;}
    std::cout<<"PASS "<<checks<<" compact drum export checks (metadata only)\n";
}

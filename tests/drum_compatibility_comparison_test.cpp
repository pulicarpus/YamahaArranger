#include "drum_compatibility_comparison.h"
#include <fstream>
#include <iostream>
#include <cstdlib>
static int checks=0;
static void check(bool ok,const char* why){++checks;if(!ok){std::cerr<<"FAIL "<<why<<'\n';std::exit(1);}}
static sf2_zones::Zone zone(int key,int lo,int hi,int sample) {
    sf2_zones::Zone z;z.keyLow=z.keyHigh=key;z.velLow=lo;z.velHigh=hi;z.sampleId=sample;z.sample="Opaque "+std::to_string(sample);z.instrument="Unknown";z.end=100;z.sampleRate=44100;return z;
}
int main(int argc,char** argv) {
    sf2_zones::Inventory inv;inv.valid=true;inv.reason="test";
    inv.presets[{128,3}]={zone(70,1,127,1)};
    inv.presets[{128,5}]={zone(7,1,40,11),zone(7,41,127,12),zone(7,41,127,12),zone(70,1,127,1)};
    inv.presets[{127,5}]={zone(7,1,127,99)};
    inv.presetNames[{128,5}]="Best snare ever";
    drum_compat::Live live;live.channel=9;live.verified=live.initialized=live.inCandidateSource=true;live.bank=128;live.pc=3;live.requestPc=73;
    std::vector<drum_compat::Demand> demand={{0,9,7,28,61},{1,9,7,42,34},{0,9,70,110,48},{0,8,60,42,2}};
    const std::vector<std::pair<int,int>> kits={{128,5},{128,3},{127,5}};
    const auto report=drum_compat::comparisonReport(inv,"test",demand,{live},kits);
    check(report.size()<=40960,"native bound");
    check(report.find("KEY ch=9 key=7 hits=95 activeMissingHits=95")<report.find("KEY ch=9 key=70 hits=48"),"high-hit active gaps before lower demand or covered keys");
    check(report.find("MAP bank=128 PC=5 ch=9 key=7 status=COVERED hits=61 velCounts=28x61, eligibleLayers=1 zones=0, uniqueSamples=11,")!=std::string::npos,"low velocity distinct layer mapping retained");
    check(report.find("MAP bank=128 PC=5 ch=9 key=7 status=COVERED hits=34 velCounts=42x34, eligibleLayers=2 zones=1,2, uniqueSamples=12,")!=std::string::npos,"two zone entries with same sample remain visible without duplicate hit counts");
    check(report.find("MAP bank=127 PC=5 ch=9 key=7 status=COVERED")!=std::string::npos,"bank collision is separate");
    check(report.find("sampleId=12 sample='Opaque 12'")!=std::string::npos && report.find("pgen=")!=std::string::npos && report.find("modsKnown=")!=std::string::npos,"zone/sample/generator evidence");
    check(report.find("musicalIdentity=UNKNOWN")!=std::string::npos && report.find("PCM=not_measured")!=std::string::npos,"no identity inference from name/coverage");
    check(report.find("gainedVsActual=95 lostVsActual=0")!=std::string::npos,"coverage delta against actual preset, not arbitrary candidate");
    const auto absent=drum_compat::comparisonReport(inv,"test",demand,{live},{{128,99}});
    check(absent.find("status=UNKNOWN")!=std::string::npos && absent.find("status=MISSING_ZONE")==std::string::npos,"absent candidate is unknown, not missing sample");
    auto unknown=inv;unknown.valid=false;
    check(drum_compat::comparisonReport(unknown,"test",demand,{live},kits).find("status=COVERED")==std::string::npos,"invalid metadata not trusted");
    auto noLive=live;noLive.verified=false;
    check(drum_compat::comparisonReport(inv,"test",demand,{noLive},kits).find("activeUnknownHits=95")!=std::string::npos,"live getter missing not invented gap");
    check(drum_compat::comparisonReport(inv,"test",demand,{live},{}).find("unavailable")!=std::string::npos,"empty candidate input explicit");
    check(drum_compat::comparisonReport(inv,"test",demand,{live},{{128,128}}).find("invalid")!=std::string::npos,"invalid PC input");
    check(drum_compat::comparisonReport(inv,"test",demand,{live},{{128,1},{128,2},{128,3},{128,4},{128,5}}).find("unavailable")!=std::string::npos,"candidate-count export limit");
    auto reversed=kits;std::reverse(reversed.begin(),reversed.end());
    check(drum_compat::comparisonReport(inv,"test",demand,{live},reversed)==report,"candidate order cannot change diagnostic results");
    auto repeated=kits;repeated.push_back(kits[0]);
    check(drum_compat::comparisonReport(inv,"test",demand,{live},repeated)==report,"duplicate candidate no extra zones or count");
    check(drum_compat::comparisonReport(inv,"test",{{0,9,7,0,1},{0,11,60,1,1}},{live},kits).find("invalidRows=2")!=std::string::npos,"invalid note inputs excluded");
    check(drum_compat::comparisonReport(inv,"test",{}, {live},kits).find("no_compatibility_claim")!=std::string::npos,"empty demand not compatibility proof");
    check(drum_compat::comparisonReport(inv,"test",demand,{live},kits)==report,"inventory immutable and output repeatable");
    check(argc>=2,"captured histogram fixture required");
    std::ifstream in(argv[1]);std::string line;std::vector<drum_compat::Demand> captured;std::set<int> keys;
    while(std::getline(in,line)) {
        if(line.empty() || line[0]=='#' || line[0]=='c')continue;
        std::replace(line.begin(),line.end(),',',' ');std::istringstream fields(line);int ch,key,vel,count;
        if(!(fields>>ch>>key>>vel>>count))check(false,"fixture CSV row invalid");
        captured.push_back({0,ch,key,vel,count});keys.insert(key);
    }
    check(captured.size()==216,"entire216-bin runtime source histogram reproduced");
    // Synthetic eligibility fixtures match counters only, NOT actual SF2 sample/timbre evidence.
    sf2_zones::Inventory fixture;fixture.valid=true;fixture.reason="synthetic_not_device_samples";
    for(int pc:{0,1,24})for(const auto key:keys) {
        if(pc==0 && std::set<int>{13,15,16,17,18,19,21,22,31}.count(key))continue;
        if(pc==1 && std::set<int>{18,19,21,22}.count(key))continue;
        if(pc==24 && std::set<int>{21,22}.count(key))continue;
        fixture.presets[{128,pc}].push_back(zone(key,1,127,key));
    }
    live.pc=0;const auto focused=drum_compat::comparisonReport(fixture,"SYNTHETIC",captured,{live},{{128,0},{128,1},{128,24}});
    check(focused.find("COVERAGE bank=128 PC=0 ch=9 coveredHits=300/539 missingHits=239")!=std::string::npos,"actual demand fixture reproduces PC0 counter shape");
    check(focused.find("COVERAGE bank=128 PC=1 ch=9 coveredHits=484/539 missingHits=55")!=std::string::npos,"synthetic candidate1 shape");
    check(focused.find("COVERAGE bank=128 PC=24 ch=9 coveredHits=491/539 missingHits=48")!=std::string::npos,"synthetic candidate24 shape");
    check(focused.find("KEY ch=9 key=16 hits=95")<focused.find("KEY ch=9 key=31 hits=48"),"largest real gap first");
    check(focused.find("KEY ch=9 key=31 hits=48")<focused.find("KEY ch=9 key=21 hits=47"),"frequency priority not key ascending");
    check(focused.find("BLOCK mapping omittedRows=0")!=std::string::npos && focused.find("BLOCK zones omittedRows=0")!=std::string::npos,"all real216-bin shapes retained for three compact candidates");
    auto large=fixture;for(auto& k:large.presets)for(auto& z:k.second){z.sample=std::string(10000,'x');z.presetGenerators.mods.resize(500);}
    const auto bounded=drum_compat::comparisonReport(large,"x",captured,{live},{{128,0},{128,1},{128,24}});
    check(bounded.size()<=40960 && bounded.find("BLOCK zones omittedRows=0")==std::string::npos,"heavy mod detail truncation explicit and bounded");
    check(bounded.find("BLOCK mapping omittedRows=0")!=std::string::npos,"zone dump cannot consume mapping budget");
    if(argc==3){std::ofstream out(argv[2]);out<<"SYNTHETIC ZONES / CAPTURED SOURCE DEMAND — NOT DEVICE SAMPLE EVIDENCE\n"<<focused;}
    std::cout<<"PASS "<<checks<<" focused drum comparison checks (metadata only)\n";
}

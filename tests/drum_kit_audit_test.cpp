#include "drum_kit_audit.h"
#include <cstdlib>
#include <iostream>
static int checks=0;
static void check(bool ok,const char* why) { ++checks; if(!ok) { std::cerr<<"FAIL "<<why<<'\n';std::exit(1); } }
int main() {
    sf2_zones::Inventory inventory; inventory.valid=true;
    const std::vector<drum_audit::Hit> hits={{8,54,110,8},{8,75,57,6},{8,80,54,27},{8,81,60,12},
        {8,82,28,32},{8,82,65,32},{9,21,28,16},{9,21,42,16},{9,31,110,8},
        {9,33,59,8},{9,33,94,8},{9,51,31,34},{9,53,34,6}};
    for(int pc=0;pc<21;++pc) {
        inventory.presetNames[{128,pc}]="Kit "+std::to_string(pc);
        auto& zones=inventory.presets[{128,pc}];
        for(int key:{21,31,33,51,53,54,75,80,81,82}) {
            if(pc==0 && (key==21||key==31)) continue;
            sf2_zones::Zone z; z.keyLow=z.keyHigh=key; z.instrument="Instrument"; z.sample="Sample";
            if(pc==2 && key==21) z.velLow=40;
            zones.push_back(z);
            if(pc==1) zones.push_back(z); // Layered zones cannot double hit coverage.
        }
    }
    const auto original=inventory.presets;
    const auto report=drum_audit::report(inventory,hits);
    check(report.find("presets=21")!=std::string::npos,"all21 loaded drum presets reported");
    check(report.find("rank=1 name='Kit 1' bank=128 PC=1 coveredHits=213/213 coveredUniqueKeys=10/10")!=std::string::npos,"rank weighted hits, not preset order");
    check(report.find("PC=0 coveredHits=173/213 coveredUniqueKeys=8/10 missingImportantKeys=[21,31]")!=std::string::npos,"missing MainD click/snare coverage proven without remap");
    check(report.find("PC=2 coveredHits=197/213 coveredUniqueKeys=9/10 missingImportantKeys=[21]")!=std::string::npos,"velocity holes counted using actual hits");
    check(report.find("partialKeys=[21]")!=std::string::npos,"partially covered key cannot pass fully covered key count");
    check(report.find("PC=2 | ch=9 | key=21 | velocity=28 | hits=16 | matchingZones=0")!=std::string::npos,"exact uncovered velocity exported");
    check(report.find("PC=2 | ch=9 | key=21 | velocity=42 | hits=16 | matchingZones=1")!=std::string::npos,"exact covered velocity exported");
    check(report.find("PC=1 | ch=9 | key=31 | velocity=110 | hits=8 | matchingZones=2")!=std::string::npos,"multiple sample layers exposed");
    check(report.find("samples=Instrument/Sample[key=31-31,vel=0-127,frames=0,type=0]")!=std::string::npos,"sample names and ranges exported");
    check(inventory.presets.size()==original.size() && inventory.presets.at({128,0}).size()==original.at({128,0}).size(),"audit does not mutate metadata");
    check(drum_audit::report(inventory,{}).find("unavailable")!=std::string::npos,"no style data cannot rank kits");
    check(drum_audit::report(inventory,{{9,31,0,1}}).find("invalid")!=std::string::npos,"velocity zero is not NOTE_ON coverage");
    check(drum_audit::report(inventory,{{11,31,110,1}}).find("invalid")!=std::string::npos,"melody excluded from drum audit");
    inventory.valid=false;
    check(drum_audit::report(inventory,hits).find("unavailable")!=std::string::npos,"invalid metadata is unknown, not missing coverage");
    std::cout<<"PASS "<<checks<<" all-kit coverage checks (metadata only)\n";
}

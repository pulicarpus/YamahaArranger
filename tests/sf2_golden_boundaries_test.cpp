// Synthetic boundary audit, compiled against the unchanged checkpoint player.
#include "bassmidi_player.h"
#include "sf2_rich_fixture.h"
#include "measured_balance_profiles.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <cstdlib>
#include <cmath>
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static unsigned checks=0;
static void check(bool ok,const char* text) {++checks;if(!ok){std::cerr<<text<<'\n';std::exit(1);}}
struct Preset {const char* name;int bank,pc;};
static std::string font(const std::string& dir,const char* filename,std::initializer_list<Preset> presets) {
    using namespace rich_fixture;
    Bytes ph(38*(presets.size()+1)),pdta={'p','d','t','a'};size_t i=0;
    for(const auto& p:presets){std::memcpy(ph.data()+38*i,p.name,std::strlen(p.name));word(ph,38*i+20,p.pc);word(ph,38*i+22,p.bank);++i;}
    std::memcpy(ph.data()+38*i,"EOP",3);chunk(pdta,"phdr",ph);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    const auto path=dir+'/'+filename;std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
int main(int argc,char**argv) {
    check(argc==2,"fixture directory required");const std::string dir=argv[1];BassMidiPlayer p;
    check(p.loadMelody(font(dir,"Yamaha synthetic melody.sf2",{{"Grand Piano",0,0},{"Flute",126,73}})),"melody load");
    check(p.loadDrum(font(dir,"synthetic missing kit.sf2",{{"Other Kit",128,7},{"Default Kit",127,0}})),"drum load");
    p.setChannelPreset(8,128,73,"PopDrumKit");
    check(mock_bass::preloadBank==128 && mock_bass::preloadProgram==7,"PC73 missing falls back to first phdr drum entry, NOT sorted lowest PC");
    check(BASS_MIDI_StreamGetEvent(1,8,MIDI_EVENT_PROGRAM)==7,"effective destination PC is fallback");
    p.setChannelPreset(10,126,73,"Flute");
    check(mock_bass::preloadBank==1,"raw126 normalized independently of drums");
    p.setChannelPreset(10,126*128,73,"Flute");
    int before=mock_bass::noteOns;p.noteOn(10,60,.5f);check(mock_bass::noteOns==before,"MIDI MSB126 rejected for melody");
    check(p.loadDrum(font(dir,"synthetic exact kit.sf2",{{"Default Kit",128,0},{"Exact Kit",128,73}})),"exact kit load");
    p.setChannelPreset(9,128,73,"PopDrumKit");
    check(mock_bass::preloadProgram==73,"newly available PC73 changes fallback decision");
    check(p.loadDrum(font(dir,"synthetic collision.sf2",{{"Bank128 Kit",128,0},{"Bank127 Kit",127,0}})),"conflicting bank load");
    p.setChannelPreset(8,128,0,"Standard Kit");
    check(mock_bass::preloadBank==128,"cache follows phdr source order");
    p.noteOn(8,36,.5f);BASS_MIDI_FONT live{};
    check(BASS_MIDI_StreamGetPreset(1,8,&live) && live.bank==127,"generic FONTEX2 priority is 127 before128; readback must be captured separately");
    const std::string melody="e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82";
    const std::string tyros="de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce";
    check(measured_balance::lookup(melody,0,0).db==-6,"piano trim");
    check(measured_balance::lookup(melody,8,1).db==-6,"guitar trim");
    check(measured_balance::lookup(tyros,0,49).db==12,"strings trim");
    check(std::string(measured_balance::lookup("new-master-hash",0,49).evidence)=="NONE","changed hash loses trim evidence");
    check(std::abs(measured_balance::gain(-6)-std::pow(10.f,-6.f/20))<1e-6,"dB conversion");
    std::cout<<"GOLDEN_BOUNDARIES checks="<<checks<<" synthetic=true mock_BASS=true PC73_bank126_127_128_gain_collision=true runtime_UNVERIFIED\n";
}

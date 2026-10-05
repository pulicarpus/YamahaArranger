#include "bassmidi_player.h"
#include "sf2_rich_fixture.h"
#include <jni.h>
#include <fstream>
#include <iostream>
#include <cstdlib>
#include <set>

JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool ok,const char* message) { ++checks;if(!ok) {std::cerr<<"FAIL: "<<message<<'\n';std::exit(1);} }
struct Preset {const char* name;int bank,pc;};
static std::string font(const std::string& dir,const char* filename,std::initializer_list<Preset> presets) {
    using namespace rich_fixture;
    Bytes ph(38*(presets.size()+1)),pdta={'p','d','t','a'};size_t i=0;
    for(const auto& p:presets) {std::memcpy(ph.data()+38*i,p.name,std::strlen(p.name));word(ph,38*i+20,p.pc);word(ph,38*i+22,p.bank);++i;}
    std::memcpy(ph.data()+38*i,"EOP",3);chunk(pdta,"phdr",ph);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    const auto path=dir+'/'+filename;std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
static std::vector<unsigned char> read(const std::string& path) {
    std::ifstream f(path,std::ios::binary);return {(std::istreambuf_iterator<char>(f)),std::istreambuf_iterator<char>()};
}
static BASS_MIDI_FONTEX2 mapping(int ch) {
    const auto count=BASS_MIDI_StreamGetFonts(1,nullptr,0);std::vector<BASS_MIDI_FONTEX2> table(count);
    check(BASS_MIDI_StreamGetFonts(1,table.data(),count|BASS_MIDI_FONT_EX2)==count,"native FONTEX2 table readback succeeds");
    for(const auto& m:table)if(m.minchan==ch && m.numchan==1)return m;
    return {};
}
int main(int argc,char**argv) {
    check(argc==2,"fixture directory supplied");const std::string dir=argv[1];
    const auto primary=font(dir,"MELODI YAMAHA bank contract.sf2",{
        {"Yamaha ConcertGrand",0,0},{"Drawbar Organ",0,17},{"BASS",8,17},{"Steel Guitar",8,1},
        {"Accordion",62,21},{"Lead",63,80},{"Flute",126,73}});
    const auto secondary=font(dir,"yamaha tyros bank contract.sf2",{{"t4 strings slow",0,49}});
    const auto original=read(primary);
    BassMidiPlayer p;check(p.loadMelody(primary),"production primary loader succeeds");
    const auto normalized=read(primary+".bassmidi-normalized.sf2");
    check(normalized.size()==original.size(),"normalization preserves SF2 size");
    // This fixture's phdr is at offset32; only its bank fields may be rewritten.
    std::set<size_t> bankBytes;for(size_t i=0;i<7;++i) {bankBytes.insert(32+38*i+22);bankBytes.insert(32+38*i+23);}
    bool changed=false;for(size_t i=0;i<original.size();++i)if(original[i]!=normalized[i]) {changed=true;check(bankBytes.count(i)==1,"normalization changes bank fields only");}
    check(changed && read(primary)==original,"original file untouched; normalized file separate");
    const int rawBanks[]={0,0,8,8,62,63,126},virtualBanks[]={0,0,1,1,2,3,4};
    for(size_t i=0;i<7;++i) {
        const size_t pos=32+38*i+22;
        check((original[pos]|original[pos+1]<<8)==rawBanks[i] && (normalized[pos]|normalized[pos+1]<<8)==virtualBanks[i],"all source banks retain distinct normalized identities");
    }
    check(p.loadMelodyFallback(secondary),"production secondary loader succeeds");
    for(int ch:{10,12,13,14}) {
        const int request=ch==10?1028:ch==12?1034:1029;
        const int pc=ch==10?17:ch==12?1:49,sourceBank=(ch==10||ch==12)?1:0;
        p.setChannelPreset(ch,request,pc,ch==10?"Bass":ch==12?"A.Guitar":ch==13?"Strings1":"Strings2");
        const auto m=mapping(ch);
        check(m.font && m.sbank==sourceBank && m.spreset==pc && m.dbank==request/128 && m.dbanklsb==request%128 && m.dpreset==pc,"selected source and preserved MIDI destination occupy different FONTEX2 fields");
        check(BASS_MIDI_StreamGetEvent(1,ch,MIDI_EVENT_BANK)==static_cast<DWORD>(request/128) && BASS_MIDI_StreamGetEvent(1,ch,MIDI_EVENT_BANK_LSB)==static_cast<DWORD>(request%128),"MIDI bank controllers remain requested destination, not normalized source bank");
        const bool primaryRole=ch==10||ch==12;
        check(mock_bass::fonts[m.font].find(primaryRole?"MELODI":"tyros")!=std::string::npos,"bank numbers are scoped to the selected font, not shared across SF2");
        const int before=mock_bass::noteOns;p.noteOn(ch,60,55/127.f,AudioPathOrigin{ch,60,request,1,1,false});
        check(mock_bass::noteOns==before+1,"real production player dispatches one admitted note");
        BASS_MIDI_FONT live{};
        check(BASS_MIDI_StreamGetPreset(1,ch,&live) && live.font==m.font && live.bank==sourceBank && live.preset==pc,"preset readback is loaded normalized-font source identity");
        check(BASS_MIDI_FontGetPreset(live.font,pc,sourceBank)!=nullptr,"readback source exists in loaded font");
        if(primaryRole)check(BASS_MIDI_FontGetPreset(live.font,pc,8)==nullptr,"raw bank8 is absent from normalized loaded font; forcing it would be invalid");
        const auto history=mock_bass::history;const auto report=p.noteZoneReport();
        check(mock_bass::history==history,"readback/report writes no MIDI state");
        const auto at=report.find("NATIVE ch="+std::to_string(ch)+" ");check(at!=std::string::npos,"existing report observes the actual production route");
        const auto row=report.substr(at,report.find('\n',at)-at);
        check(row.find("selectedRawBank="+std::to_string(primaryRole?8:0)+" selectedPC="+std::to_string(pc))!=std::string::npos && row.find("liveBank="+std::to_string(sourceBank)+" livePC="+std::to_string(pc))!=std::string::npos,"selected raw bank8/live bank1 are compatible namespaces, not mismatch");
        p.noteOff(ch,60);check(mock_bass::events[{ch,MIDI_EVENT_NOTE}]==60,"unchanged production NOTE_OFF reaches destination channel/key");
    }
    std::cout<<"BANK_TRANSLATION checks="<<checks<<" production_player=true mapping_error=false mock_API_scope=true no_PCM_claim=true\n";
}

#include "sf2_zone_diagnostic.h"
#include <cstdlib>
#include <iostream>
using Bytes=std::vector<unsigned char>;
static int checks=0;
static void check(bool ok,const char* why) { ++checks; if(!ok) { std::cerr<<"FAIL "<<why<<'\n'; std::exit(1); } }
static void word(Bytes& b,size_t p,int v) { b[p]=v&255; b[p+1]=(v>>8)&255; }
static void dword(Bytes& b,size_t p,int v) { word(b,p,v); word(b,p+2,v>>16); }
static void chunk(Bytes& out,const char* id,Bytes bytes) {
    const size_t pos=out.size(); out.resize(pos+8); std::memcpy(out.data()+pos,id,4); dword(out,pos+4,bytes.size());
    out.insert(out.end(),bytes.begin(),bytes.end()); if(bytes.size()&1) out.push_back(0);
}
static Bytes fixture(int key=21,int vel=42,int sample=0) {
    Bytes pdta={'p','d','t','a'};
    Bytes ph(76); std::memcpy(ph.data(),"Yamaha Kit",10); word(ph,20,0);word(ph,22,128); word(ph,38+24,2);chunk(pdta,"phdr",ph);
    Bytes pb(12);word(pb,4,1);word(pb,8,4);chunk(pdta,"pbag",pb);
    // Preset global key range 10-30; local range overrides it to 15-40.
    Bytes pg(20);word(pg,0,43);word(pg,2,10|(30<<8));word(pg,4,43);word(pg,6,15|(40<<8));
    word(pg,8,44);word(pg,10,20|(80<<8));word(pg,12,41);chunk(pdta,"pgen",pg);
    Bytes inst(44);std::memcpy(inst.data(),"Extended Snare",14);word(inst,22+20,3);chunk(pdta,"inst",inst);
    Bytes ib(16);word(ib,4,2);word(ib,8,4);word(ib,12,7);chunk(pdta,"ibag",ib);
    // Instrument global ranges; local key 21 and velocity 40-60 override globals.
    Bytes ig(32);word(ig,0,43);word(ig,2,0|(20<<8));word(ig,4,44);word(ig,6,0|(30<<8));
    word(ig,8,43);word(ig,10,key|(key<<8));word(ig,12,53);word(ig,14,sample);
    word(ig,16,43);word(ig,18,33|(33<<8));word(ig,20,44);word(ig,22,40|(60<<8));word(ig,24,53);
    chunk(pdta,"igen",ig);
    Bytes sh(92);std::memcpy(sh.data(),"XG Snare Sample",15);dword(sh,24,100);word(sh,44,1);chunk(pdta,"shdr",sh);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    (void)vel; return out;
}
int main() {
    const auto data=fixture();const auto inventory=sf2_zones::parse(data);
    check(inventory.valid,"full metadata accepted");
    check(inventory.presets.size()==1,"dedicated drum preset only");
    check(inventory.presetNames.at({128,0})=="Yamaha Kit","actual phdr preset name cached for all-kit export");
    check(!sf2_zones::match(inventory,128,73,21,25).known,"absent PC73 is unknown, not invented coverage");
    auto m=sf2_zones::match(inventory,128,0,21,25);
    check(m.known && m.zones==1,"extended key21 eligible at style velocity25");
    check(m.names.find("XG Snare Sample")!=std::string::npos,"actual sample name exposed without GM assumption");
    check(m.names.find("frames=100")!=std::string::npos,"sample header length evidence");
    check(sf2_zones::match(inventory,128,0,21,42).zones==0,"instrument global velocity20-30 inherited and intersected with preset20-80");
    check(sf2_zones::match(inventory,128,0,33,42).zones==1,"local velocity40-60 overrides own global0-30");
    check(sf2_zones::match(inventory,128,0,33,42).zones==1,"preset local key15-40 overrides own global10-30");
    check(sf2_zones::match(inventory,128,0,33,81).zones==0,"preset velocity upper bound respected");
    check(sf2_zones::match(inventory,128,0,38,60).known && sf2_zones::match(inventory,128,0,38,60).zones==0,"GM snare38 is not manufactured");
    check(!sf2_zones::parse(fixture(21,42,99)).valid,"invalid sample link fails closed to unknown");
    check(!sf2_zones::parse(fixture(200)).valid,"invalid key range fails closed");
    Bytes cut=data;cut.pop_back();check(!sf2_zones::parse(cut).valid,"truncated file unknown");
    Bytes empty;check(!sf2_zones::parse(empty).valid,"missing metadata unknown");
    Bytes bad=data;bad[8]='x';check(!sf2_zones::parse(bad).valid,"non-SF2 unknown");
    Bytes named=data;std::memcpy(named.data()+32,"CLOW D'ACADEMY",std::strlen("CLOW D'ACADEMY"));
    check(sf2_zones::parse(named).presetNames.at({128,0})=="CLOW D'ACADEMY","preset apostrophe preserved in Inspector inventory");
    std::cout<<"PASS "<<checks<<" SF2 zone metadata checks\n";
}

#pragma once
#include <vector>
#include <cstring>
#include <cstdint>
namespace rich_fixture {
using Bytes=std::vector<unsigned char>;
inline void word(Bytes& b,size_t p,int v) { b[p]=v&255; b[p+1]=(v>>8)&255; }
inline void dword(Bytes& b,size_t p,uint32_t v) { word(b,p,v&65535); word(b,p+2,v>>16); }
inline void chunk(Bytes& out,const char* id,Bytes data) {
    const auto pos=out.size();out.resize(pos+8);std::memcpy(out.data()+pos,id,4);dword(out,pos+4,data.size());
    out.insert(out.end(),data.begin(),data.end());if(data.size()&1)out.push_back(0);
}
inline void gen(Bytes& b,int op,int amount) { auto n=b.size();b.resize(n+4);word(b,n,op);word(b,n+2,amount); }
inline Bytes font(int bank=0,int pc=49,const char* preset="t4 strings slow") {
    Bytes pdta={'p','d','t','a'},ph(76),pb(12),pg,inst(44),ib(16),ig,sh(138);
    std::memcpy(ph.data(),preset,std::strlen(preset));word(ph,20,pc);word(ph,22,bank);word(ph,62,2);
    // Preset global attack offset+100; local overrides+200, attenuation+20cb.
    gen(pg,34,100);gen(pg,34,200);gen(pg,48,20);gen(pg,41,0);
    word(pb,4,1);word(pb,8,4);
    Bytes pmod(20);word(pmod,0,2);word(pmod,2,48);word(pmod,4,60);word(pb,6,1);word(pb,10,1);
    std::memcpy(inst.data(),"Slow layer",10);word(inst,42,3);
    // Instrument global ADSR + attenuation100; local first overrides attenuation200.
    gen(ig,34,-2400);gen(ig,35,-1200);gen(ig,36,1200);gen(ig,37,100);gen(ig,38,0);gen(ig,48,100);
    gen(ig,48,200);gen(ig,43,52|(84<<8));gen(ig,44,0|(40<<8));gen(ig,53,0);
    gen(ig,43,52|(84<<8));gen(ig,44,41|(127<<8));gen(ig,53,1);
    word(ib,4,6);word(ib,8,10);word(ib,12,13);
    // Identical local mod replaces global even with amount0; preset mods remain additive evidence.
    Bytes imod(30);word(imod,0,2);word(imod,2,48);word(imod,4,960);
    word(imod,10,2);word(imod,12,48);word(imod,14,0);
    word(ib,6,1);word(ib,10,2);word(ib,14,2);
    for(int sample=0;sample<2;++sample) {
        const auto pos=sample*46; const char* name=sample?"Loud sample":"Quiet sample";
        std::memcpy(sh.data()+pos,name,std::strlen(name));dword(sh,pos+20,sample*100);dword(sh,pos+24,sample*100+100);
        dword(sh,pos+28,sample*100+20);dword(sh,pos+32,sample*100+80);dword(sh,pos+36,44100);sh[pos+40]=60;sh[pos+41]=uint8_t(-2);word(sh,pos+44,1);
    }
    chunk(pdta,"phdr",ph);chunk(pdta,"pbag",pb);chunk(pdta,"pmod",pmod);chunk(pdta,"pgen",pg);
    chunk(pdta,"inst",inst);chunk(pdta,"ibag",ib);chunk(pdta,"imod",imod);chunk(pdta,"igen",ig);chunk(pdta,"shdr",sh);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'};chunk(out,"LIST",pdta);dword(out,4,out.size()-8);return out;
}
}

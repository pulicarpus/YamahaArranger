#pragma once
#include "sf2_rich_fixture.h"
#include <fstream>
#include <cmath>
namespace percussion_fixture {
inline std::string write(const std::string& dir,const char* file,int bank,int pc,bool opaque=false,bool badPitch=false) {
    using namespace rich_fixture;
    const char* labels[]={"Acoustic Kick","Tight Snare","Close HiHat","Pedal HiHat","Open HiHat","Tambourine","Cabasa","Mute Triangle","Open Triangle","Small Shaker","claves"};
    const int keys[]={36,40,42,44,46,54,69,80,81,82,58};
    const int count=opaque?1:12; // two velocity layers on the shaker
    Bytes pdta={'p','d','t','a'},ph(76),pb(8),pg,inst(44),ib(4*(count+1)),ig,sh(46*(count+1));
    const char* name=bank==0?"Acoustic Guitar":opaque?"Opaque kit":"Compatible kit";
    std::memcpy(ph.data(),name,std::strlen(name));word(ph,20,pc);word(ph,22,bank);word(ph,62,1);std::memcpy(ph.data()+38,"EOP",3);
    gen(pg,41,0);word(pb,4,1);std::memcpy(inst.data(),"Acoustic drum hits",18);word(inst,42,count);std::memcpy(inst.data()+22,"EOI",3);
    for(int i=0;i<count;++i) {
        const int which=std::min(i,10);const int key=opaque?60:keys[which];const char* label=opaque?"opaque sample":labels[which];
        // Last two zones are shaker velocity layers; cross-key claves is at 58.
        const int mappedKey=!opaque && i>=10?(i==10?58:82):key;
        const char* sampleLabel=!opaque && i==11?"Small Shaker":label;
        word(ib,4*i,ig.size()/4);gen(ig,43,opaque?(0|(127<<8)):(mappedKey|(mappedKey<<8)));
        gen(ig,58,badPitch?mappedKey+1:mappedKey);
        if(!opaque && (i==9 || i==11))gen(ig,44,i==9?(0|(63<<8)):(64|(127<<8)));
        if(!opaque && (mappedKey==42 || mappedKey==44 || mappedKey==46))gen(ig,57,1);
        if(!opaque && (mappedKey==80 || mappedKey==81))gen(ig,57,2);
        gen(ig,53,i);
        const int pos=i*46;std::memcpy(sh.data()+pos,sampleLabel,std::strlen(sampleLabel));
        dword(sh,pos+20,i*4096);dword(sh,pos+24,i*4096+4000);dword(sh,pos+28,i*4096+100);dword(sh,pos+32,i*4096+3900);
        dword(sh,pos+36,48000);sh[pos+40]=60;word(sh,pos+44,1);
    }
    gen(pg,0,0);
    word(ib,4*count,ig.size()/4);gen(ig,0,0);std::memcpy(sh.data()+46*count,"EOS",3);
    chunk(pdta,"phdr",ph);chunk(pdta,"pbag",pb);chunk(pdta,"pmod",Bytes(10));chunk(pdta,"pgen",pg);
    chunk(pdta,"inst",inst);chunk(pdta,"ibag",ib);chunk(pdta,"imod",Bytes(10));chunk(pdta,"igen",ig);chunk(pdta,"shdr",sh);
    Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'},info={'I','N','F','O'},sdta={'s','d','t','a'};
    Bytes version(4);word(version,0,2);word(version,2,1);chunk(info,"ifil",version);chunk(info,"isng",Bytes{'E','M','U','8','0','0','0',0});chunk(info,"INAM",Bytes{'t','e','s','t',0,0});chunk(out,"LIST",info);
    Bytes pcm(count*4096*2+92);for(int i=0;i<count*4096;++i) word(pcm,i*2,int(6000*std::sin(i*0.05)));chunk(sdta,"smpl",pcm);chunk(out,"LIST",sdta);chunk(out,"LIST",pdta);dword(out,4,out.size()-8);
    const std::string path=dir+"/"+file;std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
}

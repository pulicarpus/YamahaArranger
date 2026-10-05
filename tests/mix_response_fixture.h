#pragma once
#include "sf2_rich_fixture.h"
#include <fstream>
#include <cmath>
// Synthetic, identical PCM under different exported generator configurations.
namespace mix_response_fixture {
inline std::string write(const std::string& dir,int attenuation,int fixedVelocity) {
    using namespace rich_fixture;
    Bytes ph(76),pb(8),pg,ins(44),ib(8),ig,sh(92),pd={'p','d','t','a'};
    std::memcpy(ph.data(),"Response probe",14);word(ph,20,24);word(ph,62,1);std::memcpy(ph.data()+38,"EOP",3);
    if(attenuation)gen(pg,48,attenuation);gen(pg,41,0);word(pb,4,pg.size()/4);gen(pg,0,0);
    std::memcpy(ins.data(),"Response probe",14);word(ins,42,1);std::memcpy(ins.data()+22,"EOI",3);
    gen(ig,58,60);if(fixedVelocity>=0)gen(ig,47,fixedVelocity);gen(ig,53,0);word(ib,4,ig.size()/4);gen(ig,0,0);
    std::memcpy(sh.data(),"Same PCM",8);dword(sh,24,4000);dword(sh,28,100);dword(sh,32,3900);dword(sh,36,48000);sh[40]=60;word(sh,44,1);std::memcpy(sh.data()+46,"EOS",3);
    chunk(pd,"phdr",ph);chunk(pd,"pbag",pb);chunk(pd,"pmod",Bytes(10));chunk(pd,"pgen",pg);
    chunk(pd,"inst",ins);chunk(pd,"ibag",ib);chunk(pd,"imod",Bytes(10));chunk(pd,"igen",ig);chunk(pd,"shdr",sh);
    Bytes info={'I','N','F','O'},ver(4),sd={'s','d','t','a'},pcm(8092),out={'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver,0,2);word(ver,2,1);chunk(info,"ifil",ver);chunk(info,"isng",Bytes{'E','M','U','8','0','0','0',0});chunk(info,"INAM",Bytes{'p','r','o','b','e',0});
    for(int i=0;i<4000;++i)word(pcm,i*2,int(6000*std::sin(i*0.05)));
    chunk(sd,"smpl",pcm);chunk(out,"LIST",info);chunk(out,"LIST",sd);chunk(out,"LIST",pd);dword(out,4,out.size()-8);
    auto path=dir+"/response-"+std::to_string(attenuation)+"-"+std::to_string(fixedVelocity)+".sf2";
    std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(out.data()),out.size());return path;
}
}

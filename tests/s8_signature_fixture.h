#pragma once
// Test-only sustained, velocity-selected orthogonal PCM signatures.
#include "sf2_rich_fixture.h"
#include <cmath>
#include <fstream>
namespace s8_fixture {
inline std::string write(const std::string& dir,int release) {
 using namespace rich_fixture;
 Bytes ph(76),pb(8),pg,inst(44),ib(12),ig,sh(138),pcm(2*8192*2+92);
 std::memcpy(ph.data(),"S8 signatures",13);word(ph,20,24);word(ph,62,1);std::memcpy(ph.data()+38,"EOP",3);
 gen(pg,41,0);word(pb,4,1);std::memcpy(inst.data(),"Two signatures",14);word(inst,42,2);std::memcpy(inst.data()+22,"EOI",3);
 for(int i=0;i<2;++i){
  word(ib,4*i,ig.size()/4);gen(ig,43,60|(60<<8));gen(ig,44,i?(64|(127<<8)):(1|(63<<8)));
  gen(ig,34,-12000);gen(ig,36,-12000);gen(ig,37,0);gen(ig,38,release);gen(ig,54,1);gen(ig,58,60);gen(ig,53,i);
  auto pos=i*46;std::memcpy(sh.data()+pos,i?"B 1125 Hz":"A 375 Hz",9);
  dword(sh,pos+20,i*8192);dword(sh,pos+24,i*8192+8192);dword(sh,pos+28,i*8192+128);dword(sh,pos+32,i*8192+8064);
  dword(sh,pos+36,48000);sh[pos+40]=60;word(sh,pos+44,1);
  for(int j=0;j<8192;++j)word(pcm,(i*8192+j)*2,int(5000*std::sin(2*3.141592653589793*(i?1125:375)*j/48000)));
 }
 word(ib,8,ig.size()/4);gen(ig,0,0);std::memcpy(sh.data()+92,"EOS",3);
 Bytes pd={'p','d','t','a'};chunk(pd,"phdr",ph);chunk(pd,"pbag",pb);chunk(pd,"pmod",Bytes(10));chunk(pd,"pgen",pg);chunk(pd,"inst",inst);chunk(pd,"ibag",ib);chunk(pd,"imod",Bytes(10));chunk(pd,"igen",ig);chunk(pd,"shdr",sh);
 Bytes out={'R','I','F','F',0,0,0,0,'s','f','b','k'},info={'I','N','F','O'},sd={'s','d','t','a'},v(4);word(v,0,2);word(v,2,1);chunk(info,"ifil",v);chunk(info,"isng",Bytes{'E','M','U','8','0','0','0',0});chunk(out,"LIST",info);chunk(sd,"smpl",pcm);chunk(out,"LIST",sd);chunk(out,"LIST",pd);dword(out,4,out.size()-8);
 auto path=dir+"/s8-"+std::to_string(release)+".sf2";std::ofstream f(path,std::ios::binary);f.write((char*)out.data(),out.size());return path;
}
}

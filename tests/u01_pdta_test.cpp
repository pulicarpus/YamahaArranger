#include "sf2_pdta_fingerprint.h"
#include <cassert>
#include <iostream>
using Bytes=std::vector<unsigned char>;
static void u32(Bytes& b,uint32_t n){for(int i=0;i<4;++i)b.push_back(static_cast<unsigned char>(n>>(8*i)));}
static Bytes chunk(const char* tag,const Bytes& body){Bytes b(tag,tag+4);u32(b,body.size());b.insert(b.end(),body.begin(),body.end());if(body.size()&1)b.push_back(0);return b;}
static void add(Bytes& a,const Bytes& b){a.insert(a.end(),b.begin(),b.end());}
static Bytes tables(){Bytes b;const char* ids[]={"phdr","pbag","pmod","pgen","inst","ibag","imod","igen","shdr"};const size_t widths[]={38,4,10,4,22,4,10,4,46};for(int i=0;i<9;++i)add(b,chunk(ids[i],Bytes(widths[i],0)));return b;}
static Bytes sf2(Bytes payload,const Bytes& samples={1,2,3}){Bytes list{'p','d','t','a'};add(list,payload);Bytes body{'s','f','b','k'};Bytes sdta{'s','d','t','a'};add(sdta,chunk("smpl",samples));add(body,chunk("LIST",sdta));add(body,chunk("LIST",list));return chunk("RIFF",body);}
int main(){
    const auto payload=tables(),valid=sf2(payload);auto result=sf2_pdta::fingerprint(valid);
    assert(result.valid && result.sha256==sf2_fingerprint::sha256(Bytes(valid.end()-static_cast<std::ptrdiff_t>(payload.size()+4),valid.end())) && result.bytes==payload.size()+4);
    assert(sf2_pdta::fingerprint(sf2(payload,{9,8,7,6})).sha256==result.sha256);
    auto changed=payload;changed[8]^=1;assert(sf2_pdta::fingerprint(sf2(changed)).sha256!=result.sha256);
    for(size_t n=0;n<valid.size();++n){Bytes b(valid.begin(),valid.begin()+n);assert(!sf2_pdta::fingerprint(b).valid);}
    auto bad=valid;bad[4]=0xff;assert(!sf2_pdta::fingerprint(bad).valid);
    bad=valid;bad[0]='X';assert(!sf2_pdta::fingerprint(bad).valid);
    bad=payload;bad[0]='X';assert(!sf2_pdta::fingerprint(sf2(bad)).valid);
    bad=payload;bad[4]=0xff;bad[5]=0xff;bad[6]=0xff;bad[7]=0xff;assert(!sf2_pdta::fingerprint(sf2(bad)).valid);
    bad=payload;bad.push_back(0);assert(!sf2_pdta::fingerprint(sf2(bad)).valid);
    Bytes list{'p','d','t','a'};add(list,payload);Bytes duplicate{'s','f','b','k'};add(duplicate,chunk("LIST",list));add(duplicate,chunk("LIST",list));assert(!sf2_pdta::fingerprint(chunk("RIFF",duplicate)).valid);
    assert(!sf2_pdta::fingerprint(sf2(Bytes(33*1024*1024,0))).valid);
    const std::string abc="abc";assert(sf2_fingerprint::sha256(Bytes(abc.begin(),abc.end()))=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    for(size_t i=0;i<valid.size();++i){bad=valid;bad[i]^=0xff;auto r=sf2_pdta::fingerprint(bad);assert(!r.valid || (r.sha256.size()==64 && r.bytes<=32*1024*1024));}
    std::cout<<"U1 PDTA PASS: exact domain, sample exclusion, padding, every truncation/byte mutation, duplicate/order/extent/limit checks\n";
}

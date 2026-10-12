#pragma once
#include "s8_signature_fixture.h"
#include <filesystem>
namespace voice_release_fixture {
using namespace rich_fixture;
inline std::string write(const std::string& dir, const std::string& filename, int frequency, int release=0, bool oneShot=false) {
    const auto original = s8_fixture::write(dir, release);
    std::ifstream input(original, std::ios::binary);
    Bytes data((std::istreambuf_iterator<char>(input)), {});
    Bytes out(data.begin(), data.begin()+12);
    for (size_t pos=12; pos+8<=data.size();) {
        const uint32_t size = uint32_t(data[pos+4])|(uint32_t(data[pos+5])<<8)|
            (uint32_t(data[pos+6])<<16)|(uint32_t(data[pos+7])<<24);
        Bytes payload(data.begin()+pos+8, data.begin()+pos+8+size);
        if (std::memcmp(data.data()+pos,"LIST",4)==0 && std::memcmp(payload.data(),"pdta",4)==0) {
            Bytes pd={'p','d','t','a'}, ph(6*38), pb(6*4), pg;
            const int banks[]={0,1025,127,128,65535};
            const int pcs[]={24,24,1,0,127};
            const char* names[]={"Acoustic Guitar","Variation","Alternate Kit","Drum Kit","High bank"};
            for (int i=0;i<5;++i) {
                std::memcpy(ph.data()+i*38,names[i],std::strlen(names[i]));
                word(ph,i*38+20,pcs[i]);word(ph,i*38+22,banks[i]);word(ph,i*38+24,i);
                word(pb,i*4,i);gen(pg,41,0);
            }
            std::memcpy(ph.data()+5*38,"EOP",3);word(ph,5*38+24,5);word(pb,5*4,5);gen(pg,0,0);
            for (size_t at=4; at+8<=payload.size();) {
                const uint32_t n=uint32_t(payload[at+4])|(uint32_t(payload[at+5])<<8)|
                    (uint32_t(payload[at+6])<<16)|(uint32_t(payload[at+7])<<24);
                Bytes bytes(payload.begin()+at+8,payload.begin()+at+8+n);
                if (std::memcmp(payload.data()+at,"phdr",4)==0) bytes=ph;
                if (std::memcmp(payload.data()+at,"pbag",4)==0) bytes=pb;
                if (std::memcmp(payload.data()+at,"pgen",4)==0) bytes=pg;
                if (std::memcmp(payload.data()+at,"igen",4)==0)
                    for(size_t op=0;op+4<=bytes.size();op+=4) {
                        const int generator=bytes[op]|(bytes[op+1]<<8);
                        if(generator==43)word(bytes,op+2,127<<8); // test LEFT in the real split range, too
                        if(oneShot && generator==54)word(bytes,op+2,0);
                    }
                chunk(pd,reinterpret_cast<const char*>(payload.data()+at),bytes);
                at+=8+n+(n&1);
            }
            payload=pd;
        } else if (std::memcmp(data.data()+pos,"LIST",4)==0 && std::memcmp(payload.data(),"sdta",4)==0) {
            // Distinct source PCM even when the preset name/bank/program match.
            for(int i=0;i<2;++i) for(int j=0;j<8192;++j)
                word(payload,12+(i*8192+j)*2,int(5000*std::sin(2*3.141592653589793*frequency*j/48000)));
        }
        chunk(out,reinterpret_cast<const char*>(data.data()+pos),payload);
        pos+=8+size+(size&1);
    }
    dword(out,4,out.size()-8);
    const auto path=dir+"/"+filename;
    std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<const char*>(out.data()),out.size());
    return path;
}
}

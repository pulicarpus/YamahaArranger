#pragma once
#include "sf2_fingerprint.h"
#include <chrono>
#include <cstring>
#include <set>

// v1 domain: the exact bytes inside LIST/pdta AFTER its four-byte list type,
// including subchunk headers and their padding; excludes LIST header/type/pad.
// Not a hash of text metadata, sample PCM, a normalized copy, or a filename.
namespace sf2_pdta {
struct Result {
    bool valid=false;
    std::string sha256, reason="missing_pdta";
    size_t bytes=0;
    uint64_t elapsedNs=0;
};
inline Result fingerprint(const std::vector<unsigned char>& data) {
    Result r;
    const auto start=std::chrono::steady_clock::now();
    auto finish=[&](const char* reason) {
        r.reason=reason;r.elapsedNs=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count());return r;
    };
    auto tag=[&](size_t at,const char* id){return at<=data.size() && data.size()-at>=4 && std::memcmp(data.data()+at,id,4)==0;};
    auto u32=[&](size_t at){return uint32_t(data[at])|(uint32_t(data[at+1])<<8)|(uint32_t(data[at+2])<<16)|(uint32_t(data[at+3])<<24);};
    if(data.size()<12 || !tag(0,"RIFF") || !tag(8,"sfbk"))return finish("invalid_riff_sfbk");
    if(uint64_t(u32(4))+8!=data.size())return finish("riff_extent_mismatch");
    size_t begin=0,end=0;
    for(size_t at=12;at<data.size();) {
        if(data.size()-at<8)return finish("truncated_chunk_header");
        const size_t n=u32(at+4),body=at+8;
        if(n>data.size()-body || (n&1)>data.size()-body-n)return finish("chunk_extent_mismatch");
        if(tag(at,"LIST")) {
            if(n<4)return finish("invalid_list_type");
            if(tag(body,"pdta")) {
                if(begin)return finish("duplicate_pdta");
                begin=body+4;end=body+n;
            }
        }
        at=body+n+(n&1);
    }
    if(!begin)return finish("missing_pdta");
    if(end-begin>32*1024*1024)return finish("pdta_size_limit");
    const char* ids[]={"phdr","pbag","pmod","pgen","inst","ibag","imod","igen","shdr"};
    const size_t widths[]={38,4,10,4,22,4,10,4,46};
    size_t at=begin;
    for(size_t i=0;i<9;++i) {
        if(end-at<8 || !tag(at,ids[i]))return finish("pdta_table_order_or_missing");
        const size_t n=u32(at+4),body=at+8;
        if(n>end-body || (n&1)>end-body-n || n%widths[i]!=0 || n<widths[i])return finish("invalid_pdta_table_extent");
        at=body+n+(n&1);
    }
    if(at!=end)return finish("unexpected_pdta_table");
    const std::vector<unsigned char> payload(data.begin()+static_cast<std::ptrdiff_t>(begin),data.begin()+static_cast<std::ptrdiff_t>(end));
    r.sha256=sf2_fingerprint::sha256(payload);r.bytes=payload.size();r.valid=true;
    return finish("validated_raw_pdta_v1");
}
}

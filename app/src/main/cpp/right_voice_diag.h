#pragma once
// RIGHT voice A/B diagnostic helpers. Read-only: no audio-callback use, no SF2 mutation.
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>

namespace right_voice_diag {

// Velocity histogram updated on the MIDI/NOTE_ON thread only (never in the audio callback).
struct VelocityHist {
    std::array<uint32_t,128> bins{};
    uint64_t count=0;
    int last=0;
    void add(int v) { v=std::clamp(v,0,127); ++bins[v]; ++count; last=v; }
    void reset() { bins.fill(0); count=0; last=0; }
    int minimum() const { for(int i=0;i<128;++i) if(bins[i]) return i; return -1; }
    int maximum() const { for(int i=127;i>=0;--i) if(bins[i]) return i; return -1; }
    int median() const {
        if(!count) return -1;
        const uint64_t target=(count+1)/2;uint64_t seen=0;
        for(int i=0;i<128;++i) { seen+=bins[i]; if(seen>=target) return i; }
        return -1;
    }
};

struct Sf2Info { bool ok=false; std::string name; int presets=-1; };

namespace detail {
inline uint32_t le32(const unsigned char* p) { return p[0]|(p[1]<<8)|(p[2]<<16)|(uint32_t(p[3])<<24); }
}

// Independent parse of the ORIGINAL file: INAM and phdr record count (minus the EOP terminator).
inline Sf2Info parseSf2(const std::string& path) {
    Sf2Info out;
    std::ifstream f(path,std::ios::binary);
    unsigned char h[12];
    if(!f.read(reinterpret_cast<char*>(h),12) || std::memcmp(h,"RIFF",4) || std::memcmp(h+8,"sfbk",4)) return out;
    bool haveInfo=false,havePdta=false;
    while(f.read(reinterpret_cast<char*>(h),8)) {
        const uint32_t size=detail::le32(h+4);
        const auto bodyStart=f.tellg();
        if(!std::memcmp(h,"LIST",4)) {
            char type[4];
            if(!f.read(type,4)) break;
            if(!std::memcmp(type,"INFO",4) && size<=(1u<<20)) {
                std::string body(size-4,'\0');
                if(!f.read(body.data(),body.size())) break;
                size_t at=0;
                while(at+8<=body.size()) {
                    const uint32_t n=detail::le32(reinterpret_cast<const unsigned char*>(body.data()+at+4));
                    if(!std::memcmp(body.data()+at,"INAM",4) && at+8+n<=body.size()) {
                        out.name.assign(body.data()+at+8,n);
                        while(!out.name.empty() && out.name.back()==0) out.name.pop_back();
                    }
                    at+=8+n+(n&1);
                }
                haveInfo=true;
            } else if(!std::memcmp(type,"pdta",4)) {
                uint32_t used=4;
                while(used+8<=size) {
                    unsigned char sh[8];
                    if(!f.read(reinterpret_cast<char*>(sh),8)) break;
                    const uint32_t n=detail::le32(sh+4);
                    if(!std::memcmp(sh,"phdr",4) && n%38==0 && n>=38) out.presets=int(n/38)-1;
                    f.seekg(n+(n&1),std::ios::cur);
                    used+=8+n+(n&1);
                }
                havePdta=true;
            }
        }
        if(haveInfo && havePdta) break;
        f.clear();
        f.seekg(bodyStart+std::streamoff(size+(size&1)));
    }
    out.ok=haveInfo && !out.name.empty();
    return out;
}

inline Sf2Info cachedSf2(const std::string& path) {
    static std::mutex m;static std::map<std::string,Sf2Info> cache;
    { std::lock_guard<std::mutex> l(m); auto it=cache.find(path); if(it!=cache.end()) return it->second; }
    auto info=parseSf2(path);
    std::lock_guard<std::mutex> l(m);cache[path]=info;return info;
}

inline std::string fileLabel(const std::string& path) {
    const auto base=path.substr(path.find_last_of("/\\")==std::string::npos?0:path.find_last_of("/\\")+1);
    std::string lower=base;for(auto& c:lower)c=char(std::tolower(static_cast<unsigned char>(c)));
    if(lower.find("colombo")!=std::string::npos) return "COLOMBO";
    if(lower.find("tyros")!=std::string::npos) return "TYROS";
    return "OTHER";
}

inline std::string baseName(const std::string& path) {
    const auto at=path.find_last_of("/\\");return at==std::string::npos?path:path.substr(at+1);
}

struct IdentityInputs {
    bool selectionVerified=false, liveOk=false, fontHandleMatches=false, triple=false;
    bool bassPresetNameMatches=false, infoOk=false, fileInfoOk=false, inamMatches=false;
    bool requested=false;
};

// MATCH only when every independently readable layer agrees. UNVERIFIED when a layer cannot be read.
inline const char* status(const IdentityInputs& i,std::string& reason) {
    if(!i.requested) { reason="no_exact_request_for_this_part"; return "UNVERIFIED"; }
    if(!i.liveOk) { reason="live_preset_readback_unavailable"; return "UNVERIFIED"; }
    if(!i.fontHandleMatches) { reason="live_font_handle_differs_from_requested"; return "MISMATCH"; }
    if(!i.triple) { reason="live_bank_or_program_differs"; return "MISMATCH"; }
    if(!i.selectionVerified) { reason="native_selection_not_verified"; return "MISMATCH"; }
    if(!i.bassPresetNameMatches) { reason="bass_preset_name_differs_from_catalog"; return "MISMATCH"; }
    if(!i.infoOk || !i.fileInfoOk) { reason="font_info_or_file_parse_unavailable"; return "UNVERIFIED"; }
    if(!i.inamMatches) { reason="bass_font_name_differs_from_file_INAM"; return "MISMATCH"; }
    reason="handle+bank+program+preset_name+font_INAM_agree";
    return "MATCH";
}

}  // namespace right_voice_diag

#pragma once
// Read-only SF2 metadata evidence. Never selects fonts, remaps keys or changes audio.
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#include <array>
#include <sstream>
#include <cmath>
#include <tuple>

namespace sf2_zones {
struct Modulator {
    uint16_t source=0, destination=0, amountSource=0, transform=0;
    int16_t amount=0;
};
struct Generators {
    std::array<int16_t,61> amount{};
    uint64_t present=0;
    std::vector<Modulator> mods;
    bool modsKnown=false;
    bool has(int op) const { return op>=0 && op<61 && (present & (uint64_t(1)<<op)); }
};
struct Zone {
    int keyLow=0, keyHigh=127, velLow=0, velHigh=127;
    std::string instrument, sample;
    uint32_t start=0, end=0;
    int sampleType=0;
    int presetBag=-1, instrumentBag=-1, sampleId=-1;
    uint32_t sampleRate=0, loopStart=0, loopEnd=0;
    int rootKey=0, pitchCorrection=0, sampleLink=0;
    Generators presetGenerators, instrumentGenerators;
};
struct Inventory {
    bool valid=false;
    std::string reason="missing_pdta";
    std::map<std::pair<int,int>, std::vector<Zone>> presets;
    std::map<std::pair<int,int>, std::string> presetNames;
};
struct Table { const unsigned char* p=nullptr; size_t n=0, stride=0;
    size_t count() const { return stride ? n/stride : 0; }
    const unsigned char* row(size_t i) const { return p+i*stride; }
};
inline uint16_t u16(const unsigned char* p) { return uint16_t(p[0]) | uint16_t(p[1])<<8; }
inline uint32_t u32(const unsigned char* p) { return uint32_t(u16(p)) | uint32_t(u16(p+2))<<16; }
inline std::string name(const unsigned char* p, bool quoteSafe=true) {
    size_t n=0; while(n<20 && p[n]) ++n;
    std::string s(reinterpret_cast<const char*>(p),n);
    for(auto& c:s) if(static_cast<unsigned char>(c)<32 || static_cast<unsigned char>(c)>126 || (quoteSafe && c=='\'')) c='?';
    return s;
}
struct Range { int kl=0,kh=127,vl=0,vh=127; bool key=false,vel=false; int link=-1; Generators generators; };
inline Inventory parse(const std::vector<unsigned char>& data, bool includeMelody=false) {
    Inventory out;
    auto fail=[&](const char* reason) { out.valid=false; out.reason=reason; out.presets.clear(); out.presetNames.clear(); return out; };
    if(data.size()<12 || std::memcmp(data.data(),"RIFF",4) || std::memcmp(data.data()+8,"sfbk",4)) return fail("not_sf2");
    const uint64_t end=uint64_t(u32(data.data()+4))+8;
    if(end>data.size() || end<12) return fail("truncated_riff");
    std::map<std::string,Table> tables;
    for(size_t pos=12; pos+8<=end;) {
        const auto* p=data.data()+pos; const size_t n=u32(p+4);
        if(n>end-pos-8) return fail("truncated_chunk");
        if(!std::memcmp(p,"LIST",4) && n>=4 && !std::memcmp(p+8,"pdta",4)) {
            const size_t stop=pos+8+n;
            for(size_t q=pos+12; q+8<=stop;) {
                const auto* h=data.data()+q; const size_t len=u32(h+4);
                if(len>stop-q-8) return fail("truncated_pdta");
                const std::string id(reinterpret_cast<const char*>(h),4);
                if(tables.count(id)) return fail("duplicate_table");
                tables[id]={h+8,len,0}; q+=8+len+(len&1);
            }
        }
        pos+=8+n+(n&1);
    }
    for(const auto& spec:std::vector<std::pair<std::string,size_t>>{{"phdr",38},{"pbag",4},{"pgen",4},{"inst",22},{"ibag",4},{"igen",4},{"shdr",46}}) {
        auto& t=tables[spec.first]; t.stride=spec.second;
        if(!t.p || !t.n || t.n%t.stride) return fail("missing_or_invalid_table");
    }
    const auto& ph=tables["phdr"]; const auto& pb=tables["pbag"]; const auto& pg=tables["pgen"];
    const auto& in=tables["inst"]; const auto& ib=tables["ibag"]; const auto& ig=tables["igen"]; const auto& sh=tables["shdr"];
    bool ok=true;
    auto modulators=[&](const char* id,const Table& bags,size_t b, Generators& g) {
        const auto it=tables.find(id);
        if(it==tables.end()) return; // Preserve old drum parsing; mark omitted modulator evidence unknown.
        const auto& t=it->second;
        if(t.n%10 || !t.p || b+1>=bags.count()) return;
        const size_t first=u16(bags.row(b)+2),last=u16(bags.row(b+1)+2);
        if(first>last || last>t.n/10) return;
        g.modsKnown=true;
        for(size_t m=first;m<last;++m) {
            const auto* row=t.p+m*10;
            g.mods.push_back({u16(row),u16(row+2),u16(row+6),u16(row+8),static_cast<int16_t>(u16(row+4))});
        }
    };
    auto zone=[&](const Table& bags,const Table& gens,size_t b,int linkOp) {
        Range r;
        if(b+1>=bags.count()) { ok=false; return r; }
        const size_t a=u16(bags.row(b)),z=u16(bags.row(b+1));
        if(a>z || z>gens.count()) { ok=false; return r; }
        for(size_t j=a;j<z;++j) {
            const auto* g=gens.row(j); const auto op=u16(g),v=u16(g+2);
            if(op<61) { r.generators.present|=uint64_t(1)<<op; r.generators.amount[op]=static_cast<int16_t>(v); }
            if(op==43) { r.key=true; r.kl=g[2]; r.kh=g[3]; }
            if(op==44) { r.vel=true; r.vl=g[2]; r.vh=g[3]; }
            if(op==linkOp) r.link=v;
        }
        modulators(linkOp==41?"pmod":"imod",bags,b,r.generators);
        if(r.kl>r.kh || r.kh>127 || r.vl>r.vh || r.vh>127) ok=false;
        return r;
    };
    auto inherit=[](Range r,const Range& global) {
        // Local generators override the same generator in their own global zone.
        if(!r.key) { r.kl=global.kl; r.kh=global.kh; }
        if(!r.vel) { r.vl=global.vl; r.vh=global.vh; }
        for(int op=0;op<61;++op) if(!r.generators.has(op) && global.generators.has(op)) {
            r.generators.present|=uint64_t(1)<<op;
            r.generators.amount[op]=global.generators.amount[op];
        }
        auto local=r.generators.mods;
        for(const auto& m:global.generators.mods) {
            const bool overridden=std::any_of(local.begin(),local.end(),[&](const Modulator& n) {
                return std::tie(m.source,m.destination,m.amountSource,m.transform)==std::tie(n.source,n.destination,n.amountSource,n.transform);
            });
            if(!overridden) r.generators.mods.push_back(m);
        }
        r.generators.modsKnown=r.generators.modsKnown && global.generators.modsKnown;
        return r;
    };
    size_t zoneCount=0;
    for(size_t p=0;p+1<ph.count();++p) {
        const auto* header=ph.row(p); const int bank=u16(header+22),pc=u16(header+20);
        if(!includeMelody && bank!=127 && bank!=128) continue;
        const size_t first=u16(header+24),last=u16(ph.row(p+1)+24);
        if(first>last || last>=pb.count()) return fail("invalid_preset_bags");
        auto& zones=out.presets[{bank,pc}]; Range presetGlobal; presetGlobal.generators.modsKnown=tables.count("pmod") && tables["pmod"].n%10==0;
        out.presetNames[{bank,pc}]=name(header,false);
        for(size_t b=first;b<last;++b) {
            Range pr=zone(pb,pg,b,41);
            if(!ok) return fail("invalid_preset_generators");
            if(pr.link<0) { if(b!=first) return fail("nonleading_preset_global"); presetGlobal=pr; continue; }
            pr=inherit(pr,presetGlobal);
            if(size_t(pr.link)+1>=in.count()) return fail("invalid_instrument_link");
            const auto* instrument=in.row(pr.link);
            const size_t ia=u16(instrument+20),iz=u16(in.row(pr.link+1)+20);
            if(ia>iz || iz>=ib.count()) return fail("invalid_instrument_bags");
            Range instrumentGlobal; instrumentGlobal.generators.modsKnown=tables.count("imod") && tables["imod"].n%10==0;
            for(size_t k=ia;k<iz;++k) {
                Range ir=zone(ib,ig,k,53);
                if(!ok) return fail("invalid_instrument_generators");
                if(ir.link<0) { if(k!=ia) return fail("nonleading_instrument_global"); instrumentGlobal=ir; continue; }
                ir=inherit(ir,instrumentGlobal);
                if(size_t(ir.link)+1>=sh.count()) return fail("invalid_sample_link");
                const auto* sample=sh.row(ir.link);
                Zone z;
                z.keyLow=std::max(pr.kl,ir.kl); z.keyHigh=std::min(pr.kh,ir.kh);
                z.velLow=std::max(pr.vl,ir.vl); z.velHigh=std::min(pr.vh,ir.vh);
                z.instrument=name(instrument); z.sample=name(sample);
                z.start=u32(sample+20); z.end=u32(sample+24); z.sampleType=u16(sample+44);
                z.presetBag=b; z.instrumentBag=k; z.sampleId=ir.link;
                z.sampleRate=u32(sample+36); z.loopStart=u32(sample+28); z.loopEnd=u32(sample+32);
                z.rootKey=sample[40]; z.pitchCorrection=static_cast<int8_t>(sample[41]); z.sampleLink=u16(sample+42);
                z.presetGenerators=pr.generators; z.instrumentGenerators=ir.generators;
                if(z.keyLow<=z.keyHigh && z.velLow<=z.velHigh) zones.push_back(z);
                if(++zoneCount>200000) return fail("zone_limit");
            }
        }
    }
    out.valid=true; out.reason="metadata_only_not_pcm"; return out;
}
struct Match { bool known=false; size_t zones=0; std::string names; };
inline Match match(const Inventory& inventory,int bank,int pc,int key,int velocity) {
    Match result; if(!inventory.valid) return result;
    const auto it=inventory.presets.find({bank,pc}); if(it==inventory.presets.end()) return result;
    result.known=true;
    for(const auto& z:it->second) if(key>=z.keyLow && key<=z.keyHigh && velocity>=z.velLow && velocity<=z.velHigh) {
        ++result.zones;
        if(result.names.size()<180) {
            if(!result.names.empty()) result.names+=';';
            result.names+=z.instrument+"/"+z.sample+"["+std::to_string(z.keyLow)+"-"+std::to_string(z.keyHigh)+","+std::to_string(z.velLow)+"-"+std::to_string(z.velHigh)+",frames="+std::to_string(z.end>=z.start?z.end-z.start:0)+",type="+std::to_string(z.sampleType)+"]";
        }
    }
    return result;
}
// Static SF2 generator values; BASS does not expose which sample voice it instantiated.
inline int base(int op) {
    if(op==21 || op==23 || (op>=25 && op<=28) || op==30 || (op>=33 && op<=36) || op==38) return -12000;
    if(op==8) return 13500;
    if(op==56) return 100;
    if(op==46 || op==47 || op==58) return -1;
    return 0;
}
inline int effective(const Zone& z,int op) {
    const int instrument=z.instrumentGenerators.has(op)?z.instrumentGenerators.amount[op]:base(op);
    // Preset values are additive offsets, not a second instrument default.
    return instrument+(z.presetGenerators.has(op)?z.presetGenerators.amount[op]:0);
}
inline std::string describe(const Zone& z) {
    std::ostringstream o;
    o << "zone=" << z.presetBag << '/' << z.instrumentBag << " sampleId=" << z.sampleId
      << " instrument='" << z.instrument << "' sample='" << z.sample << "' keyRange=" << z.keyLow << '-' << z.keyHigh
      << " velocityRange=" << z.velLow << '-' << z.velHigh << " frames=" << (z.end>=z.start?z.end-z.start:0)
      << " rate=" << z.sampleRate << " loop=" << z.loopStart << '-' << z.loopEnd << " type=" << z.sampleType
      << " sampleLink=" << z.sampleLink << " rootKey=" << z.rootKey << " pitchCorrection=" << z.pitchCorrection
      << " attenuationCb=" << effective(z,48) << " staticGainProxy=" << std::pow(10.0,-effective(z,48)/200.0)
      << " volEnvTimecents(delay,attack,hold,decay,release)=" << effective(z,33) << ',' << effective(z,34) << ','
      << effective(z,35) << ',' << effective(z,36) << ',' << effective(z,38)
      << " sustainCb=" << effective(z,37) << " keyHoldDecay=" << effective(z,39) << ',' << effective(z,40)
      << " filterFcCents=" << effective(z,8) << " filterQCb=" << effective(z,9)
      << " fixedKeyVelocity=" << effective(z,46) << ',' << effective(z,47)
      << " sampleModes=" << effective(z,54) << " exclusiveClass=" << effective(z,57) << " overrideRoot=" << effective(z,58);
    return o.str();
}
inline std::string generators(const Zone& z) {
    std::ostringstream o;
    for(const auto* g:{&z.presetGenerators,&z.instrumentGenerators}) {
        o << (g==&z.presetGenerators?"preset":"instrument") << "Generators={";
        for(int op=0;op<61;++op) if(g->has(op)) o << op << ':' << g->amount[op] << ',';
        o << "} modsKnown=" << g->modsKnown << " customMods={";
        for(const auto& m:g->mods) o << m.source << ':' << m.destination << ':' << m.amount << ':' << m.amountSource << ':' << m.transform << ',';
        o << "} ";
    }
    o << "defaultSF2Mods=not_enumerated velocityModulationAndBASSOverrides=not_evaluated staticValues=not_PCM_gain";
    return o.str();
}
inline std::string detailedMatch(const Inventory& inv,int bank,int pc,int key,int velocity) {
    const auto m=match(inv,bank,pc,key,velocity);
    std::ostringstream o;
    o << "metadataKnown=" << m.known << " matchingZones=" << m.zones
      << " sampleSelection=eligible_layers_only_BASS_voice_sample_ID_unavailable\n";
    const auto it=inv.presets.find({bank,pc});
    if(it!=inv.presets.end()) for(const auto& z:it->second)
        if(key>=z.keyLow && key<=z.keyHigh && velocity>=z.velLow && velocity<=z.velHigh)
            o << describe(z) << '\n' << generators(z) << '\n';
    return o.str();
}
} // namespace sf2_zones


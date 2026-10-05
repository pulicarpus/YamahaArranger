#pragma once
#include "sf2_zone_diagnostic.h"
#include "yamaha_drum_semantics.h"
#include "percussion_audition_evidence.h"
#include <cctype>
#include <set>

namespace percussion_fidelity {
enum class Family { Unknown, Tambourine, Cabasa, Claves, TriangleMute, TriangleOpen,
    Shaker, SnareHit, Kick, HatPedalClosed, HatClosed, HatOpen };
inline std::string lower(std::string s) { for(auto& c:s)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));return s; }
inline bool contains(const std::string& s,const char* p) { return s.find(p)!=std::string::npos; }
inline Family family(const std::string& text) {
    const auto s=lower(text);
    // Special/synthetic articulations are not silently collapsed into acoustic hits.
    for(const auto* reject:{"808","909","analog","electro","synth","reverse","roll","brush","swirl","no-rim","side-stick","rim","splash","edge","tip"})
        if(contains(s,reject))return Family::Unknown;
    if(contains(s,"triangle")) {
        if(contains(s,"mute"))return Family::TriangleMute;
        if(contains(s,"open"))return Family::TriangleOpen;
        return Family::Unknown;
    }
    if(contains(s,"hi-hat")||contains(s,"hihat")||contains(s,"hi hat")) {
        if(contains(s,"pedal")||contains(s,"foot"))return Family::HatPedalClosed;
        if(contains(s,"closed")||contains(s,"close "))return Family::HatClosed;
        if(contains(s,"open"))return Family::HatOpen;
        return Family::Unknown;
    }
    if(contains(s,"tambour"))return Family::Tambourine;
    if(contains(s,"cabasa"))return Family::Cabasa;
    if(contains(s,"claves")||contains(s,"clave("))return Family::Claves;
    if(contains(s,"shaker"))return Family::Shaker;
    if(contains(s,"snare"))return Family::SnareHit;
    if(contains(s,"kick")||contains(s,"bass drum"))return Family::Kick;
    return Family::Unknown;
}
inline bool supported(Family f) { return f!=Family::Unknown && f!=Family::HatClosed && f!=Family::HatOpen; }
struct Candidate { unsigned font=0; int bank=0,nativeBank=0,pc=0,key=0,exclusive=0; Family family=Family::Unknown;
    std::string path,preset,samples,fingerprint,sampleIdentity; int layers=0; bool auditioned=false; double seconds=0; };
inline int root(const sf2_zones::Zone& z) { const auto& g=z.instrumentGenerators;return g.has(58)?g.amount[58]:z.rootKey; }
inline bool pitchAndVelocitySafe(const sf2_zones::Zone& z,int key) {
    for(int op:{0,1,2,3,4,12,45,50})if(sf2_zones::effective(z,op)!=0)return false;
    if(sf2_zones::effective(z,54)!=0)return false; // only finite, non-looping drum samples
    if(z.keyLow!=key || z.keyHigh!=key || z.start>=z.end || !z.sampleRate || root(z)!=key || z.pitchCorrection!=0) return false;
    if(sf2_zones::effective(z,56)!=100 || sf2_zones::effective(z,51)!=0 || sf2_zones::effective(z,52)!=0 ||
       sf2_zones::effective(z,5)!=0 || sf2_zones::effective(z,6)!=0 || sf2_zones::effective(z,7)!=0 ||
       sf2_zones::effective(z,46)!=-1 || sf2_zones::effective(z,47)!=-1) return false;
    // Implicit standard SF2 velocity response is retained; custom modulators require separate proof.
    for(const auto* g:{&z.presetGenerators,&z.instrumentGenerators})
        if(!g->modsKnown || !g->mods.empty())return false;
    return true;
}
inline bool stereoSafe(const sf2_zones::Zone& z,const std::vector<sf2_zones::Zone>& zones) {
    if(z.sampleType==1)return true;
    if(z.sampleType!=2 && z.sampleType!=4)return false;
    for(const auto& pair:zones) if(pair.sampleId==z.sampleLink && pair.sampleLink==z.sampleId &&
       pair.sampleType==(z.sampleType==2?4:2) && pair.keyLow==z.keyLow && pair.keyHigh==z.keyHigh &&
       pair.velLow==z.velLow && pair.velHigh==z.velHigh && pair.sampleRate==z.sampleRate && root(pair)==root(z))return true;
    return false;
}
// Search only at font load. Names need corroboration: coherent drum context,
// exact key/velocity zones, pitch, modulators, stereo and articulation/choke.
inline std::vector<Candidate> catalog(const sf2_zones::Inventory& inv,unsigned font,const std::string& path) {
    std::vector<Candidate> out;if(!inv.valid || !font)return out;
    for(const auto& p:inv.presets) {
        const auto ni=inv.presetNames.find(p.first);if(ni==inv.presetNames.end())continue;
        const auto name=lower(ni->second);
        if(p.first.first<126 && !contains(name,"drum") && !contains(name,"kit"))continue;
        std::set<Family> context;
        for(const auto& z:p.second) { auto f=family(z.sample);if(f!=Family::Unknown && z.keyLow==z.keyHigh)context.insert(f); }
        if(context.size()<4)continue; // A named melodic sample alone is not a drum kit.
        for(int key=0;key<128;++key) {
            std::array<bool,128> velocities{};Candidate c;c.font=font;c.bank=p.first.first;c.nativeBank=c.bank;c.pc=p.first.second;c.key=key;c.path=path;c.preset=ni->second;
            bool ok=true;std::set<int> sampleIds;
            for(const auto& z:p.second) if(z.keyLow<=key && key<=z.keyHigh) {
                const auto f=family(z.sample);
                if(!supported(f) || (c.family!=Family::Unknown && f!=c.family) ||
                   !pitchAndVelocitySafe(z,key) || !stereoSafe(z,p.second)) {ok=false;break;}
                c.family=f;c.seconds=std::max(c.seconds,double(z.end-z.start)/z.sampleRate);
                const auto exclusive=sf2_zones::effective(z,57);
                if(c.layers && c.exclusive!=exclusive) {ok=false;break;}
                c.exclusive=exclusive;++c.layers;
                for(int v=std::max(1,z.velLow);v<=z.velHigh;++v)velocities[v]=true;
                if(sampleIds.insert(z.sampleId).second) {if(!c.samples.empty())c.samples+=';';c.samples+=z.sample;}
            }
            if(!ok || !c.layers || std::find(velocities.begin()+1,velocities.end(),false)!=velocities.end())continue;
            const bool group=c.family==Family::TriangleMute || c.family==Family::TriangleOpen || c.family==Family::HatPedalClosed;
            if(group && c.exclusive<=0)continue;
            if(!group && c.exclusive!=0)continue;
            if(c.family==Family::HatPedalClosed && (!context.count(Family::HatClosed) || !context.count(Family::HatOpen)))continue;
            for(int id:sampleIds)c.sampleIdentity+=std::to_string(id)+",";
            out.push_back(c);
        }
    }
    return out;
}
// Audition is evidence for its recorded Yamaha semantic target, not for every
// unrelated request that happens to share the same broad instrument family.
inline bool observedFor(const Candidate& c,const std::string& target) {
    for(const auto& e:auditionEvidence)if(c.fingerprint==e.fingerprint && c.bank==e.bank &&
        c.pc==e.pc && c.key==e.key && target==e.targetIdentity)return true;
    return false;
}
inline std::string renderIdentity(const Candidate& c) {
    return (c.fingerprint.empty()?c.path:c.fingerprint)+":"+c.sampleIdentity;
}
inline const Candidate* choose(const std::vector<Candidate>& candidates,Family wanted,int sourceKey,
                               const std::string& target="") {
    const Candidate* best=nullptr;
    auto score=[&](const Candidate& c) {return std::make_tuple(observedFor(c,target)?0:1,c.key==sourceKey?0:1,c.bank==128?0:c.bank==127?1:2,c.layers,c.path,c.pc,c.key);};
    for(const auto& c:candidates)if(c.family==wanted && (!best || score(c)<score(*best)))best=&c;
    return best;
}
// Do not silently flatten distinct Yamaha variants into the same sample bundle.
// True documented source aliases may share a donor; distinct full identities
// need independent evidence. Keep the source-auditioned target, abstain for
// conflicting unproved variants rather than invent a different voice.
inline std::array<Candidate,128> distinctPlan(const std::vector<Candidate>& candidates,int msb,int lsb,int pc) {
    std::array<Candidate,128> plan{};
    std::array<const YamahaNote*,128> sources{};
    for(const auto& n:yamahaNotes)if(n.msb==msb && n.lsb==lsb && n.pc==pc && !n.keyOff && supported(family(n.identity))) {
        sources[n.key]=&n;
        if(const auto* c=choose(candidates,family(n.identity),n.key,n.identity))plan[n.key]=*c;
    }
    const auto proposed=plan;
    for(int key=0;key<128;++key)if(proposed[key].font) {
        const auto id=renderIdentity(proposed[key]);
        for(int peer=0;peer<128;++peer)if(peer!=key && proposed[peer].font &&
            id==renderIdentity(proposed[peer]) && std::string(sources[key]->identity)!=sources[peer]->identity) {
            if(!observedFor(proposed[key],sources[key]->identity))plan[key]={};
        }
    }
    return plan;
}
struct Owner { bool routed=false,accepted=false,choked=false; int key=0,group=0;
    unsigned stream=0,font=0; uint64_t generation=0; int bank=-1,pc=-1,lane=-1,sourceChannel=-1;
    uint64_t count=1,endFrame=0; bool oneShot=false; };
struct Owners {
    std::array<Owner,32> entries{};unsigned head=0,size=0,overflow=0;
    static bool same(const Owner& a,const Owner& b) {
        return a.routed==b.routed && a.accepted==b.accepted && a.choked==b.choked && a.key==b.key && a.group==b.group &&
            a.stream==b.stream && a.font==b.font && a.generation==b.generation && a.bank==b.bank && a.pc==b.pc && a.lane==b.lane && a.sourceChannel==b.sourceChannel && a.oneShot==b.oneShot;
    }
    bool push(const Owner& o) {
        // Compress only completed, identical bindings; a pending/failed ON never
        // overwrites acceptance of an earlier voice. One-shot rhythm has no scheduled OFF.
        unsigned compact=0;
        for(unsigned i=0;i<size;++i) {
            const Owner current=entries[(head+i)%entries.size()];
            if(compact && same(entries[(head+compact-1)%entries.size()],current)) {
                auto& prior=entries[(head+compact-1)%entries.size()];prior.count+=current.count;prior.endFrame=std::max(prior.endFrame,current.endFrame);
            } else entries[(head+compact++)%entries.size()]=current;
        }
        size=compact;
        if(size==entries.size() || overflow) {++overflow;return false;}
        entries[(head+size++)%entries.size()]=o;return true;
    }
    bool pop(Owner& o) {
        if(size) {o=entries[head];o.count=1;if(--entries[head].count==0){head=(head+1)%entries.size();--size;}return true;}
        if(overflow){--overflow;o={};return true;}return false;
    }
    bool popSource(Owner& o,int source,bool style) {
        for(unsigned i=0;i<size;++i) {
            auto& current=entries[(head+i)%entries.size()];
            if(current.sourceChannel!=source || current.oneShot!=style)continue;
            o=current;o.count=1;
            if(--current.count==0) {for(unsigned j=i+1;j<size;++j)entries[(head+j-1)%entries.size()]=entries[(head+j)%entries.size()];--size;}
            return true;
        }
        if(overflow){--overflow;o={};return true;}
        return false;
    }
    void expire(uint64_t frame) {
        while(size && entries[head].oneShot && entries[head].endFrame<=frame) {head=(head+1)%entries.size();--size;}
    }
    bool pending()const{return size || overflow;}
    void silence() {for(unsigned i=0;i<size;++i)entries[(head+i)%entries.size()].choked=true;}
};
// Yamaha alternate groups 96..127 are stopped by group-32, not symmetrically.
inline bool chokes(int incoming,int held) { return incoming>0 && incoming<96 && (incoming==held || incoming+32==held); }
}

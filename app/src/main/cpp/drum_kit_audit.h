#pragma once
// Coverage evidence only. No BASS calls, kit selection, note remap or PCM reads.
#include "sf2_zone_diagnostic.h"
#include <set>
#include <sstream>
#include <tuple>

namespace drum_audit {
struct Hit { int channel, key, velocity, count; };
struct Score {
    int bank=0, pc=0;
    int64_t coveredHits=0, totalHits=0, unknownHits=0;
    size_t coveredKeys=0, totalKeys=0;
    std::set<int> missing, partial, unknown, important;
    std::string name, rows;
};
inline std::string keys(const std::set<int>& values) {
    std::ostringstream out; out << '[';
    bool first=true;
    for(int key:values) { if(!first) out << ','; out << key; first=false; }
    out << ']'; return out.str();
}
inline std::string report(const sf2_zones::Inventory& inventory,
                          const std::vector<Hit>& hits) {
    if(!inventory.valid) return "DRUM KIT AUDIT unavailable: metadata="+inventory.reason+"\n";
    // Canonicalize repeated bins; never count overlapping sample zones as extra hits.
    std::map<std::tuple<int,int,int>,int64_t> bins;
    for(const auto& h:hits) {
        if((h.channel!=8 && h.channel!=9) || h.key<0 || h.key>127 ||
           h.velocity<1 || h.velocity>127 || h.count<=0)
            return "DRUM KIT AUDIT unavailable: invalid style histogram\n";
        bins[{h.channel,h.key,h.velocity}]+=h.count;
    }
    if(bins.empty()) return "DRUM KIT AUDIT unavailable: no rhythm NOTE_ON histogram\n";
    std::vector<Score> scores;
    for(const auto& preset:inventory.presets) {
        Score s; s.bank=preset.first.first; s.pc=preset.first.second;
        auto nm=inventory.presetNames.find(preset.first);
        s.name=nm==inventory.presetNames.end()?"(unnamed)":nm->second;
        std::map<int,std::pair<int64_t,int64_t>> keyHits;
        std::map<std::pair<int,int>,std::vector<std::pair<int,int64_t>>> partKeys;
        std::ostringstream rows;
        for(const auto& bin:bins) {
            const auto [channel,key,velocity]=bin.first;
            const auto count=bin.second;
            const auto matched=sf2_zones::match(inventory,s.bank,s.pc,key,velocity);
            keyHits[key].second+=count; s.totalHits+=count;
            if(matched.known && matched.zones) { keyHits[key].first+=count; s.coveredHits+=count; }
            if(!matched.known) { s.unknown.insert(key); s.unknownHits+=count; }
            partKeys[{channel,key}].push_back({velocity,count});
        }
        for(const auto& kh:keyHits) {
            if(kh.second.first==kh.second.second) ++s.coveredKeys;
            else {
                s.missing.insert(kh.first);
                if(kh.second.first) s.partial.insert(kh.first);
                if(kh.first==31 || kh.first==21) s.important.insert(kh.first);
            }
        }
        s.totalKeys=keyHits.size();
        for(const auto& pk:partKeys) {
            const int channel=pk.first.first, key=pk.first.second;
            int64_t count=0, covered=0;
            size_t minZones=SIZE_MAX, maxZones=0;
            std::set<std::string> names;
            for(const auto& v:pk.second) {
                const auto m=sf2_zones::match(inventory,s.bank,s.pc,key,v.first);
                count+=v.second; if(m.zones) covered+=v.second;
                minZones=std::min(minZones,m.zones); maxZones=std::max(maxZones,m.zones);
                for(const auto& z:preset.second) if(key>=z.keyLow && key<=z.keyHigh &&
                    v.first>=z.velLow && v.first<=z.velHigh) {
                    names.insert(z.instrument+"/"+z.sample+"[key="+std::to_string(z.keyLow)+"-"+
                        std::to_string(z.keyHigh)+",vel="+std::to_string(z.velLow)+"-"+
                        std::to_string(z.velHigh)+",frames="+
                        std::to_string(z.end>=z.start?z.end-z.start:0)+",type="+std::to_string(z.sampleType)+"]");
                }
            }
            rows << "KIT KEY | " << s.name << " | bank=" << s.bank << " | PC=" << s.pc
                 << " | ch=" << channel << " | key=" << key << " | velocity="
                 << pk.second.front().first << "-" << pk.second.back().first
                 << " | hits=" << count << " | coveredHits=" << covered
                 << " | matchingZones=" << minZones << "-" << maxZones << " | samples=";
            if(names.empty()) rows << "(none)";
            for(const auto& name:names) rows << name << ';';
            rows << '\n';
            // Exact velocities make partial coverage explicit; range alone can hide holes.
            for(const auto& v:pk.second) {
                const auto m=sf2_zones::match(inventory,s.bank,s.pc,key,v.first);
                rows << "KIT VELOCITY | " << s.name << " | bank=" << s.bank << " | PC=" << s.pc
                     << " | ch=" << channel << " | key=" << key << " | velocity=" << v.first
                     << " | hits=" << v.second << " | matchingZones=" << m.zones
                     << " | metadataKnown=" << m.known << " | samples=";
                if(!m.zones) rows << "(none)";
                for(const auto& z:preset.second) if(key>=z.keyLow && key<=z.keyHigh &&
                    v.first>=z.velLow && v.first<=z.velHigh)
                    rows << z.instrument << '/' << z.sample << "[key=" << z.keyLow << '-' << z.keyHigh
                         << ",vel=" << z.velLow << '-' << z.velHigh << ",frames="
                         << (z.end>=z.start?z.end-z.start:0) << ",type=" << z.sampleType << "];";
                rows << '\n';
                rows << "KIT ZONE DETAIL bank=" << s.bank << " PC=" << s.pc << " ch=" << channel
                     << " key=" << key << " velocity=" << v.first << '\n'
                     << sf2_zones::detailedMatch(inventory,s.bank,s.pc,key,v.first);
            }
        }
        s.rows=rows.str(); scores.push_back(std::move(s));
    }
    std::sort(scores.begin(),scores.end(),[](const Score& a,const Score& b) {
        if(a.coveredHits!=b.coveredHits) return a.coveredHits>b.coveredHits;
        if(a.coveredKeys!=b.coveredKeys) return a.coveredKeys>b.coveredKeys;
        if(a.important.size()!=b.important.size()) return a.important.size()<b.important.size();
        return std::tie(a.bank,a.pc)<std::tie(b.bank,b.pc);
    });
    std::ostringstream out;
    out << "DRUM KIT AUDIT presets=" << scores.size()
        << " evidence=cached_loaded_dedicated_SF2_metadata raw_style_section_profile channelNumbers=zero_based\n"
        << "Coverage is key/velocity eligibility, NOT timbre/PCM audibility. Playback unchanged; ranking selects nothing.\n"
        << "Covered unique key requires ALL actual velocities on both rhythm parts. missingKeys includes partial coverage.\n";
    for(size_t i=0;i<scores.size();++i) {
        const auto& s=scores[i];
        out << "KIT RANK rank=" << i+1 << " name='" << s.name << "' bank=" << s.bank << " PC=" << s.pc
            << " coveredHits=" << s.coveredHits << '/' << s.totalHits
            << " coveredUniqueKeys=" << s.coveredKeys << '/' << s.totalKeys
            << " missingImportantKeys=" << keys(s.important) << " missingKeys=" << keys(s.missing)
            << " partialKeys=" << keys(s.partial) << " unknownKeys=" << keys(s.unknown)
            << " unknownHits=" << s.unknownHits << '\n';
    }
    for(const auto& s:scores) out << s.rows;
    return out.str();
}
} // namespace drum_audit


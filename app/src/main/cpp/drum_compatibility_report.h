#pragma once
// Explicit export only. Metadata eligibility is not a kit-selection algorithm.
#include "sf2_zone_diagnostic.h"
#include <set>
#include <tuple>

namespace drum_compat {
constexpr size_t REPORT_BYTES = 40 * 1024; // Kotlin provenance reserves another 8 KiB.
struct Demand { int section, channel, key, velocity, count; };
struct Live {
    int channel=-1, requestBank=-1, requestPc=-1, effectivePc=-1, bank=-1, pc=-1;
    bool initialized=false, verified=false, inCandidateSource=false;
    std::string source, name, reason="unknown";
    uint64_t generation=0;
};
// Printable ASCII avoids JNI modified-UTF8 expansion and arbitrary SF2/path record framing.
inline std::string label(std::string s, size_t cap=96) {
    for(char& c:s) if(static_cast<unsigned char>(c)<32 || static_cast<unsigned char>(c)>126 || c=='\'') c='?';
    if(s.size()>cap) { s.resize(cap-3); s+="..."; }
    return s;
}
struct Block {
    std::string text; size_t used=0, omitted=0, limit;
    explicit Block(size_t bytes):limit(bytes) {}
    void line(const std::string& row) {
        if(used+row.size()+1+128>limit) { ++omitted; return; }
        text+=row+'\n'; used+=row.size()+1;
    }
    std::string finish(const char* name) const {
        return text+"BLOCK "+name+" omittedRows="+std::to_string(omitted)+" (omitted != missing_zone)\n";
    }
};
using Bin = std::tuple<int,int,int>; // destination, unmodified source key, velocity
inline std::string binText(const Bin& b) {
    return "ch="+std::to_string(std::get<0>(b))+" key="+std::to_string(std::get<1>(b))+" vel="+std::to_string(std::get<2>(b));
}
inline std::string kitText(int bank,int pc) { return "bank="+std::to_string(bank)+" PC="+std::to_string(pc); }
inline bool eligible(const sf2_zones::Zone& z,int key,int velocity) {
    return key>=z.keyLow && key<=z.keyHigh && velocity>=z.velLow && velocity<=z.velHigh;
}
inline std::string report(const sf2_zones::Inventory& inv, const std::string& source,
                          const std::vector<Demand>& demands,const std::vector<Live>& lives) {
    std::map<std::tuple<int,int,int,int>,int64_t> sections;
    std::map<Bin,int64_t> bins;
    size_t invalid=0;
    for(const auto& h:demands) {
        if(h.section<0 || (h.channel!=8 && h.channel!=9) || h.key<0 || h.key>127 ||
           h.velocity<1 || h.velocity>127 || h.count<=0) { ++invalid; continue; }
        sections[{h.section,h.channel,h.key,h.velocity}]+=int64_t(h.count);
        bins[{h.channel,h.key,h.velocity}]+=int64_t(h.count);
    }
    int64_t total=0; std::set<std::pair<int,int>> keys;
    for(const auto& b:bins) { total+=b.second; keys.insert({std::get<0>(b.first),std::get<1>(b.first)}); }
    Block state(4096), summary(8192), histogram(10240), layers(18432);
    state.line("DRUM COMPATIBILITY v1 maxNativeBytes=40960 channel/PC=zero_based");
    state.line("COVERAGE=eligible_SF2_key_velocity_zones; musicalIdentity=UNKNOWN; BASS_voice_sample_ID=unavailable; PCM=not_measured");
    state.line("DEMAND=parsed_source_NOTE_ON_each_section_once; keys_unremapped; NOT_runtime_sent_counts; CASM_masks/mutes/chord_choice/repetitions=not_evaluated");
    state.line("SCOPE=loaded_dedicated_drum_cache_only; secondary_SF2_drums=not_candidates; selection/playback=UNCHANGED; no_candidate_recommendation");
    state.line("SOURCE SF2='"+label(source,256)+"' metadataKnown="+std::to_string(inv.valid)+" reason='"+label(inv.reason)+"' kits="+std::to_string(inv.presets.size()));
    state.line("TOTAL sourceHits="+std::to_string(total)+" keyVelocityBins="+std::to_string(bins.size())+" channelKeys="+std::to_string(keys.size())+" invalidInputRows="+std::to_string(invalid));
    if(bins.empty()) state.line("DEMAND empty_or_unavailable; coverage=UNKNOWN/no_required_hits; see_profile_demandComplete; 0/0_is_not_compatibility");
    for(const auto& l:lives) {
        state.line("LIVE export_time_snapshot ch="+std::to_string(l.channel)+" initialized="+std::to_string(l.initialized)+
            " nativeRequestBank="+std::to_string(l.requestBank)+" requestPC="+std::to_string(l.requestPc)+
            " effectivePC="+std::to_string(l.effectivePc)+" actualKnown="+std::to_string(l.verified)+
            " actualBank="+std::to_string(l.bank)+" actualPC="+std::to_string(l.pc)+" preset='"+label(l.name)+
            "' SF2='"+label(l.source,256)+"' inCandidateSource="+std::to_string(l.inCandidateSource)+
            " mappingGeneration="+std::to_string(l.generation)+" fallbackReason="+label(l.reason,160));
    }
    for(const auto& l:lives) {
        int64_t count=0,covered=0,unknown=0;
        std::string missing;
        for(const auto& b:bins) if(std::get<0>(b.first)==l.channel) {
            count+=b.second;
            const auto m=(l.verified && l.inCandidateSource)?sf2_zones::match(inv,l.bank,l.pc,std::get<1>(b.first),std::get<2>(b.first)):sf2_zones::Match{};
            if(!m.known) unknown+=b.second;
            else if(m.zones) covered+=b.second;
            else if(missing.size()<512) missing+=std::to_string(std::get<1>(b.first))+"@"+std::to_string(std::get<2>(b.first))+",";
        }
        state.line("LIVE_COVERAGE ch="+std::to_string(l.channel)+" coveredHits="+std::to_string(covered)+"/"+std::to_string(count)+
            " unknownHits="+std::to_string(unknown)+" missing(key@vel)="+(missing.empty()?"none_known":missing)+(missing.size()>=512?" [list_capped]":"")+
            " scope=current_preset_vs_all_source_sections_not_historical_PCM");
    }
    // Canonical bank/PC order, never ranked by names or aggregate coverage.
    for(const auto& kit:inv.presets) {
        int64_t covered=0; size_t missingBins=0; std::set<std::pair<int,int>> missingKeys;
        std::string missing;
        for(const auto& b:bins) {
            const auto m=sf2_zones::match(inv,kit.first.first,kit.first.second,std::get<1>(b.first),std::get<2>(b.first));
            if(m.known && m.zones) covered+=b.second;
            else if(m.known) {
                ++missingBins;
                missingKeys.insert({std::get<0>(b.first),std::get<1>(b.first)});
                if(missing.size()<64) missing+=std::to_string(std::get<0>(b.first))+":"+std::to_string(std::get<1>(b.first))+"@"+std::to_string(std::get<2>(b.first))+",";
            }
        }
        const auto n=inv.presetNames.find(kit.first);
        summary.line("KIT "+kitText(kit.first.first,kit.first.second)+" name='"+label(n==inv.presetNames.end()?"unknown":n->second,40)+
            "' metadataKnown="+std::to_string(inv.valid)+" coveredHits="+(inv.valid?std::to_string(covered):"UNKNOWN")+"/"+std::to_string(total)+
            " fullyCoveredChannelKeys="+(inv.valid?std::to_string(keys.size()-missingKeys.size()):"unknown")+"/"+std::to_string(keys.size())+
            " unknownHits="+std::to_string(inv.valid?0:total)+" missingBins="+std::to_string(missingBins)+" missing(ch:key@vel)="+(inv.valid?(missing.empty()?"none":missing):"UNKNOWN")+
            (missing.size()>=64?" [list_capped_see_MATCH]":"")+" musicalIdentity=UNKNOWN");
    }
    if(!inv.valid || inv.presets.empty()) summary.line("CANDIDATES unavailable; coverage=UNKNOWN (not zero); reason='"+label(inv.reason)+"'");
    for(const auto& b:bins) histogram.line("UNION "+binText(b.first)+" count="+std::to_string(b.second));
    for(const auto& b:sections) {
        histogram.line("SECTION id="+std::to_string(std::get<0>(b.first))+" "+binText({std::get<1>(b.first),std::get<2>(b.first),std::get<3>(b.first)})+" count="+std::to_string(b.second));
    }
    // Retain evidence for gaps in the actual preset first, determined from data, not fixed keys.
    std::set<Bin> holes;
    for(const auto& l:lives) if(l.verified && l.inCandidateSource) for(const auto& b:bins) if(std::get<0>(b.first)==l.channel) {
        const auto m=sf2_zones::match(inv,l.bank,l.pc,std::get<1>(b.first),std::get<2>(b.first));
        if(m.known && !m.zones) holes.insert(b.first);
    }
    std::vector<Bin> ordered;
    for(const auto& b:bins) if(holes.count(b.first)) ordered.push_back(b.first);
    for(const auto& b:bins) if(!holes.count(b.first)) ordered.push_back(b.first);
    layers.line("MATCH scope=all_candidates/all_source_bins; active_missing_bins_first; layers=eligible_not_actual_BASS_voice; musicalIdentity=UNKNOWN");
    // Zone IDs are stable within a kit vector, not global sample identities.
    std::set<std::tuple<int,int,size_t>> described;
    for(const auto& b:ordered) if(inv.valid) for(const auto& kit:inv.presets) {
        std::string ids; size_t matches=0;
        for(size_t i=0;i<kit.second.size();++i) if(eligible(kit.second[i],std::get<1>(b),std::get<2>(b))) {
            ++matches;
            if(ids.size()<160) ids+=std::to_string(i)+",";
            if(!described.insert({kit.first.first,kit.first.second,i}).second) continue;
            const auto& z=kit.second[i];
            layers.line("ZONE "+kitText(kit.first.first,kit.first.second)+" z="+std::to_string(i)+
                " bags="+std::to_string(z.presetBag)+":"+std::to_string(z.instrumentBag)+" sampleId="+std::to_string(z.sampleId)+
                " sample='"+label(z.sample,40)+"' instrument='"+label(z.instrument,40)+"' keys="+std::to_string(z.keyLow)+":"+std::to_string(z.keyHigh)+
                " velocities="+std::to_string(z.velLow)+":"+std::to_string(z.velHigh)+" frames="+std::to_string(z.start)+":"+std::to_string(z.end)+
                " rate="+std::to_string(z.sampleRate)+" sampleType="+std::to_string(z.sampleType)+
                " exclusiveClass="+std::to_string(sf2_zones::effective(z,57))+" attenuationCb="+std::to_string(sf2_zones::effective(z,48)));
        }
        layers.line("MATCH "+kitText(kit.first.first,kit.first.second)+" "+binText(b)+" activeGap="+std::to_string(holes.count(b))+
            " status="+(!inv.valid?"UNKNOWN":matches?"COVERED":"MISSING_ZONE")+" eligibleLayers="+std::to_string(matches)+
            " zones="+(ids.empty()?"none":ids)+(ids.size()>=160?" [IDs_capped]":""));
    }
    return state.finish("state")+summary.finish("kits")+histogram.finish("demand")+layers.finish("zones");
}
} // namespace drum_compat

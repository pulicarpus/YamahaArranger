#pragma once
#include "drum_compatibility_report.h"

namespace drum_compat {
// Focused export: user-specified candidates, never an audio/selection decision.
inline std::string velocityCounts(const std::map<int,int64_t>& bins) {
    std::string text;
    for(const auto& v:bins) text+=std::to_string(v.first)+"x"+std::to_string(v.second)+",";
    return text;
}
inline std::string zoneIds(const std::vector<size_t>& ids) {
    std::string text;for(const auto id:ids) text+=std::to_string(id)+",";return text.empty()?"none":text;
}
inline std::string rawGenerators(const sf2_zones::Generators& g) {
    std::string text;
    for(int op=0;op<61;++op) if(g.has(op)) text+=std::to_string(op)+":"+std::to_string(g.amount[op])+",";
    return text.empty()?"none":text;
}
inline std::string rawMods(const sf2_zones::Generators& g) {
    std::string text;
    for(const auto& m:g.mods) text+=std::to_string(m.source)+":"+std::to_string(m.destination)+":"+
        std::to_string(m.amount)+":"+std::to_string(m.amountSource)+":"+std::to_string(m.transform)+",";
    return text.empty()?"none":text;
}
inline std::string comparisonReport(const sf2_zones::Inventory& inv,const std::string& source,
        const std::vector<Demand>& demand,const std::vector<Live>& lives,
        const std::vector<std::pair<int,int>>& requested) {
    std::set<std::pair<int,int>> kits(requested.begin(),requested.end());
    if(kits.empty() || kits.size()>4) return "DRUM COMPARE unavailable: select 1..4 unique bank:PC candidates\n";
    for(const auto& kit:kits) if(kit.first<0 || kit.first>65535 || kit.second<0 || kit.second>127)
        return "DRUM COMPARE unavailable: invalid bank/PC input\n";
    using Key=std::pair<int,int>;
    std::map<Key,std::map<int,int64_t>> keys;
    std::map<Key,int64_t> gap,unknown;
    size_t invalid=0;int64_t total=0;
    for(const auto& d:demand) {
        if(d.section<0 || (d.channel!=8 && d.channel!=9) || d.key<0 || d.key>127 || d.velocity<1 || d.velocity>127 || d.count<=0) {++invalid;continue;}
        keys[{d.channel,d.key}][d.velocity]+=int64_t(d.count);total+=int64_t(d.count);
    }
    for(const auto& key:keys) for(const auto& v:key.second) {
        auto live=std::find_if(lives.begin(),lives.end(),[&](const Live& l){return l.channel==key.first.first;});
        const auto m=(live!=lives.end() && live->verified && live->inCandidateSource)?
            sf2_zones::match(inv,live->bank,live->pc,key.first.second,v.first):sf2_zones::Match{};
        if(!m.known) unknown[key.first]+=v.second;
        else if(!m.zones) gap[key.first]+=v.second;
    }
    std::vector<Key> order;for(const auto& k:keys) order.push_back(k.first);
    std::sort(order.begin(),order.end(),[&](const Key& a,const Key& b){
        if(gap[a]!=gap[b]) return gap[a]>gap[b];
        int64_t na=0,nb=0;for(const auto& v:keys.at(a))na+=v.second;for(const auto& v:keys.at(b))nb+=v.second;
        return na!=nb?na>nb:a<b;
    });
    Block state(4096),summary(4096),mapping(20480);
    std::vector<std::tuple<int,int,size_t>> zoneOrder;
    state.line("DRUM COMPARE v2 maxNativeBytes=40960 channel/PC=zero_based user_selected_candidates_only playback=UNCHANGED");
    state.line("COVERAGE=eligible_metadata; musicalIdentity=UNKNOWN; actual_BASS_sample_voice_ID=unavailable; PCM=not_measured");
    state.line("DEMAND=source_NOTE_ON_each_section_once; no_remap; masks/mutes/chord_repetitions=not_evaluated; priority=actual_missing_hits_then_demand_hits_NOT_kit_selection");
    state.line("SOURCE SF2='"+label(source,256)+"' metadataKnown="+std::to_string(inv.valid)+" reason='"+label(inv.reason)+"' sourceHits="+std::to_string(total)+" channelKeys="+std::to_string(keys.size())+" invalidRows="+std::to_string(invalid));
    if(keys.empty()) state.line("DEMAND empty_or_unavailable; no_compatibility_claim; see_profile_demandComplete");
    for(const auto& l:lives) state.line("LIVE export_time ch="+std::to_string(l.channel)+" nativeRequestBank="+std::to_string(l.requestBank)+" requestPC="+std::to_string(l.requestPc)+
        " effectivePC="+std::to_string(l.effectivePc)+" actualKnown="+std::to_string(l.verified)+" actualBank="+std::to_string(l.bank)+" actualPC="+std::to_string(l.pc)+
        " preset='"+label(l.name)+"' inCandidateSource="+std::to_string(l.inCandidateSource)+" mappingGeneration="+std::to_string(l.generation)+" fallbackReason="+label(l.reason,160));
    for(const auto& kit:kits) {
        auto name=inv.presetNames.find(kit);
        summary.line("CANDIDATE "+kitText(kit.first,kit.second)+" name='"+label(name==inv.presetNames.end()?"unknown":name->second)+"' presetMetadataKnown="+
            std::to_string(inv.valid && inv.presets.count(kit))+" musicalIdentity=UNKNOWN (names_are_not_evidence)");
        for(int ch:{8,9}) {
            int64_t count=0,covered=0,missing=0,unknownHits=0,gained=0,lost=0,deltaUnknown=0;size_t missingBins=0;
            auto live=std::find_if(lives.begin(),lives.end(),[&](const Live& l){return l.channel==ch;});
            for(const auto& key:keys) if(key.first.first==ch) for(const auto& v:key.second) {
                count+=v.second;
                const auto m=sf2_zones::match(inv,kit.first,kit.second,key.first.second,v.first);
                if(!m.known)unknownHits+=v.second;else if(m.zones)covered+=v.second;else {missing+=v.second;++missingBins;}
                const auto active=(live!=lives.end() && live->verified && live->inCandidateSource)?sf2_zones::match(inv,live->bank,live->pc,key.first.second,v.first):sf2_zones::Match{};
                if(!m.known || !active.known)deltaUnknown+=v.second;
                else if(m.zones && !active.zones)gained+=v.second;
                else if(!m.zones && active.zones)lost+=v.second;
            }
            summary.line("COVERAGE "+kitText(kit.first,kit.second)+" ch="+std::to_string(ch)+" coveredHits="+std::to_string(covered)+"/"+std::to_string(count)+
                " missingHits="+std::to_string(missing)+" missingBins="+std::to_string(missingBins)+" unknownHits="+std::to_string(unknownHits)+
                " gainedVsActual="+std::to_string(gained)+" lostVsActual="+std::to_string(lost)+" deltaUnknownHits="+std::to_string(deltaUnknown));
        }
    }
    std::set<std::tuple<int,int,size_t>> described;
    for(const auto& key:order) {
        const auto& bins=keys.at(key);int64_t count=0;for(const auto& v:bins)count+=v.second;
        mapping.line("KEY ch="+std::to_string(key.first)+" key="+std::to_string(key.second)+" hits="+std::to_string(count)+
            " activeMissingHits="+std::to_string(gap[key])+" activeUnknownHits="+std::to_string(unknown[key])+" velCounts="+velocityCounts(bins));
        for(const auto& kit:kits) {
            const auto found=inv.presets.find(kit);
            const bool known=inv.valid && found!=inv.presets.end();
            std::map<std::vector<size_t>,std::map<int,int64_t>> groups;
            for(const auto& v:bins) {
                std::vector<size_t> ids;
                if(known) for(size_t i=0;i<found->second.size();++i) if(eligible(found->second[i],key.second,v.first))ids.push_back(i);
                groups[ids][v.first]+=v.second;
            }
            for(const auto& group:groups) {
                const auto& ids=group.first;
                std::set<int> samples;int64_t hits=0;for(const auto& v:group.second)hits+=v.second;
                if(known)for(const auto i:ids)samples.insert(found->second[i].sampleId);
                std::string sampleText;for(const auto id:samples)sampleText+=std::to_string(id)+",";
                mapping.line("MAP "+kitText(kit.first,kit.second)+" ch="+std::to_string(key.first)+" key="+std::to_string(key.second)+
                    " status="+(!known?"UNKNOWN":ids.empty()?"MISSING_ZONE":"COVERED")+" hits="+std::to_string(hits)+" velCounts="+velocityCounts(group.second)+
                    " eligibleLayers="+(known?std::to_string(ids.size()):"UNKNOWN")+" zones="+zoneIds(ids)+" uniqueSamples="+(sampleText.empty()?"none":sampleText)+" musicalIdentity=UNKNOWN");
                if(known)for(const auto i:ids) if(described.insert({kit.first,kit.second,i}).second) {
                    zoneOrder.emplace_back(kit.first,kit.second,i);
                }
            }
        }
    }
    const auto prefix=state.finish("state")+summary.finish("candidates")+mapping.finish("mapping");
    // Mapping/state retain independent budgets; zones borrow unused space after those blocks.
    Block zones(REPORT_BYTES-prefix.size());
    for(const auto& ref:zoneOrder) {
        const std::pair<int,int> kit={std::get<0>(ref),std::get<1>(ref)};
        const auto i=std::get<2>(ref);
        const auto& z=inv.presets.at(kit)[i];
                    zones.line("ZONE "+kitText(kit.first,kit.second)+" z="+std::to_string(i)+" bags="+std::to_string(z.presetBag)+":"+std::to_string(z.instrumentBag)+
                        " sampleId="+std::to_string(z.sampleId)+" sample='"+label(z.sample,40)+"' instrument='"+label(z.instrument,40)+"' keys="+std::to_string(z.keyLow)+":"+std::to_string(z.keyHigh)+
                        " velocities="+std::to_string(z.velLow)+":"+std::to_string(z.velHigh)+" frames="+std::to_string(z.start)+":"+std::to_string(z.end)+" loops="+std::to_string(z.loopStart)+":"+std::to_string(z.loopEnd)+
                        " rate="+std::to_string(z.sampleRate)+" type="+std::to_string(z.sampleType)+" link="+std::to_string(z.sampleLink)+" root="+std::to_string(z.rootKey)+" correction="+std::to_string(z.pitchCorrection)+
                        " pgen="+rawGenerators(z.presetGenerators)+" igen="+rawGenerators(z.instrumentGenerators)+" modsKnown="+std::to_string(z.presetGenerators.modsKnown)+":"+std::to_string(z.instrumentGenerators.modsKnown)+
                        " pmod="+rawMods(z.presetGenerators)+" imod="+rawMods(z.instrumentGenerators));
    }
    zones.line("ZONE sampleId/frame/rate/type/link describe cached SF2 structure; repeated sampleIds do not prove redundant BASS voices; actual_pitch/envelope/PCM require audition/reference. Default SF2 modulators and BASS overrides are not enumerated.");
    return prefix+zones.finish("zones");
}
} // namespace drum_compat

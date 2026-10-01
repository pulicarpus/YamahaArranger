#pragma once
#include <chrono>
#include <string>
#include <vector>
#include <deque>
#include <sstream>
#include "audio_path_diagnostic.h"
#include "voice_resolver.h"

// Explicit, finite capture. No logging/formatting, file IO or SF2 scan at send.
namespace chord_diagnostic {
inline uint64_t monoNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
inline int64_t wallMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
struct Row {
    AudioPathOrigin origin;
    uint64_t captureOrder=0, midiOrder=0, mono=0, mapGeneration=0;
    int64_t windowChordId=0;
    int64_t wall=0;
    std::string stage, requestedName, selectedName, liveName, liveSf2, controlType;
    int channel=0,key=-1,velocity=0,sent=-1,error=0,event=0,param=0;
    int requestedBank=-1,requestedPc=-1,initialized=0,drum=0;
    int dstMsb=-1,dstLsb=-1,dstPc=-1,sourceRawBank=-1,sourcePc=-1;
    unsigned expectedFont=0,liveFont=0;
    int expectedBank=-1,liveBank=-1,livePc=-1,liveOk=0,mappingMatch=0;
    int cc7=-1,cc11=-1;
};
class Capture {
public:
    static constexpr size_t cap=4096;
    std::vector<Row> rows;
    uint64_t deadline=0, dropped=0, order=0;
    bool armed=false;
    uint64_t windowUntil=0;
    int64_t windowChordId=0;
    std::deque<Row> recentPiano;
    void mark(int64_t id,uint64_t now=monoNs()) {
        if(!active(now) || !id) return;
        windowChordId=id; windowUntil=now+750000000ULL;
        // Pending ch11 context is bounded to 32 rows / 250ms and flushed once.
        for(auto& row:recentPiano) if(row.mono<=now && now-row.mono<=250000000ULL) {
            row.windowChordId=id; retain(std::move(row));
        }
        recentPiano.clear();
    }
    bool interested(int channel,const AudioPathOrigin& origin,bool control,uint64_t now) const {
        return active(now) && (channel==11 || (origin.operation==1 && origin.chordId!=0) ||
            (control && windowChordId!=0 && now<windowUntil));
    }
    void retain(Row row) {
        if(rows.size()==cap) { ++dropped; return; }
        rows.push_back(std::move(row));
    }
    void arm(uint64_t now=monoNs()) {
        rows.clear(); rows.reserve(cap); dropped=order=0;
        recentPiano.clear(); windowUntil=0; windowChordId=0;
        deadline=now+60000000000ULL; armed=true;
    }
    bool active(uint64_t now=monoNs()) const { return armed && now<deadline; }
    void stop() { armed=false; recentPiano.clear(); }
    void append(Row row) {
        if(!active(row.mono)) return;
        row.captureOrder=++order;
        if(row.origin.operation==1 && row.origin.chordId!=0 && windowChordId!=row.origin.chordId)
            mark(row.origin.chordId,row.mono); // Defensive fallback for diagnostic callers.
        row.windowChordId=windowChordId;
        if(row.stage=="BASELINE" || (row.origin.operation==1 && row.origin.chordId!=0) ||
            (windowChordId!=0 && row.mono<windowUntil)) { retain(std::move(row)); return; }
        if(row.channel==11) {
            while(!recentPiano.empty() && row.mono-recentPiano.front().mono>250000000ULL) recentPiano.pop_front();
            if(recentPiano.size()==32) recentPiano.pop_front();
            recentPiano.push_back(std::move(row));
        }
    }
};
// UTF-8-safe name shortening, explicit marker; never split a multibyte codepoint.
inline std::string shortName(const std::string& name) {
    if(name.size()<=64) return name;
    size_t end=61; while(end && (static_cast<unsigned char>(name[end])&0xc0)==0x80) --end;
    return name.substr(0,end)+"...";
}
inline std::string compactReport(const Capture& capture) {
    constexpr size_t byteCap=30*1024, reservedFooter=160;
    std::ostringstream head;
    head << "=== CHORD NATIVE COMPACT ===\nrows=" << capture.rows.size() << " captureDropped=" << capture.dropped
         << " active=" << capture.active() << " maxBytes=" << byteCap
         << " windows=chord_minus250ms_plus750ms ch11_priority\n"
         << "NOTE_PRE/POST=NOTE_ON OFF_PRE/POST=NOTE_OFF; sent=-1 before,0 rejected,1 accepted. "
         << "context=window chord; chordId/id=event origin. order/midiOrder preserve order across priority groups. "
         << "live=font:bank:PC; preset is BASS readback, not voice sample/PCM. Names over64 UTF8 bytes have ... .\n";
    std::string out=head.str(); size_t omitted=0;
    // Preserve ch11 evidence first; another channel's actual Piano/mismatch next.
    auto priority=[](const Row& r) { return r.channel==11 ? 0 : r.livePc==0 || voice_resolver::namedFamily(r.liveName)==voice_resolver::Family::Piano || !r.mappingMatch ? 1 : 2; };
    for(int group=0;group<3;++group) for(const auto& row:capture.rows) {
        if(priority(row)!=group) continue;
        std::ostringstream line;
        line << "N order=" << row.captureOrder << " m=" << row.midiOrder << " t=" << row.wall
             << " context=" << row.windowChordId << " chordId=" << row.origin.chordId << " id=" << row.origin.id
             << " op=" << row.origin.operation << " " << row.stage << " dst=" << row.channel
             << " src=" << row.origin.sourceChannel << " original=" << row.origin.sourceNote << " output=" << row.key
             << " v=" << row.velocity << " sent=" << row.sent << " error=" << row.error
             << " request=" << row.requestedBank << ':' << row.requestedPc << " voice='" << shortName(row.requestedName)
             << "' dstState=" << row.dstMsb << ':' << row.dstLsb << ':' << row.dstPc
             << " live=" << row.liveFont << ':' << row.liveBank << ':' << row.livePc
             << " preset='" << shortName(row.liveName) << "' sf2='" << shortName(row.liveSf2)
             << "' family=" << voice_resolver::familyName(voice_resolver::namedFamily(row.liveName))
             << " expected=" << row.expectedFont << ":" << row.expectedBank << ":" << row.sourcePc
             << " liveOk=" << row.liveOk << " match=" << row.mappingMatch << " init=" << row.initialized
             << " gen=" << row.mapGeneration << " CC=" << row.cc7 << '/' << row.cc11;
        if(row.stage=="CONTROL_POST") line << " control=" << row.controlType << " value=" << row.param;
        line << '\n';
        const auto text=line.str();
        if(out.size()+text.size()+reservedFooter>byteCap) { ++omitted; continue; }
        out+=text;
    }
    out+="exportOmittedRows="+std::to_string(omitted)+" (captureDropped is separate).\n";
    return out;
}
}

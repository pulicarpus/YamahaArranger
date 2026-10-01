#pragma once
#include <chrono>
#include <string>
#include <vector>
#include "audio_path_diagnostic.h"

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
    int64_t wall=0;
    std::string stage, requestedName, selectedName, liveName, liveSf2;
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
    void arm(uint64_t now=monoNs()) {
        rows.clear(); rows.reserve(cap); dropped=order=0;
        deadline=now+60000000000ULL; armed=true;
    }
    bool active(uint64_t now=monoNs()) const { return armed && now<deadline; }
    void stop() { armed=false; }
    void append(Row row) {
        if(!active(row.mono)) return;
        row.captureOrder=++order;
        if(rows.size()==cap) { ++dropped; return; }
        rows.push_back(std::move(row));
    }
};
}

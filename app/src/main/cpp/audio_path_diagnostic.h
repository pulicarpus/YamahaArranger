#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>

// Observation only: never changes MIDI, mixer, presets, routing or rendered PCM.
struct AudioPathOrigin {
    int sourceChannel = -1, sourceNote = -1, styleBank = -1;
    int64_t tick = -1, id = 0;
    bool sampled = false;
};
namespace audio_path {
inline bool snare(int note) { return note == 38 || note == 40; }
inline const char* drumName(int note) {
    switch (note) {
        case 35: case 36: return "GM_KICK";
        case 37: return "GM_SIDE_STICK";
        case 38: return "GM_ACOUSTIC_SNARE";
        case 39: return "GM_HAND_CLAP";
        case 40: return "GM_ELECTRIC_SNARE";
        default: return "KIT_SPECIFIC_UNVERIFIED";
    }
}
struct Stats {
    uint64_t attempts=0, sent=0, rejected=0, offs=0, orphanOffs=0, shortOffs=0;
    uint64_t snare38=0, snare38Sent=0, snare40=0, snare40Sent=0, ccZero=0;
    uint64_t velocitySum=0, durationSum=0, durations=0, ledgerOverflow=0;
    int velocityMin=128, velocityMax=0;
    double controlLevelSum=0;
};
class Channel {
public:
    Stats stats;
    int cc7=-1, cc11=-1, requestedBank=-1, requestedPc=-1;
    uint64_t window=0;
    bool started=false;
    void on(int key, int vel, bool sent, int volume, int expression, uint64_t now, bool drum = false) {
        ++stats.attempts; stats.sent+=sent; stats.rejected+=!sent;
        stats.velocityMin=std::min(stats.velocityMin,vel); stats.velocityMax=std::max(stats.velocityMax,vel);
        stats.velocitySum+=vel;
        if (volume==0 || expression==0) ++stats.ccZero;
        if (volume>=0 && expression>=0) stats.controlLevelSum+=double(vel)*volume*expression/(127.0*127*127);
        if (drum && key==38) { ++stats.snare38; stats.snare38Sent+=sent; }
        if (drum && key==40) { ++stats.snare40; stats.snare40Sent+=sent; }
        if (sent) {
            auto& times=notes_[key];
            if (times.size()==16) { times.pop_front(); ++stats.ledgerOverflow; }
            times.push_back(now);
        }
    }
    int64_t off(int key, bool sent, uint64_t now) {
        if (!sent) return -1;
        ++stats.offs;
        auto& times=notes_[key];
        if (times.empty()) { ++stats.orphanOffs; return -1; }
        const auto ms=now-times.front(); times.pop_front();
        stats.shortOffs+=ms<80; stats.durationSum+=ms; ++stats.durations;
        return static_cast<int64_t>(ms);
    }
    size_t pending() const { size_t n=0; for (const auto& q:notes_) n+=q.size(); return n; }
    void allOff() { for (auto& q:notes_) q.clear(); }
    bool sample(uint64_t now, bool isSnare) {
        if (!started || now-window>=2000) { started=true; window=now; normal_=special_=0; }
        auto& count=isSnare?special_:normal_;
        if (count>=4) return false;
        ++count; return true;
    }
    bool summaryDue(uint64_t now) {
        if (!summaryStarted_) { summaryStarted_=true; summaryAt_=now; return false; }
        if (now-summaryAt_<2000) return false;
        summaryAt_=now; return true;
    }
    void resetSummary() { stats=Stats{}; }
private:
    std::array<std::deque<uint64_t>,128> notes_{};
    unsigned normal_=0, special_=0;
    bool summaryStarted_=false;
    uint64_t summaryAt_=0;
};
} // namespace audio_path

#pragma once
// P-B0.1 TARGETED INSTRUMENTATION (observer only; see diagnostics/PB01_TARGETED_INSTRUMENTATION.md).
// Measures, per thread ROLE and per OPERATION, how long a call waits for BassMidiPlayer::mutex_ and how long it holds it,
// the cost of applyFonts() / BASS_MIDI_StreamSetFonts attributed to the operation that triggered it, and per-window totals for a
// section voice apply and a chord retarget. It never changes an event, its order, a font, a preset or a lock.
// Hot path cost when enabled: a few steady_clock reads + relaxed atomics; when disabled: one relaxed load. No allocation, no logging,
// no JNI, no lock. Reports are formatted by the caller, outside any realtime path.
#include "latency_probe.h"
#include <atomic>
#include <cstdint>
#include <sstream>
#include <string>

namespace pb01 {
enum Role : unsigned { RoleAudio = 0, RoleSched, RoleMain, RoleOther, RoleCount };
enum Op : unsigned { OpNoteOn = 0, OpNoteOff, OpRender, OpPreset, OpMixer, OpBindDrumAB, OpOther, OpCount };
inline constexpr const char* kRoleNames[RoleCount] = {"audio", "scheduler", "main", "other"};
inline constexpr const char* kOpNames[OpCount] = {"noteOn", "noteOff", "render", "setChannelPreset", "setChannelMixer", "bindDrumAB", "other"};

inline std::atomic<uint32_t> enabled{1};
inline thread_local int role = -1;        // set once per thread by the app (JNI) or per callback (audio); -1 = other
inline thread_local int currentOp = -1;   // innermost probed operation on this thread (applyFonts attribution)

inline bool on() { return enabled.load(std::memory_order_relaxed) != 0; }
inline uint64_t now() { return latency_probe::now(); }

struct Cell { latency_probe::Stat wait, hold; };
inline Cell cells[RoleCount][OpCount];
inline latency_probe::Stat applyFonts[OpCount];   // applyFonts() duration by the operation that called it (inside the caller's hold)
inline latency_probe::Stat setFonts;              // BASS_MIDI_StreamSetFonts() alone
inline std::atomic<uint32_t> fontEntriesLast{0}, fontEntriesMax{0};
// Cumulative totals (ns) for window deltas.
inline std::atomic<uint64_t> cumWaitNs[RoleCount][OpCount]{}, cumHoldNs[RoleCount][OpCount]{}, cumCalls[RoleCount][OpCount]{};
inline std::atomic<uint64_t> cumApplyFontsNs{0}, cumApplyFontsCalls{0}, cumSetFontsNs{0};

inline unsigned roleNow() { return role >= 0 && role < int(RoleCount) ? unsigned(role) : unsigned(RoleOther); }

/** Declare BEFORE the lock (so it is destroyed after the lock is released); call locked() right after the lock is taken. */
struct Scope {
    uint64_t enter = 0, lockedAt = 0; unsigned op; int prevOp; unsigned forcedRole; bool active;
    explicit Scope(Op o, int forceRole = -1) : op(o), prevOp(currentOp), forcedRole(forceRole < 0 ? RoleCount : unsigned(forceRole)), active(on()) {
        currentOp = int(o);
        if (active) enter = now();
    }
    void locked() { if (active) lockedAt = now(); }
    ~Scope() {
        currentOp = prevOp;
        if (!active || !lockedAt) return;
        const uint64_t end = now();
        const unsigned r = forcedRole < RoleCount ? forcedRole : roleNow();
        const uint64_t w = lockedAt - enter, h = end - lockedAt;
        cells[r][op].wait.record(w); cells[r][op].hold.record(h);
        cumWaitNs[r][op].fetch_add(w, std::memory_order_relaxed); cumHoldNs[r][op].fetch_add(h, std::memory_order_relaxed);
        cumCalls[r][op].fetch_add(1, std::memory_order_relaxed);
    }
    Scope(const Scope&) = delete; Scope& operator=(const Scope&) = delete;
};

/** applyFonts() duration, attributed to the enclosing probed operation. */
struct FontsTimer {
    uint64_t start; bool active;
    FontsTimer() : start(0), active(on()) { if (active) start = now(); }
    ~FontsTimer() {
        if (!active) return;
        const uint64_t d = now() - start;
        const unsigned o = currentOp >= 0 && currentOp < int(OpCount) ? unsigned(currentOp) : unsigned(OpOther);
        applyFonts[o].record(d);
        cumApplyFontsNs.fetch_add(d, std::memory_order_relaxed); cumApplyFontsCalls.fetch_add(1, std::memory_order_relaxed);
    }
    FontsTimer(const FontsTimer&) = delete; FontsTimer& operator=(const FontsTimer&) = delete;
};
inline uint64_t stamp() { return on() ? now() : 0; }
inline void setFontsDone(uint64_t start, uint32_t entries) {
    if (!start) return;
    const uint64_t d = now() - start;
    setFonts.record(d); cumSetFontsNs.fetch_add(d, std::memory_order_relaxed);
    fontEntriesLast.store(entries, std::memory_order_relaxed);
    auto m = fontEntriesMax.load(std::memory_order_relaxed);
    while (m < entries && !fontEntriesMax.compare_exchange_weak(m, entries, std::memory_order_relaxed)) {}
}

// ---- Windows (one section voice apply / one chord retarget). Start/end come from the app, never from the audio thread. ----
struct Snapshot { uint64_t wait[RoleCount][OpCount], hold[RoleCount][OpCount], calls[RoleCount][OpCount], fontsNs, fontsCalls, setFontsNs; };
inline Snapshot take() {
    Snapshot s{};
    for (unsigned r = 0; r < RoleCount; ++r) for (unsigned o = 0; o < OpCount; ++o) {
        s.wait[r][o] = cumWaitNs[r][o].load(std::memory_order_relaxed); s.hold[r][o] = cumHoldNs[r][o].load(std::memory_order_relaxed);
        s.calls[r][o] = cumCalls[r][o].load(std::memory_order_relaxed);
    }
    s.fontsNs = cumApplyFontsNs.load(std::memory_order_relaxed); s.fontsCalls = cumApplyFontsCalls.load(std::memory_order_relaxed);
    s.setFontsNs = cumSetFontsNs.load(std::memory_order_relaxed);
    return s;
}
struct Window {
    Snapshot base{}; bool open = false;
    latency_probe::Stat total, nativeAll, nonNative, applyFontsSum, setFontsSum, bindDrumAB, preset, mixer, noteOn, noteOff, lockWaitSum, perReplacement;
    std::atomic<uint64_t> windows{0}, fontCallsSum{0}, fontCallsMax{0}, acquisitionsSum{0}, acquisitionsMax{0}, replacementsSum{0};
    void reset() {
        for (auto* s : {&total, &nativeAll, &nonNative, &applyFontsSum, &setFontsSum, &bindDrumAB, &preset, &mixer, &noteOn, &noteOff, &lockWaitSum, &perReplacement}) s->reset();
        windows = 0; fontCallsSum = 0; fontCallsMax = 0; acquisitionsSum = 0; acquisitionsMax = 0; replacementsSum = 0; open = false;
    }
};
inline Window voiceWin, chordWin;   // voiceWin: scheduler role; chordWin: main role (retarget runs on the UI thread)
inline std::atomic<int> winLock{0}; // windows are opened/closed by app threads only; tiny spin guard for the snapshot pair

inline void maxStore(std::atomic<uint64_t>& a, uint64_t v) { auto m = a.load(std::memory_order_relaxed); while (m < v && !a.compare_exchange_weak(m, v, std::memory_order_relaxed)) {} }
inline void windowEdge(Window& w, unsigned r, bool start, uint64_t kotlinNs, uint64_t replacements) {
    if (!on()) return;
    int expected = 0; while (!winLock.compare_exchange_weak(expected, 1, std::memory_order_acquire)) expected = 0;
    if (start) { w.base = take(); w.open = true; }
    else if (w.open) {
        const Snapshot e = take(); w.open = false;
        uint64_t nat = 0, waitSum = 0, acq = 0;
        auto d = [&](unsigned o) { const uint64_t x = (e.wait[r][o] - w.base.wait[r][o]) + (e.hold[r][o] - w.base.hold[r][o]); nat += x; return x; };
        const uint64_t bind = d(OpBindDrumAB), pre = d(OpPreset), mix = d(OpMixer), non = d(OpNoteOn), nof = d(OpNoteOff); d(OpOther);
        for (unsigned o = 0; o < OpCount; ++o) { waitSum += e.wait[r][o] - w.base.wait[r][o]; acq += e.calls[r][o] - w.base.calls[r][o]; }
        const uint64_t fonts = e.fontsNs - w.base.fontsNs, fontCalls = e.fontsCalls - w.base.fontsCalls, sf = e.setFontsNs - w.base.setFontsNs;
        w.total.record(kotlinNs); w.nativeAll.record(nat); w.nonNative.record(kotlinNs > nat ? kotlinNs - nat : 0);
        w.applyFontsSum.record(fonts); w.setFontsSum.record(sf); w.bindDrumAB.record(bind); w.preset.record(pre); w.mixer.record(mix);
        w.noteOn.record(non); w.noteOff.record(nof); w.lockWaitSum.record(waitSum);
        if (replacements) { w.perReplacement.record(kotlinNs / replacements); w.replacementsSum.fetch_add(replacements, std::memory_order_relaxed); }
        w.windows.fetch_add(1, std::memory_order_relaxed);
        w.fontCallsSum.fetch_add(fontCalls, std::memory_order_relaxed); maxStore(w.fontCallsMax, fontCalls);
        w.acquisitionsSum.fetch_add(acq, std::memory_order_relaxed); maxStore(w.acquisitionsMax, acq);
    }
    winLock.store(0, std::memory_order_release);
}

inline void reset() {
    for (auto& row : cells) for (auto& c : row) { c.wait.reset(); c.hold.reset(); }
    for (auto& a : applyFonts) a.reset();
    setFonts.reset(); fontEntriesLast = 0; fontEntriesMax = 0;
    for (auto& row : cumWaitNs) for (auto& a : row) a = 0;
    for (auto& row : cumHoldNs) for (auto& a : row) a = 0;
    for (auto& row : cumCalls) for (auto& a : row) a = 0;
    cumApplyFontsNs = 0; cumApplyFontsCalls = 0; cumSetFontsNs = 0;
    voiceWin.reset(); chordWin.reset();
}

inline void printWindow(std::ostringstream& out, const char* tag, const Window& w) {
    const uint64_t n = w.windows.load();
    out << "PB01_WINDOW " << tag << " windows=" << n << " applyFontsCallsPerWindowMean=" << (n ? w.fontCallsSum.load() / double(n) : 0.0)
        << " applyFontsCallsMax=" << w.fontCallsMax.load() << " mutexAcquisitionsPerWindowMean=" << (n ? w.acquisitionsSum.load() / double(n) : 0.0)
        << " mutexAcquisitionsMax=" << w.acquisitionsMax.load() << " replacements=" << w.replacementsSum.load() << "\n";
    std::string p = std::string("PB01_") + tag + "_";
    w.total.print(out, (p + "totalAsSeenByKotlin").c_str()); w.nativeAll.print(out, (p + "nativeWaitPlusHold").c_str());
    w.nonNative.print(out, (p + "nonNativeRemainder").c_str()); w.lockWaitSum.print(out, (p + "mutexWaitSum").c_str());
    w.applyFontsSum.print(out, (p + "applyFontsSum").c_str()); w.setFontsSum.print(out, (p + "streamSetFontsSum").c_str());
    w.bindDrumAB.print(out, (p + "bindDrumAB").c_str()); w.preset.print(out, (p + "setChannelPreset").c_str()); w.mixer.print(out, (p + "setChannelMixer").c_str());
    w.noteOn.print(out, (p + "noteOn").c_str()); w.noteOff.print(out, (p + "noteOff").c_str()); w.perReplacement.print(out, (p + "perReplacement").c_str());
}

inline std::string report() {
    std::ostringstream out;
    out << "PB01 enabled=" << enabled.load() << " (P-B0.1 targeted probes; counters since RESET LATENCY; LATENCY_NATIVE lines below use the same units)\n";
    for (unsigned r = 0; r < RoleCount; ++r) for (unsigned o = 0; o < OpCount; ++o) {
        if (!cells[r][o].wait.count.load()) continue;
        const std::string base = std::string("PB01_mutex_") + kRoleNames[r] + "_" + kOpNames[o];
        cells[r][o].wait.print(out, (base + "_wait").c_str()); cells[r][o].hold.print(out, (base + "_hold").c_str());
    }
    for (unsigned o = 0; o < OpCount; ++o) if (applyFonts[o].count.load()) applyFonts[o].print(out, (std::string("PB01_applyFonts_by_") + kOpNames[o]).c_str());
    setFonts.print(out, "PB01_streamSetFonts");
    out << "PB01_fontTable entriesLast=" << fontEntriesLast.load() << " entriesMax=" << fontEntriesMax.load() << "\n";
    printWindow(out, "voiceApply", voiceWin);
    printWindow(out, "chordRetarget", chordWin);
    return out.str();
}
}  // namespace pb01

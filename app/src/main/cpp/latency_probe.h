#pragma once
// LATENCY_PROBE: lightweight native latency statistics.
//
// Observer only. Hot paths do a few relaxed atomic operations and steady_clock
// reads; no logging, formatting, allocation, locking or JNI. Reports are built
// by the caller outside the audio callback.
//
// Clock: std::chrono::steady_clock is CLOCK_MONOTONIC on Android, the same
// clock as Java System.nanoTime(), so native and Kotlin stamps are comparable.
//
// What is and is not measured:
//   noteLockWait   NOTE_ON entry -> synth mutex acquired (contention with render/preset work)
//   noteDispatch   mutex acquired -> BASS_MIDI_StreamEvent returned (event queued in BASSMIDI)
//   noteToRender   NOTE_ON queued -> start of the next decode that consumes it
//   noteToBuffer   NOTE_ON queued -> end of that decode (samples ready for Oboe)
//   renderLockWait callback entry -> synth mutex acquired (a preset/mixer call held it)
//   renderDecode   BASS_ChannelGetData duration (+ percussion lanes)
//   callbackTotal  whole audio callback
// None of these include the Android output pipeline after the callback returns.
// That part is an ESTIMATE from Oboe (calculateLatencyMillis), reported separately.
#include <atomic>
#include <chrono>
#include <cstdint>
#include <sstream>
#include <string>

namespace latency_probe {

inline uint64_t now() {
    return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

// Upper bounds in microseconds; the last bucket is open-ended.
inline constexpr uint32_t kBounds[9] = {250, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000};
inline constexpr unsigned kBuckets = 10;

struct Stat {
    std::atomic<uint64_t> count{0}, sumUs{0};
    std::atomic<uint32_t> maxUs{0};
    std::atomic<uint32_t> bucket[kBuckets]{};
    void record(uint64_t nanos) {
        const uint64_t us64 = nanos / 1000;
        const uint32_t us = us64 > 0xFFFFFFFFull ? 0xFFFFFFFFu : uint32_t(us64);
        count.fetch_add(1, std::memory_order_relaxed);
        sumUs.fetch_add(us, std::memory_order_relaxed);
        auto old = maxUs.load(std::memory_order_relaxed);
        while (old < us && !maxUs.compare_exchange_weak(old, us, std::memory_order_relaxed)) {}
        unsigned b = 0;
        while (b < kBuckets - 1 && us >= kBounds[b]) ++b;
        bucket[b].fetch_add(1, std::memory_order_relaxed);
    }
    void reset() {
        count = 0; sumUs = 0; maxUs = 0;
        for (auto& b : bucket) b = 0;
    }
    // Smallest bucket upper bound that covers `fraction` of samples (microseconds; 0 = no data).
    uint32_t percentileUpperUs(double fraction) const {
        const uint64_t total = count.load(std::memory_order_relaxed);
        if (!total) return 0;
        const uint64_t need = uint64_t(double(total) * fraction + 0.999999);
        uint64_t seen = 0;
        for (unsigned b = 0; b < kBuckets; ++b) {
            seen += bucket[b].load(std::memory_order_relaxed);
            if (seen >= need) return b < kBuckets - 1 ? kBounds[b] : maxUs.load(std::memory_order_relaxed);
        }
        return maxUs.load(std::memory_order_relaxed);
    }
    void print(std::ostringstream& out, const char* name) const {
        const uint64_t n = count.load(std::memory_order_relaxed);
        out << "LATENCY_NATIVE stat=" << name << " count=" << n;
        if (n) {
            out << " meanUs=" << sumUs.load(std::memory_order_relaxed) / n
                << " p50UpperUs=" << percentileUpperUs(0.50) << " p95UpperUs=" << percentileUpperUs(0.95)
                << " p99UpperUs=" << percentileUpperUs(0.99) << " maxUs=" << maxUs.load(std::memory_order_relaxed)
                << " buckets=";
            for (unsigned b = 0; b < kBuckets; ++b) out << (b ? "," : "") << bucket[b].load(std::memory_order_relaxed);
        }
        out << "\n";
    }
};

// Who holds the synth mutex, and for how long (a long hold is what makes render or NOTE_ON wait).
enum HoldKind : unsigned { HoldNoteKey = 0, HoldNoteStyle, HoldNoteOff, HoldRender, HoldPreset, HoldMixer, HoldFonts, HoldDiagnostic, HoldKindCount };
inline constexpr const char* kHoldNames[HoldKindCount] = {"keyboardNoteOn", "styleNoteOn", "noteOff", "render", "presetChange", "mixerController", "fontLoad", "diagnostics"};

struct Probe {
    std::atomic<uint32_t> enabled{1};
    Stat hold[HoldKindCount];
    // Keyboard RIGHT/LEFT-voice parts (channels 0..3) and style channels (4..15) are separate.
    Stat keyLockWait, keyDispatch, styleLockWait, styleDispatch;
    Stat noteToRender, noteToBuffer;
    // Style channels (incl. chord retargets): first style NOTE_ON queued after the previous render -> start / end of the decode that consumes it.
    Stat styleNoteToRender, styleNoteToBuffer;
    // Callback start-to-start interval: a value far above the burst period means a late/missed callback (underrun proxy, not an Oboe xrun count).
    Stat callbackInterval;
    std::atomic<uint64_t> lastCallbackStartNs{0}, intervalOver150pct{0};
    // Active BASSMIDI voices sampled once per callback (count, not time).
    static constexpr unsigned kVoiceBuckets = 7;
    static constexpr uint32_t kVoiceBounds[6] = {50, 100, 200, 400, 600, 800};
    std::atomic<uint64_t> voiceSamples{0}, voiceSum{0}; std::atomic<uint32_t> voiceMax{0}; std::atomic<uint32_t> voiceBucket[kVoiceBuckets]{};
    // Last callbacks: start (CLOCK_MONOTONIC ns) and duration, for offline correlation with Kotlin chord-transition stamps.
    static constexpr unsigned kTimeline = 256;
    std::atomic<uint64_t> tlStart[kTimeline]{}, tlDur[kTimeline]{}; std::atomic<uint32_t> tlN{0};
    Stat renderLockWait, renderDecode, callbackTotal;
    std::atomic<uint64_t> callbacks{0}, overBudget{0}, overEightyPercent{0};
    std::atomic<uint64_t> budgetNs{0};
    // First keyboard NOTE_ON queued and not yet consumed by a render pass (0 = none).
    std::atomic<uint64_t> pendingKeyNoteNs{0};
    std::atomic<uint64_t> pendingStyleNoteNs{0};
    // NOT lost notes: the timing probe tracks ONE pending stamp, so every extra keyboard NOTE_ON queued to BASSMIDI
    // before the next render only merges its timing sample into the first. All events still reach BASSMIDI.
    std::atomic<uint64_t> unconsumedKeyNotesOverwritten{0};
    std::atomic<uint64_t> keyNoteOnsQueued{0};

    bool on() const { return enabled.load(std::memory_order_relaxed) != 0; }
    void reset() {
        keyLockWait.reset(); keyDispatch.reset(); styleLockWait.reset(); styleDispatch.reset();
        noteToRender.reset(); noteToBuffer.reset(); renderLockWait.reset(); renderDecode.reset(); callbackTotal.reset();
        styleNoteToRender.reset(); styleNoteToBuffer.reset(); callbackInterval.reset(); lastCallbackStartNs = 0; intervalOver150pct = 0;
        voiceSamples = 0; voiceSum = 0; voiceMax = 0; for (auto& b : voiceBucket) b = 0; tlN = 0;
        callbacks = 0; overBudget = 0; overEightyPercent = 0; pendingKeyNoteNs = 0; pendingStyleNoteNs = 0;
        unconsumedKeyNotesOverwritten = 0; keyNoteOnsQueued = 0;
        for (auto& h : hold) h.reset();
    }
    // Called after BASS_MIDI_StreamEvent(NOTE) returned, with the earlier stamps.
    void noteQueued(bool keyboard, uint64_t enterNs, uint64_t lockedNs, uint64_t sentNs) {
        if (keyboard) keyNoteOnsQueued.fetch_add(1, std::memory_order_relaxed);
        (keyboard ? keyLockWait : styleLockWait).record(lockedNs - enterNs);
        (keyboard ? keyDispatch : styleDispatch).record(sentNs - lockedNs);
        auto& pending = keyboard ? pendingKeyNoteNs : pendingStyleNoteNs;
        uint64_t expected = 0;
        if (!pending.compare_exchange_strong(expected, sentNs, std::memory_order_relaxed) && keyboard)
            unconsumedKeyNotesOverwritten.fetch_add(1, std::memory_order_relaxed);
    }
    // Called by render() around the decode; all stamps are taken by the caller.
    void renderDone(uint64_t callbackStartNs, uint64_t lockedNs, uint64_t decodeEndNs) {
        renderLockWait.record(lockedNs - callbackStartNs);
        renderDecode.record(decodeEndNs - lockedNs);
        for (auto* pending : {&pendingKeyNoteNs, &pendingStyleNoteNs}) {
            const uint64_t noteNs = pending->exchange(0, std::memory_order_relaxed);
            if (noteNs && lockedNs >= noteNs) {
                if (pending == &pendingKeyNoteNs) { noteToRender.record(lockedNs - noteNs); noteToBuffer.record(decodeEndNs - noteNs); }
                else { styleNoteToRender.record(lockedNs - noteNs); styleNoteToBuffer.record(decodeEndNs - noteNs); }
            }
        }
    }
    void voices(uint32_t n) {
        voiceSamples.fetch_add(1, std::memory_order_relaxed); voiceSum.fetch_add(n, std::memory_order_relaxed);
        auto old = voiceMax.load(std::memory_order_relaxed);
        while (old < n && !voiceMax.compare_exchange_weak(old, n, std::memory_order_relaxed)) {}
        unsigned b = 0; while (b < kVoiceBuckets - 1 && n >= kVoiceBounds[b]) ++b;
        voiceBucket[b].fetch_add(1, std::memory_order_relaxed);
    }
    void callbackDone(uint64_t startNs, uint64_t endNs) {
        const uint64_t total = endNs - startNs;
        callbackTotal.record(total);
        {   // interval since the previous callback start; single writer (the audio thread)
            const uint64_t prev = lastCallbackStartNs.exchange(startNs, std::memory_order_relaxed);
            const uint64_t budget0 = budgetNs.load(std::memory_order_relaxed);
            if (prev && startNs > prev) { callbackInterval.record(startNs - prev); if (budget0 && (startNs - prev) * 2 > budget0 * 3) intervalOver150pct.fetch_add(1, std::memory_order_relaxed); }
            const uint32_t i = tlN.load(std::memory_order_relaxed) % kTimeline;
            tlStart[i].store(startNs, std::memory_order_relaxed); tlDur[i].store(total, std::memory_order_relaxed); tlN.store(tlN.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
        }
        callbacks.fetch_add(1, std::memory_order_relaxed);
        const uint64_t budget = budgetNs.load(std::memory_order_relaxed);
        if (budget) {
            if (total > budget) overBudget.fetch_add(1, std::memory_order_relaxed);
            if (total * 10 > budget * 8) overEightyPercent.fetch_add(1, std::memory_order_relaxed);
        }
    }
    std::string report() const {
        std::ostringstream out;
        const uint64_t cb = callbacks.load(std::memory_order_relaxed);
        out << "LATENCY_NATIVE clock=steady_monotonic_same_as_System.nanoTime enabled=" << enabled.load()
            << " callbacks=" << cb << " callbackBudgetUs=" << budgetNs.load() / 1000
            << " overBudget=" << overBudget.load() << " over80pct=" << overEightyPercent.load()
            << " keyNotesOverwrittenBeforeRender=" << unconsumedKeyNotesOverwritten.load()
            << " keyNoteOnsQueuedToBass=" << keyNoteOnsQueued.load()
            << " (overwritten = timing samples merged because >1 keyboard NOTE_ON shared one render period; NO event is dropped)\n";
        keyLockWait.print(out, "keyboardNoteLockWait");
        keyDispatch.print(out, "keyboardNoteDispatch");
        styleLockWait.print(out, "styleNoteLockWait");
        styleDispatch.print(out, "styleNoteDispatch");
        noteToRender.print(out, "keyboardNoteQueuedToRenderStart");
        noteToBuffer.print(out, "keyboardNoteQueuedToBufferReady");
        styleNoteToRender.print(out, "styleNoteQueuedToRenderStart_includesChordRetarget");
        styleNoteToBuffer.print(out, "styleNoteQueuedToBufferReady_includesChordRetarget");
        callbackInterval.print(out, "callbackStartToStartInterval");
        out << "LATENCY_NATIVE stat=callbackIntervalOver150pctOfBudget count=" << intervalOver150pct.load() << " (late-callback proxy; Oboe xruns are in LATENCY_OUTPUT_LIVE)\n";
        {
            const uint64_t n = voiceSamples.load();
            out << "LATENCY_NATIVE stat=activeBassVoices samples=" << n;
            if (n) { out << " mean=" << voiceSum.load() / n << " max=" << voiceMax.load() << " buckets(<50,<100,<200,<400,<600,<800,>=800)=";
                for (unsigned b = 0; b < kVoiceBuckets; ++b) out << (b ? "," : "") << voiceBucket[b].load(); }
            out << "\n";
        }
        renderLockWait.print(out, "renderLockWait");
        renderDecode.print(out, "renderDecode");
        callbackTotal.print(out, "callbackTotal");
        {
            const uint32_t n = tlN.load(); const uint32_t shown = n < 48 ? n : 48;
            for (uint32_t k = 0; k < shown; ++k) { const uint32_t i = (n - 1 - k) % kTimeline;
                out << "CALLBACK_TIMELINE back=" << k << " startNs=" << tlStart[i].load() << " durUs=" << tlDur[i].load() / 1000 << "\n"; }
        }
        for (unsigned k = 0; k < HoldKindCount; ++k) {
            std::string name = std::string("synthMutexHold_") + kHoldNames[k];
            hold[k].print(out, name.c_str());
        }
        return out.str();
    }
};

inline Probe probe;

// RAII: construct right after the synth lock is taken so the hold is measured before the lock is released.
struct Hold {
    unsigned kind; uint64_t start;
    Hold(unsigned k) : kind(k), start(probe.on() ? now() : 0) {}
    ~Hold() { if (start) probe.hold[kind].record(now() - start); }
};

} // namespace latency_probe

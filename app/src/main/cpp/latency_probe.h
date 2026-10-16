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

// Runtime tuning bits so one APK can run A (all off = previous behaviour) and B (on) with the same probe.
enum Tuning : uint32_t {
    kDrumRebindSkip = 1u << 0,   // bindDrumAB: unchanged, verified binding is not rebuilt
    kFontsNoopSkip  = 1u << 1,   // applyFonts: identical FONTEX2 table is not re-submitted to BASSMIDI
    kNativeMask     = kDrumRebindSkip | kFontsNoopSkip,
};

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

struct Probe {
    std::atomic<uint32_t> enabled{1};
    // Keyboard RIGHT/LEFT-voice parts (channels 0..3) and style channels (4..15) are separate.
    Stat keyLockWait, keyDispatch, styleLockWait, styleDispatch;
    Stat noteToRender, noteToBuffer;
    Stat renderLockWait, renderDecode, callbackTotal;
    std::atomic<uint64_t> callbacks{0}, overBudget{0}, overEightyPercent{0};
    std::atomic<uint64_t> budgetNs{0};
    // Keyboard NOTE_ONs queued and not yet consumed by a render pass. noteOn() and render() both hold
    // BassMidiPlayer::mutex_ when they touch these, so plain fields are race-free and every note is paired.
    static constexpr unsigned kPending = 32;
    uint64_t pendingKey[kPending]{};
    unsigned pendingKeyCount = 0;
    std::atomic<uint64_t> keyNotesQueued{0}, renderWindowsWithKeyNotes{0}, pairingOverflow{0};
    std::atomic<uint32_t> maxKeyNotesInOneWindow{0};
    // Internal native costs, recorded under mutex_ by the code they time.
    Stat setChannelPreset, findMelodicPreset, applyFontsTotal, streamSetFonts, bindDrum, uiLogCall;
    std::atomic<uint64_t> applyFontsCalls{0}, applyFontsSkipped{0}, drumBindFull{0}, drumBindSkipped{0};
    // Default 0 = previous behaviour, so historical native contracts stay valid. The app turns the patches on
    // (setLatencyTuning) when it installs the Colombo stack.
    std::atomic<uint32_t> tuning{0};
    bool tuned(Tuning t) const { return (tuning.load(std::memory_order_relaxed) & t) != 0; }

    bool on() const { return enabled.load(std::memory_order_relaxed) != 0; }
    void reset() {
        keyLockWait.reset(); keyDispatch.reset(); styleLockWait.reset(); styleDispatch.reset();
        noteToRender.reset(); noteToBuffer.reset(); renderLockWait.reset(); renderDecode.reset(); callbackTotal.reset();
        setChannelPreset.reset(); findMelodicPreset.reset(); applyFontsTotal.reset(); streamSetFonts.reset();
        bindDrum.reset(); uiLogCall.reset();
        callbacks = 0; overBudget = 0; overEightyPercent = 0;
        keyNotesQueued = 0; renderWindowsWithKeyNotes = 0; pairingOverflow = 0; maxKeyNotesInOneWindow = 0;
        applyFontsCalls = 0; applyFontsSkipped = 0; drumBindFull = 0; drumBindSkipped = 0;
        // pendingKey is owned by mutex_; a stale stamp is harmless and is consumed by the next render.
    }
    // Called after BASS_MIDI_StreamEvent(NOTE) returned, with the earlier stamps.
    void noteQueued(bool keyboard, uint64_t enterNs, uint64_t lockedNs, uint64_t sentNs) {
        (keyboard ? keyLockWait : styleLockWait).record(lockedNs - enterNs);
        (keyboard ? keyDispatch : styleDispatch).record(sentNs - lockedNs);
        if (!keyboard) return;
        keyNotesQueued.fetch_add(1, std::memory_order_relaxed);
        if (pendingKeyCount < kPending) pendingKey[pendingKeyCount++] = sentNs;
        else pairingOverflow.fetch_add(1, std::memory_order_relaxed);
    }
    // Called by render() around the decode; all stamps are taken by the caller.
    void renderDone(uint64_t callbackStartNs, uint64_t lockedNs, uint64_t decodeEndNs) {
        renderLockWait.record(lockedNs - callbackStartNs);
        renderDecode.record(decodeEndNs - lockedNs);
        if (pendingKeyCount) {
            for (unsigned i = 0; i < pendingKeyCount; ++i) {
                const uint64_t noteNs = pendingKey[i];
                if (lockedNs >= noteNs) { noteToRender.record(lockedNs - noteNs); noteToBuffer.record(decodeEndNs - noteNs); }
            }
            renderWindowsWithKeyNotes.fetch_add(1, std::memory_order_relaxed);
            auto old = maxKeyNotesInOneWindow.load(std::memory_order_relaxed);
            while (old < pendingKeyCount && !maxKeyNotesInOneWindow.compare_exchange_weak(old, pendingKeyCount, std::memory_order_relaxed)) {}
            pendingKeyCount = 0;
        }
    }
    void callbackDone(uint64_t startNs, uint64_t endNs) {
        const uint64_t total = endNs - startNs;
        callbackTotal.record(total);
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
            << " keyNotesQueued=" << keyNotesQueued.load() << " renderWindowsWithKeyNotes=" << renderWindowsWithKeyNotes.load()
            << " maxKeyNotesInOneRenderWindow=" << maxKeyNotesInOneWindow.load() << " pairingOverflow=" << pairingOverflow.load()
            << " (every queued keyboard NOTE_ON is paired with the render pass that first consumes it)\n";
        out << "LATENCY_TUNING native=" << (tuning.load() & kNativeMask) << " drumRebindSkip=" << ((tuning.load() & kDrumRebindSkip) ? 1 : 0)
            << " fontsNoopSkip=" << ((tuning.load() & kFontsNoopSkip) ? 1 : 0) << " applyFontsCalls=" << applyFontsCalls.load()
            << " applyFontsSkipped=" << applyFontsSkipped.load() << " drumBindFull=" << drumBindFull.load()
            << " drumBindSkipped=" << drumBindSkipped.load() << "\n";
        keyLockWait.print(out, "keyboardNoteLockWait");
        keyDispatch.print(out, "keyboardNoteDispatch");
        styleLockWait.print(out, "styleNoteLockWait");
        styleDispatch.print(out, "styleNoteDispatch");
        noteToRender.print(out, "keyboardNoteQueuedToRenderStart");
        noteToBuffer.print(out, "keyboardNoteQueuedToBufferReady");
        renderLockWait.print(out, "renderLockWait");
        renderDecode.print(out, "renderDecode");
        callbackTotal.print(out, "callbackTotal");
        setChannelPreset.print(out, "nativeSetChannelPreset");
        findMelodicPreset.print(out, "nativeFindMelodicPreset");
        applyFontsTotal.print(out, "nativeApplyFontsWhenSubmitted");
        streamSetFonts.print(out, "nativeBassStreamSetFonts");
        bindDrum.print(out, "nativeBindDrumABFull");
        uiLogCall.print(out, "nativeUiLogCall_JNI_to_DebugLog");
        return out.str();
    }
};

inline Probe probe;

// Records the duration of a scope into a Stat when the probe is enabled.
struct ScopedTimer {
    Stat& stat; uint64_t start;
    explicit ScopedTimer(Stat& s) : stat(s), start(probe.on() ? now() : 0) {}
    ~ScopedTimer() { if (start) stat.record(now() - start); }
};

} // namespace latency_probe

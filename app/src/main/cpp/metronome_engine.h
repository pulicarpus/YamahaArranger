#pragma once
// METRONOME: a click generator mixed into the Oboe callback AFTER BASSMIDI. It never touches BASSMIDI, the synth mutex, any MIDI channel,
// the Style, note ownership or MIDI OUT. Everything the audio thread uses is a fixed-size atomic or a fixed array: no allocation, no lock,
// no logging, no formatting. When disabled, render() is one relaxed atomic load.
//
// Two clocks, never mixed:
//  * STANDALONE  - sample-accurate: the next pulse is counted in output FRAMES (a drift-free audio clock). Tempo is read at each pulse.
//  * FOLLOW      - the click is an event with a CLOCK_MONOTONIC target time taken from the Style's own tempo clock (the same clock that
//                  schedules Style notes). A click fires at frame 0 of the first callback whose start time is >= the target, which is
//                  exactly how a Style NOTE_ON dispatched at that time reaches BASSMIDI. So click and Style note share one latency pipeline
//                  and one quantisation (< one callback period); the click adds no timer of its own that could drift.
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <sstream>

namespace metronome {

enum Level : uint8_t { Weak = 0, Medium = 1, Strong = 2 };
enum Mode : int { Standalone = 0, Follow = 1 };

/** Accent of pulse `index` (0-based) in a bar of numerator/denominator. Pulse = one 1/denominator note.
 *  6/8 (and 9/8, 12/8): groups of three, Strong on 1, Medium on each further group start; every other meter: Strong on 1 only. */
inline Level levelFor(int index, int numerator, int denominator) {
    const int n = numerator < 1 ? 1 : numerator;
    const int i = ((index % n) + n) % n;
    if (i == 0) return Strong;
    if (denominator == 8 && n % 3 == 0 && i % 3 == 0) return Medium;
    return Weak;
}

class Engine {
public:
    static constexpr int kRing = 128;
    static constexpr int kVoices = 4;
    static constexpr uint64_t kStaleNs = 100000000ull;   // a Follow click more than 100 ms late is dropped, not played

    // ---------------- control (any thread) ----------------
    void configure(bool enabled, int mode, bool running, int bpmX100, int numerator, int denominator, int volumePermille) {
        const bool wasActive = active();
        meterNum_.store(numerator < 1 ? 1 : numerator > 16 ? 16 : numerator, std::memory_order_relaxed);
        meterDen_.store(denominator == 8 ? 8 : denominator == 2 ? 2 : 4, std::memory_order_relaxed);
        bpmX100_.store(bpmX100 < 2000 ? 2000 : bpmX100 > 28000 ? 28000 : bpmX100, std::memory_order_relaxed);
        volumePermille_.store(volumePermille < 0 ? 0 : volumePermille > 1000 ? 1000 : volumePermille, std::memory_order_relaxed);
        const int m = mode == Follow ? Follow : Standalone;
        const bool modeChanged = mode_.load(std::memory_order_relaxed) != m;
        mode_.store(m, std::memory_order_relaxed);
        const bool startStandalone = running && (!running_.load(std::memory_order_relaxed) || modeChanged);
        running_.store(running, std::memory_order_relaxed);
        if (startStandalone) restart_.store(true, std::memory_order_release);
        enabled_.store(enabled, std::memory_order_release);
        if (!enabled || modeChanged || (!wasActive && enabled)) generation_.fetch_add(1, std::memory_order_release); // cancel pending Follow clicks
    }
    bool active() const { return enabled_.load(std::memory_order_acquire); }
    int mode() const { return mode_.load(std::memory_order_relaxed); }
    uint32_t generation() const { return generation_.load(std::memory_order_acquire); }
    void bumpGeneration() { generation_.fetch_add(1, std::memory_order_release); }

    // ---------------- Follow producer (one Kotlin thread) ----------------
    bool push(uint64_t targetNs, int level, uint32_t generation) {
        const uint32_t t = tail_.load(std::memory_order_relaxed);
        if (t - head_.load(std::memory_order_acquire) >= kRing) { droppedFull_.fetch_add(1, std::memory_order_relaxed); return false; }
        Slot& s = ring_[t % kRing];
        s.target = targetNs; s.gen = generation; s.level = uint8_t(level < 0 ? 0 : level > 2 ? 2 : level);
        tail_.store(t + 1, std::memory_order_release);
        return true;
    }

    // ---------------- audio thread ----------------
    /** Adds clicks to `out` (interleaved stereo float). Returns true if anything was added. callbackNs = CLOCK_MONOTONIC at callback start. */
    bool render(float* out, int frames, int sampleRate, uint64_t callbackNs) {
        if (!enabled_.load(std::memory_order_acquire)) { drainStale(); return false; }
        bool mixed = false;
        const float volume = float(volumePermille_.load(std::memory_order_relaxed)) / 1000.0f;
        if (mode_.load(std::memory_order_relaxed) == Standalone) {
            if (!running_.load(std::memory_order_relaxed)) { drainStale(); return voicesActive() ? mix(out, 0, frames, sampleRate, volume) : false; }
            if (restart_.exchange(false, std::memory_order_acq_rel)) { framesUntilNext_ = 0.0; pulse_ = 0; curInterval_ = pulseFrames(sampleRate); meterKey_ = meterKey(); }
            // A tempo change rescales the time left to the next pulse (no waiting for the old interval to finish); a meter change restarts the bar.
            { const double pf = pulseFrames(sampleRate); if (curInterval_ > 0.0 && pf != curInterval_) { framesUntilNext_ *= pf / curInterval_; } curInterval_ = pf; }
            if (meterKey() != meterKey_) { meterKey_ = meterKey(); pulse_ = 0; }
            double cursor = 0.0;                       // frame position inside this block
            while (framesUntilNext_ < double(frames) - cursor) {
                cursor += framesUntilNext_;
                const int offset = int(cursor);
                trigger(levelFor(pulse_, meterNum_.load(std::memory_order_relaxed), meterDen_.load(std::memory_order_relaxed)), sampleRate, offset);
                ++pulse_; beatSeq_.fetch_add(1, std::memory_order_relaxed);
                framesUntilNext_ = pulseFrames(sampleRate);
            }
            framesUntilNext_ -= (double(frames) - cursor);
            return mix(out, 0, frames, sampleRate, volume) || mixed;
        }
        // FOLLOW
        const uint32_t gen = generation_.load(std::memory_order_acquire);
        uint32_t h = head_.load(std::memory_order_relaxed);
        const uint32_t t = tail_.load(std::memory_order_acquire);
        while (h != t) {
            const Slot& s = ring_[h % kRing];
            if (s.gen != gen) { ++h; continue; }                          // cancelled by a generation bump
            if (s.target > callbackNs) break;                              // not due yet: later callbacks
            if (s.target <= lastFiredTarget_) { duplicates_.fetch_add(1, std::memory_order_relaxed); ++h; continue; }   // a re-plan re-issued a click that already sounded
            const uint64_t late = callbackNs - s.target;
            if (late > kStaleNs) { droppedStale_.fetch_add(1, std::memory_order_relaxed); ++h; continue; }
            lastFiredTarget_ = s.target;
            trigger(Level(s.level), sampleRate, 0);
            firedFollow_.fetch_add(1, std::memory_order_relaxed); sumLateUs_.fetch_add(late / 1000, std::memory_order_relaxed);
            uint64_t m = maxLateUs_.load(std::memory_order_relaxed); const uint64_t lu = late / 1000;
            while (lu > m && !maxLateUs_.compare_exchange_weak(m, lu, std::memory_order_relaxed)) {}
            beatSeq_.fetch_add(1, std::memory_order_relaxed);
            ++h;
        }
        head_.store(h, std::memory_order_release);
        return mix(out, 0, frames, sampleRate, volume);
    }

    // ---------------- observers (any thread) ----------------
    uint64_t beatSeq() const { return beatSeq_.load(std::memory_order_relaxed); }
    int lastLevel() const { return lastLevel_.load(std::memory_order_relaxed); }
    std::string report() const {
        std::ostringstream o;
        o << "METRONOME enabled=" << enabled_.load() << " mode=" << (mode_.load() == Follow ? "FOLLOW" : "STANDALONE") << " running=" << running_.load()
          << " bpmX100=" << bpmX100_.load() << " meter=" << meterNum_.load() << "/" << meterDen_.load() << " volumePermille=" << volumePermille_.load()
          << " beatSeq=" << beatSeq_.load() << " followFired=" << firedFollow_.load() << " followMeanLateUs=" << (firedFollow_.load() ? sumLateUs_.load() / firedFollow_.load() : 0)
          << " followMaxLateUs=" << maxLateUs_.load() << " droppedStale=" << droppedStale_.load() << " droppedRingFull=" << droppedFull_.load() << " duplicatesDropped=" << duplicates_.load()
          << " (late = callback start minus click target; same quantisation as Style notes)\n";
        return o.str();
    }

private:
    struct Slot { uint64_t target = 0; uint32_t gen = 0; uint8_t level = 0; };
    struct Voice { bool on = false; float phase = 0, inc = 0, amp = 0, env = 0, decay = 0; int left = 0; int delay = 0; };

    double pulseFrames(int sampleRate) const {
        const double bpm = double(bpmX100_.load(std::memory_order_relaxed)) / 100.0;
        const int den = meterDen_.load(std::memory_order_relaxed);
        return double(sampleRate) * 60.0 / bpm * (4.0 / double(den));   // bpm counts quarter notes; pulse = one 1/den note
    }
    int meterKey() const { return meterNum_.load(std::memory_order_relaxed) * 100 + meterDen_.load(std::memory_order_relaxed); }
    bool voicesActive() const { for (const auto& v : voices_) if (v.on) return true; return false; }
    void drainStale() { head_.store(tail_.load(std::memory_order_acquire), std::memory_order_release); }
    void trigger(Level level, int sampleRate, int offset) {
        Voice* v = nullptr;
        for (auto& c : voices_) if (!c.on) { v = &c; break; }
        if (!v) v = &voices_[0];                                         // steal the oldest slot; never allocate
        const float hz = level == Strong ? 1760.0f : level == Medium ? 1320.0f : 1046.5f;
        const float gain = level == Strong ? 1.0f : level == Medium ? 0.8f : 0.6f;
        v->on = true; v->phase = 0; v->inc = 2.0f * 3.14159265f * hz / float(sampleRate); v->amp = 0.35f * gain; v->env = 1.0f;
        v->left = int(0.030f * float(sampleRate)); v->decay = std::exp(-6.0f / float(v->left));
        v->delay = offset;
        lastLevel_.store(int(level), std::memory_order_relaxed);
    }
    bool mix(float* out, int, int frames, int, float volume) {
        bool any = false;
        for (auto& v : voices_) {
            if (!v.on) continue;
            int i = v.delay > 0 ? v.delay : 0; v.delay = 0;
            for (; i < frames && v.left > 0; ++i, --v.left) {
                const float s = std::sin(v.phase) * v.env * v.amp * volume;
                out[2 * i] += s; out[2 * i + 1] += s;
                v.phase += v.inc; if (v.phase > 6.2831853f) v.phase -= 6.2831853f; v.env *= v.decay;
                any = true;
            }
            if (v.left <= 0) v.on = false;
        }
        return any;
    }

    std::atomic<bool> enabled_{false}, running_{false}, restart_{false};
    std::atomic<int> mode_{Standalone}, meterNum_{4}, meterDen_{4}, bpmX100_{12000}, volumePermille_{600};
    std::atomic<uint32_t> generation_{0}, head_{0}, tail_{0};
    Slot ring_[kRing];
    std::atomic<uint64_t> beatSeq_{0}, firedFollow_{0}, sumLateUs_{0}, maxLateUs_{0}, droppedStale_{0}, droppedFull_{0}, duplicates_{0};
    std::atomic<int> lastLevel_{0};
    // audio-thread-only state
    uint64_t lastFiredTarget_ = 0;
    double framesUntilNext_ = 0.0, curInterval_ = 0.0; int pulse_ = 0, meterKey_ = 0;
    Voice voices_[kVoices];
};

} // namespace metronome

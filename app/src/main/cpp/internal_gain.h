#pragma once
// INTERNAL OUTPUT GAIN: one linear gain stage on the BASSMIDI mix (RIGHT, LEFT, Style, drums together), applied after the synth and
// before the engine's existing +/-1.0 clamp. It never touches MIDI OUT, velocity, CASM/Style, envelopes or BASSMIDI's own volumes.
//  * 0 dB (default) is the legacy path: samples are not modified at all (hard clamp downstream, exactly as before) and only measured.
//  * above 0 dB each sample is multiplied by the gain; magnitudes above kKnee are bent by a smooth tanh knee that approaches (never reaches)
//    1.0, so peaks no longer hard-clip. Stateless: no look-ahead, no latency, no allocation, safe on the audio thread.
// Counters (relaxed atomics) feed the peak / RMS / clipping indicator; they are read from the UI thread.
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

namespace internal_gain {

constexpr float kMaxDb = 12.0f;
constexpr float kKnee = 0.80f;

inline float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }
inline float toDb(double lin) { return lin > 1e-9 ? float(20.0 * std::log10(lin)) : -120.0f; }

// y = gain*x with the soft knee above kKnee; exact identity pass-through is the caller's decision at 0 dB.
inline float shape(float y) {
    const float a = std::fabs(y);
    if (a <= kKnee) return y;
    const float r = 1.0f - kKnee;
    const float s = kKnee + r * std::tanh((a - kKnee) / r);
    return y < 0 ? -s : s;
}

class Stage {
public:
    void setDb(float db) {
        const float d = std::isfinite(db) ? std::max(0.0f, std::min(kMaxDb, db)) : 0.0f;
        db_.store(d, std::memory_order_relaxed);
        lin_.store(d == 0.0f ? 1.0f : dbToLinear(d), std::memory_order_relaxed);
    }
    float db() const { return db_.load(std::memory_order_relaxed); }
    /** Peak |sample| (0..1, after the stage, before the engine clamp) of the most recent audio block. */
    float lastBlockPeak() const { return lastBlockPeak_.load(std::memory_order_relaxed); }

    // Audio thread. `n` = interleaved samples.
    void process(float* x, int n) {
        const float g = lin_.load(std::memory_order_relaxed);
        float peakPre = 0, peakOut = 0; double ss = 0; uint32_t over = 0, limited = 0;
        if (g == 1.0f) {
            for (int i = 0; i < n; ++i) {
                const float a = std::fabs(x[i]);
                if (!(a == a)) continue; // NaN: leave to the downstream clamp, do not count
                if (a > peakPre) peakPre = a;
                if (a > 1.0f) ++over;
                ss += double(x[i]) * x[i];
            }
            peakOut = peakPre > 1.0f ? 1.0f : peakPre;
        } else {
            for (int i = 0; i < n; ++i) {
                const float y = x[i] * g;
                const float a = std::fabs(y);
                if (!(a == a)) { x[i] = 0.0f; continue; }
                if (a > peakPre) peakPre = a;
                if (a > 1.0f) ++over;
                ss += double(y) * y;
                if (a > kKnee) { ++limited; x[i] = shape(y); } else x[i] = y;
                const float o = std::fabs(x[i]);
                if (o > peakOut) peakOut = o;
            }
        }
        lastBlockPeak_.store(peakOut, std::memory_order_relaxed); // live output level for the Mixer's master meter: one relaxed store per callback
        float cur = peakPreMax_.load(std::memory_order_relaxed);
        while (peakPre > cur && !peakPreMax_.compare_exchange_weak(cur, peakPre, std::memory_order_relaxed)) {}
        cur = peakOutMax_.load(std::memory_order_relaxed);
        while (peakOut > cur && !peakOutMax_.compare_exchange_weak(cur, peakOut, std::memory_order_relaxed)) {}
        sumSq_.store(sumSq_.load(std::memory_order_relaxed) + ss, std::memory_order_relaxed); // single writer (audio thread)
        samples_.fetch_add(uint64_t(n), std::memory_order_relaxed);
        over_.fetch_add(over, std::memory_order_relaxed);
        limited_.fetch_add(limited, std::memory_order_relaxed);
    }

    void resetStats() {
        peakPreMax_.store(0, std::memory_order_relaxed); peakOutMax_.store(0, std::memory_order_relaxed);
        sumSq_.store(0, std::memory_order_relaxed); samples_.store(0, std::memory_order_relaxed);
        over_.store(0, std::memory_order_relaxed); limited_.store(0, std::memory_order_relaxed);
    }

    std::string report() const {
        const uint64_t n = samples_.load(std::memory_order_relaxed);
        const double rms = n ? std::sqrt(sumSq_.load(std::memory_order_relaxed) / double(n)) : 0.0;
        char b[320];
        std::snprintf(b, sizeof b,
            "IGAIN db=%.1f lin=%.3f peakPreDb=%.1f peakOutDb=%.1f rmsDb=%.1f samples=%llu overSamples=%u limitedSamples=%u\n",
            db(), lin_.load(std::memory_order_relaxed), toDb(peakPreMax_.load(std::memory_order_relaxed)), toDb(peakOutMax_.load(std::memory_order_relaxed)),
            toDb(rms), (unsigned long long)n, over_.load(std::memory_order_relaxed), limited_.load(std::memory_order_relaxed));
        return b;
    }

private:
    std::atomic<float> db_{0.0f}, lin_{1.0f}, peakPreMax_{0.0f}, peakOutMax_{0.0f}, lastBlockPeak_{0.0f};
    std::atomic<double> sumSq_{0.0};
    std::atomic<uint64_t> samples_{0};
    std::atomic<uint32_t> over_{0}, limited_{0};
};

} // namespace internal_gain

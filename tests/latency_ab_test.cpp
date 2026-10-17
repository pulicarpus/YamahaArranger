#define ONSET_PROBE_NO_BASS 1
#include "onset_probe.h"
#include "buffer_policy.h"
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>

static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }
static constexpr int kRate = 48000;

// Alternating +/-a gives an exact window RMS of a, so thresholds are deterministic.
static std::vector<float> envelope(double delayMs, double rampMs, float amp, double lengthMs = 600) {
    std::vector<float> s(size_t(kRate * lengthMs / 1000.0) * 2, 0.0f);
    for (size_t i = 0; i < s.size() / 2; ++i) {
        const double t = i * 1000.0 / kRate;
        double g = t < delayMs ? 0.0 : rampMs <= 0 ? 1.0 : std::min(1.0, (t - delayMs) / rampMs);
        const float v = float(g * amp) * ((i & 1) ? -1.0f : 1.0f);
        s[2 * i] = v; s[2 * i + 1] = v;
    }
    return s;
}

int main() {
    // ---- onset analysis ----
    auto fast = onset_probe::analyze(envelope(1.0, 0, 0.5f), kRate);
    check(fast.audible && fast.firstSampleMs >= 0.9 && fast.firstSampleMs <= 1.1, "instant attack first sample ~1 ms");
    check(fast.m10Ms >= 0.5 && fast.m10Ms <= 1.5 && std::string(onset_probe::classify(fast)) == "FAST_ONSET_le5ms", "fast classification");

    auto ramp = onset_probe::analyze(envelope(0, 20.0, 0.5f), kRate);
    check(ramp.audible && ramp.m10Ms > 5.5 && ramp.m10Ms < 7.5, "20 ms ramp reaches -10 dB near 6.3 ms");
    check(ramp.m20Ms > 1.5 && ramp.m20Ms < 2.8 && ramp.m40Ms < 0.8, "-20 / -40 dB crossings");
    check(ramp.peakMs > 18.0 && ramp.peakMs < 21.0, "peak at end of ramp");
    check(std::string(onset_probe::classify(ramp)) == "MODERATE_ONSET_5to15ms", "moderate classification");

    auto late = onset_probe::analyze(envelope(30.0, 0, 0.5f), kRate);
    check(late.firstSampleMs >= 29.9 && late.firstSampleMs <= 30.2, "30 ms of leading silence is reported");
    check(std::string(onset_probe::classify(late)) == "SLOW_ONSET_gt15ms", "slow classification");

    auto silent = onset_probe::analyze(std::vector<float>(kRate, 0.0f), kRate);
    check(!silent.audible && std::string(onset_probe::classify(silent)) == "NO_SIGNAL", "silence is not audible");
    check(!onset_probe::analyze({}, kRate).audible, "empty input");
    check(onset_probe::line(100, silent).find("audible=0") != std::string::npos, "line for silence");
    check(onset_probe::line(100, ramp).find("minus10dBMs=") != std::string::npos, "line for signal");

    // ---- live vs isolated comparison ----
    {
        auto a = envelope(0, 20.0, 0.5f), same = envelope(0, 20.0, 0.5f);
        auto c = onset_probe::compare(a, same, kRate, 100);
        check(c.valid && c.errorDb <= -100 && c.correlation > 0.9999, "identical renders compare as identical");
        check(std::string(onset_probe::compareVerdict(c)).find("ADDS_NOTHING") != std::string::npos, "identical verdict");
        auto quieter = envelope(0, 20.0, 0.495f);                         // 1 % lower gain -> ~ -40 dB error
        c = onset_probe::compare(quieter, a, kRate, 100);
        check(c.errorDb < -35 && c.errorDb > -45 && std::string(onset_probe::compareVerdict(c)).find("NEARLY_SAME") != std::string::npos, "1 % gain difference is NEARLY_SAME");
        auto slower = envelope(0, 60.0, 0.5f);                            // a smeared attack is clearly different
        c = onset_probe::compare(slower, a, kRate, 100);
        check(c.errorDb > -30 && std::string(onset_probe::compareVerdict(c)).find("DIFFERS") != std::string::npos, "smeared attack is DIFFERENT");
        check(!onset_probe::compare(std::vector<float>(100, 0.0f), a, kRate, 100).valid, "silent input is not comparable");
        const double rise20 = onset_probe::riseMs(envelope(0, 20.0, 0.5f), kRate), rise60 = onset_probe::riseMs(envelope(0, 60.0, 0.5f), kRate);
        check(rise20 > 14.0 && rise20 < 18.0 && rise60 > 44.0 && rise60 < 52.0, "10-90 % rise time follows the ramp length");
        check(onset_probe::riseMs(std::vector<float>(1000, 0.0f), kRate) < 0, "no signal -> no rise time");
    }

    // ---- buffer policy ----
    using namespace buffer_policy;
    check(framesFor(BASELINE_2X, 256, 1024) == 512, "baseline stays 2 bursts (production value)");
    check(framesFor(ONE_AND_HALF, 256, 1024) == 384 && framesFor(ONE_BURST, 256, 1024) == 256 && framesFor(ADAPTIVE, 256, 1024) == 256, "mode sizes");
    check(framesFor(-5, 256, 1024) == 512 && framesFor(99, 256, 1024) == 512, "unknown modes fall back to baseline");
    check(framesFor(BASELINE_2X, 256, 300) == 300, "capacity caps the request");
    check(framesFor(ONE_BURST, 192, 0) == 192, "unknown capacity is ignored");
    for (int burst = 1; burst <= 1024; burst += 7)
        for (int mode = 0; mode < kModeCount; ++mode)
            for (int capacity : {0, burst, burst * 4}) {
                const int f = framesFor(mode, burst, capacity);
                check(f >= burst, "never below one burst");
                check(capacity == 0 || f <= std::max(burst, capacity), "never above capacity");
            }
    check(nextAdaptive(256, 256, 0, 1024) == 256, "no underrun, no change");
    check(nextAdaptive(256, 256, 3, 1024) == 512, "underrun adds one burst");
    check(nextAdaptive(512, 256, 1, 1024) == 768 && nextAdaptive(768, 256, 9, 1024) == 768, "capped at 3 bursts");
    check(nextAdaptive(256, 256, 1, 384) == 384, "capped at capacity");
    check(nextAdaptive(100, 256, 0, 1024) == 256, "never below one burst");
    check(nextAdaptive(256, 256, -4, 1024) == 256, "negative delta ignored");
    std::cout << "LATENCY AB NATIVE PASS\n";
}

// Host validation of the pitch/amplitude analyzer with known synthetic modulation (no BASS): it must detect exact vibrato depth/rate, tell tremolo (AM) from vibrato (FM), and report ~0 for a steady tone.
#include "pitch_analysis.h"
#include <cstdio>
#include <iostream>
using namespace pitch_analysis;
static int failures = 0;
static void check(bool ok, const char* w) { if (!ok) { std::cout << "PA FAIL " << w << "\n"; ++failures; } }
static std::vector<float> tone(int rate, double f0, double sec, double vibCents, double vibHz, double amDepth, double amHz) {
    std::vector<float> s(size_t(rate * sec) * 2); double ph = 0;
    for (size_t i = 0; i < s.size() / 2; ++i) {
        const double t = double(i) / rate;
        const double f = f0 * std::pow(2.0, vibCents * std::sin(2 * M_PI * vibHz * t) / 1200.0);
        ph += 2 * M_PI * f / rate;
        double v = 0; for (int h = 1; h <= 12; ++h) v += std::sin(h * ph) / h;
        v *= 0.2 * (1.0 + amDepth * std::sin(2 * M_PI * amHz * t));
        s[i * 2] = s[i * 2 + 1] = float(v);
    }
    return s;
}
int main() {
    const double f0 = 262.5;
    { auto r = analyze(tone(48000, f0, 2.0, 0, 0, 0, 0), 48000, f0, 0.3, 1.9);
      std::printf("steady    : std=%.3f p2p=%.3f cents  AM=%.2f%%\n", r.stdCents, r.p2pCents, r.amDepthPct);
      check(r.valid && r.p2pCents < 2.0 && r.amDepthPct < 2.0, "steady tone is flat"); }
    for (double hz : {4.0, 6.0, 8.0}) {
      auto r = analyze(tone(48000, f0, 2.0, 30, hz, 0, 0), 48000, f0, 0.3, 1.9);
      std::printf("vibrato %.0fHz 30c: modHz=%.2f modCents=%.1f p2p=%.1f AM=%.2f%% amPeak=%.2f%%\n", hz, r.modHz, r.modCents, r.p2pCents, r.amDepthPct, r.amPeakPct);
      check(std::fabs(r.modHz - hz) <= 0.5 && r.modCents > 22 && r.modCents < 38, "vibrato depth/rate"); check(r.amPeakPct < 2.0, "vibrato is not AM"); }
    { auto r = analyze(tone(48000, f0, 2.0, 0, 0, 0.3, 5.0), 48000, f0, 0.3, 1.9);
      std::printf("tremolo 5Hz 30%%: modCents=%.2f amHz=%.2f amPeak=%.1f%% depth=%.1f%%\n", r.modCents, r.amHz, r.amPeakPct, r.amDepthPct);
      check(r.modCents < 2.0, "tremolo is not FM"); check(std::fabs(r.amHz - 5.0) <= 0.5 && r.amPeakPct > 22, "tremolo depth/rate"); }
    { auto r = analyze(tone(44100, f0, 2.0, 12, 5.5, 0, 0), 44100, f0, 0.3, 1.9);
      std::printf("44.1k vibrato 12c: modHz=%.2f modCents=%.1f\n", r.modHz, r.modCents);
      check(std::fabs(r.modHz - 5.5) <= 0.5 && r.modCents > 8 && r.modCents < 16, "44.1k small vibrato"); }
    { auto r = analyze(tone(48000, f0 * std::pow(2.0, 25 / 1200.0), 2.0, 0, 0, 0, 0), 48000, f0, 0.3, 1.9);
      std::printf("offset +25c: mean=%.2f\n", r.meanCents); check(std::fabs(r.meanCents - 25) < 1.0, "absolute error"); }
    std::cout << (failures ? "PA FAILED\n" : "PA PASS\n"); return failures ? 1 : 0;
}

// Host unit test of the INTERNAL OUTPUT GAIN stage (pure C++, no BASS): legacy identity at 0 dB, gain + soft knee above, no NaN/overs after the stage, monotonic, counters.
#include "../app/src/main/cpp/internal_gain.h"
#include <iostream>
#include <vector>
using namespace internal_gain;
static int failures = 0;
static void check(bool ok, const char* what) { if (!ok) { std::cout << "IGAIN FAIL " << what << "\n"; ++failures; } }
int main() {
    // 0 dB: samples untouched (even > 1.0, the downstream clamp handles them exactly as before) but measured
    { Stage s; std::vector<float> x{0.1f, -0.5f, 1.4f, -1.2f, 0.0f}; auto y = x; s.process(y.data(), int(y.size()));
      check(y == x, "0 dB is bit-identical"); const auto r = s.report(); std::cout << r;
      check(r.find("overSamples=2") != std::string::npos, "legacy over count"); }
    // +6 dB: factor 1.995, knee bends the top, output never reaches 1.0
    { Stage s; s.setDb(6); std::vector<float> x; for (int i = 0; i <= 1000; ++i) x.push_back(i / 1000.0f * 2.0f - 1.0f); auto y = x; s.process(y.data(), int(y.size()));
      float prev = -2; bool mono = true, bounded = true;
      for (size_t i = 0; i < y.size(); ++i) { if (y[i] < prev) mono = false; prev = y[i]; if (std::fabs(y[i]) >= 1.0f) bounded = false; }
      check(mono, "monotonic"); check(bounded, "never reaches 1.0");
      check(std::fabs(y[600] - shape(x[600] * dbToLinear(6))) < 1e-6f, "formula");
      const float q = 0.2f; Stage t; t.setDb(6); float v = q; t.process(&v, 1); check(std::fabs(v - q * dbToLinear(6)) < 1e-6f, "linear below knee");
      std::cout << s.report(); }
    // live level for the Mixer: peak of the most recent block
    { Stage s; s.setDb(6); std::vector<float> a{0.1f, -0.2f}, b{0.05f, 0.0f}; s.process(a.data(), 2); check(std::fabs(s.lastBlockPeak() - 0.2f * dbToLinear(6)) < 1e-5f, "block peak (loud block)");
      s.process(b.data(), 2); check(std::fabs(s.lastBlockPeak() - 0.05f * dbToLinear(6)) < 1e-5f, "block peak follows the last block"); }
    // knee continuity and slope at the threshold
    { const float a = kKnee - 1e-4f, b = kKnee + 1e-4f; check(std::fabs(shape(b) - shape(a)) < 3e-4f, "continuous at knee"); }
    // clamps of the control, NaN safety, stats reset
    { Stage s; s.setDb(99); check(s.db() == kMaxDb, "max 12 dB"); s.setDb(-5); check(s.db() == 0.0f, "min 0 dB"); s.setDb(NAN); check(s.db() == 0.0f, "NaN db");
      s.setDb(6); float v[2] = {NAN, 0.3f}; s.process(v, 2); check(v[0] == 0.0f, "NaN sample silenced"); s.resetStats(); check(s.report().find("samples=0") != std::string::npos, "reset"); }
    std::cout << (failures ? "IGAIN FAILED\n" : "IGAIN PASS\n");
    return failures ? 1 : 0;
}

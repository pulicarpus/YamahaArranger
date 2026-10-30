#pragma once
// Pitch / amplitude modulation analysis of a rendered PCM buffer (pure C++, offline, deterministic).
//  * pitch: windowed autocorrelation (2048 samples, hop 256) with parabolic peak interpolation -> cents versus the expected fundamental
//  * amplitude: windowed RMS envelope (512 samples, hop 256) -> modulation depth %
//  * both deviation series are searched for their strongest periodic component between 0.5 and 20 Hz (DFT), which separates vibrato (pitch) from tremolo / chorus beating (amplitude)
#include <algorithm>
#include <cmath>
#include <vector>

namespace pitch_analysis {

struct Result {
    int frames = 0;
    double meanCents = 0, stdCents = 0, p2pCents = 0, modHz = 0, modCents = 0;   // pitch deviation (cents), strongest periodic component (peak cents)
    double amDepthPct = 0, amHz = 0, amPeakPct = 0;                               // envelope (max-min)/(max+min), strongest periodic component (peak % of mean)
    bool valid = false;
};

inline void dft(const std::vector<double>& x, double fs, double& bestHz, double& bestAmp) {
    bestHz = 0; bestAmp = 0;
    const int n = int(x.size()); if (n < 16) return;
    for (double f = 0.5; f <= 20.0; f += 0.25) {
        double re = 0, im = 0;
        for (int i = 0; i < n; ++i) { const double a = 2.0 * M_PI * f * i / fs; re += x[i] * std::cos(a); im += x[i] * std::sin(a); }
        const double amp = 2.0 * std::sqrt(re * re + im * im) / n;
        if (amp > bestAmp) { bestAmp = amp; bestHz = f; }
    }
}

// interleaved stereo float, left channel analysed. Analysis span [t0, t1] seconds.
inline Result analyze(const std::vector<float>& st, int rate, double expectHz, double t0, double t1) {
    Result r;
    std::vector<double> x(st.size() / 2);
    for (size_t i = 0; i < x.size(); ++i) x[i] = st[i * 2];
    const int W = 2048, H = 256;
    const int i0 = int(t0 * rate), i1 = std::min(int(t1 * rate), int(x.size()));
    const double period = rate / expectHz;
    const int EW = std::max(64, int(std::lround(period * 3)));   // whole periods: no window-phase ripple in the envelope
    const int lo = std::max(8, int(period * 0.94)), hi = int(period * 1.06) + 2;
    std::vector<double> dev, env;
    for (int s = i0; s + W + hi < i1; s += H) {
        std::vector<double> c(hi + 2, 0.0);
        for (int lag = lo - 1; lag <= hi + 1; ++lag) { double a = 0; for (int i = 0; i < W; ++i) a += x[s + i] * x[s + i + lag]; c[lag] = a; }
        int best = lo; for (int lag = lo; lag <= hi; ++lag) if (c[lag] > c[best]) best = lag;
        const double y0 = c[best - 1], y1 = c[best], y2 = c[best + 1], den = y0 - 2 * y1 + y2;
        const double frac = den != 0 ? 0.5 * (y0 - y2) / den : 0.0;
        const double p = best + frac;
        dev.push_back(1200.0 * std::log2((rate / p) / expectHz));
        double e = 0; for (int i = 0; i < EW; ++i) e += x[s + W / 2 + i] * x[s + W / 2 + i]; env.push_back(std::sqrt(e / EW));
    }
    if (dev.size() < 16) return r;
    r.frames = int(dev.size()); r.valid = true;
    double m = 0; for (double d : dev) m += d; m /= dev.size(); r.meanCents = m;
    double v = 0, mn = 1e9, mx = -1e9; for (double d : dev) { v += (d - m) * (d - m); mn = std::min(mn, d); mx = std::max(mx, d); }
    r.stdCents = std::sqrt(v / dev.size()); r.p2pCents = mx - mn;
    std::vector<double> dz = dev; for (double& d : dz) d -= m;
    const double fs = double(rate) / H;
    dft(dz, fs, r.modHz, r.modCents);
    double em = 0, emn = 1e9, emx = 0; for (double e : env) { em += e; emn = std::min(emn, e); emx = std::max(emx, e); } em /= env.size();
    r.amDepthPct = (emx + emn) > 0 ? 100.0 * (emx - emn) / (emx + emn) : 0;
    std::vector<double> ez = env; for (double& e : ez) e = (e - em) / (em > 0 ? em : 1) * 100.0;
    dft(ez, fs, r.amHz, r.amPeakPct);
    return r;
}

} // namespace pitch_analysis

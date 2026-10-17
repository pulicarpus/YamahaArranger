#pragma once
// RIGHT onset probe: how long after NOTE_ON does the loaded preset become audible?
// Separates the preset's own envelope/sample-start delay from transport and output latency.
// Offline and isolated: a private decode stream shares the already loaded font; no events, mappings,
// gain or controller changes on the production stream. Not a measure of device output latency.
#include <algorithm>
#include <cmath>
#include <sstream>
#include <string>
#include <vector>
#ifndef ONSET_PROBE_NO_BASS
#include <bass.h>
#include <bassmidi.h>
#endif

namespace onset_probe {

struct Onset {
    bool audible = false;
    double firstSampleMs = -1, m40Ms = -1, m20Ms = -1, m10Ms = -1, peakMs = -1, peakDb = -200;
};

// `stereo` is interleaved L,R starting at the NOTE_ON sample. Envelope = max(|L|,|R|) RMS over 0.5 ms
// windows; thresholds are relative to the loudest window in the first `searchMs`.
inline Onset analyze(const std::vector<float>& stereo, int rate, int searchMs = 500) {
    Onset out;
    const size_t frames = stereo.size() / 2;
    const size_t hop = std::max<size_t>(1, size_t(rate) / 2000);
    const size_t limit = std::min(frames, size_t(rate) * searchMs / 1000);
    const size_t windows = limit / hop;
    if (!windows) return out;
    std::vector<double> rms(windows);
    double peak = 0;
    size_t peakAt = 0;
    for (size_t w = 0; w < windows; ++w) {
        double energy = 0;
        for (size_t i = w * hop; i < (w + 1) * hop; ++i) {
            const double v = std::max(std::fabs(double(stereo[2 * i])), std::fabs(double(stereo[2 * i + 1])));
            energy += v * v;
        }
        rms[w] = std::sqrt(energy / hop);
        if (rms[w] > peak) { peak = rms[w]; peakAt = w; }
    }
    if (peak < 1e-6) return out;
    out.audible = true;
    out.peakMs = double(peakAt * hop) * 1000.0 / rate;
    out.peakDb = 20.0 * std::log10(peak);
    for (size_t i = 0; i < frames; ++i)
        if (std::max(std::fabs(stereo[2 * i]), std::fabs(stereo[2 * i + 1])) > 1e-5f) { out.firstSampleMs = i * 1000.0 / rate; break; }
    auto crossing = [&](double db) {
        const double level = peak * std::pow(10.0, db / 20.0);
        for (size_t w = 0; w < windows; ++w) if (rms[w] >= level) return double(w * hop) * 1000.0 / rate;
        return -1.0;
    };
    out.m40Ms = crossing(-40); out.m20Ms = crossing(-20); out.m10Ms = crossing(-10);
    return out;
}

// 10 % -> 90 % rise time of the windowed envelope (ms); -1 when not measurable.
inline double riseMs(const std::vector<float>& stereo, int rate, int searchMs = 500) {
    const size_t frames = stereo.size() / 2, hop = std::max<size_t>(1, size_t(rate) / 2000);
    const size_t windows = std::min(frames, size_t(rate) * searchMs / 1000) / hop;
    if (!windows) return -1;
    std::vector<double> rms(windows);
    double peak = 0;
    for (size_t w = 0; w < windows; ++w) {
        double energy = 0;
        for (size_t i = w * hop; i < (w + 1) * hop; ++i) {
            const double v = std::max(std::fabs(double(stereo[2 * i])), std::fabs(double(stereo[2 * i + 1])));
            energy += v * v;
        }
        rms[w] = std::sqrt(energy / hop);
        peak = std::max(peak, rms[w]);
    }
    if (peak < 1e-6) return -1;
    double t10 = -1, t90 = -1;
    for (size_t w = 0; w < windows; ++w) {
        if (t10 < 0 && rms[w] >= peak * 0.1) t10 = double(w * hop) * 1000.0 / rate;
        if (t90 < 0 && rms[w] >= peak * 0.9) { t90 = double(w * hop) * 1000.0 / rate; break; }
    }
    return t10 < 0 || t90 < 0 ? -1 : t90 - t10;
}

// Sample-level comparison of two renders of the same note over the first `ms` milliseconds.
struct Compare { bool valid = false; double errorDb = 0, correlation = 0; size_t samples = 0; };
inline Compare compare(const std::vector<float>& a, const std::vector<float>& b, int rate, int ms) {
    Compare out;
    const size_t n = std::min({a.size(), b.size(), size_t(rate) * ms / 1000 * 2});
    if (n < 2) return out;
    double ea = 0, eb = 0, ed = 0, dot = 0;
    for (size_t i = 0; i < n; ++i) {
        const double x = a[i], y = b[i];
        ea += x * x; eb += y * y; ed += (x - y) * (x - y); dot += x * y;
    }
    if (eb < 1e-18 || ea < 1e-18) return out;
    out.valid = true; out.samples = n;
    out.errorDb = ed <= 1e-30 ? -200.0 : 10.0 * std::log10(ed / eb);
    out.correlation = dot / std::sqrt(ea * eb);
    return out;
}

inline const char* compareVerdict(const Compare& c) {
    if (!c.valid) return "NOT_COMPARABLE";
    if (c.errorDb <= -60.0) return "LIVE_PATH_ADDS_NOTHING_audibly(<=-60dB)";
    if (c.errorDb <= -30.0) return "LIVE_PATH_NEARLY_SAME(-60..-30dB)";
    return "LIVE_PATH_DIFFERS(>-30dB)";
}

inline std::string line(int velocity, const Onset& o) {
    std::ostringstream s;
    s << "ONSET velocity=" << velocity;
    if (!o.audible) { s << " audible=0 (no signal above -120 dBFS in 500 ms)"; return s.str(); }
    s << " audible=1 firstSampleMs=" << o.firstSampleMs << " minus40dBMs=" << o.m40Ms << " minus20dBMs=" << o.m20Ms
      << " minus10dBMs=" << o.m10Ms << " peakWindowMs=" << o.peakMs << " peakDbfs=" << o.peakDb;
    return s.str();
}

// Verdict from the velocity-100 row: how much of the felt response is the preset itself.
inline const char* classify(const Onset& o) {
    if (!o.audible) return "NO_SIGNAL";
    if (o.m10Ms < 0) return "UNKNOWN";
    if (o.m10Ms <= 5.0) return "FAST_ONSET_le5ms";
    if (o.m10Ms <= 15.0) return "MODERATE_ONSET_5to15ms";
    return "SLOW_ONSET_gt15ms";
}

// Controller state of the channel being reproduced (defaults = the isolated probe's neutral state).
struct Controllers { int cc7 = 127, cc11 = 127, reverb = 0, chorus = 0, pan = 64, release = 64; };

#ifndef ONSET_PROBE_NO_BASS
// One isolated decode of the loaded preset starting at the NOTE_ON sample; empty on any failure.
inline std::vector<float> render(HSOUNDFONT font, int bank, int pc, bool drum, int key, int velocity, int rate,
                                 const Controllers& c = {}, int ms = 500) {
    {
        const HSTREAM stream = BASS_MIDI_StreamCreate(1, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, rate);
        if (!stream) return {};
        struct Cleanup { HSTREAM s; ~Cleanup() { BASS_StreamFree(s); } } cleanup{stream};
        BASS_MIDI_FONTEX2 mapping{};
        mapping.font = font; mapping.spreset = pc; mapping.sbank = bank; mapping.dpreset = 0;
        mapping.dbank = drum ? 128 : 0; mapping.minchan = 0; mapping.numchan = 1;
        bool ok = BASS_MIDI_StreamSetFonts(stream, &mapping, 1 | BASS_MIDI_FONT_EX2);
        ok = BASS_ChannelSetAttribute(stream, BASS_ATTRIB_MIDI_SRC, 2) && ok;
        auto event = [&](DWORD e, DWORD p) { ok = BASS_MIDI_StreamEvent(stream, 0, e, p) && ok; };
        event(MIDI_EVENT_DRUMS, drum ? 1 : 0); event(MIDI_EVENT_BANK, 0); event(MIDI_EVENT_BANK_LSB, 0);
        event(MIDI_EVENT_PROGRAM, 0); event(MIDI_EVENT_VOLUME, c.cc7); event(MIDI_EVENT_EXPRESSION, c.cc11);
        event(MIDI_EVENT_PAN, c.pan); event(MIDI_EVENT_REVERB, c.reverb); event(MIDI_EVENT_CHORUS, c.chorus);
        event(MIDI_EVENT_RELEASE, c.release);
        event(MIDI_EVENT_NOTE, key | (velocity << 8));
        BASS_MIDI_FONT actual{};
        const bool presetVerified = BASS_MIDI_StreamGetPreset(stream, 0, &actual) && actual.font == font && actual.bank == bank && actual.preset == pc;
        if (!ok || !presetVerified) return {};
        std::vector<float> pcm(size_t(rate) * ms / 1000 * 2);
        size_t at = 0;
        while (at < pcm.size()) {
            const DWORD wanted = DWORD(std::min<size_t>(1024, pcm.size() - at) * sizeof(float));
            const DWORD got = BASS_ChannelGetData(stream, pcm.data() + at, wanted | BASS_DATA_FLOAT);
            if (got == DWORD(-1) || !got || got > wanted || got % sizeof(float)) return {};
            at += got / sizeof(float);
        }
        return pcm;
    }
}

inline std::string run(HSOUNDFONT font, int bank, int pc, bool drum, int key, int rate) {
    std::ostringstream report;
    Onset reference;
    for (int velocity : {40, 70, 100, 127}) {
        const HSTREAM stream = BASS_MIDI_StreamCreate(1, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, rate);
        if (!stream) return "ONSET FAIL decoder_create\n";
        struct Cleanup { HSTREAM s; ~Cleanup() { BASS_StreamFree(s); } } cleanup{stream};
        BASS_MIDI_FONTEX2 mapping{};
        mapping.font = font; mapping.spreset = pc; mapping.sbank = bank; mapping.dpreset = 0;
        mapping.dbank = drum ? 128 : 0; mapping.minchan = 0; mapping.numchan = 1;
        bool ok = BASS_MIDI_StreamSetFonts(stream, &mapping, 1 | BASS_MIDI_FONT_EX2);
        ok = BASS_ChannelSetAttribute(stream, BASS_ATTRIB_MIDI_SRC, 2) && ok;
        auto event = [&](DWORD e, DWORD p) { ok = BASS_MIDI_StreamEvent(stream, 0, e, p) && ok; };
        event(MIDI_EVENT_DRUMS, drum ? 1 : 0); event(MIDI_EVENT_BANK, 0); event(MIDI_EVENT_BANK_LSB, 0);
        event(MIDI_EVENT_PROGRAM, 0); event(MIDI_EVENT_VOLUME, 127); event(MIDI_EVENT_EXPRESSION, 127);
        event(MIDI_EVENT_REVERB, 0); event(MIDI_EVENT_CHORUS, 0);
        event(MIDI_EVENT_NOTE, key | (velocity << 8));
        BASS_MIDI_FONT actual{};
        const bool presetVerified = BASS_MIDI_StreamGetPreset(stream, 0, &actual) && actual.font == font && actual.bank == bank && actual.preset == pc;
        if (!ok || !presetVerified) return "ONSET FAIL event_or_preset_readback\n";
        std::vector<float> pcm(size_t(rate) / 2 * 2); // 500 ms stereo
        size_t at = 0;
        while (at < pcm.size()) {
            const DWORD wanted = DWORD(std::min<size_t>(1024, pcm.size() - at) * sizeof(float));
            const DWORD got = BASS_ChannelGetData(stream, pcm.data() + at, wanted | BASS_DATA_FLOAT);
            if (got == DWORD(-1) || !got || got > wanted || got % sizeof(float)) return "ONSET FAIL decode\n";
            at += got / sizeof(float);
        }
        const Onset o = analyze(pcm, rate);
        if (velocity == 100) reference = o;
        report << line(velocity, o) << '\n';
    }
    report << "ONSET classification(v100)=" << classify(reference)
           << " evidence=isolated_decode_of_actual_loaded_preset_from_NOTE_ON_sample0;excludes_midi_transport_render_alignment_and_output_latency\n";
    return report.str();
}
#endif

}  // namespace onset_probe

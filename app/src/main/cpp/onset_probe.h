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

#ifndef ONSET_PROBE_NO_BASS
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

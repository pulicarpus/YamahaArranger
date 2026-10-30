// REAL BASSMIDI (Linux x86_64) pitch / amplitude stability measurements for ONE held Piano-like note on SYNTHETIC SoundFonts, offline and deterministic.
// Question: which controller / effect / layering / gain condition makes a held note wobble ("wawawa")? Each scenario reports the pitch deviation
// (cents), its strongest periodic component (vibrato = pitch modulation), the amplitude-envelope modulation (tremolo / chorus beating) and the absolute pitch error.
// The analyzer is validated on known modulation in tests/pitch_analysis_test.cpp; scenario "VIB_POSITIVE_CONTROL" is a real SF2 vibrato and proves the chain can see it.
// NOT Colombo / Tyros / SX Prime, NOT Oboe, NOT the Redmi Pad speaker.
#include <bass.h>
#include <bassmidi.h>
#include "sf2_rich_fixture.h"
#include "pitch_analysis.h"
#include "../app/src/main/cpp/internal_gain.h"
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace rich_fixture;

namespace {
struct Var { const char* name; std::vector<std::pair<int,int>> gens; };
// Preset i -> instrument i, one zone, same looped harmonic sample (period 168 samples @44100 = 262.5 Hz, root key 60).
const Var kVars[] = {
    {"PlainPiano",   {{8, 13500}, {34, -12000}, {37, 0}}},
    {"SfVibrato",    {{8, 13500}, {34, -12000}, {37, 0}, {6, 40}, {23, -12000}, {24, 0}}},          // vibLfoToPitch 40 cents @ 8.18 Hz, no delay
    {"SfChorusSend", {{8, 13500}, {34, -12000}, {37, 0}, {15, 1000}}},                                // chorusEffectsSend 100 %
    {"SfReverbSend", {{8, 13500}, {34, -12000}, {37, 0}, {16, 1000}}},                                // reverbEffectsSend 100 %
    {"SfModLfoPitch",{{8, 13500}, {34, -12000}, {37, 0}, {5, 40}, {21, -12000}, {22, 0}}},           // modLfoToPitch 40 cents
};
const int NV = int(sizeof(kVars) / sizeof(kVars[0]));
const double kExpectHz = 44100.0 / 168.0;

std::string makeFont(const std::string& dir) {
    Bytes pd = {'p','d','t','a'}, ph(38 * (NV + 1)), pb(4 * (NV + 1)), pg, ins(22 * (NV + 1)), ib(4 * (NV + 1)), ig, sh(46 * 2);
    for (int i = 0; i < NV; ++i) {
        std::memcpy(ph.data() + 38 * i, kVars[i].name, std::strlen(kVars[i].name));
        word(ph, 38 * i + 20, i); word(ph, 38 * i + 22, 0); word(ph, 38 * i + 24, i);
        word(pb, 4 * i, i); word(pb, 4 * i + 2, 0); gen(pg, 41, i);
        std::memcpy(ins.data() + 22 * i, kVars[i].name, std::strlen(kVars[i].name)); word(ins, 22 * i + 20, i);
        word(ib, 4 * i, ig.size() / 4); word(ib, 4 * i + 2, 0);
        for (auto& g : kVars[i].gens) gen(ig, g.first, g.second);
        gen(ig, 54, 1); gen(ig, 53, 0);
    }
    std::memcpy(ph.data() + 38 * NV, "EOP", 3); word(ph, 38 * NV + 24, NV);
    word(pb, 4 * NV, NV); word(pb, 4 * NV + 2, 0); gen(pg, 0, 0);
    std::memcpy(ins.data() + 22 * NV, "EOI", 3); word(ins, 22 * NV + 20, NV);
    word(ib, 4 * NV, ig.size() / 4); word(ib, 4 * NV + 2, 0); gen(ig, 0, 0);
    const int N = 44100, period = 168, loopStart = period * 5, loopEnd = loopStart + period * 250;
    std::memcpy(sh.data(), "Saw", 3); dword(sh, 20, 0); dword(sh, 24, N); dword(sh, 28, loopStart); dword(sh, 32, loopEnd); dword(sh, 36, 44100);
    sh[40] = 60; word(sh, 44, 1); std::memcpy(sh.data() + 46, "EOS", 3);
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", ins); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes info = {'I','N','F','O'}, ver(4), sd = {'s','d','t','a'}, pcm((N + 46) * 2), out = {'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver, 0, 2); word(ver, 2, 1); chunk(info, "ifil", ver); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0}); chunk(info, "INAM", Bytes{'p','s','a',0});
    for (int i = 0; i < N; ++i) { double v = 0; for (int h = 1; h <= 24; ++h) v += std::sin(2 * M_PI * h * (i % period) / period) / h; word(pcm, i * 2, int(9000 * v)); }
    chunk(sd, "smpl", pcm); chunk(out, "LIST", info); chunk(out, "LIST", sd); chunk(out, "LIST", pd); dword(out, 4, out.size() - 8);
    const auto path = dir + "/psa-synthetic.sf2";
    std::ofstream f(path, std::ios::binary); f.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}

struct Sc {
    int preset = 0, channels = 1, rate = 48000;
    int cc91 = -1, cc93 = -1, cc7 = -1, cc11 = -1;       // -1 = never sent (BASSMIDI default)
    int modulation = -1, chanPres = -1, pitch = -1, pitchRange = -1;
    float gainDb = 0; double scaleToPeakDb = 0;         // gain stage test: scale raw render so the legacy peak is this level
    bool app = true;                                     // app stream settings: SRC 2-ish default left to BASSMIDI, voices 1000, font volume 0.90
};

std::vector<float> render(HSOUNDFONT font, const Sc& sc, const char* notes /*unused*/ = nullptr) {
    (void)notes;
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, sc.rate);
    BASS_MIDI_FONT f{font, -1, 0}; BASS_MIDI_StreamSetFonts(s, &f, 1);
    BASS_MIDI_FontSetVolume(font, 0.90f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOICES, 1000);
    for (int ch = 0; ch < sc.channels; ++ch) {
        BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_PROGRAM, sc.preset);
        if (sc.cc7 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_VOLUME, sc.cc7);
        if (sc.cc11 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_EXPRESSION, sc.cc11);
        if (sc.cc91 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_REVERB, sc.cc91);
        if (sc.cc93 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_CHORUS, sc.cc93);
#ifdef MIDI_EVENT_MODULATION
        if (sc.modulation >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_MODULATION, sc.modulation);
#endif
#ifdef MIDI_EVENT_CHANPRES
        if (sc.chanPres >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_CHANPRES, sc.chanPres);
#endif
#ifdef MIDI_EVENT_PITCHRANGE
        if (sc.pitchRange >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_PITCHRANGE, sc.pitchRange);
#endif
#ifdef MIDI_EVENT_PITCH
        if (sc.pitch >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_PITCH, sc.pitch);
#endif
        BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_NOTE, 60 | (100 << 8));
    }
    std::vector<float> pcm; std::vector<float> buf(sc.rate / 100 * 2);
    for (int b = 0; b < 200; ++b) { // 2.0 s held note
        const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT);
        if (got <= 0) break; pcm.insert(pcm.end(), buf.begin(), buf.begin() + got / 4);
    }
    BASS_StreamFree(s);
    if (sc.scaleToPeakDb != 0 || sc.gainDb != 0) {
        float pk = 0; for (float v : pcm) pk = std::max(pk, std::fabs(v));
        if (sc.scaleToPeakDb != 0 && pk > 0) { const float k = float(std::pow(10.0, sc.scaleToPeakDb / 20.0)) / pk; for (float& v : pcm) v *= k; }
        internal_gain::Stage st; st.setDb(sc.gainDb); st.process(pcm.data(), int(pcm.size()));
    }
    return pcm;
}

int failures = 0;
void check(bool ok, const std::string& w) { if (!ok) { std::cout << "PSA FAIL " << w << "\n"; ++failures; } }

pitch_analysis::Result run(HSOUNDFONT font, const char* name, const Sc& sc) {
    const auto pcm = render(font, sc);
    const auto r = pitch_analysis::analyze(pcm, sc.rate, kExpectHz, 0.4, 1.9);
    float pk = 0; for (float v : pcm) pk = std::max(pk, std::fabs(v));
    const char* verdict = !r.valid ? "NO_DATA" : (r.modCents > 3.0 && r.p2pCents > 6.0) ? "PITCH_MOD" : (r.amPeakPct > 5.0) ? "AMPLITUDE_MOD" : "STABLE";
    std::printf("PSA %-34s ch=%d rate=%d | pitch: mean=%+.2f std=%.2f p2p=%.2f cents modHz=%.2f modPeak=%.2f c | amp: depth=%.1f%% amHz=%.2f amPeak=%.1f%% | peak=%.1fdBFS | %s\n",
                name, sc.channels, sc.rate, r.meanCents, r.stdCents, r.p2pCents, r.modHz, r.modCents, r.amDepthPct, r.amHz, r.amPeakPct, pk > 0 ? 20 * std::log10(pk) : -180.0, verdict);
    return r;
}
}

int main(int argc, char** argv) {
    if (argc != 2 || !BASS_Init(0, 48000, 0, nullptr, nullptr)) return 1;
    const auto path = makeFont(argv[1]);
    HSOUNDFONT font = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!font) { std::cout << "PSA font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
#ifdef MIDI_EVENT_MODULATION
    std::cout << "PSA header: MIDI_EVENT_MODULATION present\n";
#else
    std::cout << "PSA header: MIDI_EVENT_MODULATION MISSING (CC1 scenarios skipped)\n";
#endif
    Sc base;
    const auto floor = run(font, "BASELINE_plain_cc91_0_cc93_0", [&] { Sc s = base; s.cc91 = 0; s.cc93 = 0; return s; }());
    const auto positive = run(font, "VIB_POSITIVE_CONTROL_sf2_vibrato", [&] { Sc s = base; s.preset = 1; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "APP_RIGHT_default_cc91_40_cc93_0", [&] { Sc s = base; s.cc91 = 40; s.cc93 = 0; return s; }());
    run(font, "NO_CC_SENT_plain_bassmidi_defaults", base);
    run(font, "REVERB_127", [&] { Sc s = base; s.cc91 = 127; s.cc93 = 0; return s; }());
    run(font, "CHORUS_CC93_64", [&] { Sc s = base; s.cc91 = 0; s.cc93 = 64; return s; }());
    const auto chorus127 = run(font, "CHORUS_CC93_127", [&] { Sc s = base; s.cc91 = 0; s.cc93 = 127; return s; }());
    run(font, "SF2_CHORUS_GEN_100pct_cc93_unset", [&] { Sc s = base; s.preset = 2; return s; }());
    run(font, "SF2_CHORUS_GEN_100pct_cc93_0", [&] { Sc s = base; s.preset = 2; s.cc93 = 0; return s; }());
    run(font, "SF2_CHORUS_GEN_100pct_cc93_64", [&] { Sc s = base; s.preset = 2; s.cc93 = 64; return s; }());
    run(font, "SF2_REVERB_GEN_100pct", [&] { Sc s = base; s.preset = 3; return s; }());
    const auto cc1 = run(font, "CC1_MODULATION_127_plain_sf2", [&] { Sc s = base; s.modulation = 127; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "CC1_MODULATION_0_explicit", [&] { Sc s = base; s.modulation = 0; s.cc91 = 0; s.cc93 = 0; return s; }());
    const auto press = run(font, "CHANNEL_PRESSURE_127_plain_sf2", [&] { Sc s = base; s.chanPres = 127; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "SF2_MODLFO_TO_PITCH_40c", [&] { Sc s = base; s.preset = 4; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "PITCH_BEND_CENTER_8192", [&] { Sc s = base; s.pitch = 8192; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "RIGHT1+2+3_same_preset_3ch", [&] { Sc s = base; s.channels = 3; s.cc91 = 0; s.cc93 = 0; return s; }());
    run(font, "RIGHT1+2+3_app_defaults_3ch", [&] { Sc s = base; s.channels = 3; s.cc91 = 40; s.cc93 = 0; return s; }());
    run(font, "STREAM_RATE_44100", [&] { Sc s = base; s.rate = 44100; s.cc91 = 0; s.cc93 = 0; return s; }());
    const auto g0 = run(font, "INTERNAL_GAIN_0dB_peak_-6dBFS", [&] { Sc s = base; s.cc91 = 0; s.cc93 = 0; s.scaleToPeakDb = -6; s.gainDb = 0; return s; }());
    const auto g12 = run(font, "INTERNAL_GAIN_+12dB_peak_-6dBFS", [&] { Sc s = base; s.cc91 = 0; s.cc93 = 0; s.scaleToPeakDb = -6; s.gainDb = 12; return s; }());

    // The chain must be able to see a real vibrato, and the neutral renders must be flat; everything else is reported, not asserted.
    check(floor.valid && floor.p2pCents < 3.0 && floor.amDepthPct < 3.0, "baseline is not flat (analyzer floor)");
    check(positive.valid && positive.modCents > 15 && std::fabs(positive.modHz - 8.18) < 1.0, "positive control not detected");
    check(std::fabs(g12.p2pCents - g0.p2pCents) < 3.0 && g12.amPeakPct < 5.0, "internal gain must not create pitch or amplitude modulation");
    (void)chorus127; (void)cc1; (void)press;
    BASS_MIDI_FontFree(font); BASS_Free();
    std::cout << (failures ? "PSA FAILED\n" : "PSA PASS\n");
    return failures ? 3 : 0;
}

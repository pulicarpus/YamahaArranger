// REALTIME DIAGNOSTIC (REAL BASSMIDI, Linux x86_64, SYNTHETIC SF2). Measures how the cost of one 256-frame decode block (the work done inside the
// Oboe callback under the synth mutex) scales with sounding voices and with BASS_ATTRIB_MIDI_SRC. x86 + synthetic looped sample: it gives the SHAPE
// of the cost curve, not Redmi Pad numbers. Production keeps SRC=2 (8-point sinc) and up to 1000 voices.
#include <bass.h>
#include <bassmidi.h>
#include "sf2_rich_fixture.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace rich_fixture;
using Clock = std::chrono::steady_clock;

static std::string makeFont(const std::string& dir, int zones) {
    // One preset (bank 0, program 0) -> one instrument with `zones` identical full-range looped zones (= layers per note) -> one looped sine sample.
    Bytes pd = {'p','d','t','a'}, ph(76), pb(8), pg, ins(44), ib, ig, sh(92);
    std::memcpy(ph.data(), "Bench", 5); word(ph, 20, 0); word(ph, 22, 0); word(ph, 24, 0); std::memcpy(ph.data() + 38, "EOP", 3); word(ph, 62, 1);
    word(pb, 0, 0); word(pb, 2, 0); gen(pg, 41, 0); word(pb, 4, pg.size() / 4); word(pb, 6, 0); gen(pg, 0, 0);
    std::memcpy(ins.data(), "BenchInst", 9); word(ins, 20, 0); std::memcpy(ins.data() + 22, "EOI", 3); word(ins, 42, zones);
    for (int z = 0; z < zones; ++z) { const auto pos = ib.size(); ib.resize(pos + 4); word(ib, pos, ig.size() / 4); word(ib, pos + 2, 0); gen(ig, 54, 1); gen(ig, 53, 0); }
    { const auto pos = ib.size(); ib.resize(pos + 4); word(ib, pos, ig.size() / 4); word(ib, pos + 2, 0); } gen(ig, 0, 0);
    std::memcpy(sh.data(), "Loop", 4); dword(sh, 20, 0); dword(sh, 24, 44100); dword(sh, 28, 1000); dword(sh, 32, 43000); dword(sh, 36, 44100); sh[40] = 60; word(sh, 44, 1);
    std::memcpy(sh.data() + 46, "EOS", 3);
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", ins); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes info = {'I','N','F','O'}, ver(4), sd = {'s','d','t','a'}, pcm((44100 + 46) * 2), out = {'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver, 0, 2); word(ver, 2, 1); chunk(info, "ifil", ver); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0}); chunk(info, "INAM", Bytes{'b','e','n','c','h',0});
    for (int i = 0; i < 44100; ++i) word(pcm, i * 2, int(6000 * std::sin(i * 0.05)));
    chunk(sd, "smpl", pcm); chunk(out, "LIST", info); chunk(out, "LIST", sd); chunk(out, "LIST", pd); dword(out, 4, out.size() - 8);
    const auto path = dir + "/bench-" + std::to_string(zones) + ".sf2";
    std::ofstream f(path, std::ios::binary); f.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}
static double pct(std::vector<double> v, double p) { std::sort(v.begin(), v.end()); return v[size_t((v.size() - 1) * p)]; }

int main(int argc, char** argv) {
    if (argc != 2 || !BASS_Init(0, 48000, 0, nullptr, nullptr)) return 1;
    BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, 1000);
    const double budgetUs = 256.0 / 48000.0 * 1e6;
    for (int zones : {2, 4}) {
        const auto path = makeFont(argv[1], zones);
        auto font = BASS_MIDI_FontInit(path.c_str(), 0);
        if (!font) { std::cout << "RTDIAG_REAL font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
        for (int src : {0, 1, 2, 3}) for (int notes : {10, 40, 100, 200, 400}) {
            auto stream = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
            BASS_MIDI_FONT f{font, 0, 0}; BASS_MIDI_StreamSetFonts(stream, &f, 1);
            BASS_ChannelSetAttribute(stream, BASS_ATTRIB_MIDI_SRC, float(src)); BASS_ChannelSetAttribute(stream, BASS_ATTRIB_MIDI_VOICES, 1000.0f);
            for (int ch = 0; ch < 16; ++ch) { BASS_MIDI_StreamEvent(stream, ch, MIDI_EVENT_PROGRAM, 0); }
            for (int n = 0; n < notes; ++n) BASS_MIDI_StreamEvent(stream, n % 16, MIDI_EVENT_NOTE, (30 + (n / 16) * 3 % 90) | (90 << 8));
            std::vector<float> out(256 * 2); std::vector<double> t;
            for (int b = 0; b < 40; ++b) BASS_ChannelGetData(stream, out.data(), out.size() * 4 | BASS_DATA_FLOAT);   // warm
            float active = 0; BASS_ChannelGetAttribute(stream, BASS_ATTRIB_MIDI_VOICES_ACTIVE, &active);
            for (int b = 0; b < 600; ++b) { const auto a = Clock::now(); BASS_ChannelGetData(stream, out.data(), out.size() * 4 | BASS_DATA_FLOAT); t.push_back(std::chrono::duration<double, std::micro>(Clock::now() - a).count()); }
            std::cout << "RTDIAG_REAL zonesPerNote=" << zones << " srcQuality=" << src << " notesHeld=" << notes << " voicesActive=" << int(active)
                      << " block256_p50us=" << pct(t, 0.5) << " p95us=" << pct(t, 0.95) << " maxus=" << *std::max_element(t.begin(), t.end())
                      << " budgetUs=" << budgetUs << " p50PercentOfBudget=" << 100.0 * pct(t, 0.5) / budgetUs << "\n";
            BASS_StreamFree(stream);
        }
        BASS_MIDI_FontFree(font);
    }
    BASS_Free(); std::cout << "RTDIAG_REAL PASS\n";
}

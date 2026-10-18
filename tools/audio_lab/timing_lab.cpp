// NOTE_ON -> PCM timing lab with the REAL BASS/BASSMIDI (Linux x86_64). Standalone: no BassMidiPlayer, no Android.
//   E1  does the first PCM sample depend on the render block size? (event-to-sample alignment, per preset/velocity)
//   E2  render-call cost per 256-frame block: idle, held notes, the block that consumes a NOTE_ON, polyphony
//   E3  cold vs preloaded first note (sample loading inside the render call)
// Absolute microseconds are from an x86 CI runner: use them only as RELATIVE evidence (ratios, spikes), never as Redmi Pad numbers.
//   timing_lab <colombo.sf2> <tyros.sf2>
#include "bass.h"
#include "bassmidi.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static constexpr int kRate = 48000;
using clk = std::chrono::steady_clock;
static double us(clk::time_point a, clk::time_point b) { return std::chrono::duration<double, std::micro>(b - a).count(); }

struct Preset { const char* name; int pc; };
static const Preset kPresets[] = {{"GrandPiano", 0}, {"EPiano", 4}, {"SteelGuitar", 25}, {"Strings", 48}, {"Organ", 16}, {"Brass", 61}};

static HSTREAM makeStream(HSOUNDFONT font, int bank, int pc, int channels = 1) {
    const HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, kRate);
    if (!s) return 0;
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_PPQN, 1920.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, 2.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOICES, 1000.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOL, 1.0f);
    BASS_MIDI_FontSetVolume(font, 0.9f);
    BASS_MIDI_FONTEX2 map{};
    map.font = font; map.spreset = pc; map.sbank = bank; map.dpreset = 0; map.dbank = 0; map.minchan = 0; map.numchan = DWORD(channels);
    if (!BASS_MIDI_StreamSetFonts(s, &map, 1 | BASS_MIDI_FONT_EX2)) { BASS_StreamFree(s); return 0; }
    for (int c = 0; c < channels; ++c) {
        BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_VOLUME, 127); BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_PAN, 64);
        BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_EXPRESSION, 100); BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_REVERB, 40);
        BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_CHORUS, 0);
        BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_BANK, 0); BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_BANK_LSB, 0); BASS_MIDI_StreamEvent(s, c, MIDI_EVENT_PROGRAM, 0);
    }
    return s;
}
static bool block(HSTREAM s, std::vector<float>& buf, int frames) {
    buf.resize(size_t(frames) * 2);
    return BASS_ChannelGetData(s, buf.data(), DWORD(frames * 8) | BASS_DATA_FLOAT) != DWORD(-1);
}
static double pct(std::vector<double> v, double p) { if (v.empty()) return 0; std::sort(v.begin(), v.end()); return v[size_t(p * (v.size() - 1))]; }
static double mean(const std::vector<double>& v) { double s = 0; for (double x : v) s += x; return v.empty() ? 0 : s / v.size(); }

static void e1(const char* fname, HSOUNDFONT font) {
    for (const auto& p : kPresets) for (int vel : {64, 100}) {
        int firstA[6], firstB[6]; double t20[6], t10[6]; int idx = 0;
        for (int N : {32, 64, 128, 256, 512, 1024}) {
            const HSTREAM s = makeStream(font, 0, p.pc);
            if (!s) { std::printf("LABT SKIP %s %s\n", fname, p.name); return; }
            std::vector<float> all, buf;
            BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, DWORD(60 | (vel << 8)));
            while (all.size() < size_t(kRate) * 2 * 6 / 10) { if (!block(s, buf, N)) break; all.insert(all.end(), buf.begin(), buf.end()); }
            BASS_StreamFree(s);
            const size_t frames = all.size() / 2; float peak = 0;
            std::vector<float> env(frames);
            for (size_t i = 0; i < frames; ++i) { env[i] = std::max(std::fabs(all[2 * i]), std::fabs(all[2 * i + 1])); peak = std::max(peak, env[i]); }
            auto first = [&](float th) { for (size_t i = 0; i < frames; ++i) if (env[i] > th) return int(i); return -1; };
            firstA[idx] = first(1e-4f); firstB[idx] = first(1e-3f);
            t20[idx] = first(peak * 0.1f) * 1000.0 / kRate; t10[idx] = first(peak * 0.316f) * 1000.0 / kRate; ++idx;
        }
        int lo = firstB[0], hi = firstB[0]; for (int i = 1; i < 6; ++i) { lo = std::min(lo, firstB[i]); hi = std::max(hi, firstB[i]); }
        std::printf("LABT E1 %s %s v%d | first>-80dB frames(N=32..1024): %d %d %d %d %d %d | first>-60dB: %d %d %d %d %d %d | -20dB ms %.2f | -10dB ms %.2f | spread(-60dB) %d frames\n",
                    fname, p.name, vel, firstA[0], firstA[1], firstA[2], firstA[3], firstA[4], firstA[5], firstB[0], firstB[1], firstB[2], firstB[3], firstB[4], firstB[5], t20[3], t10[3], hi - lo);
    }
}

static void e2(const char* fname, HSOUNDFONT font) {
    const int pc = 0; const HSTREAM s = makeStream(font, 0, pc, 4); if (!s) return;
    BASS_MIDI_FontLoad(font, pc, 0);
    std::vector<float> buf; std::vector<double> idle, held, onsetBlk, poly16, poly48;
    for (int i = 0; i < 300; ++i) block(s, buf, 256);
    for (int i = 0; i < 2000; ++i) { auto a = clk::now(); block(s, buf, 256); idle.push_back(us(a, clk::now())); }
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, 60 | (100 << 8));
    for (int i = 0; i < 2000; ++i) { auto a = clk::now(); block(s, buf, 256); held.push_back(us(a, clk::now())); }
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, 60);
    for (int i = 0; i < 400; ++i) block(s, buf, 256);
    for (int n = 0; n < 200; ++n) {                                   // the block that consumes a NOTE_ON, warm preset
        BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, DWORD((48 + n % 24) | (100 << 8)));
        auto a = clk::now(); block(s, buf, 256); onsetBlk.push_back(us(a, clk::now()));
        for (int i = 0; i < 40; ++i) block(s, buf, 256);
        BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, DWORD(48 + n % 24));
    }
    for (int k = 0; k < 16; ++k) BASS_MIDI_StreamEvent(s, k % 4, MIDI_EVENT_NOTE, DWORD((36 + 3 * k) | (100 << 8)));
    for (int i = 0; i < 1500; ++i) { auto a = clk::now(); block(s, buf, 256); poly16.push_back(us(a, clk::now())); }
    for (int k = 16; k < 48; ++k) BASS_MIDI_StreamEvent(s, k % 4, MIDI_EVENT_NOTE, DWORD((((24 + 2 * k) % 76) + 24) | (100 << 8)));
    for (int i = 0; i < 1500; ++i) { auto a = clk::now(); block(s, buf, 256); poly48.push_back(us(a, clk::now())); }
    auto row = [&](const char* n, const std::vector<double>& v) {
        std::printf("LABT E2 %s %-22s n=%zu mean %.0f us | p50 %.0f | p99 %.0f | max %.0f | budget 5333 us\n", fname, n, v.size(), mean(v), pct(v, .5), pct(v, .99), pct(v, 1.0));
    };
    row("idle", idle); row("one held note", held); row("block with NOTE_ON (warm)", onsetBlk); row("16 voices", poly16); row("48 voices", poly48);
    BASS_StreamFree(s);
}

static void e3(const char* fname, const std::string& path) {
    for (const bool preload : {false, true}) {
        if (std::system("sync; echo 3 | sudo tee /proc/sys/vm/drop_caches >/dev/null 2>&1") != 0) std::printf("LABT E3 (page cache not dropped)\n");
        auto t0 = clk::now();
        const HSOUNDFONT font = BASS_MIDI_FontInit(path.c_str(), 0);
        const double initUs = us(t0, clk::now());
        if (!font) return;
        double loadUs = 0;
        std::vector<float> buf;
        for (const auto& p : kPresets) {
            const HSTREAM s = makeStream(font, 0, p.pc); if (!s) continue;
            if (preload) { auto a = clk::now(); BASS_MIDI_FontLoad(font, p.pc, 0); loadUs = us(a, clk::now()); }
            BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, 60 | (100 << 8));
            auto a = clk::now(); block(s, buf, 256); const double first = us(a, clk::now());
            a = clk::now(); block(s, buf, 256); const double second = us(a, clk::now());
            std::printf("LABT E3 %s %-11s %s | FontInit %.1f ms | FontLoad %.1f ms | first block with NOTE_ON %.0f us | next block %.0f us | budget 5333 us\n",
                        fname, p.name, preload ? "PRELOADED" : "NOT-PRELOADED", initUs / 1000, loadUs / 1000, first, second);
            BASS_StreamFree(s);
        }
        BASS_MIDI_FontFree(font);
    }
}

int main(int argc, char** argv) {
    if (argc != 3) { std::fprintf(stderr, "usage: timing_lab colombo.sf2 tyros.sf2\n"); return 2; }
    if (!BASS_Init(0, kRate, 0, nullptr, nullptr)) return 3;
    BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, 1000); BASS_SetConfig(BASS_CONFIG_MIDI_COMPACT, 0); BASS_SetConfig(BASS_CONFIG_UPDATEPERIOD, 10);
    const char* names[] = {"colombo", "tyros"};
    for (int i = 0; i < 2; ++i) {
        const std::string path = argv[1 + i];
        e3(names[i], path);                                            // cold first (page cache dropped inside)
        const HSOUNDFONT font = BASS_MIDI_FontInit(path.c_str(), 0);
        if (!font) return 4;
        e1(names[i], font); e2(names[i], font);
        BASS_MIDI_FontFree(font);
    }
    return 0;
}

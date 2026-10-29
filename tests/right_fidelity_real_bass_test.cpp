// REAL BASSMIDI (Linux x86_64) measurements for the RIGHT fidelity audit, on SYNTHETIC SoundFonts that carry only SF2-spec default
// behaviour (no custom velocity modulators): Piano-like (fast attack), Strings-like (slow attack, low-pass), Flute-like and Bass-like.
// Everything is rendered offline through a BASS decode stream: no Oboe, no Android, no ColomboGMGS2_BM.sf2. These are x86 host numbers that show
// how BASSMIDI itself treats velocity, CC7/CC11, stream/font volume, CC91/CC93 and polyphony. They are NOT a measurement of the Redmi Pad
// speaker, and a different SoundFont (Colombo) can add its own velocity layers/modulators on top.
#include <bass.h>
#include <bassmidi.h>
#include "sf2_rich_fixture.h"
#include "../app/src/main/cpp/internal_gain.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace rich_fixture;

namespace {
struct Instr { const char* name; int attackTc; int fcCents; int sustainCb; int key; };
const Instr kInstr[4] = {
    {"Piano",   -12000, 13500, 300,  60},   // instant attack, bright, slowly fading
    {"Strings",  -2400,  9000,   0,  60},   // ~0.25 s attack, low-pass
    {"Flute",    -6000,  8000,   0,  72},   // ~30 ms attack, dark
    {"ElecBass", -12000, 6500,  60,  36},   // instant, very dark
};

std::string makeFont(const std::string& dir) {
    Bytes pd = {'p','d','t','a'}, ph(38 * 5), pb(4 * 5), pg, ins(22 * 5), ib(4 * 5), ig, sh(46 * 2);
    for (int i = 0; i < 4; ++i) {
        std::memcpy(ph.data() + 38 * i, kInstr[i].name, std::strlen(kInstr[i].name));
        word(ph, 38 * i + 20, i); word(ph, 38 * i + 22, 0); word(ph, 38 * i + 24, i);
        word(pb, 4 * i, i); word(pb, 4 * i + 2, 0); gen(pg, 41, i);
        std::memcpy(ins.data() + 22 * i, kInstr[i].name, std::strlen(kInstr[i].name)); word(ins, 22 * i + 20, i);
        word(ib, 4 * i, ig.size() / 4); word(ib, 4 * i + 2, 0);
        gen(ig, 8, kInstr[i].fcCents); gen(ig, 34, kInstr[i].attackTc); gen(ig, 37, kInstr[i].sustainCb);
        gen(ig, 54, 1); gen(ig, 53, 0);
    }
    std::memcpy(ph.data() + 38 * 4, "EOP", 3); word(ph, 38 * 4 + 24, 4);
    word(pb, 16, 4); word(pb, 18, 0); gen(pg, 0, 0);
    std::memcpy(ins.data() + 22 * 4, "EOI", 3); word(ins, 22 * 4 + 20, 4);
    word(ib, 16, ig.size() / 4); word(ib, 18, 0); gen(ig, 0, 0);
    const int N = 44100, period = 168, loopStart = period * 5, loopEnd = loopStart + period * 250;
    std::memcpy(sh.data(), "Saw", 3); dword(sh, 20, 0); dword(sh, 24, N); dword(sh, 28, loopStart); dword(sh, 32, loopEnd); dword(sh, 36, 44100);
    sh[40] = 60; word(sh, 44, 1);
    std::memcpy(sh.data() + 46, "EOS", 3);
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", ins); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes info = {'I','N','F','O'}, ver(4), sd = {'s','d','t','a'}, pcm((N + 46) * 2), out = {'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver, 0, 2); word(ver, 2, 1); chunk(info, "ifil", ver); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0}); chunk(info, "INAM", Bytes{'r','f','a',0});
    for (int i = 0; i < N; ++i) {
        double v = 0; for (int h = 1; h <= 24; ++h) v += std::sin(2 * M_PI * h * (i % period) / period) / h;
        word(pcm, i * 2, int(9000 * v));
    }
    chunk(sd, "smpl", pcm); chunk(out, "LIST", info); chunk(out, "LIST", sd); chunk(out, "LIST", pd); dword(out, 4, out.size() - 8);
    const auto path = dir + "/rfa-synthetic.sf2";
    std::ofstream f(path, std::ios::binary); f.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}

struct Opt {
    int cc7 = -1, cc11 = -1, cc91 = -1, cc93 = -1;   // -1 = leave BASSMIDI default
    float streamVol = 1.0f;                           // BASS_ATTRIB_MIDI_VOL (the app sets 1.0)
    float fontVol = 0.90f;                            // BASS_MIDI_FontSetVolume (the app uses 0.90)
    double noteSec = 1.2, totalSec = 1.6;
};
struct M { double peak = 0, steadyRms = 0, attackMs = -1, tailRms = 0; int clipped = 0; };

const int RATE = 48000;

M render(HSOUNDFONT font, const std::vector<std::pair<int,int>>& notes /*key,vel*/, int pc, const Opt& o, int channels = 1, std::vector<float>* pcmOut = nullptr) {
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, RATE);
    BASS_MIDI_FONT f{font, -1, 0}; BASS_MIDI_StreamSetFonts(s, &f, 1);
    BASS_MIDI_FontSetVolume(font, o.fontVol);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOL, o.streamVol);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOICES, 1000);
    for (int ch = 0; ch < channels; ++ch) {
        BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_PROGRAM, pc);
        if (o.cc7 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_VOLUME, o.cc7);
        if (o.cc11 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_EXPRESSION, o.cc11);
        if (o.cc91 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_REVERB, o.cc91);
        if (o.cc93 >= 0) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_CHORUS, o.cc93);
        for (auto& n : notes) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_NOTE, n.first | (n.second << 8));
    }
    const int block = RATE / 100; // 10 ms
    std::vector<float> buf(block * 2);
    std::vector<double> rmsPerBlock;
    M m; bool off = false; int clipped = 0; double tail = 0; int tailN = 0;
    for (int b = 0; b < int(o.totalSec * 100); ++b) {
        if (!off && b >= int(o.noteSec * 100)) { for (int ch = 0; ch < channels; ++ch) for (auto& n : notes) BASS_MIDI_StreamEvent(s, ch, MIDI_EVENT_NOTE, n.first); off = true; }
        const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT);
        if (got <= 0) break;
        if (pcmOut) pcmOut->insert(pcmOut->end(), buf.begin(), buf.begin() + got / 4);
        double e = 0; const int n = got / 4;
        for (int i = 0; i < n; ++i) { const double v = buf[i]; e += v * v; m.peak = std::max(m.peak, std::fabs(v)); if (std::fabs(v) > 1.0) ++clipped; }
        const double r = std::sqrt(e / std::max(1, n)); rmsPerBlock.push_back(r);
        if (off) { tail += e; tailN += n; }
    }
    BASS_StreamFree(s);
    m.clipped = clipped;
    double steady = 0; int sn = 0;
    for (int b = 70; b < 100 && b < int(rmsPerBlock.size()); ++b) { steady += rmsPerBlock[b]; ++sn; }
    m.steadyRms = sn ? steady / sn : 0;
    for (size_t b = 0; b < rmsPerBlock.size(); ++b) if (m.steadyRms > 0 && rmsPerBlock[b] >= 0.5 * m.steadyRms) { m.attackMs = double(b) * 10.0; break; }
    m.tailRms = tailN ? std::sqrt(tail / tailN) : 0;
    return m;
}
double db20(double x) { return x > 1e-9 ? 20.0 * std::log10(x) : -180.0; }
double db(double x) { return x > 1e-9 ? 20.0 * std::log10(x) : -180.0; }
int failures = 0;
void check(bool ok, const std::string& what) { if (!ok) { std::cout << "RFA FAIL " << what << "\n"; ++failures; } }
}

int main(int argc, char** argv) {
    if (argc != 2 || !BASS_Init(0, RATE, 0, nullptr, nullptr)) return 1;
    const auto path = makeFont(argv[1]);
    HSOUNDFONT font = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!font) { std::cout << "RFA font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
    const int vels[] = {20, 40, 48, 57, 64, 80, 100, 127};

    // 1. velocity sweep per instrument (BASSMIDI defaults: CC7=100 CC11=127, stream 1.0, font 0.90 like the app)
    for (int i = 0; i < 4; ++i) {
        const double ref = render(font, {{kInstr[i].key, 100}}, i, Opt()).steadyRms;
        double prev = -1;
        for (int v : vels) {
            const M m = render(font, {{kInstr[i].key, v}}, i, Opt());
            std::printf("RFA velocity inst=%s vel=%d peak=%.4f steadyRmsDb=%.1f vsVel100Db=%+.1f attackMs=%.0f\n", kInstr[i].name, v, m.peak, db(m.steadyRms), db(m.steadyRms) - db(ref), m.attackMs);
            check(std::isfinite(m.peak) && m.peak > 0.0, std::string("silent/NaN ") + kInstr[i].name);
            check(m.steadyRms + 1e-6 >= prev * 0.98, std::string("rms not monotonic with velocity ") + kInstr[i].name);
            prev = m.steadyRms;
        }
    }
    // 2. touch curves (same gamma table as Kotlin) applied to the E343 median 48 and 57
    for (double g : {1.0, 0.8, 0.6, 0.5, 0.4}) {
        for (int raw : {48, 57}) {
            const int mapped = std::max(1, std::min(127, int(std::lround(127.0 * std::pow(raw / 127.0, g)))));
            const double a = render(font, {{60, mapped}}, 1, Opt()).steadyRms, ref = render(font, {{60, 100}}, 1, Opt()).steadyRms;
            const double ms = render(font, {{60, mapped}}, 1, Opt()).attackMs;
            std::printf("RFA curve gamma=%.2f raw=%d mapped=%d Strings vsVel100Db=%+.1f attackMs=%.0f\n", g, raw, mapped, db(a) - db(ref), ms);
        }
    }
    // 3. CC7 / CC11 (vel 100)
    {
        const double ref = render(font, {{60, 100}}, 0, Opt()).steadyRms;
        for (int cc : {127, 100, 64, 32}) {
            Opt a; a.cc7 = cc; Opt b; b.cc11 = cc;
            std::printf("RFA controller cc=%d CC7 vsDefaultDb=%+.1f CC11 vsDefaultDb=%+.1f\n", cc, db(render(font, {{60, 100}}, 0, a).steadyRms) - db(ref), db(render(font, {{60, 100}}, 0, b).steadyRms) - db(ref));
        }
        Opt a, b; a.cc7 = 127; b.cc7 = 100;
        check(render(font, {{60, 100}}, 0, a).steadyRms >= render(font, {{60, 100}}, 0, b).steadyRms, "CC7 127 not louder than 100");
    }
    // 4. stream / font volume and headroom: single note and a dense chord
    {
        const double ref = render(font, {{60, 100}}, 0, Opt()).steadyRms;
        for (float sv : {1.0f, 0.5f, 1.27f}) { Opt o; o.streamVol = sv; std::printf("RFA streamVol=%.2f vsApp=%+.1f dB\n", sv, db(render(font, {{60, 100}}, 0, o).steadyRms) - db(ref)); }
        for (float fv : {0.9f, 1.0f, 1.5f}) { Opt o; o.fontVol = fv; std::printf("RFA fontVol=%.2f vsApp=%+.1f dB\n", fv, db(render(font, {{60, 100}}, 0, o).steadyRms) - db(ref)); }
        const M one = render(font, {{60, 127}}, 0, Opt());
        std::printf("RFA headroom single note vel127 peak=%.3f (%.1f dBFS)\n", one.peak, db(one.peak));
    }
    // 5. reverb / chorus sends: tail energy after NOTE_OFF
    {
        for (int cc : {0, 64, 127}) {
            Opt r; r.cc91 = cc; Opt c; c.cc93 = cc;
            const M mr = render(font, {{60, 100}}, 0, r), mc = render(font, {{60, 100}}, 0, c);
            std::printf("RFA reverb cc91=%d peak=%.4f tailRmsDb=%.1f | chorus cc93=%d peak=%.4f tailRmsDb=%.1f\n", cc, mr.peak, db(mr.tailRms), cc, mc.peak, db(mc.tailRms));
        }
    }
    // 6. polyphony / clipping: RIGHT1+2+3 (3 channels) chord of N notes, then + 8 "style" channels
    for (int velo : {100, 127}) {
        for (int n : {1, 3, 6, 10}) {
            std::vector<std::pair<int,int>> notes; for (int k = 0; k < n; ++k) notes.push_back({48 + k * 2, velo});
            const M right = render(font, notes, 0, Opt(), 3);
            const M all = render(font, notes, 0, Opt(), 11);
            std::printf("RFA poly vel=%d notes=%d RIGHT(3ch) peak=%.3f clipped=%d | RIGHT+8 style ch peak=%.3f clipped=%d\n", velo, n, right.peak, right.clipped, all.peak, all.clipped);
        }
    }
    // 7. INTERNAL OUTPUT GAIN stage (internal_gain.h) on real BASSMIDI output: RIGHT only (3 ch) and RIGHT + 8 style channels.
    //    "normal" = like the app (stream 1.0, font 0.90); "hot" = deliberately extreme (stream 1.27, font 1.5, CC7 127) to exercise the knee.
    for (int hot = 0; hot < 2; ++hot) {
        for (int withStyle = 0; withStyle < 2; ++withStyle) {
            Opt o; if (hot) { o.streamVol = 1.27f; o.fontVol = 1.5f; o.cc7 = 127; }
            std::vector<std::pair<int,int>> notes; for (int k = 0; k < 10; ++k) notes.push_back({48 + k * 2, 127});
            std::vector<float> pcm; render(font, notes, 1, o, withStyle ? 11 : 3, &pcm);
            double refRms = 0;
            for (float db : {0.f, 3.f, 6.f, 9.f, 12.f}) {
                internal_gain::Stage st; st.setDb(db); std::vector<float> y = pcm; st.process(y.data(), int(y.size()));
                double ss = 0, ssIdeal = 0; float pk = 0; int hard = 0;
                const float lin = internal_gain::dbToLinear(db);
                for (size_t i = 0; i < y.size(); ++i) { ss += double(y[i]) * y[i]; ssIdeal += double(pcm[i] * lin) * (pcm[i] * lin); pk = std::max(pk, std::fabs(y[i])); if (std::fabs(y[i]) > 1.0f) ++hard; }
                const double rms = std::sqrt(ss / y.size()), ideal = std::sqrt(ssIdeal / y.size());
                if (db == 0.f) refRms = rms;
                std::printf("RFA igain %s %s db=%+.0f peakOutDbFS=%.1f rmsGainVs0dB=%+.1f (ideal %+.1f, knee loss %.2f dB) %s\n", hot ? "HOT" : "normal", withStyle ? "RIGHT+Style" : "RIGHT-only",
                            db, db20(pk), db20(rms) - db20(refRms), db20(ideal) - db20(refRms), db20(ideal) - db20(rms), st.report().c_str());
                check(hard == 0 || db == 0.f, "stage output reaches 1.0 above 0 dB");
                check(db != 0.f || std::equal(y.begin(), y.end(), pcm.begin()), "0 dB is bit-identical");
            }
        }
    }
    BASS_MIDI_FontFree(font); BASS_Free();
    std::cout << (failures ? "RFA FAILED\n" : "RFA PASS\n");
    return failures ? 3 : 0;
}

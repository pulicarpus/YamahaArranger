// REAL BASSMIDI (Linux x86_64): what the MIDI SONG internal stream does with a Yamaha XG file's setup SysEx, offline, synthetic SF2.
// Question (user report on a PSR XG recording): piano "vibrates" and the Rhythm on channel 9 is not heard. The file sends CC1 = 82 on the
// piano, XG System On, XG MW LFO PMOD DEPTH = 0 for that part, and XG PART MODE = drum for part 9 (bank MSB 127). V1A dropped all SysEx.
// Each scenario feeds the stream exactly like BassMidiPlayer::songEvents / songSysEx (BASS_MIDI_StreamEvents RAW) on a stream created
// like ensureSongStreamLocked, then reports vibrato (pitch analysis of a held note) and whether channel 9 is a drum channel.
// NOT Colombo / Tyros / SX, NOT the Redmi Pad.
#include <bass.h>
#include <bassmidi.h>
#include "sf2_rich_fixture.h"
#include "pitch_analysis.h"
#include <algorithm>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
using namespace rich_fixture;

namespace {
const double kExpectHz = 44100.0 / 168.0;
// presets: (bank 0, pc 0) piano, (bank 0, pc 58) melodic, (bank 128, pc 0) drum kit; one plain looped harmonic sample (root 60)
std::string makeFont(const std::string& dir) {
    struct P { const char* name; int bank, pc; };
    const P ps[] = {{"Piano", 0, 0}, {"Melodic58", 0, 58}, {"Kit", 128, 0}, {"Kit25", 128, 25}};
    const int NP = 4;
    Bytes pd = {'p','d','t','a'}, ph(38 * (NP + 1)), pb(4 * (NP + 1)), pg, ins(22 * 2), ib(4 * 2), ig, sh(46 * 2);
    for (int i = 0; i < NP; ++i) {
        std::memcpy(ph.data() + 38 * i, ps[i].name, std::strlen(ps[i].name));
        word(ph, 38 * i + 20, ps[i].pc); word(ph, 38 * i + 22, ps[i].bank); word(ph, 38 * i + 24, i);
        word(pb, 4 * i, i); word(pb, 4 * i + 2, 0); gen(pg, 41, 0);
    }
    std::memcpy(ph.data() + 38 * NP, "EOP", 3); word(ph, 38 * NP + 24, NP);
    word(pb, 4 * NP, NP); word(pb, 4 * NP + 2, 0); gen(pg, 0, 0);
    std::memcpy(ins.data(), "Plain", 5); word(ins, 20, 0);
    word(ib, 0, 0); word(ib, 2, 0);
    gen(ig, 8, 13500); gen(ig, 34, -12000); gen(ig, 37, 0); gen(ig, 54, 1); gen(ig, 53, 0);
    std::memcpy(ins.data() + 22, "EOI", 3); word(ins, 42, 1);
    word(ib, 4, ig.size() / 4); word(ib, 6, 0); gen(ig, 0, 0);
    const int N = 44100, period = 168, loopStart = period * 5, loopEnd = loopStart + period * 250;
    std::memcpy(sh.data(), "Saw", 3); dword(sh, 20, 0); dword(sh, 24, N); dword(sh, 28, loopStart); dword(sh, 32, loopEnd); dword(sh, 36, 44100);
    sh[40] = 60; word(sh, 44, 1); std::memcpy(sh.data() + 46, "EOS", 3);
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", ins); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes info = {'I','N','F','O'}, ver(4), sd = {'s','d','t','a'}, pcm((N + 46) * 2), out = {'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver, 0, 2); word(ver, 2, 1); chunk(info, "ifil", ver); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0}); chunk(info, "INAM", Bytes{'s','x','g',0});   // even size: RIFF chunks are word-aligned
    for (int i = 0; i < N; ++i) { double v = 0; for (int h = 1; h <= 24; ++h) v += std::sin(2 * M_PI * h * (i % period) / period) / h; word(pcm, i * 2, int(9000 * v)); }
    chunk(sd, "smpl", pcm); chunk(out, "LIST", info); chunk(out, "LIST", sd); chunk(out, "LIST", pd); dword(out, 4, out.size() - 8);
    const auto path = dir + "/song-xg-synthetic.sf2";
    std::ofstream f(path, std::ios::binary); f.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}

using Msg = std::vector<uint8_t>;
const Msg GM_ON = {0xF0, 0x7E, 0x7F, 0x09, 0x01, 0xF7};
const Msg XG_ON = {0xF0, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7};
const Msg XG_P0_MW_PMOD_0 = {0xF0, 0x43, 0x10, 0x4C, 0x08, 0x00, 0x20, 0x00, 0xF7};   // part 1: MW LFO PMOD DEPTH 0
const Msg XG_P8_DRUM_S2 = {0xF0, 0x43, 0x10, 0x4C, 0x08, 0x08, 0x07, 0x03, 0xF7};     // part 9: PART MODE drum S2

bool gPlainMap = false, gNoReset = false, gV1aMap = false;   // isolation switches for the diagnostic scenarios only
HSTREAM songStream(HSOUNDFONT font) {   // as BassMidiPlayer::ensureSongStreamLocked
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
    if (gPlainMap) { BASS_MIDI_FONT f{font, -1, 0}; BASS_MIDI_StreamSetFonts(s, &f, 1); BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, 2); return s; }
    BASS_MIDI_FONTEX2 m{}; m.font = font; m.spreset = -1; m.sbank = -1; m.dpreset = -1; m.dbank = gV1aMap ? -1 : 0; m.dbanklsb = 0; m.minchan = 0; m.numchan = 16;
    const BOOL set = BASS_MIDI_StreamSetFonts(s, &m, 1 | BASS_MIDI_FONT_EX2);
    if (!set) std::printf("SXG diag StreamSetFonts(EX2) failed err=%d\n", BASS_ErrorGetCode());
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, 2);
    return s;
}
void raw(HSTREAM s, const Msg& m) { BASS_MIDI_StreamEvents(s, BASS_MIDI_EVENTS_RAW, m.data(), DWORD(m.size())); }

int failures = 0;
void check(bool ok, const std::string& w) { if (!ok) { std::cout << "SXG FAIL " << w << "\n"; ++failures; } }

pitch_analysis::Result piano(HSOUNDFONT font, const char* name, const std::vector<Msg>& setup) {
    HSTREAM s = songStream(font);
    if (!gNoReset) raw(s, GM_ON);                   // the player resets the Song synth before a song
    for (const auto& m : setup) raw(s, m);
    raw(s, {0xB0, 0x00, 0x00}); raw(s, {0xB0, 0x20, 0x00}); raw(s, {0xC0, 0x00}); raw(s, {0xB0, 0x01, 82}); raw(s, {0x90, 60, 100});
    std::vector<float> pcm, buf(480 * 2);
    for (int b = 0; b < 200; ++b) { const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT); if (got <= 0) break; pcm.insert(pcm.end(), buf.begin(), buf.begin() + got / 4); }
    BASS_MIDI_FONT live{}; const bool gp = BASS_MIDI_StreamGetPreset(s, 0, &live);
    float pk = 0; for (float v : pcm) pk = std::max(pk, std::fabs(v));
    std::printf("SXG diag %-31s ch1 preset=%d bank=%d font=%s samples=%zu peak=%.1fdBFS mod=%u\n", name, gp ? live.preset : -1, gp ? live.bank : -1,
                gp && live.font ? "ok" : "none", pcm.size(), pk > 0 ? 20 * std::log10(pk) : -180.0, unsigned(BASS_MIDI_StreamGetEvent(s, 0, MIDI_EVENT_MODULATION)));
    BASS_StreamFree(s);
    const auto r = pitch_analysis::analyze(pcm, 48000, kExpectHz, 0.4, 1.9);
    std::printf("SXG %-36s CC1=82 | pitch p2p=%.2f c modHz=%.2f modPeak=%.2f c | %s\n", name, r.p2pCents, r.modHz, r.modCents,
                !r.valid ? "NO_DATA" : (r.modCents > 3.0 && r.p2pCents > 6.0) ? "VIBRATO" : "STABLE");
    return r;
}

DWORD rhythm9(HSOUNDFONT font, const char* name, const std::vector<Msg>& setup) {
    HSTREAM s = songStream(font);
    raw(s, GM_ON);
    for (const auto& m : setup) raw(s, m);
    raw(s, {0xB8, 0x00, 127}); raw(s, {0xB8, 0x20, 0}); raw(s, {0xC8, 58}); raw(s, {0x98, 36, 100});
    std::vector<float> buf(480 * 2); BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT);
    const DWORD drums = BASS_MIDI_StreamGetEvent(s, 8, MIDI_EVENT_DRUMS);
    BASS_MIDI_FONT live{}; const bool got = BASS_MIDI_StreamGetPreset(s, 8, &live);
    std::printf("SXG %-36s ch9 bank127 pc58 | drums=%u preset=%d bank=%d\n", name, unsigned(drums), got ? live.preset : -1, got ? live.bank : -1);
    BASS_StreamFree(s);
    return drums;
}
}

// ---- V1A.2 SONG MIXER: what the synth does with the exact messages / maps the mixer produces ----
struct Timed { int block; Msg m; };   // 1 block = 10 ms at 48 kHz
std::vector<float> renderTimed(HSTREAM s, const std::vector<Timed>& ev, int blocks) {
    std::vector<float> pcm, buf(480 * 2); size_t k = 0;
    for (int b = 0; b < blocks; ++b) {
        while (k < ev.size() && ev[k].block <= b) raw(s, ev[k++].m);
        const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT); if (got <= 0) break;
        pcm.insert(pcm.end(), buf.begin(), buf.begin() + got / 4);
    }
    return pcm;
}
double rmsDb(const std::vector<float>& pcm, double t0, double t1, int side = -1) {   // side: -1 both, 0 left, 1 right
    double e = 0; size_t n = 0;
    for (size_t i = size_t(t0 * 48000) * 2; i < std::min(pcm.size(), size_t(t1 * 48000) * 2); ++i) { if (side >= 0 && int(i % 2) != side) continue; e += double(pcm[i]) * pcm[i]; ++n; }
    return n && e > 0 ? 10 * std::log10(e / n) : -180.0;
}
HSTREAM pinnedStream(HSOUNDFONT font, int ch, int spreset, int sbank) {   // as songSetChannelVoice: pin first, then the general map
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
    BASS_MIDI_FONTEX2 m[2]{};
    m[0].font = font; m[0].spreset = spreset; m[0].sbank = sbank; m[0].dpreset = 0; m[0].dbank = sbank >= 127 ? 128 : 0; m[0].dbanklsb = 0; m[0].minchan = ch; m[0].numchan = 1;
    m[1].font = font; m[1].spreset = -1; m[1].sbank = -1; m[1].dpreset = -1; m[1].dbank = 0; m[1].dbanklsb = 0; m[1].minchan = 0; m[1].numchan = 16;
    BASS_MIDI_StreamSetFonts(s, m, 2 | BASS_MIDI_FONT_EX2);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, 2);
    return s;
}
void mixerChecks(HSOUNDFONT font) {
    // voice override: CH3 pinned to "Melodic58"; the mixer sends Bank 0 / LSB 0 / Program 0 instead of the file's program
    {
        HSTREAM s = pinnedStream(font, 2, 58, 0);
        raw(s, GM_ON); raw(s, {0xB2, 0, 0}); raw(s, {0xB2, 32, 0}); raw(s, {0xC2, 0}); raw(s, {0xC0, 0});
        std::vector<float> buf(960); BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT);
        BASS_MIDI_FONT a{}, b{}; const bool ga = BASS_MIDI_StreamGetPreset(s, 2, &a), gb = BASS_MIDI_StreamGetPreset(s, 0, &b);
        std::printf("SXG mixer voice pin CH3 -> preset=%d bank=%d | CH1 untouched preset=%d bank=%d\n", ga ? a.preset : -1, ga ? a.bank : -1, gb ? b.preset : -1, gb ? b.bank : -1);
        check(ga && a.preset == 58 && a.bank == 0, "CH3 voice pin must play preset 58 on Program 0");
        check(gb && b.preset == 0, "CH1 must keep its own preset");
        BASS_StreamFree(s);
    }
    // drum kit override on an XG drum part: file bank 127 passes, Program 0 selects the pinned kit 25
    {
        HSTREAM s = pinnedStream(font, 8, 25, 128);
        raw(s, GM_ON); raw(s, XG_ON); raw(s, XG_P8_DRUM_S2); raw(s, {0xB8, 0, 127}); raw(s, {0xC8, 0}); raw(s, {0x98, 36, 100});
        std::vector<float> buf(960); BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT);
        BASS_MIDI_FONT a{}; const bool ga = BASS_MIDI_StreamGetPreset(s, 8, &a);
        std::printf("SXG mixer kit pin CH9 drums=%u -> preset=%d bank=%d\n", unsigned(BASS_MIDI_StreamGetEvent(s, 8, MIDI_EVENT_DRUMS)), ga ? a.preset : -1, ga ? a.bank : -1);
        check(ga && a.preset == 25 && a.bank == 128, "CH9 kit pin must play kit 25");
        BASS_StreamFree(s);
    }
    // volume / pan: CC7 / CC10 really change the Song stream's audio
    auto level = [&](std::vector<Msg> pre, int side) {
        HSTREAM s = songStream(font); raw(s, GM_ON);
        std::vector<Timed> ev; for (auto& m : pre) ev.push_back({0, m}); ev.push_back({0, {0xC0, 0}}); ev.push_back({0, {0x90, 60, 100}});
        const auto pcm = renderTimed(s, ev, 60); BASS_StreamFree(s); return rmsDb(pcm, 0.2, 0.55, side);
    };
    const double v127 = level(std::vector<Msg>{Msg{0xB0, 7, 127}}, -1), v30 = level(std::vector<Msg>{Msg{0xB0, 7, 30}}, -1);
    const double panL = level(std::vector<Msg>{Msg{0xB0, 10, 0}}, 0), panR = level(std::vector<Msg>{Msg{0xB0, 10, 0}}, 1);
    std::printf("SXG mixer volume CC7 127 = %.1f dB, CC7 30 = %.1f dB (diff %.1f) | pan CC10 0: left %.1f dB right %.1f dB\n", v127, v30, v127 - v30, panL, panR);
    check(v127 - v30 > 10.0, "CC7 30 must be clearly quieter than CC7 127");
    check(panL - panR > 10.0, "CC10 0 must put the sound left");
    // sustain: the file presses the pedal, the note ends at 0.2 s. FOLLOW keeps it ringing; IGNORE / FORCE OFF (no pedal / pedal 0) do not
    auto tail = [&](bool pedal) {
        HSTREAM s = songStream(font); raw(s, GM_ON);
        std::vector<Timed> ev{{0, {0xC2, 0}}, {0, pedal ? Msg{0xB2, 64, 127} : Msg{0xB2, 64, 0}}, {0, {0x92, 60, 100}}, {20, {0x82, 60, 0}}};
        const auto pcm = renderTimed(s, ev, 100); BASS_StreamFree(s); return std::make_pair(rmsDb(pcm, 0.05, 0.18), rmsDb(pcm, 0.6, 0.95));
    };
    const auto follow = tail(true), off = tail(false);
    std::printf("SXG mixer sustain CH3 FOLLOW: held %.1f dB tail %.1f dB | IGNORE/FORCE OFF: held %.1f dB tail %.1f dB\n", follow.first, follow.second, off.first, off.second);
    check(follow.second > follow.first - 6.0, "FOLLOW: the pedal keeps the note after NOTE OFF");
    check(off.first > -60.0 && off.second < off.first - 30.0, "IGNORE / FORCE OFF: the note ends at its NOTE OFF");
}

// ---- V1C INVESTIGATION: a Yamaha Bank LSB (Style voices use MSB 0 / LSB 112..) on the Song stream's generic map vs a per-channel route ----
struct LsbResult { int preset, bank; double peakDb; };
LsbResult lsbScenario(HSOUNDFONT font, bool xg, bool route, int lsb, int pc) {
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
    std::vector<BASS_MIDI_FONTEX2> maps;
    if (route) { BASS_MIDI_FONTEX2 r{}; r.font = font; r.spreset = 58; r.sbank = 0; r.dpreset = pc; r.dbank = 0; r.dbanklsb = lsb; r.minchan = 10; r.numchan = 1; maps.push_back(r); }
    BASS_MIDI_FONTEX2 g{}; g.font = font; g.spreset = -1; g.sbank = -1; g.dpreset = -1; g.dbank = 0; g.dbanklsb = 0; g.minchan = 0; g.numchan = 16; maps.push_back(g);   // = ensureSongStreamLocked
    BASS_MIDI_StreamSetFonts(s, maps.data(), DWORD(maps.size()) | BASS_MIDI_FONT_EX2);
    raw(s, GM_ON); if (xg) raw(s, XG_ON);
    raw(s, {0xBA, 0, 0}); raw(s, {0xBA, 32, (uint8_t)lsb}); raw(s, {0xCA, (uint8_t)pc}); raw(s, {0x9A, 60, 100});
    std::vector<float> buf(480 * 2); float pk = 0;
    for (int b = 0; b < 40; ++b) { const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT); if (got <= 0) break; for (int i = 0; i < got / 4; ++i) pk = std::max(pk, std::fabs(buf[i])); }
    BASS_MIDI_FONT live{}; const bool got = BASS_MIDI_StreamGetPreset(s, 10, &live);
    BASS_StreamFree(s);
    return {got ? live.preset : -1, got ? live.bank : -1, pk > 0 ? 20 * std::log10(pk) : -180.0};
}
void lsbChecks(HSOUNDFONT font) {
    struct Sc { const char* name; bool xg, route; int lsb, pc; };
    const Sc sc[] = {{"generic map, LSB 0, PC 0 (GM file)", false, false, 0, 0}, {"generic map, LSB 112, PC 0 (no XG)", false, false, 112, 0},
                     {"generic map, LSB 112, PC 0 (XG On) = V1C LOAD TAKE", true, false, 112, 0}, {"route 0/112/33 -> preset 58 (XG On) = fix", true, true, 112, 33},
                     {"route 0/112/33 -> preset 58 (no XG)", false, true, 112, 33}};
    LsbResult r[5];
    for (int i = 0; i < 5; ++i) { r[i] = lsbScenario(font, sc[i].xg, sc[i].route, sc[i].lsb, sc[i].pc);
        std::printf("SXG lsb CH11 %-52s -> preset=%d bank=%d peak=%.1f dBFS%s\n", sc[i].name, r[i].preset, r[i].bank, r[i].peakDb, r[i].peakDb < -100 ? " SILENT" : ""); }
    check(r[0].peakDb > -60, "GM file control must sound");
    check(r[3].preset == 58 && r[3].peakDb > -60, "a per-channel route must make Yamaha 0/112/33 play the routed preset (XG On)");
}

// ---- SONG TRANSPOSE: the transposed message really sounds +N semitones, and only the NOTE OFF of the SENT pitch ends it ----
void transposeChecks(HSOUNDFONT font) {
    for (int semis : {2, -5, 7}) {
        HSTREAM s = songStream(font); raw(s, GM_ON); raw(s, {0xC0, 0}); raw(s, {0x90, (uint8_t)(60 + semis), 100});
        std::vector<float> pcm, buf(480 * 2);
        for (int b = 0; b < 200; ++b) { const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT); if (got <= 0) break; pcm.insert(pcm.end(), buf.begin(), buf.begin() + got / 4); }
        BASS_StreamFree(s);
        const auto r = pitch_analysis::analyze(pcm, 48000, kExpectHz * std::pow(2.0, semis / 12.0), 0.4, 1.9);
        std::printf("SXG transpose %+d: mean %.2f cents from the expected %+d-semitone pitch\n", semis, r.meanCents, semis);
        check(r.valid && std::fabs(r.meanCents) < 5.0, "SONG TRANSPOSE: the sent pitch must sound exactly +N semitones");
    }
    auto tail = [&](int offPitch) {
        HSTREAM s = songStream(font); raw(s, GM_ON); raw(s, {0xC0, 0});
        std::vector<Timed> ev{{0, {0x90, 63, 100}}, {20, {0x80, (uint8_t)offPitch, 0}}};
        const auto pcm = renderTimed(s, ev, 80); BASS_StreamFree(s); return rmsDb(pcm, 0.5, 0.75);
    };
    const double paired = tail(63), mismatched = tail(60);
    std::printf("SXG transpose NOTE OFF pairing: off at the sent pitch -> tail %.1f dB, off at the file pitch -> tail %.1f dB (stuck)\n", paired, mismatched);
    check(paired < -60.0, "NOTE OFF at the sent pitch ends the note");
    check(mismatched > -40.0, "control: a NOTE OFF at the untransposed pitch would leave the note stuck");
}

// ---- V1C: real BASSMIDI plays a RECORDED file directly (as a MIDI player / MIDI Voyager would), with the file's own setup SysEx ----
void recordedFileChecks(HSOUNDFONT font, const std::string& dir) {
    for (const char* name : {"v1c_rt_full_native.mid", "v1c_rt_full_gm.mid"}) {
        const std::string path = dir + "/" + name;
        if (!std::ifstream(path).good()) { std::printf("SXG recorded %s: not present (skipped)\n", name); continue; }
        HSTREAM s = BASS_MIDI_StreamCreateFile(FALSE, path.c_str(), 0, 0, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT, 48000);
        check(s != 0, std::string("BASSMIDI must open the recorded file ") + name);
        if (!s) continue;
        BASS_MIDI_FONTEX2 m{}; m.font = font; m.spreset = -1; m.sbank = -1; m.dpreset = -1; m.dbank = 0; m.dbanklsb = 0; m.minchan = 0; m.numchan = 16;
        BASS_MIDI_StreamSetFonts(s, &m, 1 | BASS_MIDI_FONT_EX2);
        std::vector<float> buf(480 * 2); float peak = 0; int blocks = 0;
        for (; blocks < 300; ++blocks) {                                       // 3 s
            const int got = BASS_ChannelGetData(s, buf.data(), buf.size() * 4 | BASS_DATA_FLOAT); if (got <= 0) break;
            for (int i = 0; i < got / 4; ++i) peak = std::max(peak, std::fabs(buf[i]));
        }
        const DWORD d8 = BASS_MIDI_StreamGetEvent(s, 8, MIDI_EVENT_DRUMS), d9 = BASS_MIDI_StreamGetEvent(s, 9, MIDI_EVENT_DRUMS);
        DWORD evCount = BASS_MIDI_StreamGetEvents(s, -1, 0, nullptr);
        const bool gm = std::string(name).find("_gm") != std::string::npos;
        std::printf("SXG recorded %s: opened, %u events, ch9 drums=%u ch10 drums=%u, peak %.1f dBFS over %.2f s\n", name, unsigned(evCount), unsigned(d8), unsigned(d9),
                    peak > 0 ? 20 * std::log10(peak) : -180.0, blocks / 100.0);
        check(peak > 0.003f, std::string(name) + " must sound");
        check(d9 != 0, std::string(name) + ": channel 10 is a drum channel");
        if (!gm) check(d8 != 0, std::string(name) + ": NATIVE Rhythm1 (channel 9) must be a drum part via the file's XG System On + bank 127");
        BASS_StreamFree(s);
    }
}

int main(int argc, char** argv) {
    if (argc < 2 || !BASS_Init(0, 48000, 0, nullptr, nullptr)) return 1;
    HSOUNDFONT font = BASS_MIDI_FontInit(makeFont(argv[1]).c_str(), 0);
    if (!font) { std::cout << "SXG font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
    BASS_MIDI_FONTINFO info{}; BASS_MIDI_FontGetInfo(font, &info);
    std::printf("SXG diag font presets=%u samsize=%u version=%08x\n", unsigned(info.presets), unsigned(info.samsize), unsigned(BASS_MIDI_GetVersion()));
    for (int bank : {0, 128}) for (int pc : {0, 58}) std::printf("SXG diag preset bank=%d pc=%d name=%s\n", bank, pc, BASS_MIDI_FontGetPreset(font, pc, bank) ? BASS_MIDI_FontGetPreset(font, pc, bank) : "(none)");
    gPlainMap = true; piano(font, "diag_plain_font_map", {}); gPlainMap = false;
    gNoReset = true; piano(font, "diag_no_gm_reset", {}); gNoReset = false;
    gV1aMap = true; const auto oldMap = piano(font, "V1A_map_dbank_minus1", {}); rhythm9(font, "V1A_map_dbank_minus1", {}); gV1aMap = false;
    const auto v1a = piano(font, "V1A_no_sysex", {});
    const auto xgOnly = piano(font, "V1A1_xg_on_only", {XG_ON});
    const auto fixed = piano(font, "V1A1_xg_on_mw_pmod_depth_0", {XG_ON, XG_P0_MW_PMOD_0});
    const DWORD d0 = rhythm9(font, "V1A_no_sysex", {});
    const DWORD d1 = rhythm9(font, "V1A1_xg_on_only", {XG_ON});
    const DWORD d2 = rhythm9(font, "V1A1_xg_on_part_mode_drum", {XG_ON, XG_P8_DRUM_S2});
    (void)xgOnly; (void)d1; (void)oldMap;
    mixerChecks(font);
    lsbChecks(font);
    transposeChecks(font);
    if (argc >= 3) recordedFileChecks(font, argv[2]);
    // The V1A.1 claims: the report's symptom is reproduced without SysEx, and the file's own XG setup removes it.
    check(v1a.valid && v1a.p2pCents > 0.0 && v1a.frames > 0, "the piano must actually sound (analysis on real audio)");
    check(v1a.valid && v1a.modCents > 10.0, "without SysEx CC1=82 should vibrate (reproduces the report)");
    check(fixed.valid && fixed.modCents < 3.0, "XG MW LFO PMOD DEPTH 0 should remove the CC1 vibrato");
    check(d0 == 0, "without SysEx channel 9 is melodic (reproduces the missing Rhythm)");
    check(d2 != 0, "XG PART MODE drum should make channel 9 a drum channel");
    BASS_MIDI_FontFree(font); BASS_Free();
    std::cout << (failures ? "SXG FAILED\n" : "SXG PASS\n");
    return failures ? 3 : 0;
}

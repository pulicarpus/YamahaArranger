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
    const P ps[] = {{"Piano", 0, 0}, {"Melodic58", 0, 58}, {"Kit", 128, 0}};
    const int NP = 3;
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

bool gPlainMap = false, gNoReset = false;   // isolation switches for the diagnostic scenarios only
HSTREAM songStream(HSOUNDFONT font) {   // as BassMidiPlayer::ensureSongStreamLocked
    HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
    if (gPlainMap) { BASS_MIDI_FONT f{font, -1, 0}; BASS_MIDI_StreamSetFonts(s, &f, 1); BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, 2); return s; }
    BASS_MIDI_FONTEX2 m{}; m.font = font; m.spreset = -1; m.sbank = -1; m.dpreset = -1; m.dbank = -1; m.dbanklsb = 0; m.minchan = 0; m.numchan = 16;
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

int main(int argc, char** argv) {
    if (argc != 2 || !BASS_Init(0, 48000, 0, nullptr, nullptr)) return 1;
    HSOUNDFONT font = BASS_MIDI_FontInit(makeFont(argv[1]).c_str(), 0);
    if (!font) { std::cout << "SXG font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
    BASS_MIDI_FONTINFO info{}; BASS_MIDI_FontGetInfo(font, &info);
    std::printf("SXG diag font presets=%u samsize=%u version=%08x\n", unsigned(info.presets), unsigned(info.samsize), unsigned(BASS_MIDI_GetVersion()));
    for (int bank : {0, 128}) for (int pc : {0, 58}) std::printf("SXG diag preset bank=%d pc=%d name=%s\n", bank, pc, BASS_MIDI_FontGetPreset(font, pc, bank) ? BASS_MIDI_FontGetPreset(font, pc, bank) : "(none)");
    gPlainMap = true; piano(font, "diag_plain_font_map", {}); gPlainMap = false;
    gNoReset = true; piano(font, "diag_no_gm_reset", {}); gNoReset = false;
    const auto v1a = piano(font, "V1A_no_sysex", {});
    const auto xgOnly = piano(font, "V1A1_xg_on_only", {XG_ON});
    const auto fixed = piano(font, "V1A1_xg_on_mw_pmod_depth_0", {XG_ON, XG_P0_MW_PMOD_0});
    const DWORD d0 = rhythm9(font, "V1A_no_sysex", {});
    const DWORD d1 = rhythm9(font, "V1A1_xg_on_only", {XG_ON});
    const DWORD d2 = rhythm9(font, "V1A1_xg_on_part_mode_drum", {XG_ON, XG_P8_DRUM_S2});
    (void)xgOnly; (void)d1;
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

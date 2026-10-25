// REAL BASSMIDI (Linux x86_64) on a SYNTHETIC kendang-like SF2 whose only samples sit on keys 60, 62 and 65 (zone key ranges, nothing else).
// Shows what the drum note map is for: the Style's own notes (e.g. 38/42/45) are SILENT on such a kit, the mapped target notes (60/62/65) are audible,
// and a target without a zone (61) stays silent. Host evidence: synthetic samples, x86; NOT a Yamaha kit and NOT the Redmi Pad.
#include <bass.h>
#include <bassmidi.h>
#include "sf2_rich_fixture.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>
using namespace rich_fixture;

static std::string makeKendang(const std::string& dir) {
    // one preset (bank 0, program 0) -> one instrument with three single-key zones -> one looped sine sample
    Bytes pd = {'p','d','t','a'}, ph(76), pb(8), pg, ins(44), ib, ig, sh(92);
    std::memcpy(ph.data(), "Kendang", 7); word(ph, 20, 0); word(ph, 22, 0); word(ph, 24, 0); std::memcpy(ph.data() + 38, "EOP", 3); word(ph, 62, 1);
    word(pb, 0, 0); word(pb, 2, 0); gen(pg, 41, 0); word(pb, 4, pg.size() / 4); word(pb, 6, 0); gen(pg, 0, 0);
    std::memcpy(ins.data(), "KendangInst", 11); word(ins, 20, 0); std::memcpy(ins.data() + 22, "EOI", 3); word(ins, 42, 3);
    for (int key : {60, 62, 65}) {
        const auto pos = ib.size(); ib.resize(pos + 4); word(ib, pos, ig.size() / 4); word(ib, pos + 2, 0);
        gen(ig, 43, key | (key << 8)); gen(ig, 54, 1); gen(ig, 53, 0);
    }
    { const auto pos = ib.size(); ib.resize(pos + 4); word(ib, pos, ig.size() / 4); word(ib, pos + 2, 0); } gen(ig, 0, 0);
    std::memcpy(sh.data(), "Loop", 4); dword(sh, 20, 0); dword(sh, 24, 44100); dword(sh, 28, 1000); dword(sh, 32, 43000); dword(sh, 36, 44100); sh[40] = 60; word(sh, 44, 1);
    std::memcpy(sh.data() + 46, "EOS", 3);
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", ins); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes info = {'I','N','F','O'}, ver(4), sd = {'s','d','t','a'}, pcm((44100 + 46) * 2), out = {'R','I','F','F',0,0,0,0,'s','f','b','k'};
    word(ver, 0, 2); word(ver, 2, 1); chunk(info, "ifil", ver); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0}); chunk(info, "INAM", Bytes{'k','e','n','d','a','n','g',0});
    for (int i = 0; i < 44100; ++i) word(pcm, i * 2, int(6000 * std::sin(i * 0.05)));
    chunk(sd, "smpl", pcm); chunk(out, "LIST", info); chunk(out, "LIST", sd); chunk(out, "LIST", pd); dword(out, 4, out.size() - 8);
    const auto path = dir + "/kendang-like.sf2";
    std::ofstream f(path, std::ios::binary); f.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}

template <class F> static float peakFor(F font, int key) {
    auto stream = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, 48000);
    BASS_MIDI_FONT f{font, 0, 0}; BASS_MIDI_StreamSetFonts(stream, &f, 1);
    BASS_MIDI_StreamEvent(stream, 0, MIDI_EVENT_PROGRAM, 0);
    BASS_MIDI_StreamEvent(stream, 0, MIDI_EVENT_NOTE, key | (100 << 8));
    std::vector<float> out(256 * 2); float peak = 0;
    for (int b = 0; b < 120; ++b) {
        const int bytes = BASS_ChannelGetData(stream, out.data(), out.size() * 4 | BASS_DATA_FLOAT);
        if (bytes <= 0) break;
        for (int i = 0; i < bytes / 4; ++i) peak = std::max(peak, std::fabs(out[i]));
    }
    BASS_StreamFree(stream); return peak;
}

int main(int argc, char** argv) {
    if (argc != 2 || !BASS_Init(0, 48000, 0, nullptr, nullptr)) return 1;
    const auto path = makeKendang(argv[1]);
    auto font = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!font) { std::cout << "DRUMMAP_REAL font_init_failed err=" << BASS_ErrorGetCode() << "\n"; return 2; }
    const std::map<int, int> noteMap = {{38, 60}, {42, 62}, {45, 65}, {36, 61}};     // example only: 61 deliberately has no zone
    int failures = 0;
    for (const auto& kv : noteMap) {
        const float original = peakFor(font, kv.first), mapped = peakFor(font, kv.second);
        const bool hasZone = kv.second == 60 || kv.second == 62 || kv.second == 65;
        std::cout << "DRUMMAP_REAL styleNote=" << kv.first << " peak=" << original << " | target=" << kv.second << " peak=" << mapped << " expectTargetAudible=" << hasZone << "\n";
        if (original > 1e-4f) { std::cout << "DRUMMAP_REAL FAIL style note unexpectedly audible\n"; ++failures; }
        if (hasZone && mapped < 0.01f) { std::cout << "DRUMMAP_REAL FAIL mapped target silent\n"; ++failures; }
        if (!hasZone && mapped > 1e-4f) { std::cout << "DRUMMAP_REAL FAIL target without zone audible\n"; ++failures; }
    }
    BASS_MIDI_FontFree(font); BASS_Free();
    std::cout << (failures ? "DRUMMAP_REAL FAILED\n" : "DRUMMAP_REAL PASS\n");
    return failures ? 3 : 0;
}

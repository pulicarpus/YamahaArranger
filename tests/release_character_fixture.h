#pragma once
// Test-only SF2 with one preset per envelope character, all at bank 0.
// Values are spec-level SF2 generators; nothing here imitates a Yamaha file.
#include "sf2_rich_fixture.h"
#include <cmath>
#include <fstream>
#include <string>
namespace release_character_fixture {
using namespace rich_fixture;
struct Character { const char* name; int attackTc, releaseTc; bool hasRelease, looped; };
// pc 0..3. releaseTc is only written when hasRelease (absent = SF2 default -12000).
constexpr Character characters[] = {
    {"Strings looped", -4800, -1200, true, true},   // 62 ms attack, 0.5 s release
    {"Pad looped", -2400, 1200, true, true},        // 0.25 s attack, 2 s release
    {"Piano finite", -12000, -2400, true, false},   // 2 s decaying one-shot, 0.25 s release
    {"No release gen", -12000, 0, false, true},     // looped, release generator absent
};
constexpr int count = 4, loopFrames = 8192, pianoFrames = 96000, rate = 48000;

inline std::string write(const std::string& dir) {
    Bytes ph((count + 1) * 38), pb((count + 1) * 4), pg, inst((count + 1) * 22), ib((count + 1) * 4), ig,
        sh((count + 1) * 46), pcm;
    auto sample = [&](int frames, bool decay) {
        const size_t start = pcm.size() / 2;
        pcm.resize(pcm.size() + (frames + 46) * 2); // 46 zero frames follow every sample
        for (int j = 0; j < frames; ++j) {
            const double envelope = decay ? std::exp(-double(j) / (0.8 * rate)) : 1.0;
            word(pcm, (start + j) * 2, int(8000 * envelope * std::sin(2 * 3.141592653589793 * 375 * j / rate)));
        }
        return uint32_t(start);
    };
    for (int i = 0; i < count; ++i) {
        const auto& c = characters[i];
        std::memcpy(ph.data() + i * 38, c.name, std::strlen(c.name)); word(ph, i * 38 + 20, i); word(ph, i * 38 + 24, i);
        word(pb, i * 4, pg.size() / 4); gen(pg, 41, i);
        std::memcpy(inst.data() + i * 22, c.name, std::strlen(c.name)); word(inst, i * 22 + 20, i);
        word(ib, i * 4, ig.size() / 4);
        gen(ig, 43, 0 | (127 << 8)); gen(ig, 34, c.attackTc); gen(ig, 37, 0);
        if (c.hasRelease) gen(ig, 38, c.releaseTc);
        gen(ig, 54, c.looped ? 1 : 0); gen(ig, 58, 60); gen(ig, 53, i);
        const int frames = c.looped ? loopFrames : pianoFrames;
        const uint32_t start = sample(frames, !c.looped);
        std::memcpy(sh.data() + i * 46, c.name, std::strlen(c.name));
        // 375 Hz = 128 frames per period: loop 128..8064 is 62 whole periods.
        dword(sh, i * 46 + 20, start); dword(sh, i * 46 + 24, start + frames);
        dword(sh, i * 46 + 28, start + (c.looped ? 128 : 0)); dword(sh, i * 46 + 32, start + (c.looped ? 8064 : 0));
        dword(sh, i * 46 + 36, rate); sh[i * 46 + 40] = 60; word(sh, i * 46 + 44, 1);
    }
    std::memcpy(ph.data() + count * 38, "EOP", 3); word(ph, count * 38 + 24, count);
    word(pb, count * 4, pg.size() / 4); gen(pg, 0, 0);
    std::memcpy(inst.data() + count * 22, "EOI", 3); word(inst, count * 22 + 20, count);
    word(ib, count * 4, ig.size() / 4); gen(ig, 0, 0);
    std::memcpy(sh.data() + count * 46, "EOS", 3);
    Bytes pd = {'p','d','t','a'};
    chunk(pd, "phdr", ph); chunk(pd, "pbag", pb); chunk(pd, "pmod", Bytes(10)); chunk(pd, "pgen", pg);
    chunk(pd, "inst", inst); chunk(pd, "ibag", ib); chunk(pd, "imod", Bytes(10)); chunk(pd, "igen", ig); chunk(pd, "shdr", sh);
    Bytes out = {'R','I','F','F',0,0,0,0,'s','f','b','k'}, info = {'I','N','F','O'}, sd = {'s','d','t','a'}, v(4);
    word(v, 0, 2); word(v, 2, 1); chunk(info, "ifil", v); chunk(info, "isng", Bytes{'E','M','U','8','0','0','0',0});
    chunk(out, "LIST", info); chunk(sd, "smpl", pcm); chunk(out, "LIST", sd); chunk(out, "LIST", pd);
    dword(out, 4, out.size() - 8);
    const auto path = dir + "/release-characters.sf2";
    std::ofstream file(path, std::ios::binary); file.write(reinterpret_cast<const char*>(out.data()), out.size());
    return path;
}
} // namespace release_character_fixture

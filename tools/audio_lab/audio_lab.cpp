// Offline audio lab: renders the SF2 presets used by YamahaArranger with the REAL BASS/BASSMIDI (Linux x86_64 SDK),
// either directly ("raw" mode, controller/config sweeps) or through the REAL BassMidiPlayer ("player" mode, the app's own code
// path). Output: float32 stereo WAV files + manifest.csv. No EQ, no gain change: levels are exactly what BASSMIDI returns.
//
//   audio_lab raw    <outdir> <colombo.sf2> <tyros.sf2>
//   audio_lab player <outdir> <colombo.sf2> <tyros.sf2>
#include "bassmidi_player.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

JavaVM* g_jvm = nullptr; jclass g_debugLogClass = nullptr; jmethodID g_debugLogAddMethod = nullptr;

static constexpr int kRate = 48000;
static constexpr int kHoldMs = 2000, kTailMs = 1000;
static std::ofstream manifest;
static std::string outDir;

struct PresetSpec { const char* name; int pc; };
static const PresetSpec kPresets[] = {
    {"GrandPiano", 0}, {"BrightPiano", 1}, {"EPiano", 4}, {"Organ", 16}, {"NylonGuitar", 24},
    {"SteelGuitar", 25}, {"Strings", 48}, {"Brass", 61}, {"Sax", 65}, {"SynthLead", 80},
};
static const int kVelocities[] = {30, 60, 90, 100, 127};

// Named controller events: resolved against the real header; -1 means "not defined in this SDK".
static int eventId(const std::string& n) {
    if (n == "modulation") {
#ifdef MIDI_EVENT_MODULATION
        return MIDI_EVENT_MODULATION;
#endif
    } else if (n == "volume") return MIDI_EVENT_VOLUME;
    else if (n == "pan") return MIDI_EVENT_PAN;
    else if (n == "expression") return MIDI_EVENT_EXPRESSION;
    else if (n == "reverb") return MIDI_EVENT_REVERB;
    else if (n == "chorus") return MIDI_EVENT_CHORUS;
    else if (n == "release") return MIDI_EVENT_RELEASE;
    else if (n == "cutoff") {
#ifdef MIDI_EVENT_CUTOFF
        return MIDI_EVENT_CUTOFF;
#endif
    } else if (n == "resonance") {
#ifdef MIDI_EVENT_RESONANCE
        return MIDI_EVENT_RESONANCE;
#endif
    } else if (n == "attack") {
#ifdef MIDI_EVENT_ATTACK
        return MIDI_EVENT_ATTACK;
#endif
    } else if (n == "pitch") {
#ifdef MIDI_EVENT_PITCH
        return MIDI_EVENT_PITCH;
#endif
    } else if (n == "chanpres") {
#ifdef MIDI_EVENT_CHANPRES
        return MIDI_EVENT_CHANPRES;
#endif
    } else if (n == "soft") {
#ifdef MIDI_EVENT_SOFT
        return MIDI_EVENT_SOFT;
#endif
    }
    return -1;
}

using Ctl = std::vector<std::pair<std::string, int>>;
struct Variant { std::string name; Ctl ctl; int src; float fontVolume; bool sendNothing; };

static std::string ctlText(const Ctl& c) {
    std::ostringstream s;
    for (size_t i = 0; i < c.size(); ++i) s << (i ? ";" : "") << c[i].first << "=" << c[i].second;
    return s.str();
}

static void writeWav(const std::string& path, const std::vector<float>& stereo) {
    std::ofstream f(path, std::ios::binary);
    auto w32 = [&](uint32_t v) { f.write(reinterpret_cast<const char*>(&v), 4); };
    auto w16 = [&](uint16_t v) { f.write(reinterpret_cast<const char*>(&v), 2); };
    const uint32_t bytes = uint32_t(stereo.size() * 4);
    f.write("RIFF", 4); w32(36 + bytes); f.write("WAVEfmt ", 8); w32(16); w16(3); w16(2); w32(kRate); w32(kRate * 8); w16(8); w16(32);
    f.write("data", 4); w32(bytes); f.write(reinterpret_cast<const char*>(stereo.data()), bytes);
}

static void record(const std::string& file, const std::string& path, const std::string& font, const std::string& preset, int bank, int pc,
                   int key, int vel, const std::string& variant, int src, float fontVol, const std::string& ctl, const std::string& bassName) {
    manifest << file << ',' << path << ',' << font << ',' << preset << ',' << bank << ',' << pc << ',' << key << ',' << vel << ',' << variant
             << ',' << src << ',' << fontVol << ",\"" << ctl << "\",\"" << bassName << "\"\n";
}

// ---------------------------------------------------------------------------------------------------------------------
// RAW path: BASSMIDI configured exactly like BassMidiPlayer::ensureEngine / applyFonts for a RIGHT (keyboard) channel.
static bool rawRender(HSOUNDFONT font, int bank, int pc, int key, int vel, const Variant& v, std::vector<float>& out, std::string& bassName) {
    const HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, kRate);
    if (!s) return false;
    struct Free { HSTREAM h; ~Free() { BASS_StreamFree(h); } } guard{s};
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_PPQN, 1920.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_SRC, float(v.src));
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOICES, 1000.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_MIDI_VOL, 1.0f);
    BASS_ChannelSetAttribute(s, BASS_ATTRIB_BUFFER, 0.0f);
    BASS_MIDI_FontSetVolume(font, v.fontVolume);
    BASS_MIDI_FONTEX2 map{};
    map.font = font; map.spreset = pc; map.sbank = bank; map.dpreset = 0; map.dbank = 0; map.dbanklsb = 0; map.minchan = 0; map.numchan = 1;
    if (!BASS_MIDI_StreamSetFonts(s, &map, 1 | BASS_MIDI_FONT_EX2)) return false;
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_DRUMS, 0); BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_BANK, 0);
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_BANK_LSB, 0); BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_PROGRAM, 0);
    if (!v.sendNothing)
        for (const auto& c : v.ctl) {
            const int id = eventId(c.first);
            if (id < 0) return false;                                  // variant not expressible with this SDK
            BASS_MIDI_StreamEvent(s, 0, DWORD(id), DWORD(c.second));
        }
    BASS_MIDI_FONT actual{};
    if (!BASS_MIDI_StreamGetPreset(s, 0, &actual) || actual.font != font || actual.bank != bank || actual.preset != pc) return false;
    const char* nm = BASS_MIDI_FontGetPreset(font, pc, bank);
    bassName = nm ? nm : "";
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, DWORD(key | (vel << 8)));
    auto decode = [&](int ms) {
        const size_t frames = size_t(kRate) * ms / 1000;
        const size_t base = out.size();
        out.resize(base + frames * 2);
        size_t at = 0;
        while (at < frames * 2) {
            const DWORD want = DWORD(std::min<size_t>(1024, frames * 2 - at) * 4);
            const DWORD got = BASS_ChannelGetData(s, out.data() + base + at, want | BASS_DATA_FLOAT);
            if (got == DWORD(-1) || !got) { std::fill(out.begin() + base + at, out.end(), 0.0f); return; }
            at += got / 4;
        }
    };
    decode(kHoldMs);
    BASS_MIDI_StreamEvent(s, 0, MIDI_EVENT_NOTE, DWORD(key));
    decode(kTailMs);
    return true;
}

static std::vector<Variant> sweepVariants() {
    std::vector<Variant> v;
    auto add = [&](const std::string& n, Ctl c, int src = 2, float fv = 1.0f) {
        Ctl base = {{"volume", 127}, {"expression", 127}, {"reverb", 0}, {"chorus", 0}};
        for (auto& x : c) {
            bool replaced = false;
            for (auto& b : base) if (b.first == x.first) { b.second = x.second; replaced = true; }
            if (!replaced) base.push_back(x);
        }
        v.push_back({n, base, src, fv, false});
    };
    for (int x : {80, 100}) add("S_expression_" + std::to_string(x), {{"expression", x}});
    add("S_volume_100", {{"volume", 100}});
    for (int x : {40, 127}) add("S_reverb_" + std::to_string(x), {{"reverb", x}});
    add("S_chorus_64", {{"chorus", 64}});
    for (int x : {0, 32, 64, 96, 127}) add("S_cutoff_" + std::to_string(x), {{"cutoff", x}});
    for (int x : {0, 64, 127}) add("S_resonance_" + std::to_string(x), {{"resonance", x}});
    for (int x : {64, 127}) add("S_modulation_" + std::to_string(x), {{"modulation", x}});
    for (int x : {0, 64, 127}) add("S_release_" + std::to_string(x), {{"release", x}});
    for (int x : {0, 127}) add("S_attack_" + std::to_string(x), {{"attack", x}});
    add("S_pitchbend_center", {{"pitch", 8192}});
    for (int x : {64, 127}) add("S_chanpres_" + std::to_string(x), {{"chanpres", x}});
    add("S_fontvol_0.9", {}, 2, 0.9f);
    for (int s : {0, 1, 3}) add("S_src_" + std::to_string(s), {}, s);
    add("S_pan_0", {{"pan", 0}});
    return v;
}

static int runRaw(const std::string& colombo, const std::string& tyros) {
    if (!BASS_Init(0, kRate, 0, nullptr, nullptr)) { std::cerr << "BASS_Init failed " << BASS_ErrorGetCode() << "\n"; return 3; }
    BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, 1000);
    BASS_SetConfig(BASS_CONFIG_MIDI_COMPACT, 0);
    BASS_SetConfig(BASS_CONFIG_UPDATEPERIOD, 10);
    std::cout << "LAB bass=" << std::hex << BASS_GetVersion() << std::dec << "\n";
    const std::pair<std::string, std::string> fonts[] = {{"colombo", colombo}, {"tyros", tyros}};
    // Defaults reported by a fresh stream: nothing is assumed to be 64 or 0.
    {
        const HSTREAM s = BASS_MIDI_StreamCreate(16, BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1, kRate);
        std::cout << "LAB defaults(fresh stream, channel 0):";
        for (const char* n : {"volume", "pan", "expression", "reverb", "chorus", "release", "modulation", "cutoff", "resonance", "attack", "pitch", "chanpres"}) {
            const int id = eventId(n);
            if (id < 0) std::cout << " " << n << "=UNDEFINED_IN_SDK";
            else std::cout << " " << n << "=" << int(BASS_MIDI_StreamGetEvent(s, 0, DWORD(id)));
        }
        std::cout << "\n";
        BASS_StreamFree(s);
    }
    const Variant neutral{"RAW_neutral", {{"volume", 127}, {"expression", 127}, {"reverb", 0}, {"chorus", 0}}, 2, 1.0f, false};
    const Variant factory{"RAW_factory", {}, 2, 1.0f, true};
    // RIGHT as YamahaArranger sends it: CC7 127, CC11 100 (RIGHT volume knob), pan 64, reverb 40, chorus 0, release 64, font volume 0.9, SRC 2.
    const Variant appCtl{"RAW_app", {{"volume", 127}, {"pan", 64}, {"expression", 100}, {"reverb", 40}, {"chorus", 0}, {"release", 64}}, 2, 0.9f, false};
    const auto sweep = sweepVariants();
    int rendered = 0;
    for (const auto& f : fonts) {
        const HSOUNDFONT font = BASS_MIDI_FontInit(f.second.c_str(), 0);
        if (!font) { std::cerr << "FontInit failed " << f.first << " err=" << BASS_ErrorGetCode() << "\n"; return 4; }
        BASS_MIDI_FONTINFO info{};
        BASS_MIDI_FontGetInfo(font, &info);
        std::cout << "LAB font=" << f.first << " name='" << info.name << "' presets=" << info.presets << "\n";
        for (const auto& p : kPresets) {
            const char* nm = BASS_MIDI_FontGetPreset(font, p.pc, 0);
            if (!nm) { std::cout << "LAB MISSING font=" << f.first << " preset=" << p.name << " bank0 pc=" << p.pc << "\n"; continue; }
            std::cout << "LAB preset font=" << f.first << " " << p.name << " -> '" << nm << "'\n";
            auto emit = [&](const Variant& v, int key, int vel) {
                std::vector<float> pcm; std::string bn;
                if (!rawRender(font, 0, p.pc, key, vel, v, pcm, bn)) { std::cout << "LAB SKIP " << f.first << "/" << p.name << "/" << v.name << "\n"; return; }
                char name[256];
                std::snprintf(name, sizeof name, "raw__%s__%s__k%d__v%d__%s.wav", f.first.c_str(), p.name, key, vel, v.name.c_str());
                writeWav(outDir + "/" + name, pcm);
                record(name, "raw", f.first, p.name, 0, p.pc, key, vel, v.name, v.src, v.fontVolume, v.sendNothing ? "none" : ctlText(v.ctl), bn);
                ++rendered;
            };
            for (int vel : kVelocities) { emit(neutral, 60, vel); emit(appCtl, 60, vel); }
            emit(factory, 60, 100);
            if (p.pc == 0 || p.pc == 48) for (int key : {36, 48, 72, 84}) { emit(neutral, key, 100); emit(appCtl, key, 100); }
            if (p.pc == 0 || p.pc == 4 || p.pc == 48)
                for (const auto& v : sweep) emit(v, 60, 100);
        }
        BASS_MIDI_FontFree(font);
    }
    std::cout << "LAB raw rendered=" << rendered << "\n";
    return rendered > 0 ? 0 : 5;
}

// ---------------------------------------------------------------------------------------------------------------------
// PLAYER path: the production BassMidiPlayer, driven like the app (Colombo primary, Tyros fallback, exact RIGHT1 preset).
struct ListedPreset { uint64_t id; std::string file, name; int bank, pc; };
static std::vector<ListedPreset> listPresets(BassMidiPlayer& p) {
    std::vector<ListedPreset> out;
    std::stringstream list(p.presetList()); std::string rec;
    while (std::getline(list, rec, '\x1e')) {
        std::vector<std::string> f; std::stringstream in(rec); std::string x;
        while (std::getline(in, x, '\x1f')) f.push_back(x);
        if (f.size() < 6) continue;
        out.push_back({std::stoull(f[1]), f[2], f[5], std::stoi(f[3]), std::stoi(f[4])});
    }
    return out;
}

static void playerRender(BassMidiPlayer& p, int key, int vel, std::vector<float>& out) {
    std::vector<float> block(256 * 2);
    auto pull = [&](int ms) {
        const int blocks = kRate * ms / 1000 / 256;
        for (int i = 0; i < blocks; ++i) {
            p.render(block.data(), 256);
            for (float& s : block) s = std::max(-1.0f, std::min(1.0f, s));   // AudioEngine::onAudioReady output clamp
            out.insert(out.end(), block.begin(), block.end());
        }
    };
    p.noteOn(0, key, vel / 127.0f);
    pull(kHoldMs);
    p.noteOff(0, key);
    pull(kTailMs);
}

static int runPlayer(const std::string& colombo, const std::string& tyros) {
    BassMidiPlayer p;
    p.setSourceTiered(true);
    if (!p.loadMelody(colombo)) { std::cerr << "loadMelody(colombo) failed\n"; return 3; }
    if (!p.loadMelodyFallback(tyros)) { std::cerr << "loadMelodyFallback(tyros) failed\n"; return 4; }
    p.setMasterGain(1.0f);                       // master volume 100 = unity in the app
    const auto presets = listPresets(p);
    std::cout << "LAB player presets listed=" << presets.size() << " version bass=" << std::hex << BASS_GetVersion() << std::dec << "\n";
    int rendered = 0;
    struct Mode { const char* name; int cc7, pan, expr, reverb, chorus; };
    const Mode modes[] = {{"PLAYER_app", 127, 64, 100, 40, 0}, {"PLAYER_neutral", 127, 64, 127, 0, 0}};
    for (const char* fontKey : {"colombo", "tyros"}) {
        for (const auto& spec : kPresets) {
            const ListedPreset* chosen = nullptr;
            for (const auto& lp : presets) {
                std::string lower = lp.file; for (auto& c : lower) c = char(std::tolower(static_cast<unsigned char>(c)));
                if (lower.find(fontKey) != std::string::npos && lp.bank == 0 && lp.pc == spec.pc) { chosen = &lp; break; }
            }
            if (!chosen) { std::cout << "LAB MISSING player font=" << fontKey << " " << spec.name << "\n"; continue; }
            if (!p.setKeyboardPreset(0, chosen->id, chosen->bank, chosen->pc)) { std::cout << "LAB SKIP player select failed " << fontKey << "/" << spec.name << "\n"; continue; }
            for (const auto& m : modes) {
                p.setChannelMixer(0, m.cc7, m.pan, m.expr, m.reverb, m.chorus);
                p.setKeyboardReleaseTime(64);
                if (spec.pc == 0 && std::string(m.name) == "PLAYER_app") {
                    std::stringstream diag(p.rightVoiceDiagnostic()); std::string line; std::getline(diag, line);
                    std::cout << "LAB player readback " << fontKey << " " << line.substr(0, 900) << "\n";
                }
                for (int vel : kVelocities) {
                    if (std::string(m.name) == "PLAYER_neutral" && vel != 100) continue;
                    std::vector<float> pcm;
                    playerRender(p, 60, vel, pcm);
                    char name[256];
                    std::snprintf(name, sizeof name, "player__%s__%s__k60__v%d__%s.wav", fontKey, spec.name, vel, m.name);
                    writeWav(outDir + "/" + name, pcm);
                    record(name, "player", fontKey, spec.name, chosen->bank, chosen->pc, 60, vel, m.name, 2, 0.9f,
                           "volume=" + std::to_string(m.cc7) + ";expression=" + std::to_string(m.expr) + ";reverb=" + std::to_string(m.reverb) + ";chorus=" + std::to_string(m.chorus), chosen->name);
                    ++rendered;
                }
                if (spec.pc == 0 || spec.pc == 48) for (int key : {36, 48, 72, 84}) {
                    if (std::string(m.name) != "PLAYER_app") continue;
                    std::vector<float> pcm; playerRender(p, key, 100, pcm);
                    char name[256];
                    std::snprintf(name, sizeof name, "player__%s__%s__k%d__v100__%s.wav", fontKey, spec.name, key, m.name);
                    writeWav(outDir + "/" + name, pcm);
                    record(name, "player", fontKey, spec.name, chosen->bank, chosen->pc, key, 100, m.name, 2, 0.9f, "app", chosen->name);
                    ++rendered;
                }
            }
        }
    }
    std::cout << "LAB player rendered=" << rendered << "\n";
    return rendered > 0 ? 0 : 5;
}

int main(int argc, char** argv) {
    if (argc != 5) { std::cerr << "usage: audio_lab raw|player <outdir> <colombo.sf2> <tyros.sf2>\n"; return 2; }
    const std::string mode = argv[1];
    outDir = argv[2];
    manifest.open(outDir + "/manifest_" + mode + ".csv");
    manifest << "file,path,font,preset,bank,pc,key,vel,variant,src,fontvol,controllers,bass_preset_name\n";
    const int rc = mode == "raw" ? runRaw(argv[3], argv[4]) : mode == "player" ? runPlayer(argv[3], argv[4]) : 2;
    manifest.flush();
    return rc;
}

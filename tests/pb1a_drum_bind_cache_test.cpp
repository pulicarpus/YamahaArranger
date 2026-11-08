// P-B1a EXPERIMENT on the host: production BassMidiPlayer + R2-3 mocks/golden fixtures (exact Colombo fingerprint).
// Proves, cache OFF (legacy) vs ON, over a FunkPopRock-like session (repeated Main C <-> Main D voice applies with drum notes,
// melodic preset changes, a kit change, an unresolved request, a lost readback, a mode round-trip):
//   * every NOTE event (stream role, channel, key, velocity) is identical and in the same order,
//   * after every step the live kit readback on ch8/ch9 and the drum notes actually accepted are identical,
//   * the final controller/program state per channel is identical (ON only omits re-sends of identical values),
//   * ON skips only identical, verified rebinds (no font-table rebuild), and still rebinds on a kit change / unresolved request /
//     lost readback / after a mode change.
// HOST evidence only.
#include "bassmidi_player.h"
#include "pb1a_drum_bind_cache.h"
#include <iostream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>
#include <jni.h>
JavaVM* g_jvm = nullptr; jclass g_debugLogClass = nullptr; jmethodID g_debugLogAddMethod = nullptr;  // same as the other native tests
static int checks = 0;
static void check(bool v, const char* m) { ++checks; if (!v) throw std::runtime_error(m); }
static const std::string col = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";

struct Run {
    std::vector<std::tuple<int, DWORD, DWORD, DWORD>> notes;   // (stream role, ch, event, value) NOTE events only
    std::vector<std::string> steps;                           // per-step readback + accepted drum notes
    std::map<std::pair<int, DWORD>, DWORD> finalEvents;
    uint64_t skipped = 0, rebound = 0, calls = 0; size_t setFontsCalls = 0;
};

static std::string readback(int ch) {
    BASS_MIDI_FONT live{};
    if (!BASS_MIDI_StreamGetPreset(1, ch, &live)) return "none";
    const auto name = mock_bass::fonts.count(live.font) ? mock_bass::fonts[live.font] : std::string("?");
    return name.substr(name.find_last_of('/') + 1) + ":" + std::to_string(live.bank) + ":" + std::to_string(live.preset);
}

static Run run(const std::string& d, bool cache) {
    pb1a::set(cache); pb1a::resetCounters();
    // Fresh mock state per run: the mocks treat stream handle 1 as the player's main stream.
    mock_bass::history.clear(); mock_bass::events.clear(); mock_bass::mappings.clear(); mock_bass::otherMappings.clear();
    mock_bass::otherEvents.clear(); mock_bass::noteOns = 0; mock_bass::nextStream = 1;
    mock_bass::mismatchPreset = false; mock_bass::forcedLivePc = -1;
    const HSTREAM before = mock_bass::nextStream;
    BassMidiPlayer p;
    check(p.loadMelody(d + "/prime_melodi.sf2") && p.loadDrum(d + "/prime_drum.sf2"), "load pair");
    check(p.loadMelodyFallback(d + "/colombo.sf2") && p.loadMelodyFallback(d + "/tyros4.sf2"), "load golden pool");
    check(p.setDrumABMode(true), "resolved mode");
    for (int ch = 10; ch < 13; ++ch) p.setChannelPreset(ch, 0, 33 + ch, "Part");
    const HSTREAM mainStream = before;  // the player's first stream
    Run r;
    auto step = [&](const char* tag) {
        const auto ons = mock_bass::noteOns;
        p.noteOn(8, 26, 0.7f); p.noteOn(9, 36, 0.9f); p.noteOn(9, 42, 0.6f); p.noteOn(11, 60, 0.5f);
        p.noteOff(8, 26); p.noteOff(9, 36); p.noteOff(9, 42); p.noteOff(11, 60);
        r.steps.push_back(std::string(tag) + " ch8=" + readback(8) + " ch9=" + readback(9) + " accepted=" + std::to_string(mock_bass::noteOns - ons));
    };
    auto apply = [&](int pc8, int pc9, const std::string& fp8, const std::string& fp9, const char* section) {
        p.bindDrumAB(8, fp8, 127, pc8, section); p.bindDrumAB(9, fp9, 127, pc9, section);
    };
    apply(27, 0, col, col, "MainC"); step("first");
    for (int i = 0; i < 6; ++i) {                                           // Main C <-> Main D (same kits): redundant rebinds
        apply(27, 0, col, col, i % 2 ? "MainC" : "MainD");
        if (i == 2) p.setChannelPreset(11, 0, 24, "Gt");                   // melodic preset change rebuilds the table on its own
        step("same-kit");
    }
    apply(8, 0, col, col, "FillDD"); step("kit-change-ch8");              // different program -> must rebind
    apply(8, 0, col, col, "MainD"); step("kit-kept");
    apply(8, 0, "", col, "Unresolved"); step("unresolved-ch8");          // unresolved request invalidates ch8 (both modes)
    apply(8, 0, col, col, "MainD"); step("re-resolved");
    mock_bass::mismatchPreset = true; apply(8, 0, col, col, "Lost"); mock_bass::mismatchPreset = false; step("lost-readback");
    apply(8, 0, col, col, "MainD"); step("after-loss");
    check(p.setDrumABMode(false) && p.setDrumABMode(true), "mode round trip");
    apply(8, 0, col, col, "MainA"); step("after-mode-change");
    for (const auto& e : mock_bass::history)
        if (std::get<2>(e) == MIDI_EVENT_NOTE) r.notes.emplace_back(std::get<0>(e) == mainStream ? 0 : 1, std::get<1>(e), std::get<2>(e), std::get<3>(e));
    r.finalEvents = mock_bass::events;
    r.skipped = pb1a::skipped.load(); r.rebound = pb1a::rebound.load(); r.calls = pb1a::calls.load();
    return r;
}

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    check(!pb1a::on(), "default OFF (legacy)");
    const Run off = run(argv[1], false);
    const Run on = run(argv[1], true);
    for (size_t i = 0; i < off.steps.size(); ++i) std::cout << "OFF " << off.steps[i] << "\nON  " << (i < on.steps.size() ? on.steps[i] : "-") << '\n';
    std::cout.flush();
    check(off.steps == on.steps, "every step: identical ch8/ch9 kit readback and accepted drum notes");
    check(off.notes == on.notes && !off.notes.empty(), "identical NOTE events (role, channel, key, velocity) in the same order");
    check(off.finalEvents == on.finalEvents, "identical final controller/program state on every channel");
    check(off.skipped == 0 && off.rebound == off.calls, "OFF never skips");
    check(on.calls == off.calls, "same number of bindDrumAB requests");
    // ON: the 6 same-kit applies (12 binds) + "kit-kept" ch8/ch9 + ch9 in later identical requests are skipped; changes rebind.
    check(on.skipped >= 12 && on.rebound >= 6, "ON skips identical verified rebinds and still rebinds changed/unresolved/lost/mode-changed ones");
    std::cout << "PB1A calls=" << on.calls << " skipped=" << on.skipped << " rebound=" << on.rebound << " (OFF rebound=" << off.rebound << ")\n";
    pb1a::set(false);
    std::cout << "PB1A DRUM BIND CACHE PASS checks=" << checks << " noteEvents=" << on.notes.size() << '\n';
    return 0;
}

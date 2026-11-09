// P-B1a VALIDATION on the host: a Style whose drum kit CHANGES between sections (ROSITA.STY, repo fixture), replayed through the
// production BassMidiPlayer with the R2-3 golden fixtures/mocks (exact Colombo fingerprint).
// Per-section drum requests (from the production parser dump of ROSITA): ch8 0/16/0/0 (none in Intro B / Fills / Ending),
// ch9 0/8/0/0. The resolver maps a Colombo request to the same raw bank/program (device evidence: 127/0/PC27 -> PC27).
// Cache OFF (legacy) vs ON, at EVERY step: ch8/ch9 live readback (SF2 file, bank, program), drum notes accepted, the whole
// published font table, NOTE events in order, ON/OFF balance (no stuck notes), final controller/program state; and ON may skip
// ONLY a request identical to the current verified binding of that channel (checked against an independent model).
// HOST evidence only (no device timing, no xrun measurement).
#include "bassmidi_player.h"
#include "pb1a_drum_bind_cache.h"
#include <jni.h>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>
JavaVM* g_jvm = nullptr; jclass g_debugLogClass = nullptr; jmethodID g_debugLogAddMethod = nullptr;
static int checks = 0;
static void check(bool v, const std::string& m) { ++checks; if (!v) throw std::runtime_error(m); }
static const std::string col = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";

struct Section { const char* name; int ch8; int ch9; };   // -1 = section has no part on that drum channel (no bind request)
// A realistic arranger tour: Intro -> Main A -> fills -> Main B -> C -> D -> back to A -> Ending.
static const std::vector<Section> tour = {
    {"IntroB", -1, 0}, {"MainA", 0, 8}, {"FillAA", -1, 0}, {"MainA", 0, 8}, {"FillBB", -1, 0}, {"MainB", 16, 0}, {"MainB", 16, 0},
    {"FillCC", -1, 0}, {"MainC", 0, 0}, {"FillDD", -1, 0}, {"MainD", 0, 0}, {"MainD", 0, 0}, {"FillAA", -1, 0}, {"MainA", 0, 8},
    {"MainB", 16, 0}, {"EndingB", -1, 0}};

struct Run {
    std::vector<std::string> steps;
    std::vector<std::tuple<int, DWORD, DWORD>> notes;
    std::map<std::pair<int, DWORD>, DWORD> finalEvents;
    uint64_t skipped = 0, rebound = 0, expectedSkips = 0;
    int unbalanced = 0;
};
static std::string readback(int ch) {
    BASS_MIDI_FONT live{};
    if (!BASS_MIDI_StreamGetPreset(1, ch, &live)) return "none";
    std::string n = mock_bass::fonts.count(live.font) ? mock_bass::fonts[live.font] : "?";
    return n.substr(n.find_last_of('/') + 1) + ":" + std::to_string(live.bank) + ":" + std::to_string(live.preset);
}
static std::string table() {
    std::string s;
    for (const auto& m : mock_bass::mappings) {
        std::string n = mock_bass::fonts.count(m.font) ? mock_bass::fonts[m.font] : "?";
        s += n.substr(n.find_last_of('/') + 1) + "/" + std::to_string(m.spreset) + "/" + std::to_string(m.sbank) + "/" + std::to_string(m.dpreset) + "/" +
             std::to_string(m.dbank) + "/" + std::to_string(m.dbanklsb) + "/" + std::to_string(m.minchan) + "/" + std::to_string(m.numchan) + ";";
    }
    return s;
}

static Run run(const std::string& d, bool cache) {
    pb1a::set(cache); pb1a::resetCounters(); pb1a::tableStale = true;
    mock_bass::history.clear(); mock_bass::events.clear(); mock_bass::mappings.clear(); mock_bass::otherMappings.clear();
    mock_bass::otherEvents.clear(); mock_bass::noteOns = 0; mock_bass::nextStream = 1; mock_bass::mismatchPreset = false; mock_bass::forcedLivePc = -1;
    BassMidiPlayer p;
    check(p.loadMelody(d + "/prime_melodi.sf2") && p.loadDrum(d + "/prime_drum.sf2"), "load pair");
    check(p.loadMelodyFallback(d + "/colombo.sf2") && p.loadMelodyFallback(d + "/tyros4.sf2"), "load golden pool");
    check(p.setDrumABMode(true), "resolved mode");
    for (int ch = 10; ch < 14; ++ch) p.setChannelPreset(ch, 0, 24 + ch, "Part");
    Run r;
    int bound[2] = {-1, -1};  // independent model: last VERIFIED binding program per drum channel
    for (size_t i = 0; i < tour.size(); ++i) {
        const auto& s = tour[i];
        const auto skipsBefore = pb1a::skipped.load();
        uint64_t expect = 0;
        for (int ch : {8, 9}) {
            const int pc = ch == 8 ? s.ch8 : s.ch9;
            if (pc < 0) continue;                                    // no part, no request: binding stays (both modes)
            if (cache && bound[ch - 8] == pc) ++expect;              // ON may only skip an identical verified binding
            const bool ok = p.bindDrumAB(ch, col, 127, pc, s.name);
            check(ok, std::string("bind verified ") + s.name);
            bound[ch - 8] = pc;
        }
        if (i == 6) p.setChannelPreset(11, 0, 30, "Gt");            // a melodic preset change rebuilds the table by itself
        const auto skipsHere = pb1a::skipped.load() - skipsBefore;
        if (cache) check(skipsHere <= expect, std::string("ON skipped a NON-identical request at ") + s.name);
        r.expectedSkips += expect;
        const auto ons = mock_bass::noteOns;
        for (int k : {36, 38, 42, 46}) { p.noteOn(9, k, 0.8f); p.noteOn(8, k + 20, 0.6f); }
        p.noteOn(11, 60, 0.5f);
        for (int k : {36, 38, 42, 46}) { p.noteOff(9, k); p.noteOff(8, k + 20); }
        p.noteOff(11, 60);
        r.steps.push_back(std::string(s.name) + " ch8=" + readback(8) + " ch9=" + readback(9) + " accepted=" + std::to_string(mock_bass::noteOns - ons) +
                          " table=" + table());
    }
    std::map<std::tuple<HSTREAM, DWORD, DWORD>, int> balance;  // (stream, channel, key): +1 ON, -1 OFF
    for (const auto& e : mock_bass::history) {
        if (std::get<2>(e) != MIDI_EVENT_NOTE) continue;
        r.notes.emplace_back(std::get<0>(e) == 1 ? 0 : 1, std::get<1>(e), std::get<3>(e));
        const DWORD key = std::get<3>(e) & 0xFF, vel = std::get<3>(e) >> 8;
        balance[{std::get<0>(e), std::get<1>(e), key}] += (vel && vel != 255) ? 1 : -1;
    }
    // Stuck = a NOTE ON without NOTE OFF. (A NOTE OFF without ON is harmless: an unbound drum channel rejects the ON, the OFF still goes.)
    for (const auto& b : balance) if (b.second > 0) ++r.unbalanced;
    r.finalEvents = mock_bass::events; r.skipped = pb1a::skipped.load(); r.rebound = pb1a::rebound.load();
    return r;
}

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const Run off = run(argv[1], false), on = run(argv[1], true);
    for (size_t i = 0; i < on.steps.size(); ++i) {
        const auto cut = [](const std::string& s) { return s.substr(0, s.find(" table=")); };
        std::cout << "OFF " << cut(off.steps[i]) << "\nON  " << cut(on.steps[i]) << (off.steps[i] == on.steps[i] ? "   (identical incl. font table)" : "   DIFFERENT") << '\n';
    }
    std::cout.flush();
    check(off.steps == on.steps, "every step: readback (SF2/bank/program), accepted drum notes and the whole font table identical");
    check(off.notes == on.notes && !off.notes.empty(), "NOTE events identical, same order");
    check(off.unbalanced == 0 && on.unbalanced == 0, "every NOTE ON has its NOTE OFF (no stuck notes) in both modes");
    check(off.finalEvents == on.finalEvents, "final controller/program state identical");
    check(off.skipped == 0, "OFF never skips");
    check(on.skipped > 0 && on.skipped <= on.expectedSkips, "ON skips only identical verified bindings");
    check(on.rebound >= 6, "every kit change (MainA ch9 8, MainB ch8 16 and back to 0) is rebound");
    std::cout << "PB1A KIT CHANGE requests=" << on.skipped + on.rebound << " skipped=" << on.skipped << " (identical candidates " << on.expectedSkips
              << ") rebound=" << on.rebound << " noteEvents=" << on.notes.size() << '\n';
    std::cout << "PB1A KIT CHANGE PASS checks=" << checks << '\n';
    pb1a::set(false);
    return 0;
}

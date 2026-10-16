#include "bassmidi_player.h"
#include "latency_probe.h"
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <vector>

static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }
static std::vector<std::string> fields(const std::string& row) {
    std::vector<std::string> out; std::stringstream in(row); std::string f;
    while (std::getline(in, f, '\t')) out.push_back(f);
    while (out.size() < 9) out.push_back("");
    return out;
}

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const std::string d = argv[1];
    const std::string col = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";

    // 1. Colombo primary + Tyros fallback, no dedicated drum font.
    BassMidiPlayer p;
    p.setSourceTiered(true);
    check(p.loadMelody(d + "/colombo.sf2"), "load Colombo primary");
    check(p.loadMelodyFallback(d + "/tyros4.sf2"), "attach Tyros fallback");
    check(!p.isDrumLoaded(), "no dedicated drum SF2 in CPV stack");

    // 2. GM requests resolve on Colombo whenever it has a compatible preset.
    int primary = 0, secondary = 0, unresolved = 0;
    for (int pc = 0; pc < 128; ++pc) {
        p.setChannelPreset(10, 0, pc);
        const auto b = fields(p.channelBinding(10));
        if (b[1] == "BOUND" && b[2] == "PRIMARY") { ++primary; check(b[3].find("colombo") != std::string::npos, "primary is Colombo"); }
        else if (b[1] == "BOUND") ++secondary;
        else ++unresolved;
    }
    std::cout << "CPV GM bank0 primary=" << primary << " secondary=" << secondary << " unresolved=" << unresolved << "\n";
    check(primary >= 120, "Colombo serves the GM bank");

    // 3. Same pool without tiers may choose Tyros; with tiers Colombo must win
    //    any request it can serve. Compare the two policies on Yamaha banks.
    BassMidiPlayer legacy;
    check(legacy.loadMelody(d + "/colombo.sf2") && legacy.loadMelodyFallback(d + "/tyros4.sf2"), "legacy pool");
    int tyrosWinsUntiered = 0, tieredMovedToColombo = 0;
    for (int pc = 0; pc < 128; ++pc) {
        legacy.setChannelPreset(11, 0, pc);
        p.setChannelPreset(11, 0, pc);
        const auto u = fields(legacy.channelBinding(11)), t = fields(p.channelBinding(11));
        if (u[1] == "BOUND" && u[2] == "SECONDARY") {
            ++tyrosWinsUntiered;
            if (t[2] == "PRIMARY") ++tieredMovedToColombo;
        }
        if (t[1] == "BOUND" && t[2] == "SECONDARY") check(u[1] == "BOUND", "tier never invents a candidate");
    }
    std::cout << "CPV untiered Tyros wins=" << tyrosWinsUntiered << " moved to Colombo by tiers=" << tieredMovedToColombo << "\n";

    // 4. A request no source can serve is UNRESOLVED and stays silent.
    p.setChannelPreset(12, 104 * 128 + 5, 77, "");
    auto b = fields(p.channelBinding(12));
    if (b[1] != "BOUND") {
        check(b[1] == "UNRESOLVED", "unserved request must be UNRESOLVED");
        const auto ons = mock_bass::noteOns;
        p.noteOn(12, 60, 1.0f);
        check(mock_bass::noteOns == ons, "UNRESOLVED channel must not sound a substitute");
    }

    // 5. Rhythm channels bind the verified Colombo XG kit (R2-3) and report it.
    check(p.setDrumABMode(true), "R2-3 resolved drum route");
    check(fields(p.channelBinding(8))[1] == "UNRESOLVED", "unbound rhythm channel is UNRESOLVED");
    check(p.bindDrumAB(8, col, 127, 0, "Main A"), "Colombo 127:0 kit");
    b = fields(p.channelBinding(8));
    check(b[1] == "BOUND" && b[2] == "DRUM_R23" && b[3].find("colombo") != std::string::npos && b[5] == "127" && b[6] == "0",
          "rhythm binding reported");
    check(!b[7].empty(), "kit name reported");
    std::cout << "CPV rhythm binding: " << p.channelBinding(8) << "\n";

    // 6. Manual pin: exact Tyros preset on a melodic channel, immune to style requests.
    uint64_t tyrosId = 0; int tyrosBank = -1, tyrosPc = -1; std::string tyrosName;
    {
        std::stringstream list(p.presetList()); std::string rec;
        while (std::getline(list, rec, '\x1e')) {
            std::vector<std::string> f; std::stringstream in(rec); std::string x;
            while (std::getline(in, x, '\x1f')) f.push_back(x);
            if (f.size() < 6) continue;
            const int bank = std::stoi(f[3]);
            if (f[2].find("tyros") != std::string::npos && bank != 127 && bank != 128) {
                tyrosId = std::stoull(f[1]); tyrosBank = bank; tyrosPc = std::stoi(f[4]); tyrosName = f[5]; break;
            }
        }
    }
    check(tyrosId != 0, "Tyros melodic preset listed");
    check(p.pinChannelPreset(13, tyrosId, tyrosBank, tyrosPc), "pin verified by readback");
    b = fields(p.channelBinding(13));
    check(b[1] == "BOUND" && b[2] == "PINNED" && b[3].find("tyros") != std::string::npos && b[7] == tyrosName, "pinned binding reported");
    p.setChannelPreset(13, 0, 48, "Strings");          // section setup from the style
    p.setChannelPreset(13, 0, 49);                     // dynamic Program Change
    b = fields(p.channelBinding(13));
    check(b[2] == "PINNED" && b[6] == std::to_string(tyrosPc), "style requests cannot replace a pin");
    BASS_MIDI_FONT live{};
    check(BASS_MIDI_StreamGetPreset(1, 13, &live) && live.preset == tyrosPc && mock_bass::fonts[live.font].find("tyros") != std::string::npos,
          "native stream plays the pinned preset");
    check(!p.pinChannelPreset(8, tyrosId, tyrosBank, tyrosPc) && !p.pinChannelPreset(2, tyrosId, tyrosBank, tyrosPc),
          "pins are for melodic style channels only");
    check(!p.pinChannelPreset(14, tyrosId, tyrosBank, 200), "invalid preset rejected");
    check(p.pinChannelPreset(13, 0, 0, 0), "unpin");
    p.setChannelPreset(13, 0, 48, "Strings");
    b = fields(p.channelBinding(13));
    check(b[2] == "PRIMARY", "after unpin the style request resolves on Colombo again");
    check(p.fontPdta(tyrosId).size() == 64, "font fingerprint available for drum pins");

    // 7. Latency probe: records queueing and render pairing, and never changes MIDI behaviour.
    {
        auto& probe = latency_probe::probe;
        p.setChannelPreset(10, 0, 33);                      // style bass on Colombo
        probe.reset();
        float pcm[512];
        const auto historyBefore = mock_bass::history.size();
        p.noteOn(0, 60, 0.8f);                              // keyboard RIGHT1
        p.noteOn(10, 40, 0.8f);                             // style channel
        check(probe.keyLockWait.count.load() == 1 && probe.keyDispatch.count.load() == 1, "keyboard note timed once");
        check(probe.styleLockWait.count.load() == 1 && probe.styleDispatch.count.load() == 1, "style note timed separately");
        check(probe.noteToRender.count.load() == 0, "no render yet");
        p.noteOn(1, 64, 0.8f); p.noteOn(2, 67, 0.8f);       // two more layers before the next render
        p.render(pcm, 256);
        check(probe.renderDecode.count.load() == 1 && probe.renderLockWait.count.load() == 1, "render timed");
        // Every queued keyboard note is paired, including layers that share one render window.
        check(probe.keyNotesQueued.load() == 3 && probe.noteToRender.count.load() == 3 && probe.noteToBuffer.count.load() == 3, "all keyboard notes paired");
        check(probe.renderWindowsWithKeyNotes.load() == 1 && probe.maxKeyNotesInOneWindow.load() == 3 && probe.pairingOverflow.load() == 0, "shared window accounting");
        check(probe.noteToBuffer.sumUs.load() >= probe.noteToRender.sumUs.load(), "buffer-ready is not before render start");
        p.render(pcm, 256);
        check(probe.noteToRender.count.load() == 3, "a note is paired with exactly one render");
        check(probe.pendingKeyCount == 0, "pending stamps consumed");
        // Probe on vs off: identical MIDI event stream.
        const auto onHistory = std::vector<decltype(mock_bass::history)::value_type>(mock_bass::history.begin() + historyBefore, mock_bass::history.end());
        probe.enabled = 0;
        const auto offStart = mock_bass::history.size();
        p.noteOn(0, 60, 0.8f); p.noteOn(10, 40, 0.8f); p.render(pcm, 256);
        probe.enabled = 1;
        probe.reset();
        const std::vector<decltype(mock_bass::history)::value_type> offHistory(mock_bass::history.begin() + offStart, mock_bass::history.end());
        std::vector<decltype(mock_bass::history)::value_type> onNotes, offNotes;
        for (const auto& e : onHistory) if (std::get<2>(e) == MIDI_EVENT_NOTE) onNotes.push_back(e);
        for (const auto& e : offHistory) if (std::get<2>(e) == MIDI_EVENT_NOTE) offNotes.push_back(e);
        check(onNotes.size() == 4 && offNotes.size() == 2, "note events counted per run");
        check(onNotes[0] == offNotes[0] && onNotes[1] == offNotes[1], "probe does not change NOTE events");
        check(probe.keyLockWait.count.load() == 0, "disabled probe records nothing");
        const auto text = probe.report();
        check(text.find("stat=keyboardNoteQueuedToBufferReady") != std::string::npos, "report names the queue-to-buffer stat");
        // Whole-callback bookkeeping.
        probe.reset(); probe.budgetNs = 5333333;
        probe.callbackDone(0, 1000000); probe.callbackDone(0, 5000000); probe.callbackDone(0, 6000000);
        check(probe.callbacks.load() == 3 && probe.overBudget.load() == 1 && probe.overEightyPercent.load() == 2, "budget counters");
        check(probe.callbackTotal.percentileUpperUs(0.5) == 8000 && probe.callbackTotal.maxUs.load() == 6000, "percentile and max");
        std::cout << "CPV LATENCY PROBE OK\n";
    }

    // 7b. Latency tuning: identical FONTEX2 tables and unchanged drum kits are not re-submitted.
    {
        auto& probe = latency_probe::probe;
        check(probe.tuning.load() == 0, "native tuning defaults to the previous behaviour");
        probe.tuning = latency_probe::kNativeMask;
        p.setChannelPreset(12, 0, 25);                       // first use: table changes
        probe.reset();
        const int before = mock_bass::setFontsCalls;
        p.setChannelPreset(12, 0, 25);                       // same request again (cache-miss path in the sequencer)
        check(mock_bass::setFontsCalls == before && probe.applyFontsSkipped.load() >= 1, "identical table is not re-submitted");
        auto b = fields(p.channelBinding(12));
        check(b[1] == "BOUND" && b[6] == "25", "channel keeps its binding after a skipped apply");
        p.setChannelPreset(12, 0, 26);                       // a real change must still be submitted
        check(mock_bass::setFontsCalls == before + 1, "a changed table is submitted");
        // Contract kept with tuning off: a table submission that fails marks only this channel fail-closed
        // (tests/accompaniment_presence_test.cpp). With the skip on, an identical table is never submitted, so
        // there is nothing to fail and the channel keeps the route the established table already serves.
        mock_bass::failMapping = true; p.setChannelPreset(12, 0, 26); mock_bass::failMapping = false;
        check(fields(p.channelBinding(12))[1] == "BOUND", "skip on: identical table, established route is kept");
        probe.tuning = 0;                                    // A: previous behaviour
        mock_bass::failMapping = true; p.setChannelPreset(12, 0, 26); mock_bass::failMapping = false;
        check(fields(p.channelBinding(12))[1] != "BOUND", "skip off: failed submission is fail-closed as before");
        p.setChannelPreset(12, 0, 26);                       // retry recovers
        check(fields(p.channelBinding(12))[1] == "BOUND", "channel recovers after a successful retry");
        const int offBefore = mock_bass::setFontsCalls;
        p.setChannelPreset(12, 0, 26);
        check(mock_bass::setFontsCalls == offBefore + 1, "tuning off submits every time");
        probe.tuning = latency_probe::kNativeMask;
        // A font load makes the table dirty: never skipped even if the handles look identical.
        BassMidiPlayer q;
        check(q.loadMelody(d + "/colombo.sf2"), "second player");
        q.setChannelPreset(10, 0, 33);
        auto otherEntries = [] { size_t n = 0; for (const auto& kv : mock_bass::otherMappings) n += kv.second.size(); return n; };
        const size_t tableBefore = otherEntries();
        check(q.loadMelodyFallback(d + "/tyros4.sf2"), "attach fallback");
        check(otherEntries() > tableBefore, "font load always submits a table");

        // Drum rebind: same kit is only re-verified; a different kit or failed readback is a full bind.
        check(p.setDrumABMode(true) || true, "resolved mode");
        probe.reset();
        check(p.bindDrumAB(9, col, 127, 8, "Main A"), "first bind");
        const auto historyAfterFirst = mock_bass::history.size();
        const int fontsAfterFirst = mock_bass::setFontsCalls;
        check(probe.drumBindFull.load() == 1 && probe.drumBindSkipped.load() == 0, "first bind is full");
        check(p.bindDrumAB(9, col, 127, 8, "Main B"), "same kit again");
        check(probe.drumBindSkipped.load() == 1 && probe.drumBindFull.load() == 1, "same kit is skipped");
        check(mock_bass::history.size() == historyAfterFirst && mock_bass::setFontsCalls == fontsAfterFirst, "skip sends no MIDI and no table");
        const auto skipped = fields(p.channelBinding(9));
        check(skipped[1] == "BOUND" && skipped[2] == "DRUM_R23" && skipped[6] == "8", "binding intact after skip");
        check(p.drumABReport().find("section='Main B'") != std::string::npos, "section label follows the request");
        check(p.bindDrumAB(9, col, 127, 16, "Main C"), "different kit");
        check(probe.drumBindFull.load() == 2 && mock_bass::setFontsCalls > fontsAfterFirst, "different kit is a full bind");
        mock_bass::mismatchPreset = true;                    // independent readback disagrees
        check(!p.bindDrumAB(9, col, 127, 16, "Main D"), "failed readback is never skipped");
        check(probe.drumBindSkipped.load() == 1, "mismatch did not take the skip path");
        mock_bass::mismatchPreset = false;
        probe.tuning = 0;
        check(p.bindDrumAB(9, col, 127, 16, "Main E"), "rebind after mismatch");
        const int fontsOff = mock_bass::setFontsCalls;
        check(p.bindDrumAB(9, col, 127, 16, "Main F"), "same kit with tuning off");
        check(mock_bass::setFontsCalls > fontsOff && probe.drumBindSkipped.load() == 1, "tuning off rebuilds every time");
        probe.tuning = latency_probe::kNativeMask;
        std::cout << "CPV LATENCY TUNING OK\n";
    }

    // 8. The getter is read-only.
    const auto history = mock_bass::history;
    for (int ch = 0; ch < 16; ++ch) p.channelBinding(ch);
    check(mock_bass::history == history, "channelBinding must not send MIDI");
    std::cout << "CPV NATIVE PASS\n";
}

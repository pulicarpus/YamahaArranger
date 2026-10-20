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
        p.render(pcm, 256);
        check(probe.renderDecode.count.load() == 1 && probe.renderLockWait.count.load() == 1, "render timed");
        check(probe.noteToRender.count.load() == 1 && probe.noteToBuffer.count.load() == 1, "note paired with next render");
        check(probe.noteToBuffer.sumUs.load() >= probe.noteToRender.sumUs.load(), "buffer-ready is not before render start");
        p.render(pcm, 256);
        check(probe.noteToRender.count.load() == 1, "a note is paired with exactly one render");
        check(probe.pendingKeyNoteNs.load() == 0, "pending stamp consumed");
        // Probe on vs off: identical MIDI event stream.
        const auto onHistory = std::vector<decltype(mock_bass::history)::value_type>(mock_bass::history.begin() + historyBefore, mock_bass::history.end());
        probe.enabled = 0;
        const auto offStart = mock_bass::history.size();
        p.noteOn(0, 60, 0.8f); p.noteOn(10, 40, 0.8f); p.render(pcm, 256);
        probe.enabled = 1;
        const std::vector<decltype(mock_bass::history)::value_type> offHistory(mock_bass::history.begin() + offStart, mock_bass::history.end());
        std::vector<decltype(mock_bass::history)::value_type> onNotes, offNotes;
        for (const auto& e : onHistory) if (std::get<2>(e) == MIDI_EVENT_NOTE) onNotes.push_back(e);
        for (const auto& e : offHistory) if (std::get<2>(e) == MIDI_EVENT_NOTE) offNotes.push_back(e);
        check(onNotes == offNotes && onNotes.size() == 2, "probe does not change NOTE events");
        check(probe.keyLockWait.count.load() == 1, "disabled probe records nothing");
        const auto text = probe.report();
        check(text.find("stat=keyboardNoteQueuedToBufferReady") != std::string::npos, "report names the queue-to-buffer stat");
        // Whole-callback bookkeeping.
        probe.reset(); probe.budgetNs = 5333333;
        probe.callbackDone(0, 1000000); probe.callbackDone(0, 5000000); probe.callbackDone(0, 6000000);
        check(probe.callbacks.load() == 3 && probe.overBudget.load() == 1 && probe.overEightyPercent.load() == 2, "budget counters");
        check(probe.callbackTotal.percentileUpperUs(0.5) == 8000 && probe.callbackTotal.maxUs.load() == 6000, "percentile and max");
        std::cout << "CPV LATENCY PROBE OK\n";
    }

    // 9. SX Prime Melody (real pdta) attached as a manual-only SF2: selectable by hand, invisible to the automatic resolver.
    {
        const std::string prime = d + "/prime_melodi.sf2";
        check(p.loadMelodyFallback(prime, true), "attach SX Prime Melody as manual-only");
        // Enumerate exactly what BASSMIDI exposes for it (record: role, fontId, file, bank, program, name).
        uint64_t primeId = 0; int primeCount = 0;
        std::vector<std::vector<std::string>> rows;
        {
            std::stringstream in(p.presetList()); std::string rec;
            while (std::getline(in, rec, '\x1e')) {
                std::vector<std::string> f; std::stringstream r(rec); std::string x;
                while (std::getline(r, x, '\x1f')) f.push_back(x);
                if (f.size() == 6 && f[2].find("prime_melodi") != std::string::npos) { primeId = std::stoull(f[1]); ++primeCount; rows.push_back(f); }
            }
        }
        std::cout << "CPV PRIME_MELODY presets exposed=" << primeCount << "\n";
        for (const auto& f : rows) {
            std::string ascii = f[5]; for (auto& ch : ascii) if (static_cast<unsigned char>(ch) > 126 || static_cast<unsigned char>(ch) < 32) ch = '?';
            std::cout << "PRIME_PRESET bank=" << f[3] << " program0=" << f[4] << " program1=" << (std::stoi(f[4]) + 1) << " name='" << ascii << "'\n";
        }
        check(primeCount == 93, "all 93 SX Prime Melody presets are exposed");
        auto find = [&](int bank, int program, const std::string& name) {
            for (const auto& f : rows) if (std::stoi(f[3]) == bank && std::stoi(f[4]) == program && f[5] == name) return true;
            return false;
        };
        check(find(0, 32, "elctrick bass"), "Prime 0:32 'elctrick bass' present with the pdta bank/program");
        check(find(8, 17, "BASS"), "Prime 8:17 'BASS' present");
        check(find(0, 52, "Suling S770") && find(0, 72, "Suling S770") && find(0, 73, "Suling S770"), "Prime Suling presets present");
        // Automatic resolution never lands on the manual-only font.
        int primeAuto = 0;
        for (int pc = 0; pc < 128; ++pc) for (int bank : {0, 8 * 128 + 16, 104 * 128 + 5}) {
            p.setChannelPreset(14, bank, pc);
            const auto bb = fields(p.channelBinding(14));
            if (bb[3].find("prime_melodi") != std::string::npos) ++primeAuto;
        }
        check(primeAuto == 0, "automatic resolver never chooses a manual-only SF2");
        // Manual pin of the Style Bass channel to Prime 0:32 keeps SF2 identity, bank and program.
        check(p.pinChannelPreset(10, primeId, 0, 32), "pin Style Bass to Prime 0:32");
        auto pb = fields(p.channelBinding(10));
        check(pb[1] == "BOUND" && pb[2] == "PINNED" && pb[3].find("prime_melodi") != std::string::npos && pb[5] == "0" && pb[6] == "32",
              "binding reports Prime, bank 0, program 32");
        BASS_MIDI_FONT liveP{};
        check(BASS_MIDI_StreamGetPreset(1, 10, &liveP) && liveP.preset == 32 && mock_bass::fonts[liveP.font].find("prime_melodi") != std::string::npos,
              "native stream plays the Prime preset, not a Colombo/Piano fallback");
        // RIGHT1 can select Prime Suling through the keyboard path, independent of the Style pin.
        check(p.setKeyboardPreset(0, primeId, 0, 52), "RIGHT1 selects Prime 'Suling S770' by font id + bank + program");
        auto kb = fields(p.channelBinding(0));
        check(kb[2] == "KEYBOARD" && kb[3].find("prime_melodi") != std::string::npos && kb[6] == "52", "RIGHT1 binding reports Prime Suling 0:52");
        check(fields(p.channelBinding(10))[3].find("prime_melodi") != std::string::npos, "RIGHT selection did not touch the Style pin");
        check(p.pinChannelPreset(10, 0, 0, 0), "unpin Style Bass");
        std::cout << "CPV PRIME MANUAL OK\n";
    }

    // 8. The getter is read-only.
    const auto history = mock_bass::history;
    for (int ch = 0; ch < 16; ++ch) p.channelBinding(ch);
    check(mock_bass::history == history, "channelBinding must not send MIDI");
    std::cout << "CPV NATIVE PASS\n";
}

#include "bassmidi_player.h"
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

    // 7. The getter is read-only.
    const auto history = mock_bass::history;
    for (int ch = 0; ch < 16; ++ch) p.channelBinding(ch);
    check(mock_bass::history == history, "channelBinding must not send MIDI");
    std::cout << "CPV NATIVE PASS\n";
}

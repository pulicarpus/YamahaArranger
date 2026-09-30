#include "voice_resolver.h"
#include <cstdlib>
#include <iostream>

using namespace voice_resolver;
static int checks = 0;
static void check(bool ok, const char* message) {
    ++checks;
    if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static Candidate preset(int source, int bank, int pc, const std::string& name, bool yamaha = false) {
    return {source, yamaha ? (source == 0 ? "Yamaha SX700 SX900 Melody.sf2" : "Tyros 4.sf2")
                          : "ColomboGMGS2_BM.sf2", bank, pc, name, yamaha, !yamaha};
}
static void loveSong(const Request& request, const std::vector<Candidate>& pool, Family family) {
    const int index = select(request, pool);
    check(index >= 0, "Love Song voice must resolve when compatible preset exists");
    const auto& c = pool[index];
    const auto d = evaluate(decodeRequest(request, pool), c);
    check(d.accepted && d.family == family, "Love Song must preserve family");
    std::cout << "Love Song " << request.name << " family=" << familyName(request.family())
              << " FINAL " << c.sf2 << " / " << c.name << " score=" << d.score
              << " reason=" << d.reason << '\n';
}
int main() {
    // Identities recorded in docs/VOICE_RESOLVER_V2_CHECKPOINT_20260930.md.
    // Synthetic phdr inventory exercises policy, not actual sample/audio fidelity.
    const std::vector<Candidate> pool = {
        preset(0, 8, 17, "BASS", true), preset(0, 0, 0, "Yamaha ConcertGrand", true),
        preset(0, 8, 1, "Steel Guitar", true), preset(0, 0, 49, "String Yamaha", true),
        preset(1, 8, 1, "Wide Piano 2"), preset(1, 104 * 128 + 21, 0, "Wide Piano 2"),
        preset(1, 0, 25, "Steel Guitar"), preset(1, 0, 33, "Finger Bass"),
        preset(1, 0, 48, "Strings"), preset(1, 0, 49, "Slow Strings"),
        preset(2, 8, 1, "Acoustic Guitar", true)
    };
    loveSong({1028, 17, "Bass"}, pool, Family::Bass);
    loveSong({104 * 128 + 21, 0, "Piano"}, pool, Family::Piano);
    loveSong({8 * 128 + 16, 1, "A.Guitar"}, pool, Family::Guitar);
    loveSong({1029, 49, "Strings1"}, pool, Family::Strings);
    loveSong({1029, 49, "Strings2"}, pool, Family::Strings);
    for (int bank : {8, 8 * 128 + 16, 104 * 128 + 16}) {
        const Request guitar{bank, 1, "A.Guitar"};
        const auto bad = preset(1, bank, 1, "Wide Piano 2");
        check(evaluate(guitar, bad).reason == "wrong_family", "Exact foreign PC/bank cannot accept Piano for Guitar");
        check(select(guitar, {bad}) == -1, "No compatible candidates means no Piano fallback");
        auto wrongYamaha = bad; wrongYamaha.yamaha = true;
        check(!evaluate(guitar, wrongYamaha).accepted, "Even exact Yamaha identity must pass family gate");
    }
    const std::vector<std::string> names = {"Piano", "Vibraphone", "Organ", "Accordion", "Guitar",
        "Bass", "Strings", "Choir", "Brass", "Sax", "Flute", "Synth Lead", "Pad"};
    for (const auto& requested : names) for (const auto& supplied : names) {
        const Request r{1029, 49, requested};
        const auto c = preset(1, r.bank, r.program, supplied);
        check(evaluate(r, c).accepted == (r.family() == c.family()), "All exact numeric collisions must preserve family");
    }
    check(namedFamily("Bass Clarinet") == Family::Woodwind && namedFamily("Bass Trombone") == Family::Brass,
          "Bass register does not change Woodwind/Brass instrument family");
    check(namedFamily("Bassoon") == Family::Woodwind, "Bassoon is not Bass");
    check(namedFamily("Contrabass") == Family::Strings, "Contrabass is bowed Strings");
    check(gmFamily(8) == Family::Chromatic && gmFamily(16) == Family::Organ &&
          gmFamily(24) == Family::Guitar && gmFamily(32) == Family::Bass, "Correct zero-based GM ranges");
    check(!evaluate({0, 65, "Sax"}, preset(1, 0, 65, "Clarinet")).accepted, "Sax cannot become Clarinet");
    check(evaluate({0, 73, "Flute"}, preset(1, 0, 71, "Clarinet")).accepted, "Woodwind family remains compatible");
    for (int bank : {127, 128}) {
        check(!evaluate({0, 25, "Guitar"}, preset(1, bank, 25, "Guitar")).accepted, "Drum banks never enter melodic resolution");
    }
    auto drum = preset(1, 0, 25, "Guitar"); drum.melodic = false;
    check(!evaluate({0, 25, "Guitar"}, drum).accepted, "Drum role never competes with melodic pool");
    check(!evaluate({126 * 128, 25, "Guitar"}, preset(1, 0, 25, "Guitar")).accepted, "Yamaha drum request stays outside melody");
    check(select({104 * 128, 25, ""}, pool) == -1, "Unnamed MSB104 is not inferred from PC");
    check(select({1028, 17, ""}, pool) >= 0 && decodeRequest({1028, 17, ""}, pool).family() == Family::Bass,
          "Unnamed known Yamaha identity can decode Bass despite GM Organ PC");
    check(decodeRequest({1028, 17, ""}, {preset(1, 8, 17, "Organ")}).family() == Family::Unknown,
          "Foreign numeric identity cannot decode unnamed Yamaha request");
    check(decodeRequest({1028, 17, ""}, {preset(0, 8, 17, "Bass", true), preset(2, 8, 17, "Organ", true)}).family() == Family::Unknown,
          "Conflicting Yamaha identities must fail closed");
    check(select({0, 33, ""}, {preset(1, 0, 33, "unlabelled")}) == 0, "Standard bank0 may use documented GM family");
    check(!evaluate({0, 33, "Bass"}, preset(0, 0, 33, "unlabelled", true)).accepted,
          "Unknown Yamaha candidate family must reject");
    check(!evaluate({104 * 128, 33, "Bass"}, preset(1, 8, 33, "unlabelled")).accepted,
          "Foreign nonzero bank plus same PC alone is not GM family evidence");
    const Request variation{104 * 128 + 16, 1, "Acoustic Guitar"};
    std::vector<Candidate> varied = {preset(0, variation.bank, 1, "Electric Guitar", true),
        preset(1, 0, 25, "Steel Guitar"), preset(2, 0, 25, "Acoustic Guitar", true)};
    check(select(variation, varied) == 2, "MSB104 semantic/variation match beats numeric primary identity");
    std::reverse(varied.begin(), varied.end());
    check(varied[select(variation, varied)].source == 2, "Pool enumeration order must not determine winning source");
    check(select({1028, 17, "Bass"}, {preset(1, 0, 33, "Bass"), preset(0, 8, 17, "Finger Bass", true)}) == 1,
          "Compatible Yamaha identity has high priority");
    check(select({104 * 128, 25, "Steel Guitar"}, {preset(0, 0, 25, "Electric Guitar", true), preset(1, 0, 25, "Steel Guitar")}) == 1,
          "Colombo semantic winner can beat primary load order");
    auto unnamed = preset(1, 0, 25, "");
    check(evaluate({104 * 128, 25, "Steel Guitar"}, unnamed).reason.find("semantic_partial") == std::string::npos,
          "Empty candidate name must not receive semantic score");
    std::cout << "PASS: " << checks << " resolver checks\n";
}

// Host test: static SF2 release metadata interpretation. No BASS, no PCM claim.
#include "release_envelope_evidence.h"
#include "release_character_fixture.h"
#include "voice_release_fixture.h"
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
static int checks = 0;
static void check(bool ok, const std::string& message) {
    ++checks; if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static std::vector<unsigned char> bytes(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}
int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const auto characters = sf2_zones::parse(bytes(release_character_fixture::write(argv[1])), true);
    check(characters.valid, "character fixture parses");
    const char* expected[] = {"SF2_RELEASE_AUDIBLE_LOOPED", "SF2_RELEASE_AUDIBLE_LOOPED",
                              "FINITE_SAMPLES_TAIL_BOUNDED_BY_SAMPLE_END", "SF2_RELEASE_NEAR_INSTANT"};
    const int releaseTc[] = {-1200, 1200, -2400, -12000};
    for (int pc = 0; pc < release_character_fixture::count; ++pc) {
        const auto s = release_envelope::summarize(characters, 0, pc, 60, 100, 0.5);
        std::cout << release_envelope::describe(s, 0, pc, 60, 100);
        check(s.metadataKnown && s.zones == 1, "one eligible zone per character");
        check(s.minReleaseTc == releaseTc[pc] && s.maxReleaseTc == releaseTc[pc], "effective release timecents (absent = SF2 default)");
        check(s.verdict == expected[pc], std::string("verdict for ") + release_character_fixture::characters[pc].name);
    }
    const auto piano = release_envelope::summarize(characters, 0, 2, 60, 100, 0.5);
    check(piano.loopedZones == 0 && std::abs(piano.maxFiniteSeconds - 2.0) < 0.001, "finite sample duration from shdr");

    const auto oneShot = sf2_zones::parse(bytes(voice_release_fixture::write(argv[1], "one-shot.sf2", 375, 0, true)), true);
    const auto ended = release_envelope::summarize(oneShot, 0, 24, 60, 100, 0.5);
    check(ended.verdict == "FINITE_SAMPLES_END_BEFORE_KEY_UP", "170 ms one-shot ends before a 500 ms key hold");
    check(release_envelope::summarize(oneShot, 0, 24, 60, 100, 0.1).verdict != "FINITE_SAMPLES_END_BEFORE_KEY_UP",
          "the same sample outlasts a shorter hold");
    check(release_envelope::summarize(characters, 0, 99, 60, 100, 0.5).verdict == "SF2_METADATA_UNAVAILABLE", "absent preset");
    check(release_envelope::summarize(sf2_zones::Inventory{}, 0, 0, 60, 100, 0.5).verdict == "SF2_METADATA_UNAVAILABLE", "invalid inventory");

    // Diagnosis never blames the preset when PCM responds, and never blames BASS without metadata.
    const auto strings = release_envelope::summarize(characters, 0, 0, 60, 100, 0.5);
    const auto none = release_envelope::summarize(characters, 0, 3, 60, 100, 0.5);
    check(release_envelope::diagnose(none, "RESPONDS") == "RELEASE_RESPONDS_AT_DECODER", "PCM response wins");
    check(release_envelope::diagnose(none, "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW") ==
          "PRESET_LIMITATION_SF2_RELEASE_NEAR_INSTANT", "near-instant SF2 release is a preset limitation");
    check(release_envelope::diagnose(none, "TAIL_CHANGES_NONMONOTONIC", 49) == "PRESET_LIMITATION_SF2_RELEASE_NEAR_INSTANT" &&
          release_envelope::diagnose(none, "TAIL_CHANGES_NONMONOTONIC", 50) == "RELEASE_RESPONDS_AT_DECODER",
          "an audible 0->127 tail span is never blamed on the preset");
    check(release_envelope::diagnose(ended, "NO_HELD_PCM_sample_key_or_attack_limitation") ==
          "PRESET_LIMITATION_FINITE_SAMPLES_END_BEFORE_KEY_UP", "one-shot ended before key-up");
    check(release_envelope::diagnose(strings, "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW") ==
          "UNEXPLAINED_NO_RESPONSE_REPORT_AS_DEFECT", "audible looped release without response is a defect");
    check(release_envelope::diagnose(release_envelope::Summary{}, "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW") ==
          "INCONCLUSIVE_SF2_METADATA_UNAVAILABLE", "no metadata stays inconclusive");
    check(release_envelope::diagnose(none, "") == "INCONCLUSIVE_PCM_PROBE_FAILED", "decoder failure never blames the preset");
    check(release_envelope::diagnose(strings, "FAIL", 200) == "INCONCLUSIVE_PCM_PROBE_FAILED", "failed PCM cannot establish response");
    auto modulated = none; modulated.modulatorsToRelease = 1;
    check(release_envelope::diagnose(modulated, "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW") ==
          "INCONCLUSIVE_RELEASE_MODULATION", "custom release modulation prevents a static preset limitation claim");
    modulated = none; modulated.releaseModulatorsKnown = false;
    check(release_envelope::diagnose(modulated, "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW") ==
          "INCONCLUSIVE_RELEASE_MODULATION", "unknown modulators stay inconclusive");
    const auto pitched = release_envelope::summarize(oneShot, 0, 24, 48, 100, 0.2);
    check(!pitched.finiteDurationAtProbeKeyKnown && pitched.verdict != "FINITE_SAMPLES_END_BEFORE_KEY_UP",
          "raw sample duration cannot prove exhaustion for a transposed note");
    std::cout << "RELEASE_ENVELOPE_EVIDENCE checks=" << checks << " PASS metadata_only_no_PCM_claim\n";
}

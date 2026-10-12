#pragma once
// Read-only interpretation of the SF2 volume-envelope release metadata that can
// sound for one exact preset/key/velocity. Never selects fonts, sends MIDI or
// changes PCM; BASS does not expose the instantiated voice, so every matching
// layer is reported as eligible evidence.
#include "sf2_zone_diagnostic.h"
#include <cmath>
#include <sstream>
#include <string>

namespace release_envelope {
// SF2 2.04 generator 38 (releaseVolEnv) is absolute timecents for the full
// release ramp. -6000 tc = 31 ms: shorter ramps are not perceived as a tail.
constexpr int nearInstantTc = -6000;
constexpr int generatorRelease = 38, generatorSampleModes = 54;

inline double seconds(int timecents) { return std::pow(2.0, timecents / 1200.0); }

struct Summary {
    bool metadataKnown = false;
    bool releaseModulatorsKnown = false, finiteDurationAtProbeKeyKnown = false;
    size_t zones = 0, loopedZones = 0, modulatorsToRelease = 0;
    int minReleaseTc = 0, maxReleaseTc = 0;
    double maxFiniteSeconds = 0; // longest non-looped eligible sample
    std::string verdict = "SF2_METADATA_UNAVAILABLE";
};

inline int releaseTimecents(const sf2_zones::Zone& z) {
    return std::clamp(sf2_zones::effective(z, generatorRelease), -12000, 8000);
}

inline bool looped(const sf2_zones::Zone& z) {
    const int mode = sf2_zones::effective(z, generatorSampleModes) & 3;
    return (mode == 1 || mode == 3) && z.loopEnd > z.loopStart;
}

inline Summary summarize(const sf2_zones::Inventory& inventory, int bank, int pc,
                         int key, int velocity, double holdSeconds) {
    Summary s;
    if (!inventory.valid) return s;
    const auto it = inventory.presets.find({bank, pc});
    if (it == inventory.presets.end()) return s;
    s.metadataKnown = true;
    s.releaseModulatorsKnown = true;
    s.finiteDurationAtProbeKeyKnown = true;
    for (const auto& z : it->second) {
        if (key < z.keyLow || key > z.keyHigh || velocity < z.velLow || velocity > z.velHigh) continue;
        const int tc = releaseTimecents(z);
        s.minReleaseTc = s.zones ? std::min(s.minReleaseTc, tc) : tc;
        s.maxReleaseTc = s.zones ? std::max(s.maxReleaseTc, tc) : tc;
        ++s.zones;
        s.releaseModulatorsKnown = s.releaseModulatorsKnown &&
            z.presetGenerators.modsKnown && z.instrumentGenerators.modsKnown;
        // Raw shdr duration is not playback duration when the note is pitched,
        // sample addresses are adjusted, or custom pitch modulators are present.
        const int root = sf2_zones::effective(z, 58) >= 0 ? sf2_zones::effective(z, 58) : z.rootKey;
        const int fixedKey = sf2_zones::effective(z, 46);
        const int soundingKey = fixedKey >= 0 ? fixedKey : key;
        bool durationKnown = (soundingKey == root || sf2_zones::effective(z, 56) == 0) &&
            z.pitchCorrection == 0 && sf2_zones::effective(z, 51) == 0 && sf2_zones::effective(z, 52) == 0 &&
            z.sampleRate > 0 && z.end > z.start;
        for (int op : {0, 1, 4, 12}) durationKnown = durationKnown && sf2_zones::effective(z, op) == 0;
        for (const auto* g : {&z.presetGenerators, &z.instrumentGenerators}) {
            durationKnown = durationKnown && g->modsKnown;
            for (const auto& m : g->mods)
                if (m.amount && (m.destination == 51 || m.destination == 52 || m.destination == 56)) durationKnown = false;
        }
        if (!looped(z)) s.finiteDurationAtProbeKeyKnown = s.finiteDurationAtProbeKeyKnown && durationKnown;
        if (looped(z)) ++s.loopedZones;
        else if (z.sampleRate && z.end > z.start)
            s.maxFiniteSeconds = std::max(s.maxFiniteSeconds, double(z.end - z.start) / z.sampleRate);
        for (const auto* g : {&z.presetGenerators, &z.instrumentGenerators})
            for (const auto& m : g->mods) if (m.destination == generatorRelease) ++s.modulatorsToRelease;
    }
    if (!s.zones) s.verdict = "NO_ZONE_FOR_KEY_VELOCITY";
    else if (!s.loopedZones && s.finiteDurationAtProbeKeyKnown && s.maxFiniteSeconds <= holdSeconds) s.verdict = "FINITE_SAMPLES_END_BEFORE_KEY_UP";
    else if (s.maxReleaseTc <= nearInstantTc) s.verdict = "SF2_RELEASE_NEAR_INSTANT";
    else if (!s.loopedZones) s.verdict = "FINITE_SAMPLES_TAIL_BOUNDED_BY_SAMPLE_END";
    else s.verdict = "SF2_RELEASE_AUDIBLE_LOOPED";
    return s;
}

inline std::string describe(const Summary& s, int bank, int pc, int key, int velocity) {
    std::ostringstream o;
    o << "RELEASE SF2 ENVELOPE bank=" << bank << " pc=" << pc << " key=" << key << " velocity=" << velocity
      << " metadataKnown=" << s.metadataKnown << " eligibleZones=" << s.zones << " loopedZones=" << s.loopedZones;
    if (s.zones)
        o << " releaseTc=" << s.minReleaseTc << ".." << s.maxReleaseTc
          << " releaseRampMs=" << std::lround(seconds(s.minReleaseTc) * 1000) << ".."
          << std::lround(seconds(s.maxReleaseTc) * 1000)
          << " longestFiniteSampleMs=" << std::lround(s.maxFiniteSeconds * 1000);
    o << " releaseModulators=" << s.modulatorsToRelease << " releaseModulatorsKnown=" << s.releaseModulatorsKnown
      << " finiteDurationAtProbeKeyKnown=" << s.finiteDurationAtProbeKeyKnown << " verdict=" << s.verdict
      << " evidence=static_SF2_generators_not_BASS_voice\n";
    return o.str();
}

// A 0 -> 127 difference of at least 50 ms at -40 dB is an audible response
// even when the RMS-window classifier calls the tails non-monotonic.
constexpr int audibleSpanMs = 50;

// Combine static metadata with the measured isolated-decoder PCM.
// spanMs: tail(-40 dB) at RELEASE 127 minus tail at RELEASE 0.
inline std::string diagnose(const Summary& s, const std::string& pcmClass, int spanMs = 0) {
    if (pcmClass != "RESPONDS" && pcmClass != "TAIL_CHANGES_NONMONOTONIC" &&
        pcmClass != "NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW" && pcmClass.rfind("NO_HELD_PCM", 0) != 0)
        return "INCONCLUSIVE_PCM_PROBE_FAILED";
    if (pcmClass == "RESPONDS" || spanMs >= audibleSpanMs) return "RELEASE_RESPONDS_AT_DECODER";
    if (s.metadataKnown && (!s.releaseModulatorsKnown || s.modulatorsToRelease))
        return "INCONCLUSIVE_RELEASE_MODULATION";
    if (s.verdict == "SF2_RELEASE_NEAR_INSTANT" || s.verdict == "FINITE_SAMPLES_END_BEFORE_KEY_UP")
        return "PRESET_LIMITATION_" + s.verdict;
    if (pcmClass.rfind("NO_HELD_PCM", 0) == 0) return "NO_HELD_PCM_AT_PROBE_KEY";
    if (s.verdict == "SF2_RELEASE_AUDIBLE_LOOPED") return "UNEXPLAINED_NO_RESPONSE_REPORT_AS_DEFECT";
    return "INCONCLUSIVE_" + s.verdict;
}
} // namespace release_envelope

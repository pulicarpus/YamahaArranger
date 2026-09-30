#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

// Pure policy shared by the Android resolver and host regression tests.
// No BASS calls: role/family rejection MUST precede every numeric match.
namespace voice_resolver {
enum class Family {
    Unknown, Piano, Chromatic, Organ, Accordion, Guitar, Bass, Strings,
    Choir, Brass, Sax, Woodwind, Synth, Pad, Effects, Ethnic, Percussion
};

inline const char* familyName(Family f) {
    static constexpr const char* names[] = {
        "UNKNOWN", "PIANO", "CHROMATIC", "ORGAN", "ACCORDION", "GUITAR",
        "BASS", "STRINGS", "CHOIR", "BRASS_HORN", "SAX", "WOODWIND",
        "SYNTH", "PAD", "EFFECTS", "ETHNIC", "PERCUSSION"
    };
    return names[static_cast<int>(f)];
}

inline std::string normalized(const std::string& s) {
    std::string out;
    for (unsigned char c : s) {
        if (std::isalnum(c)) out.push_back(static_cast<char>(std::tolower(c)));
    }
    // CASM names often end in a PC or layer number (strg48, Strings1/2).
    while (!out.empty() && std::isdigit(static_cast<unsigned char>(out.back()))) out.pop_back();
    return out;
}

inline bool contains(const std::string& s, const char* word) {
    return s.find(word) != std::string::npos;
}

inline Family namedFamily(const std::string& name) {
    const auto n = normalized(name);
    // Explicit name wins over GM numbers, including names that conflict with PC.
    if (contains(n, "drum") || contains(n, "drkit") || contains(n, "percussion") || contains(n, "standardkit")) return Family::Percussion;
    if (contains(n, "bassoon")) return Family::Woodwind;
    if (contains(n, "sax")) return Family::Sax;
    if (contains(n, "flute") || contains(n, "piccolo") || contains(n, "oboe") || contains(n, "clarinet") || contains(n, "bassoon") || contains(n, "recorder") || contains(n, "whistle") || contains(n, "harmonica")) return Family::Woodwind;
    if (contains(n, "brass") || contains(n, "horn") || contains(n, "trumpet") || contains(n, "trpt") || contains(n, "trombone") || contains(n, "tuba")) return Family::Brass;
    if (contains(n, "contrabass")) return Family::Strings;
    if (contains(n, "bass") || contains(n, "fretless") || contains(n, "slapbas")) return Family::Bass;
    if (contains(n, "guitar") || contains(n, "gtr")) return Family::Guitar;
    if (contains(n, "accordion") || contains(n, "accord") || contains(n, "bandoneon")) return Family::Accordion;
    if (contains(n, "organ") || contains(n, "drawbar") || contains(n, "hammond")) return Family::Organ;
    if (contains(n, "piano") || contains(n, "grand") || n == "ep" || contains(n, "rhodes") || contains(n, "wurly") || contains(n, "clav") || contains(n, "harpsichord")) return Family::Piano;
    if (contains(n, "choir") || contains(n, "choral") || contains(n, "vocal") || contains(n, "voice") || contains(n, "aah") || contains(n, "ooh")) return Family::Choir;
    if (contains(n, "string") || contains(n, "strg") || n == "str" || contains(n, "violin") || contains(n, "viola") || contains(n, "cello") || contains(n, "ensemble") || contains(n, "orchestra") || n == "orch" || contains(n, "sforz")) return Family::Strings;
    if (contains(n, "pad")) return Family::Pad;
    if (contains(n, "synth") || contains(n, "lead")) return Family::Synth;
    if (contains(n, "celesta") || contains(n, "glock") || contains(n, "vibra") || contains(n, "marimba") || contains(n, "xylophone") || contains(n, "tubularbell")) return Family::Chromatic;
    return Family::Unknown;
}

inline Family gmFamily(int pc) {
    if (pc < 0 || pc > 127) return Family::Unknown;
    if (pc <= 7) return Family::Piano;
    if (pc <= 15) return Family::Chromatic;
    if (pc <= 20) return Family::Organ;
    if (pc <= 22) return Family::Accordion;
    if (pc == 23) return Family::Woodwind;
    if (pc <= 31) return Family::Guitar;
    if (pc <= 39) return Family::Bass;
    if (pc <= 51 || pc == 55) return Family::Strings;
    if (pc <= 54) return Family::Choir;
    if (pc <= 63) return Family::Brass;
    if (pc <= 67) return Family::Sax;
    if (pc <= 79) return Family::Woodwind;
    if (pc <= 87) return Family::Synth;
    if (pc <= 95) return Family::Pad;
    if (pc <= 103) return Family::Effects;
    if (pc <= 111) return Family::Ethnic;
    if (pc <= 119) return Family::Percussion;
    return Family::Effects;
}

inline bool isYamahaSource(const std::string& path) {
    const auto n = normalized(path.substr(path.find_last_of("/\\") + 1));
    return contains(n, "yamaha") || contains(n, "tyros") || contains(n, "psrsx");
}

inline bool isGmSource(const std::string& path) {
    const auto n = normalized(path.substr(path.find_last_of("/\\") + 1));
    return contains(n, "colombo") || contains(n, "gmgs") || contains(n, "merlin") || contains(n, "timbres");
}

struct Request {
    int bank;
    int program;
    std::string name;
    Family family() const {
        const auto f = namedFamily(name);
        // Yamaha variation PCs are not GM identity. Missing names in those
        // banks stay unknown instead of manufacturing a potentially wrong family.
        return f != Family::Unknown ? f : (bank == 0 ? gmFamily(program) : Family::Unknown);
    }
};

struct Candidate {
    int source;
    std::string sf2;
    int bank;
    int program;
    std::string name;
    bool yamaha;
    bool gm;
    bool melodic = true;
    Family family() const {
        const auto f = namedFamily(name);
        return f != Family::Unknown ? f : (gm && bank == 0 ? gmFamily(program) : Family::Unknown);
    }
};

struct Decision {
    Family family = Family::Unknown;
    bool accepted = false;
    int score = -1;
    std::string reason;
};

// A missing name may be decoded from a consistent Yamaha identity only.
// Foreign SF2 numeric collisions and MSB104 never supply request identity.
inline Request decodeRequest(const Request& input, const std::vector<Candidate>& pool) {
    if (input.family() != Family::Unknown || input.bank / 128 == 104) return input;
    Family found = Family::Unknown;
    std::string name;
    const bool exact = std::any_of(pool.begin(), pool.end(), [&](const Candidate& c) {
        return c.yamaha && c.melodic && c.program == input.program &&
            c.bank == input.bank && c.bank != 127 && c.bank != 128 &&
            c.family() != Family::Unknown && c.family() != Family::Percussion;
    });
    for (const auto& c : pool) {
        if (!c.yamaha || !c.melodic || c.program != input.program ||
            c.bank == 127 || c.bank == 128) continue;
        const bool isExact = c.bank == input.bank;
        if (!isExact && c.bank != input.bank / 128) continue;
        const auto f = c.family();
        if (f == Family::Unknown || f == Family::Percussion) continue;
        if (exact && !isExact) continue;
        if (found != Family::Unknown && f != found) return input;
        found = f; name = c.name;
    }
    return found == Family::Unknown ? input : Request{input.bank, input.program, name};
}

inline bool acousticGuitar(const std::string& n) {
    return contains(n, "aguitar") || contains(n, "acguitar") || contains(n, "acoustic") ||
        contains(n, "steel") || contains(n, "nylon") || contains(n, "spanish");
}

inline Decision evaluate(const Request& request, const Candidate& candidate) {
    Decision d;
    d.family = candidate.family();
    const auto wanted = request.family();
    const int msb = request.bank / 128;
    if (!candidate.melodic || candidate.bank == 127 || candidate.bank == 128 ||
        d.family == Family::Percussion || msb == 126 || msb == 127) {
        d.reason = "wrong_role";
        return d;
    }
    if (wanted == Family::Unknown) { d.reason = "unknown_request_family"; return d; }
    if (d.family == Family::Unknown) { d.reason = "unknown_candidate_family"; return d; }
    if (wanted != d.family) { d.reason = "wrong_family"; return d; }

    d.accepted = true;
    d.score = 1000;
    d.reason = "same_family";
    const auto rn = normalized(request.name);
    const auto cn = normalized(candidate.name);
    // MSB104 requires semantic/variation evidence; never treat another SF2's
    // numeric 104/PC as an exact Yamaha identity.
    if (candidate.yamaha && msb != 104 && candidate.program == request.program) {
        if (candidate.bank == request.bank) {
            d.score += 100000; d.reason += ";yamaha_exact";
        } else if (candidate.bank == msb) {
            d.score += 90000; d.reason += ";yamaha_msb";
        }
    }
    if (!rn.empty() && rn == cn) {
        d.score += 8000; d.reason += ";semantic_exact";
    } else if (!rn.empty() && !cn.empty() && (contains(cn, rn.c_str()) || contains(rn, cn.c_str()))) {
        d.score += 1200; d.reason += ";semantic_partial";
    }
    if (wanted == Family::Guitar && acousticGuitar(rn) && acousticGuitar(cn)) {
        d.score += 700; d.reason += ";acoustic_variation";
    }
    if (candidate.program == request.program) {
        d.score += 40; d.reason += ";same_pc_support_only";
    }
    // Source order is only a small tie-break after compatible semantics.
    if (candidate.yamaha) { d.score += 20; d.reason += ";yamaha_source"; }
    if (candidate.source == 0) d.score += 5;
    return d;
}

inline int select(const Request& request, const std::vector<Candidate>& candidates) {
    const auto decoded = decodeRequest(request, candidates);
    int best = -1;
    Decision bestDecision;
    for (size_t i = 0; i < candidates.size(); ++i) {
        const auto d = evaluate(decoded, candidates[i]);
        if (!d.accepted) continue;
        const auto& c = candidates[i];
        // Stable ties independent of candidate pool enumeration order.
        const bool tie = best >= 0 && d.score == bestDecision.score &&
            (c.sf2 < candidates[best].sf2 || (c.sf2 == candidates[best].sf2 &&
             (c.bank < candidates[best].bank || (c.bank == candidates[best].bank &&
              (c.program < candidates[best].program || (c.program == candidates[best].program && c.name < candidates[best].name))))));
        if (best < 0 || d.score > bestDecision.score || tie) {
            best = static_cast<int>(i); bestDecision = d;
        }
    }
    return best;
}
} // namespace voice_resolver

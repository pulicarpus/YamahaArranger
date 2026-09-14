#pragma once
#include <map>
#include <string>
#include <vector>
#include "smf_reader.h"

// A Yamaha .sty file is an SMF container where each *track* corresponds to
// a style part (Rhythm1/2, Bass, Chord1/2, Pad, Phrase1/2) and each
// *section* (Intro A-C, Main A-D, Fill A-D(+BreakDown variants), Break,
// Ending A-C) is stored either as a separate track range delimited by
// marker meta-events, or — in the more common layout — the whole style is
// one SMF where 0xFF 0x01 (Text) / 0xFF 0x06 (Marker) meta events name the
// section boundaries. CASM (Custom Style Section) data with the NTT/NTR
// tables lives in SYEX/proprietary chunks outside the plain SMF spec.
//
// PHASE 1 SCOPE: this class extracts section boundaries + per-track note
// data using SmfReader, enough to play back a style's Main section as a
// fixed backing loop. Full CASM/NTT/NTR parsing (real chord-aware note
// transposition) is Phase 2 work — see the TODOs below.

enum class StyleSection {
    IntroA, IntroB, IntroC,
    MainA, MainB, MainC, MainD,
    FillAA, FillBB, FillCC, FillDD, // "fill into same variation"
    BreakDown,
    EndingA, EndingB, EndingC,
    Unknown
};

struct StylePart {
    // One instrument line within a section, e.g. "Bass" or "Rhythm 1".
    std::string name;
    std::vector<MidiEvent> events; // tick-relative to the section start
};

struct StyleSectionData {
    StyleSection section = StyleSection::Unknown;
    uint32_t lengthTicks = 0; // one bar-loop length for this section
    std::vector<StylePart> parts;
};

class StyleParser {
public:
    // rawStyBytes: the full .sty file contents (it IS a valid SMF you can
    // hand straight to SmfReader — the extra Yamaha chunks are additional
    // sysex/meta events interleaved with normal note data, not a different
    // container format).
    bool parse(const uint8_t* rawStyBytes, size_t size);

    int ppq() const { return smf_.ppq(); }
    const std::map<StyleSection, StyleSectionData>& sections() const { return sections_; }

private:
    SmfReader smf_;
    std::map<StyleSection, StyleSectionData> sections_;

    // TODO(Phase 2b): parse CASM sub-chunks (source chord table, NTT/NTR)
    // out of the SYEX events for byte-exact Yamaha note transposition.
    // Phase 2 ships a simplified interval-preserving transposer instead
    // (see arranger/NoteTransposer.kt) which covers the common cases
    // (major/minor/7th) without needing the proprietary table format.
    static StyleSection classifyMarkerText(const std::string& text);
};

// Exposed for the JNI layer (native_lib.cpp) so section identity can cross
// the boundary as a plain string instead of leaking the enum's int values
// (which aren't stable ABI once more sections are added).
std::string styleSectionToString(StyleSection s);
StyleSection styleSectionFromString(const std::string& s);

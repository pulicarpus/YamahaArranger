#pragma once
#include <map>
#include <string>
#include <vector>
#include "smf_reader.h"

enum class StyleSection {
    IntroA, IntroB, IntroC,
    MainA, MainB, MainC, MainD,
    FillAA, FillBB, FillCC, FillDD,
    BreakDown,
    EndingA, EndingB, EndingC,
    Unknown
};

struct StylePart {
    uint8_t midiChannel = 0;
    std::string name;
    std::vector<MidiEvent> events;
};

struct StyleSectionData {
    StyleSection section = StyleSection::Unknown;
    uint32_t lengthTicks = 0;
    std::vector<StylePart> parts;
};

class StyleParser {
public:
    bool parse(const uint8_t* rawStyBytes, size_t size);

    int ppq() const { return smf_.ppq(); }
    double defaultTempoBpm() const { return smf_.defaultTempoBpm(); }
    const std::map<StyleSection, StyleSectionData>& sections() const { return sections_; }

private:
    SmfReader smf_;
    std::map<StyleSection, StyleSectionData> sections_;

    static StyleSection classifyMarkerText(const std::string& text);
};

std::string styleSectionToString(StyleSection s);
StyleSection styleSectionFromString(const std::string& s);

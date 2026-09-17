#pragma once
#include <cstdint>
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

struct CasmPolicy {
    bool valid = false;
    uint8_t sourceChannel = 0;
    uint8_t destinationChannel = 0;
    std::string voiceName;
    uint8_t sourceChordRoot = 0;
    uint8_t sourceChordType = 0;
    uint8_t ntr = 3;
    uint8_t ntt = 0;
    uint8_t highKey = 127;
    uint8_t noteLimitLow = 0;
    uint8_t noteLimitHigh = 127;
    uint8_t rtr = 0;
    bool bassOn = false;
};

struct StylePart {
    uint8_t midiChannel = 0;
    std::string name;
    std::vector<MidiEvent> events;
    CasmPolicy casm;

    int program = -1;
    int bankMsb = 0;
    int bankLsb = 0;
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
    const std::map<StyleSection, StyleSectionData>& sections() const { return sections_; }

private:
    SmfReader smf_;
    std::map<StyleSection, StyleSectionData> sections_;

    static StyleSection classifyMarkerText(const std::string& text);
    void parseCasm(const uint8_t* data, size_t size);
    static std::string trimAscii(const std::string& text);
};

std::string styleSectionToString(StyleSection s);
StyleSection styleSectionFromString(const std::string& s);

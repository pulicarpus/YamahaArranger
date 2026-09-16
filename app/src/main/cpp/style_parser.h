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
    // FIX: sebelumnya struct ini cuma { name, events } dan digrupkan per
    // TRACK — satu part bisa berisi campuran beberapa channel MIDI
    // sekaligus (contoh nyata dari debug log: "parts=2" padahal event-nya
    // dari ch1/ch3/ch6/ch8). Sekarang setiap StylePart = SATU channel,
    // sesuai bagaimana Yamaha sendiri mengidentifikasi instrumen part
    // (lewat channel, bukan nama track).
    uint8_t midiChannel = 0; // 0-15
    std::string name;        // label tampilan, boleh dari nama track asli
    std::vector<MidiEvent> events; // tick-relative to the section start
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
};

std::string styleSectionToString(StyleSection s);
StyleSection styleSectionFromString(const std::string& s);

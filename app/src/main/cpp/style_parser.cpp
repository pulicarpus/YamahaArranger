#include "style_parser.h"
#include <algorithm>
#include <cctype>

namespace {
std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}
}

StyleSection StyleParser::classifyMarkerText(const std::string& text) {
    std::string t = toLower(text);
    // Yamaha style files commonly use marker names like "Intro 1",
    // "Main A", "Fill In AA", "Ending 1" inside 0xFF 0x06 Marker events.
    if (t.find("intro") != std::string::npos) {
        if (t.find('1') != std::string::npos || t.find('a') != std::string::npos) return StyleSection::IntroA;
        if (t.find('2') != std::string::npos || t.find('b') != std::string::npos) return StyleSection::IntroB;
        return StyleSection::IntroC;
    }
    if (t.find("main") != std::string::npos) {
        if (t.find('a') != std::string::npos) return StyleSection::MainA;
        if (t.find('b') != std::string::npos) return StyleSection::MainB;
        if (t.find('c') != std::string::npos) return StyleSection::MainC;
        if (t.find('d') != std::string::npos) return StyleSection::MainD;
    }
    if (t.find("fill") != std::string::npos) {
        if (t.find("aa") != std::string::npos) return StyleSection::FillAA;
        if (t.find("bb") != std::string::npos) return StyleSection::FillBB;
        if (t.find("cc") != std::string::npos) return StyleSection::FillCC;
        if (t.find("dd") != std::string::npos) return StyleSection::FillDD;
    }
    if (t.find("break") != std::string::npos) return StyleSection::BreakDown;
    if (t.find("ending") != std::string::npos) {
        if (t.find('1') != std::string::npos || t.find('a') != std::string::npos) return StyleSection::EndingA;
        if (t.find('2') != std::string::npos || t.find('b') != std::string::npos) return StyleSection::EndingB;
        return StyleSection::EndingC;
    }
    return StyleSection::Unknown;
}

bool StyleParser::parse(const uint8_t* rawStyBytes, size_t size) {
    if (!smf_.parse(rawStyBytes, size)) return false;
    sections_.clear();

    // Approach: scan every track for Marker(0x06)/Text(0x01) meta events
    // naming a section; between two consecutive markers (or end-of-track)
    // is that section's note data, per track = per style part.
    for (const auto& track : smf_.tracks()) {
        // Find marker boundaries within this track.
        struct Boundary { uint32_t tick; StyleSection section; };
        std::vector<Boundary> boundaries;
        for (const auto& ev : track.events) {
            if (ev.status == 0xFF && (ev.metaType == 0x06 || ev.metaType == 0x01)) {
                std::string text(ev.metaOrSysexData.begin(), ev.metaOrSysexData.end());
                StyleSection sec = classifyMarkerText(text);
                if (sec != StyleSection::Unknown) boundaries.push_back({ev.tick, sec});
            }
        }
        if (boundaries.empty()) continue; // e.g. a pure tempo/meta track

        for (size_t i = 0; i < boundaries.size(); ++i) {
            uint32_t startTick = boundaries[i].tick;
            uint32_t endTick = (i + 1 < boundaries.size()) ? boundaries[i + 1].tick
                                                             : track.events.back().tick + 1;
            auto& secData = sections_[boundaries[i].section];
            secData.section = boundaries[i].section;
            secData.lengthTicks = std::max(secData.lengthTicks, endTick - startTick);

            StylePart part;
            part.name = track.name.empty() ? "Part" : track.name;
            for (const auto& ev : track.events) {
                if (ev.tick >= startTick && ev.tick < endTick &&
                    ev.status >= 0x80 && ev.status < 0xF0) { // channel voice events only
                    MidiEvent relative = ev;
                    relative.tick -= startTick;
                    part.events.push_back(relative);
                }
            }
            secData.parts.push_back(std::move(part));
        }
    }

    // TODO(Phase 2b): if no markers were found at all (some .sty variants
    // encode section layout only in the proprietary CASM chunk rather
    // than plain SMF markers), fall back to parsing CASM directly.
    return !sections_.empty();
}

std::string styleSectionToString(StyleSection s) {
    switch (s) {
        case StyleSection::IntroA: return "IntroA";
        case StyleSection::IntroB: return "IntroB";
        case StyleSection::IntroC: return "IntroC";
        case StyleSection::MainA: return "MainA";
        case StyleSection::MainB: return "MainB";
        case StyleSection::MainC: return "MainC";
        case StyleSection::MainD: return "MainD";
        case StyleSection::FillAA: return "FillAA";
        case StyleSection::FillBB: return "FillBB";
        case StyleSection::FillCC: return "FillCC";
        case StyleSection::FillDD: return "FillDD";
        case StyleSection::BreakDown: return "BreakDown";
        case StyleSection::EndingA: return "EndingA";
        case StyleSection::EndingB: return "EndingB";
        case StyleSection::EndingC: return "EndingC";
        default: return "Unknown";
    }
}

StyleSection styleSectionFromString(const std::string& s) {
    static const std::map<std::string, StyleSection> kMap = {
        {"IntroA", StyleSection::IntroA}, {"IntroB", StyleSection::IntroB}, {"IntroC", StyleSection::IntroC},
        {"MainA", StyleSection::MainA}, {"MainB", StyleSection::MainB},
        {"MainC", StyleSection::MainC}, {"MainD", StyleSection::MainD},
        {"FillAA", StyleSection::FillAA}, {"FillBB", StyleSection::FillBB},
        {"FillCC", StyleSection::FillCC}, {"FillDD", StyleSection::FillDD},
        {"BreakDown", StyleSection::BreakDown},
        {"EndingA", StyleSection::EndingA}, {"EndingB", StyleSection::EndingB}, {"EndingC", StyleSection::EndingC},
    };
    auto it = kMap.find(s);
    return it != kMap.end() ? it->second : StyleSection::Unknown;
}

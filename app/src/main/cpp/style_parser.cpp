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

    if (t.find("intro") != std::string::npos) {
        if (t.find('1') != std::string::npos || t.find('a') != std::string::npos) return StyleSection::IntroA;
        if (t.find('2') != std::string::npos || t.find('b') != std::string::npos) return StyleSection::IntroB;
        if (t.find('3') != std::string::npos || t.find('c') != std::string::npos) return StyleSection::IntroC;
        return StyleSection::IntroA;
    }

    if (t.find("main") != std::string::npos) {
        size_t pos = t.find("main") + 4;
        while (pos < t.size() && (t[pos] == ' ' || t[pos] == '_' || t[pos] == '-')) pos++;
        if (pos < t.size()) {
            char c = t[pos];
            if (c == 'a') return StyleSection::MainA;
            if (c == 'b') return StyleSection::MainB;
            if (c == 'c') return StyleSection::MainC;
            if (c == 'd') return StyleSection::MainD;
        }
        return StyleSection::MainA;
    }

    if (t.find("fill") != std::string::npos) {
        size_t pos = t.find("fill") + 4;
        while (pos < t.size() && (t[pos] == ' ' || t[pos] == '_' || t[pos] == '-')) pos++;
        if (pos < t.size()) {
            char c = t[pos];
            if (c == 'a') return StyleSection::FillAA;
            if (c == 'b') return StyleSection::FillBB;
            if (c == 'c') return StyleSection::FillCC;
            if (c == 'd') return StyleSection::FillDD;
        }
        if (t.find("aa") != std::string::npos) return StyleSection::FillAA;
        if (t.find("bb") != std::string::npos) return StyleSection::FillBB;
        if (t.find("cc") != std::string::npos) return StyleSection::FillCC;
        if (t.find("dd") != std::string::npos) return StyleSection::FillDD;
    }

    if (t.find("break") != std::string::npos) return StyleSection::BreakDown;

    if (t.find("ending") != std::string::npos) {
        if (t.find('1') != std::string::npos || t.find('a') != std::string::npos) return StyleSection::EndingA;
        if (t.find('2') != std::string::npos || t.find('b') != std::string::npos) return StyleSection::EndingB;
        if (t.find('3') != std::string::npos || t.find('c') != std::string::npos) return StyleSection::EndingC;
        return StyleSection::EndingA;
    }

    return StyleSection::Unknown;
}

bool StyleParser::parse(const uint8_t* rawStyBytes, size_t size) {
    if (!smf_.parse(rawStyBytes, size)) return false;
    sections_.clear();

    for (const auto& track : smf_.tracks()) {
        struct Boundary { uint32_t tick; StyleSection section; };
        std::vector<Boundary> boundaries;
        for (const auto& ev : track.events) {
            if (ev.status == 0xFF && (ev.metaType == 0x06 || ev.metaType == 0x01)) {
                std::string text(ev.metaOrSysexData.begin(), ev.metaOrSysexData.end());
                StyleSection sec = classifyMarkerText(text);
                if (sec != StyleSection::Unknown) boundaries.push_back({ev.tick, sec});
            }
        }
        if (boundaries.empty()) continue;

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
                    ev.status >= 0x80 && ev.status < 0xF0) {
                    MidiEvent relative = ev;
                    relative.tick -= startTick;
                    part.events.push_back(relative);
                }
            }
            secData.parts.push_back(std::move(part));
        }
    }

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
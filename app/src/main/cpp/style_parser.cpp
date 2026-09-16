#include "style_parser.h"
#include <algorithm>
#include <cctype>

namespace {
std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

bool isAsciiNameChar(uint8_t c) {
    return std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '+' || c == '@';
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

std::string StyleParser::trimAscii(const std::string& text) {
    size_t a = 0;
    while (a < text.size() && std::isspace(static_cast<unsigned char>(text[a]))) ++a;
    size_t b = text.size();
    while (b > a && std::isspace(static_cast<unsigned char>(text[b - 1]))) --b;
    return text.substr(a, b - a);
}

void StyleParser::parseCasm(const uint8_t* data, size_t size) {
    // Keep this parser deliberately conservative. The current project already
    // has a working Ctb2 voice-name probe; this promotes that information into
    // the native StylePart instead of pretending arbitrary bytes are NTR/NTT.
    // Unknown CASM fields remain at their documented safe defaults until the
    // exact Ctab layout is decoded against real Yamaha styles.
    size_t casmStart = 0;
    uint32_t casmLen = 0;
    for (size_t i = 0; i + 8 <= size; ++i) {
        if (data[i] == 'C' && data[i + 1] == 'A' && data[i + 2] == 'S' && data[i + 3] == 'M') {
            casmStart = i;
            casmLen = (uint32_t(data[i + 4]) << 24) | (uint32_t(data[i + 5]) << 16) |
                      (uint32_t(data[i + 6]) << 8) | uint32_t(data[i + 7]);
            break;
        }
    }
    if (casmLen == 0 || casmStart + 8 >= size) return;

    size_t casmEnd = std::min(size, casmStart + size_t(8) + casmLen);
    for (size_t i = casmStart + 8; i + 4 < casmEnd; ) {
        if (data[i] != 'C' || data[i + 1] != 't' || data[i + 2] != 'b' || data[i + 3] != '2') {
            ++i;
            continue;
        }
        i += 4;

        // Existing extractor's framing is retained: locate '/' within the
        // small Ctb2 header, then read the 1-based part number.
        size_t slash = i;
        for (int n = 0; n < 8 && slash < casmEnd && data[slash] != '/'; ++n, ++slash) {}
        if (slash >= casmEnd || data[slash] != '/') continue;
        i = slash + 1;
        if (i >= casmEnd) break;

        const int partNum = data[i++];
        while (i < casmEnd && (data[i] == 0 || data[i] == ' ')) ++i;
        const size_t nameStart = i;
        while (i < casmEnd && isAsciiNameChar(data[i])) ++i;
        if (partNum < 1 || partNum > 16 || i <= nameStart) continue;

        const std::string voiceName(reinterpret_cast<const char*>(data + nameStart), i - nameStart);
        if (voiceName.empty() || voiceName.size() >= 32) continue;

        for (auto& [section, sectionData] : sections_) {
            for (auto& part : sectionData.parts) {
                // CASM's 1-based part identifier is kept as the stable lookup
                // key for now. We do not reorder the SMF channel itself.
                if (static_cast<int>(part.midiChannel) + 1 == partNum) {
                    part.casm.valid = true;
                    part.casm.sourceChannel = part.midiChannel;
                    part.casm.destinationChannel = part.midiChannel;
                    part.casm.voiceName = voiceName;
                }
            }
        }
    }
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

            std::map<uint8_t, std::vector<MidiEvent>> byChannel;
            for (const auto& ev : track.events) {
                if (ev.tick >= startTick && ev.tick < endTick &&
                    ev.status >= 0x80 && ev.status < 0xF0) {
                    MidiEvent relative = ev;
                    relative.tick -= startTick;
                    byChannel[ev.channel].push_back(relative);
                }
            }

            for (auto& [channel, events] : byChannel) {
                StylePart part;
                part.midiChannel = channel;
                part.name = track.name.empty() ? ("Ch" + std::to_string(channel)) : track.name;
                part.events = std::move(events);

                // Capture the latest bank/program setup encountered before the
                // first musical note in this section. These values are kept in
                // the part metadata so Kotlin can configure FluidSynth before
                // scheduling notes. CC0/CC32 are bank select MSB/LSB.
                for (const auto& ev : part.events) {
                    const uint8_t hi = ev.status & 0xF0;
                    if (hi == 0xB0 && ev.data1 == 0) part.bankMsb = ev.data2;
                    else if (hi == 0xB0 && ev.data1 == 32) part.bankLsb = ev.data2;
                    else if (hi == 0xC0) part.program = ev.data1;
                    else if (hi == 0x90 && ev.data2 > 0) break;
                }
                secData.parts.push_back(std::move(part));
            }
        }
    }

    parseCasm(rawStyBytes, size);
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

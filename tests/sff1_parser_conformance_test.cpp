// S1 observation driver. Compile the unmodified production parser/SMF reader.
// Ordinals below are observer-side; none are added to the playback model.
#include "style_parser.h"
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <sstream>

static std::string hex(const std::string& value) {
    std::ostringstream out;
    for (unsigned char byte : value)
        out << std::hex << std::setw(2) << std::setfill('0') << unsigned(byte);
    return out.str();
}
static void event(const MidiEvent& value) {
    std::cout << '\t' << value.tick << '\t' << unsigned(value.status)
              << '\t' << unsigned(value.channel) << '\t' << unsigned(value.data1)
              << '\t' << unsigned(value.data2) << '\t' << unsigned(value.metaType)
              << '\t' << hex(std::string(value.metaOrSysexData.begin(), value.metaOrSysexData.end()));
}
int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "usage: sff1_parser_conformance_test style-file\n"; return 2; }
    std::ifstream input(argv[1], std::ios::binary);
    if (!input) { std::cerr << "cannot open input\n"; return 2; }
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
    SmfReader smf;
    StyleParser parser;
    if (!smf.parse(bytes.data(), bytes.size()) || !parser.parse(bytes.data(), bytes.size())) {
        std::cerr << "production parser failed\n"; return 3;
    }
    std::cout << "HEADER\t" << smf.format() << '\t' << smf.tracks().size() << '\t'
              << parser.ppq() << '\t' << std::setprecision(17) << parser.defaultTempoBpm() << '\n';
    for (size_t track = 0; track < smf.tracks().size(); ++track) {
        const auto& data = smf.tracks()[track];
        std::cout << "TRACK\t" << track << '\t' << hex(data.name) << '\n';
        for (size_t ordinal = 0; ordinal < data.events.size(); ++ordinal) {
            std::cout << "RAW\t" << track << '\t' << ordinal;
            event(data.events[ordinal]); std::cout << '\n';
        }
    }
    for (const auto& [id, section] : parser.sections()) {
        std::cout << "SECTION\t" << styleSectionToString(id) << '\t' << section.lengthTicks << '\n';
        for (size_t index = 0; index < section.parts.size(); ++index) {
            const auto& part = section.parts[index];
            std::cout << "PART\t" << index << '\t' << unsigned(part.midiChannel)
                      << '\t' << hex(part.name) << '\n';
            for (size_t ordinal = 0; ordinal < part.casmPolicies.size(); ++ordinal) {
                const auto& p = part.casmPolicies[ordinal];
                std::cout << "POLICY\t" << ordinal << '\t' << unsigned(p.sourceChannel)
                          << '\t' << unsigned(p.destinationChannel) << '\t' << hex(p.voiceName)
                          << '\t' << unsigned(p.sourceChordRoot) << '\t' << unsigned(p.sourceChordType)
                          << '\t' << unsigned(p.ntr) << '\t' << unsigned(p.ntt)
                          << '\t' << unsigned(p.highKey) << '\t' << unsigned(p.noteLimitLow)
                          << '\t' << unsigned(p.noteLimitHigh) << '\t' << unsigned(p.rtr)
                          << '\t' << int(p.bassOn) << '\t' << p.chordMuteMask
                          << '\t' << unsigned(p.sourceNoteLow) << '\t' << unsigned(p.sourceNoteHigh) << '\n';
            }
            for (size_t ordinal = 0; ordinal < part.events.size(); ++ordinal) {
                std::cout << "EVENT\t" << ordinal;
                event(part.events[ordinal]); std::cout << '\n';
            }
        }
    }
    return 0;
}

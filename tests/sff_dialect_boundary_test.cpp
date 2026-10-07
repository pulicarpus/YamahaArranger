#include "style_parser.h"
#include <cassert>
#include <fstream>
#include <iterator>
#include <vector>

static std::vector<uint8_t> unknownStyle() {
    // Valid generic SMF with a playable section. No invented dialect evidence.
    return {'M','T','h','d',0,0,0,6,0,0,0,1,7,128,
            'M','T','r','k',0,0,0,21,
            0,255,6,5,'M','a','i','n','A',0,144,60,100,
            1,128,60,0,0,255,47,0};
}
static void metadata(const StyleParser& parser, StyleDialect expected) {
    assert(parser.dialect()==expected);
    for(const auto& entry:parser.sections())assert(entry.second.dialect==expected);
}
int main(int argc,char** argv) {
    assert(argc==2);
    std::ifstream input(argv[1],std::ios::binary);
    assert(input.good());
    const std::vector<uint8_t> known((std::istreambuf_iterator<char>(input)),{});
    StyleParser parser;
    assert(parser.parse(known.data(),known.size()));
    metadata(parser,StyleDialect::SFF1);
    const auto unknown=unknownStyle();
    assert(parser.parse(unknown.data(),unknown.size()));
    metadata(parser,StyleDialect::UNKNOWN);
    assert(parser.parse(known.data(),known.size()));
    metadata(parser,StyleDialect::SFF1);
    const std::vector<uint8_t> malformed={'n','o','t','s','m','f'};
    assert(!parser.parse(malformed.data(),malformed.size()));
    metadata(parser,StyleDialect::UNKNOWN);
    assert(parser.parse(known.data(),known.size()));
    metadata(parser,StyleDialect::SFF1);
    // Byte marker text in a non-SMF buffer is never sufficient evidence.
    const std::vector<uint8_t> naked={'S','F','F','1'};
    assert(!parser.parse(naked.data(),naked.size()));
    metadata(parser,StyleDialect::UNKNOWN);
}

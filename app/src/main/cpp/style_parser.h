#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include "smf_reader.h"

/* SFF_DIALECT_METADATA_BEGIN */
// Numeric JNI codes are stable; SFF2 is representable, not positively detected.
enum class StyleDialect : int { UNKNOWN=0, SFF1=1, SFF2=2 };
/* SFF_DIALECT_METADATA_END */enum class StyleSection { IntroA, IntroB, IntroC, MainA, MainB, MainC, MainD, FillAA, FillAB, FillAC, FillAD, FillBA, FillBB, FillBC, FillBD, FillCA, FillCB, FillCC, FillCD, FillDA, FillDB, FillDC, FillDD, BreakDown, EndingA, EndingB, EndingC, Unknown };
struct CasmPolicy {
    bool valid=false; uint8_t sourceChannel=0; uint8_t destinationChannel=0; std::string voiceName;
    uint8_t sourceChordRoot=0; uint8_t sourceChordType=0; uint8_t ntr=3; uint8_t ntt=0; uint8_t highKey=127;
    uint8_t noteLimitLow=0; uint8_t noteLimitHigh=127; uint8_t rtr=0; bool bassOn=false;
    uint8_t sourceNoteLow=0; uint8_t sourceNoteHigh=127; uint64_t chordMuteMask=0;
    int program=-1; int bankMsb=0; int bankLsb=0;
};
struct StylePart { uint8_t midiChannel=0; std::string name; std::vector<MidiEvent> events; CasmPolicy casm; std::vector<CasmPolicy> casmPolicies; int program=-1; int bankMsb=0; int bankLsb=0; };
struct StyleSectionData { StyleSection section=StyleSection::Unknown; uint32_t lengthTicks=0; std::vector<StylePart> parts;/* SFF_DIALECT_METADATA_BEGIN */ StyleDialect dialect=StyleDialect::UNKNOWN;/* SFF_DIALECT_METADATA_END */ };
class StyleParser {
public:
/* SFF_DIALECT_METADATA_BEGIN */    StyleDialect dialect() const { return dialect_; }
    void clearDialectMetadata() {
        dialect_=StyleDialect::UNKNOWN;
        for(auto& entry:sections_)entry.second.dialect=StyleDialect::UNKNOWN;
    }
/* SFF_DIALECT_METADATA_END */    bool parse(const uint8_t* rawStyBytes,size_t size); int ppq() const{return smf_.ppq();} double defaultTempoBpm() const{return smf_.defaultTempoBpm();}
    const std::map<StyleSection,StyleSectionData>& sections() const{return sections_;}
private:
/* SFF_DIALECT_METADATA_BEGIN */    StyleDialect dialect_=StyleDialect::UNKNOWN;
    void observeDialectDeclaration() {
        bool declared=false, unsupported=false;
        if(smf_.format()!=0 || smf_.tracks().size()!=1)return;
        for(const auto& ev:smf_.tracks().front().events) {
            if(ev.tick!=0 || ev.status!=0xFF || ev.metaType!=0x06)continue;
            const auto& p=ev.metaOrSysexData;
            if(p==std::vector<uint8_t>{'S','F','F','1'})declared=true;
            else if(p.size()>=3 && p[0]=='S' && p[1]=='F' && p[2]=='F')unsupported=true;
        }
        if(declared && !unsupported)dialect_=StyleDialect::SFF1;
    }
/* SFF_DIALECT_METADATA_END */    SmfReader smf_; std::map<StyleSection,StyleSectionData> sections_; static StyleSection classifyMarkerText(const std::string& text); void parseCasm(const uint8_t* data,size_t size); static std::string trimAscii(const std::string& text);
};
std::string styleSectionToString(StyleSection s); StyleSection styleSectionFromString(const std::string& s);

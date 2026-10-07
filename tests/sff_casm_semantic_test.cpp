#include "style_parser.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
static std::vector<uint8_t> genericSmf() {
    return {'M','T','h','d',0,0,0,6,0,0,0,1,7,128,'M','T','r','k',0,0,0,21,
            0,255,6,5,'M','a','i','n','A',0,148,60,100,1,132,60,0,0,255,47,0};
}
static void chunk(std::vector<uint8_t>& out,const std::string& tag,const std::vector<uint8_t>& body) {
    out.insert(out.end(),tag.begin(),tag.end()); const auto n=body.size();
    for(int shift:{24,16,8,0})out.push_back(static_cast<uint8_t>(n>>shift));
    out.insert(out.end(),body.begin(),body.end());
}
static std::vector<uint8_t> table(uint8_t source=4) {
    std::vector<uint8_t> t(27);t[0]=source;t[9]=11;t[11]=5;t[12]=85;t[21]=130;t[22]=11;t[24]=127;t[25]=1;
    return t;
}
static void selfTest() {
    // Mechanical legacy-parser probes on generic UNKNOWN SMF, never positive dialect fixtures.
    for(bool override:{false,true})for(bool before:{false,true}) {
        auto bytes=genericSmf();std::vector<uint8_t> group,casm;
        chunk(group,"Sdec",{'M','a','i','n',' ','A'});
        if(override&&before)chunk(group,"Cntt",{4,134});
        chunk(group,"Ctab",table());
        chunk(group,"Ctab",table(5)); // Source absent: registry retained, no invented part.
        if(override&&!before)chunk(group,"Cntt",{4,134});
        chunk(casm,"CSEG",group);chunk(bytes,"CASM",casm);
        StyleParser p;assert(p.parse(bytes.data(),bytes.size()));assert(p.dialect()==StyleDialect::UNKNOWN);
        const auto& policy=p.sections().at(StyleSection::MainA).parts.front().casmPolicies.front();
        assert(policy.ntt==(override?6:2));assert(policy.bassOn==override);
        assert(policy.semanticDescriptor>=0);
        const auto& raw=p.casmRawDescriptors().at(policy.semanticDescriptor);
        assert(raw.bytes==table()&&raw.bytes[11]==5&&raw.bytes[12]==85);
        assert((policy.semanticCntt>=0)==override);
        auto snapshot=p.casmSemanticProtocol();assert(snapshot.find("Ctab")!=std::string::npos);
        auto unknown=genericSmf();assert(p.parse(unknown.data(),unknown.size()));assert(p.casmRawDescriptors().empty());
        assert(p.parse(bytes.data(),bytes.size()));assert(p.casmSemanticProtocol()==snapshot);
        const uint8_t bad[]={1,2};assert(!p.parse(bad,sizeof(bad)));assert(p.casmRawDescriptors().empty());
        for(const auto& e:p.sections())for(const auto& part:e.second.parts)for(const auto& v:part.casmPolicies)
            assert(v.semanticDescriptor==-1&&v.semanticCntt==-1);
    }
    // Existing last-Cntt map and cross-CSEG application are observed, not certified semantics.
    for(bool cross:{false,true}) {
        auto bytes=genericSmf();std::vector<uint8_t> first,second,casm;
        chunk(first,"Sdec",{'M','a','i','n',' ','A'});chunk(first,"Ctab",table());chunk(first,"Cntt",{4,134});
        auto& target=cross?second:first;
        if(cross)chunk(second,"Sdec",{'M','a','i','n',' ','A'});
        chunk(target,"Cntt",{4,9});chunk(target,std::string("\t\0\r\xff",4),{0,255});
        chunk(casm,"CSEG",first);if(cross)chunk(casm,"CSEG",second);chunk(bytes,"CASM",casm);
        StyleParser p;assert(p.parse(bytes.data(),bytes.size()));assert(p.dialect()==StyleDialect::UNKNOWN);
        const auto& policy=p.sections().at(StyleSection::MainA).parts.front().casmPolicies.front();
        assert(policy.ntt==9&&!policy.bassOn);
        const auto& override=p.casmRawDescriptors().at(policy.semanticCntt);
        assert(override.bytes==std::vector<uint8_t>({4,9}));assert(override.cseg==(cross?1:0));
        const auto protocol=p.casmSemanticProtocol();assert(protocol.find("hex:09000dff")!=std::string::npos);
        for(unsigned char b:protocol)assert(b<128);
    }
    std::cout<<"S3_NATIVE_SELF_TEST PASS: Ctab-only/Bass-bit omission, Cntt before/after override, last-Cntt/cross-CSEG override provenance, unknown-tag encoding, absent source, load/failure isolation; UNKNOWN SMF only\n";
}
int main(int argc,char** argv) {
    if(argc==2&&std::string(argv[1])=="--self-test"){selfTest();return 0;}
    assert(argc==2);std::ifstream f(argv[1],std::ios::binary);assert(f.good());
    const std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)),{});StyleParser p;
    assert(p.parse(bytes.data(),bytes.size()));assert(p.dialect()==StyleDialect::SFF1);
    std::cout<<p.casmSemanticProtocol();
    for(const auto& e:p.sections())for(size_t i=0;i<e.second.parts.size();++i) {
        const auto& part=e.second.parts[i];
        std::cout<<"L\t"<<styleSectionToString(e.first)<<'\t'<<i<<'\t'<<unsigned(part.midiChannel)<<'\t'<<e.second.lengthTicks<<'\n';
        for(size_t j=0;j<part.casmPolicies.size();++j) {
            const auto& v=part.casmPolicies[j];
            std::cout<<"P\t"<<styleSectionToString(e.first)<<'\t'<<i<<'\t'<<j<<'\t'<<unsigned(v.sourceChannel)<<'|'<<unsigned(v.destinationChannel)<<'|'<<v.voiceName<<'|'<<unsigned(v.sourceChordRoot)<<'|'<<unsigned(v.sourceChordType)<<'|'<<unsigned(v.ntr)<<'|'<<unsigned(v.ntt)<<'|'<<unsigned(v.highKey)<<'|'<<unsigned(v.noteLimitLow)<<'|'<<unsigned(v.noteLimitHigh)<<'|'<<unsigned(v.rtr)<<'|'<<int(v.bassOn)<<'|'<<v.chordMuteMask<<'|'<<unsigned(v.sourceNoteLow)<<'|'<<unsigned(v.sourceNoteHigh)<<'\n';
        }
    }
}

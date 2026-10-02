// Test fixture extraction using the unmodified production parser, not an alternative MIDI decoder.
#include "style_parser.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <iomanip>
int main(int argc,char**argv){
 if(argc!=2)return 2;
 std::ifstream input(argv[1],std::ios::binary);
 std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
 StyleParser p;if(!p.parse(bytes.data(),bytes.size()))return 3;
 std::cout<<"PPQ\t"<<p.ppq()<<"\n";
 for(const auto& [id,section]:p.sections()){
  std::cout<<"S\t"<<styleSectionToString(id)<<"\t"<<section.lengthTicks<<"\n";
  for(const auto&part:section.parts){
   std::cout<<"P\t"<<part.name<<"\n";
   for(const auto& c:part.casmPolicies)std::cout<<"C\t"<<int(c.sourceChannel)<<"\t"<<int(c.destinationChannel)<<"\t"<<c.voiceName<<"\t"<<int(c.sourceChordRoot)<<"\t"<<int(c.sourceChordType)<<"\t"<<int(c.ntr)<<"\t"<<int(c.ntt)<<"\t"<<int(c.highKey)<<"\t"<<int(c.noteLimitLow)<<"\t"<<int(c.noteLimitHigh)<<"\t"<<int(c.rtr)<<"\t"<<c.bassOn<<"\t"<<c.chordMuteMask<<"\t"<<int(c.sourceNoteLow)<<"\t"<<int(c.sourceNoteHigh)<<"\n";
   for(const auto&e:part.events){
    std::cout<<"E\t"<<e.tick<<"\t"<<int(e.status)<<"\t"<<int(e.data1)<<"\t"<<int(e.data2)<<"\t"<<int(e.metaType)<<"\t";
    for(auto b:e.metaOrSysexData)std::cout<<std::hex<<std::setw(2)<<std::setfill('0')<<int(b);
    std::cout<<std::dec<<"\n";
   }
  }
 }
}

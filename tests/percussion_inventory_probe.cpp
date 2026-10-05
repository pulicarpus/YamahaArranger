// Offline metadata evidence evaluator; includes the actual production policy.
#include "percussion_fidelity_policy.h"
#include <fstream>
#include <iostream>
#include <sstream>
using namespace percussion_fidelity;
static std::vector<std::string> split(const std::string& s,char d){std::vector<std::string> r;std::stringstream in(s);std::string x;while(std::getline(in,x,d))r.push_back(x);return r;}
static sf2_zones::Generators gens(const std::string& s){sf2_zones::Generators g;g.modsKnown=true;for(const auto& part:split(s,',')){auto p=split(part,':');if(p.size()!=2)continue;int op=std::stoi(p[0]),v=std::stoi(p[1]);if(op==99){g.modsKnown=v==0;if(v>0)g.mods.push_back({});}else if(op<61){g.present|=uint64_t(1)<<op;g.amount[op]=v;}}return g;}
int main(int argc,char**argv){if(argc!=3)return 2;std::ifstream file(argv[1]);std::string line;std::map<int,sf2_zones::Inventory> inv;std::map<int,std::string> names,fingerprints;
while(std::getline(file,line)){auto p=split(line,'\t');if(p[0]=="F"){names[std::stoi(p[1])]=p[2];fingerprints[std::stoi(p[1])]=p[3];continue;}
 if(p[0]!="Z")continue;int id=std::stoi(p[1]),bank=std::stoi(p[2]),pc=std::stoi(p[3]);sf2_zones::Zone z;
 z.keyLow=std::stoi(p[4]);z.keyHigh=std::stoi(p[5]);z.velLow=std::stoi(p[6]);z.velHigh=std::stoi(p[7]);
 z.rootKey=std::stoi(p[8]);z.pitchCorrection=std::stoi(p[9]);z.sampleId=std::stoi(p[10]);z.sampleType=std::stoi(p[11]);z.sampleLink=std::stoi(p[12]);z.start=std::stoul(p[13]);z.end=std::stoul(p[14]);z.sampleRate=std::stoul(p[15]);
 inv[id].valid=true;inv[id].presetNames[{bank,pc}]=p[16];z.instrument=p[17];z.sample=p[18];z.presetGenerators=gens(p[19]);z.instrumentGenerators=gens(p[20]);inv[id].presets[{bank,pc}].push_back(z);
}
std::vector<Candidate> candidates;for(auto& f:inv){auto v=catalog(f.second,f.first,names[f.first]);std::cout<<"CATALOG|"<<names[f.first]<<"|"<<v.size()<<'\n';for(auto& c:v){c.fingerprint=fingerprints[f.first];for(const auto& evidence:auditionEvidence)if(c.fingerprint==evidence.fingerprint && c.bank==evidence.bank && c.pc==evidence.pc && c.key==evidence.key)c.auditioned=true;candidates.push_back(c);}}
std::ifstream demands(argv[2]);unsigned total=0,compatible=0,legacy=0;while(std::getline(demands,line)){
 auto p=split(line,'\t');int ch=std::stoi(p[0]),key=std::stoi(p[1]),count=std::stoi(p[2]);total+=count;
 const auto* n=sourceIdentity(127,0,73,key);const auto* c=n?choose(candidates,family(n->identity),key):nullptr;
 if(c && supported(family(n->identity)) && !n->keyOff) {compatible+=count;std::cout<<"ROUTE|"<<ch<<'|'<<key<<'|'<<count<<"|COMPATIBLE|"<<c->path<<'|'<<c->bank<<'|'<<c->pc<<'|'<<c->key<<'|'<<c->samples<<"|layers="<<c->layers<<"|auditioned="<<c->auditioned<<'\n';}
 else {legacy+=count;std::cout<<"ROUTE|"<<ch<<'|'<<key<<'|'<<count<<"|LEGACY_ABSTAIN|family_or_articulation_or_runtime_metadata_unproven\n";}
}
std::cout<<"SUMMARY|total="<<total<<"|metadataCompatible="<<compatible<<"|legacyAbstain="<<legacy<<"|EXACT=0|runtimeReadiness=DEVICE_ONLY\n";
}

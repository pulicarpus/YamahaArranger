#ifndef BASSMIDI_R1_BASELINE_HEADER
#error "Define BASSMIDI_R1_BASELINE_HEADER as the protected baseline header path"
#endif
#define voice_resolver baseline_resolver
#include BASSMIDI_R1_BASELINE_HEADER
#undef voice_resolver
#include "../app/src/main/cpp/voice_resolver.h"
#include <chrono>
#include <map>
#include <tuple>
#include <fstream>
#include <sstream>
#include <iostream>
using namespace voice_resolver;
static int fails=0;
static void chk(bool ok_,const std::string& msg){std::cout<<(ok_?"PASS ":"FAIL ")<<msg<<"\n";if(!ok_)++fails;}
#define CHECK(...) chk(__VA_ARGS__)
const std::string PRIME="MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", TYROS="yamaha tyros 4_just_t4_fixed.sf2", COL="ColomboGMGS2_BM.sf2";
int main(int argc,char**argv){
 if(argc!=3 && argc!=4){std::cerr<<"fixture, suite, and (for named) named_inv.out arguments required\n";return 2;}
 std::ifstream in(argv[1]); if(!in){std::cerr<<"fixture unavailable\n";return 2;}std::string line;std::vector<Candidate> pool;std::vector<baseline_resolver::Candidate> base;
 while(std::getline(in,line)){std::istringstream s(line);std::string f,id,b,p,n;std::getline(s,f,'\t');std::getline(s,id,'\t');std::getline(s,b,'\t');std::getline(s,p,'\t');std::getline(s,n);if(f.find("DRUMKIT")!=std::string::npos)continue;int src=f.find("MELODI")==0?0:f.find("Colombo")==0?1:2;
  pool.push_back({src,f,std::stoi(b),std::stoi(p),n,isYamahaSource(f),isGmSource(f),true});base.push_back({src,f,std::stoi(b),std::stoi(p),n,baseline_resolver::isYamahaSource(f),baseline_resolver::isGmSource(f),true});}
 CHECK(pool.size()==1127 && base.size()==1127,"FIXTURE exact full pool size");
 auto win=[&](int bank,int pc,const std::string&n){int i=select({bank,pc,n},pool);return i<0?std::string("REJECT"):pool[i].sf2+"|"+std::to_string(pool[i].bank)+"|"+std::to_string(pool[i].program)+"|"+pool[i].name;};
 auto fam=[&](int b,int p,const std::string& n){int i=select({b,p,n},pool);return i<0?std::string("REJECT"):std::string(familyName(slotFamily(Request{b,p,n},pool[i])));};
 // Positive
 CHECK(win(112,3,"Chord1")==PRIME+"|0|3|Yamaha Honky Tonk" && fam(112,3,"Chord1")=="PIANO","P1 Chord1 112/3 accepted PIANO -> "+win(112,3,"Chord1"));
 CHECK(win(112,3,"")!="REJECT","P2 empty 112/3 accepted -> "+win(112,3,""));
 CHECK(win(112,27,"Chord2")==PRIME+"|0|27|Clean yamaha" && fam(112,27,"Chord2")=="GUITAR","P3 Chord2 112/27 accepted GUITAR -> "+win(112,27,"Chord2"));
 CHECK(win(112,48,"Chord1")==TYROS+"|0|48|t4 strings marcato","P4 Chord1 112/48 unchanged t4 strings marcato");
 CHECK(win(112,33,"Phrase2")==TYROS+"|0|33|FINGER BASS","P5 Phrase2 112/33 unchanged FINGER BASS");
 CHECK(win(112,54,"Pad")==TYROS+"|0|89|woarm pad","P6 Pad 112/54 unchanged woarm pad");
 CHECK(win(112,52,"Chord1")==TYROS+"|0|52|tyros 4 choir","P7 Choir 112/52 stays tyros 4 choir (Suling counterexample)");
 // Negative / counterexamples: candidate family never inferred from PC alone
 Candidate suling{0,PRIME,0,52,"Suling S770",true,false,true};
 CHECK(slotFamily(Request{112,52,"Chord1"},suling)==Family::Unknown && !evaluate(Request{112,52,"Chord1",Family::Choir},suling).accepted,"N1 Suling S770 0/52 never CHOIR");
 Candidate shaku{0,PRIME,0,53,"Shakuhachi",true,false,true};
 CHECK(slotFamily(Request{112,53,"Chord1",Family::Choir},shaku)==Family::Unknown && !evaluate(Request{112,53,"Chord1",Family::Choir},shaku).accepted,"N2 Prime Shakuhachi 0/53 never CHOIR");
 Candidate mtom{0,PRIME,0,47,"Melodic Tom1",true,false,true};
 CHECK(slotFamily(Request{112,47,"Chord1",Family::Strings},mtom)==Family::Unknown && !evaluate(Request{112,47,"Chord1",Family::Strings},mtom).accepted,"N3 Prime Melodic Tom1 0/47 never Timpani/STRINGS");
 Candidate sarune{0,PRIME,0,84,"SARUNE",true,false,true};
 CHECK(slotFamily(Request{112,84,"Chord1",Family::Synth},sarune)==Family::Unknown && !evaluate(Request{112,84,"Chord1",Family::Synth},sarune).accepted,"N4 Prime SARUNE 0/84 never SYNTH");
 CHECK(slotFamily(Request{112,3,""},Candidate{0,"Other Yamaha.sf2",0,3,"Yamaha Honky Tonk",true,false,true})==Family::Unknown,"N5 allowlist requires exact file");
 CHECK(slotFamily(Request{112,3,""},Candidate{0,PRIME,0,3,"Yamaha Honky Tonk ",true,false,true})==Family::Unknown,"N6 allowlist requires exact name");
 CHECK(slotFamily(Request{112,3,""},Candidate{0,PRIME,1,3,"Yamaha Honky Tonk",true,false,true})==Family::Unknown,"N7 allowlist requires exact bank");
 CHECK(slotFamily(Request{112,3,""},Candidate{1,PRIME,0,3,"Yamaha Honky Tonk",false,false,true})==Family::Unknown,"N8 allowlist requires Yamaha source");
 {int a=baseline_resolver::select({8*128+112,3,""},base),c=select({8*128+112,3,""},pool);CHECK(a==c,"N9 MSB8/LSB112 unnamed identical to baseline (no GM inference outside MSB0)");}
 {int mismatches=0, cases=0;
 for(int lsb : {0,112}) for(int pc=0;pc<128;++pc)
 for(const std::string name : {"Chord1","Chord2","Phrase2","Pad",""}) {
   const int bank=104*128+lsb; ++cases;
   const int a=baseline_resolver::select({bank,pc,name},base);
   const int c=select({bank,pc,name},pool);
   if(a!=c) ++mismatches;
   const auto decoded=decodeRequest({bank,pc,name},pool);
   if(decoded.bank!=bank || decoded.program!=pc || decoded.name!=name || decoded.hint!=Family::Unknown) ++mismatches;
 }
 CHECK(cases==1280 && mismatches==0,"N10 MSB104 actual baseline invariance and decode unchanged (1280 cases)");
 }
 CHECK(select({126*128,3,"Chord1"},pool)<0 && select({127*128,3,"Chord1"},pool)<0 && evaluate({126*128,3,"Piano"},pool[0]).reason=="wrong_role" && evaluate({127*128,3,"Piano"},pool[0]).reason=="wrong_role","N11 MSB126/127 wrong_role");
 CHECK(win(112,115,"Chord1")=="REJECT","N12 percussive GM category 112/115 stays REJECT (no melodic substitution)");

 CHECK(slotFamily(Request{8*128,3,"Piano"},Candidate{0,PRIME,0,3,"Yamaha Honky Tonk",true,false,true})==Family::Unknown,"N13 allowlist inert for MSB!=0 requests");
 CHECK(slotFamily(Request{112,4,""},Candidate{0,PRIME,0,3,"Yamaha Honky Tonk",true,false,true})==Family::Unknown,"N14 allowlist inert for different PC");
 CHECK(slotFamily(Request{112,3,""},Candidate{0,PRIME,0,3,"Yamaha Honky Tonk",true,false,true})==Family::Piano,"P8 allowlist active at own MSB0 slot");
 // Regression vs baseline for bank 0 named/standard requests outside PC3/27
 int diff=0,tot=0; for(int pc=0;pc<128;++pc)for(std::string n:{"Chord1","Chord2","Phrase2","Pad","","Piano","Strings","Brass"}){++tot;int a=baseline_resolver::select({0,pc,n},base),c=select({0,pc,n},pool);
  std::string ka=a<0?"R":base[a].sf2+base[a].name+std::to_string(base[a].program), kc=c<0?"R":pool[c].sf2+pool[c].name+std::to_string(pool[c].program); if(ka!=kc){ if(pc!=3&&pc!=27){++diff;std::cout<<"  unexpected bank0 change pc="<<pc<<" name="<<n<<"\n";}}}
 CHECK(diff==0,"R1 bank0 winners identical to baseline except PC3/PC27 ("+std::to_string(tot)+" cases)");

 // A/B uses complete file/bank/PC/name winner identity, never abbreviated filenames.
 auto key=[](int i,const auto& candidates){return i<0?std::string("REJECT"):candidates[i].sf2+"|"+std::to_string(candidates[i].bank)+"|"+std::to_string(candidates[i].program)+"|"+candidates[i].name;};
 std::string suite=argv[2]; long cases=0,opened=0,swaps=0,closed=0,unexpected=0;
 using GoldenKey=std::tuple<int,int,std::string>;
 using GoldenWinners=std::pair<std::string,std::string>;
 std::map<GoldenKey,GoldenWinners> namedGolden;
 long goldenSeen=0,goldenMismatch=0;
 if(suite=="named") {
   if(argc!=4){std::cerr<<"named suite requires Claude named_inv.out\n";return 2;}
   std::ifstream golden(argv[3]);if(!golden)return 2;
   while(std::getline(golden,line)) {
     if(line=="named invariance cases=39936 changed=122")continue;
     const auto space=line.find(' '),colon=line.find(": ",space),arrow=line.find(" => ",colon);
     if(space==std::string::npos || colon==std::string::npos || arrow==std::string::npos)return 2;
     auto address=line.substr(0,space);std::replace(address.begin(),address.end(),'/',' ');
     std::istringstream addressStream(address);int msb,lsb,pc;if(!(addressStream>>msb>>lsb>>pc))return 2;
     auto before=line.substr(colon+2,arrow-colon-2),after=line.substr(arrow+4);
     if(before=="R")before="REJECT";
     if(after=="R")after="REJECT";
     if(!namedGolden.emplace(GoldenKey{msb*128+lsb,pc,line.substr(space+1,colon-space-1)},GoldenWinners{before,after}).second)return 2;
   }
   CHECK(namedGolden.size()==122,"NAMED Claude golden contains exactly 122 unique changes");
 }

 std::vector<int> banks;
 if(suite=="main")banks={0,112};
 else if(suite=="nonzero")for(int m:{8,16,64,104,126,127})for(int l:{0,112})banks.push_back(m*128+l);
 else if(suite=="msb0")for(int l=0;l<128;++l)banks.push_back(l);
 else if(suite=="named")for(int m:{0,1,2,3,8,16,24,32,40,64,104,108,112})for(int l:{0,1,112})banks.push_back(m*128+l);
 else {std::cerr<<"unknown suite\n";return 2;}
 const std::vector<std::string> names=suite=="named"?std::vector<std::string>{"Piano","Guitar","Grand Piano","Honky Tonk","Clean Guitar","Jazz Gtr","E.Piano","Strings"}:std::vector<std::string>{"Chord1","Chord2","Phrase2","Pad",""};
 for(int bank:banks)for(int pc=0;pc<128;++pc)for(const auto& name:names){
   ++cases;
   auto a=key(baseline_resolver::select({bank,pc,name},base),base);
   auto c=key(select({bank,pc,name},pool),pool);
   std::cout<<"AB\t"<<name<<'\t'<<bank<<'\t'<<pc<<'\t'<<a<<'\t'<<c<<'\n';
   if(suite=="named") {
     const auto expected=namedGolden.find({bank,pc,name});
     if(expected!=namedGolden.end()) {++goldenSeen;if(expected->second!=GoldenWinners{a,c})++goldenMismatch;}
     else if(a!=c)++goldenMismatch;
   }
   if(a==c)continue;
   if(a=="REJECT") {++opened;if(bank/128!=0 || bank==0)++unexpected;}
   else if(c=="REJECT"){++closed;++unexpected;}
   else {++swaps;if(bank/128!=0 || (pc!=3 && pc!=27) || (suite!="named" && bank!=0))++unexpected;
     const std::string expected=PRIME+"|0|"+std::to_string(pc)+"|"+(pc==3?"Yamaha Honky Tonk":"Clean yamaha");if(c!=expected)++unexpected;}
 }
 long wantCases=0,wantOpened=0,wantSwaps=0;
 if(suite=="main"){wantCases=1280;wantOpened=204;wantSwaps=8;}
 if(suite=="nonzero"){wantCases=7680;}
 // Audit correction: named_inv.out has 20 swaps and 102 openings. Four Honky Tonk
 // requests at LSB1/112 PC3/27 start REJECT; the report table counted them as swaps.
 if(suite=="named"){wantCases=39936;wantOpened=102;wantSwaps=20;
   CHECK(goldenSeen==122 && goldenMismatch==0,"NAMED all 39936 winners match Claude per-case golden; no extra changes");
 }
 if(suite=="msb0"){wantCases=81920;wantOpened=25900;wantSwaps=8;}
 CHECK(cases==wantCases && opened==wantOpened && swaps==wantSwaps && closed==0 && unexpected==0,"AB "+suite+" cases="+std::to_string(cases)+" opened="+std::to_string(opened)+" swaps="+std::to_string(swaps)+" closed="+std::to_string(closed)+" unexpected="+std::to_string(unexpected));
 std::cout<<(fails?"CONTRACT FAIL ":"CONTRACT PASS ")<<fails<<"\n"; return fails!=0;}

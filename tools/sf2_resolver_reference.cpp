// Offline read-only policy replay. No engine, BASS, SF2 writes, or runtime claim.
#include "voice_resolver.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

static std::vector<std::string> fields(const std::string& line) {
    std::vector<std::string> out; std::istringstream in(line); std::string s;
    while (std::getline(in, s, '\t')) out.push_back(s);
    if (!line.empty() && line.back() == '\t') out.push_back("");
    return out;
}
int main(int argc, char** argv) {
    try {
        if (argc != 3) throw std::runtime_error("usage: replay candidates.tsv requests.tsv");
        std::ifstream pool(argv[1]), requests(argv[2]);
        if (!pool || !requests) throw std::runtime_error("input unavailable");
        std::vector<voice_resolver::Candidate> cs; std::string line;
        while (std::getline(pool, line)) {
            auto f=fields(line); if (f.size()!=6) throw std::runtime_error("candidate needs source,filename,bank,pc,name,melodic");
            cs.push_back({std::stoi(f[0]),f[1],std::stoi(f[2]),std::stoi(f[3]),f[4],
                voice_resolver::isYamahaSource(f[1]),voice_resolver::isGmSource(f[1]),std::stoi(f[5])!=0});
        }
        std::cout << "fixture_id\tcandidate\tsource\tsf2\tbank\tpc\tfamily\taccepted\tscore\tselected\treason\tdecoded_name\n";
        while (std::getline(requests,line)) {
            auto f=fields(line); if(f.size()!=4) throw std::runtime_error("request needs id,packed_bank,pc,name");
            voice_resolver::Request input{std::stoi(f[1]),std::stoi(f[2]),f[3]};
            auto r=voice_resolver::decodeRequest(input,cs); int chosen=voice_resolver::select(r,cs);
            for(size_t i=0;i<cs.size();++i) {
                const auto& c=cs[i]; auto d=voice_resolver::evaluate(r,c);
                std::cout<<f[0]<<'\t'<<i<<'\t'<<c.source<<'\t'<<c.sf2<<'\t'<<c.bank<<'\t'<<c.program<<'\t'
                    <<voice_resolver::familyName(d.family)<<'\t'<<d.accepted<<'\t'<<d.score<<'\t'
                    <<(chosen==int(i))<<'\t'<<d.reason<<'\t'<<r.name<<'\n';
            }
            if(chosen<0) std::cout<<f[0]<<"\tNONE\tUNKNOWN\tUNKNOWN\tUNKNOWN\tUNKNOWN\t"
                <<voice_resolver::familyName(r.family())<<"\t0\t-1\t0\tno_compatible_candidate\t"<<r.name<<'\n';
        }
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n';return 2; }
}

// Read-only batch simulation using the exact checkpoint pure policy.
#include "voice_resolver.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <clocale>

static std::vector<std::string> split(const std::string& s) {
    std::vector<std::string> out;std::istringstream in(s);std::string v;
    while(std::getline(in,v,'\t'))out.push_back(v);
    if(!s.empty()&&s.back()=='\t')out.push_back("");
    return out;
}
int main(int argc,char**argv) {
    try {
        std::setlocale(LC_ALL,"C");
        if(argc!=4)throw std::runtime_error("usage: batch pool.tsv requests.tsv classifications.tsv");
        std::ifstream source(argv[1]),requests(argv[2]);std::ofstream classes(argv[3]);
        if(!source||!requests||!classes)throw std::runtime_error("input/output unavailable");
        std::vector<voice_resolver::Candidate> all;std::vector<std::string> ids;std::string line;
        classes<<"preset_id\tfamily_by_checkpoint\tmelody_cache_member\n";
        while(std::getline(source,line)) {
            auto f=split(line);if(f.size()!=7)throw std::runtime_error("invalid pool row");
            all.push_back({std::stoi(f[1]),f[2],std::stoi(f[3]),std::stoi(f[4]),f[5],
                voice_resolver::isYamahaSource(f[2]),voice_resolver::isGmSource(f[2]),f[6]=="1"});
            ids.push_back(f[0]);
            classes<<f[0]<<'\t'<<voice_resolver::familyName(all.back().family())<<'\t'<<all.back().melodic<<'\n';
        }
        std::cout<<"case_id\trequest_family\tdecoded_name\tselected_id\tscore\taccepted_candidates\ttop_score_ties\tselection_reason\ttied_preset_ids\n";
        while(std::getline(requests,line)) {
            auto f=split(line);if(f.size()!=5)throw std::runtime_error("invalid request row");
            std::vector<voice_resolver::Candidate> pool;std::vector<size_t> indices;
            for(size_t i=0;i<all.size();++i) {
                if(!all[i].melodic)continue;
                if(f[1]=="NO_COLOMBO"&&all[i].source==1)continue;
                if(f[1]=="NO_TYROS"&&all[i].source==2)continue;
                pool.push_back(all[i]);indices.push_back(i);
            }
            if(f[1]!="FULL"&&f[1]!="NO_COLOMBO"&&f[1]!="NO_TYROS")throw std::runtime_error("unknown pool mode");
            voice_resolver::Request input{std::stoi(f[2]),std::stoi(f[3]),f[4]};
            auto req=voice_resolver::decodeRequest(input,pool);const int selected=voice_resolver::select(req,pool);
            int accepted=0,score=-1,ties=0;std::string reason="no_compatible_candidate",tied;
            if(selected>=0){auto d=voice_resolver::evaluate(req,pool[selected]);score=d.score;reason=d.reason;}
            for(size_t i=0;i<pool.size();++i){auto d=voice_resolver::evaluate(req,pool[i]);if(d.accepted){++accepted;if(d.score==score){++ties;if(!tied.empty())tied+='|';tied+=ids[indices[i]];}}}
            std::cout<<f[0]<<'\t'<<voice_resolver::familyName(req.family())<<'\t'<<req.name<<'\t'
                <<(selected<0?"NONE":ids[indices[selected]])<<'\t'<<score<<'\t'<<accepted<<'\t'<<ties<<'\t'<<reason<<'\t'<<tied<<'\n';
        }
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 2;}
}

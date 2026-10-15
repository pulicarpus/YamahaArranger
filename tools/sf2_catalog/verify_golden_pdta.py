#!/usr/bin/env python3
"""Verify all four ORIGINAL PDTA payloads with the production native parser.

Expected files: COLOMBO.pdta, PRIME_DRUM.pdta, PRIME_MELODY.pdta, TYROS4.pdta.
Each file is LIST payload including fourcc pdta, excluding LIST header/outer pad.
No sample data is accepted, stored, or inferred. Missing bytes fail this gate.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
from binary_audit import load

ROOT=Path(__file__).resolve().parents[2]
DRIVER=r'''
#include "sf2_pdta_fingerprint.h"
#include <fstream>
#include <iostream>
int main(int argc,char**argv){
    if(argc!=2)return 2;
    std::ifstream in(argv[1],std::ios::binary|std::ios::ate);
    if(!in || in.tellg()<4 || in.tellg()>32*1024*1024)return 3;
    const size_t size=static_cast<size_t>(in.tellg());in.seekg(0);
    std::vector<unsigned char> b{'R','I','F','F',0,0,0,0,'s','f','b','k','L','I','S','T',0,0,0,0};
    b.resize(20+size+(size&1));if(!in.read(reinterpret_cast<char*>(b.data()+20),size))return 4;
    auto put=[&](size_t at,uint32_t n){for(int i=0;i<4;++i)b[at+i]=static_cast<unsigned char>(n>>(8*i));};
    put(4,b.size()-8);put(16,size);
    // The wrapper does not modify the original payload or affect its hash domain.
    const auto result=sf2_pdta::fingerprint(b);
    if(!result.valid){std::cerr<<result.reason;return 5;}
    std::cout<<result.sha256<<" "<<result.bytes<<"\n";
}
'''

def verify(directory):
    review=json.loads((Path(__file__).with_name('catalog_review.yaml')).read_text());load(review)
    directory=Path(directory)
    from pdta_inventory import read_source
    inventory=read_source(directory,review)
    payloads={}
    if inventory is not None:
        for f in review['font_provenance']:payloads[f['role']]=inventory[f['file_sha256']]['payload']
    inputs=[(f,directory/(f['role']+'.pdta')) for f in review['font_provenance']]
    missing=[str(p) for f,p in inputs if f['role'] not in payloads and not p.is_file()]
    if missing:raise ValueError('original PDTA evidence unavailable: '+', '.join(missing))
    results=[]
    with tempfile.TemporaryDirectory(prefix='u1-golden-') as temp:
        cpp=Path(temp)/'driver.cpp';cpp.write_text(DRIVER);exe=Path(temp)/'verify'
        subprocess.run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-I'+str(ROOT/'app/src/main/cpp'),str(cpp),'-o',str(exe)],check=True)
        for f,path in inputs:
            if f['role'] in payloads:
                path=Path(temp)/(f['role']+'.pdta');path.write_bytes(payloads[f['role']])
            digest,length=subprocess.check_output([str(exe),str(path)],text=True).split()
            if (digest,int(length))!=(f['expected_pdta_sha256'],f['pdta_bytes']):
                raise ValueError('golden PDTA mismatch: '+f['role'])
            results.append({'role':f['role'],'pdta_sha256':digest,'bytes':int(length),'status':'HOST_ORIGINAL_PDTA_PASS'})
    return results

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--pdta-dir',required=True);a=p.parse_args()
    print(json.dumps(verify(a.pdta_dir),indent=2))

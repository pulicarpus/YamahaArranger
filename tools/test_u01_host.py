#!/usr/bin/env python3
"""U0/U1 gates; external owner metadata and pinned JVM dependencies required.

No APK, source rewriting, historical hash updates, or runtime catalog activation.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT=Path(__file__).resolve().parents[1]
BASE='c3f3b0a67f4d4acc2310f687abb248be1cae6643'
PROTECTED='f4f2c613bebd09a3137bdbe0bc60f0895b597979'

def run(args,log=None):
    result=subprocess.run(list(map(str,args)),cwd=ROOT,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'},stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    if log:Path(log).write_text(result.stdout)
    if result.returncode:raise RuntimeError('Command failed: '+str(args)+'\n'+result.stdout[-6000:])
    return result.stdout

def main():
    p=argparse.ArgumentParser();p.add_argument('--metadata',required=True);p.add_argument('--jvm-deps',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();out=Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
    allowed={'app/src/main/cpp/bassmidi_player.cpp','app/src/main/cpp/bassmidi_player.h','app/src/main/cpp/sf2_pdta_fingerprint.h','app/src/main/java/com/yourapp/ui/MainViewModel.kt','app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleFidelityReport.kt','app/src/main/java/com/yourapp/audio/Sf2SemanticInventory.kt'}
    original={}
    for line in run(['git','ls-tree','-r',BASE,'--','app/src/main']).splitlines():
        attrs,path=line.split('\t',1);original[path]=attrs.split()[2]
    physical={str(p.relative_to(ROOT)):p for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}
    changed=set()
    for path in set(original)|set(physical):
        b=physical[path].read_bytes() if path in physical else b''
        oid=hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
        if path not in physical or oid!=original.get(path):changed.add(path)
    assert changed==allowed,('production scope drift',changed)
    assert (ROOT/'app/src/main/cpp/voice_resolver.h').read_bytes()==subprocess.check_output(['git','show',BASE+':app/src/main/cpp/voice_resolver.h'],cwd=ROOT)
    print('U0/U1 ISOLATION PASS: six diagnostic-only production paths; all other production bytes identical',flush=True)
    print(run(['python3',ROOT/'tools/sf2_catalog/verify_u01_boundary.py']).strip(),flush=True)
    print(run(['python3',ROOT/'tests/test_golden_pdta_tool.py'],out/'golden-tool.log').strip(),flush=True)
    print(run(['python3',ROOT/'tests/test_pdta_inventory.py'],out/'pdta-inventory.log').strip(),flush=True)
    print(run(['python3',ROOT/'tools/sf2_catalog/verify_golden_pdta.py','--pdta-dir',Path(a.metadata).resolve()],out/'original-golden.json').strip(),flush=True)
    run(['g++','-std=c++17','-O1','-g','-fsanitize=address,undefined','-fno-omit-frame-pointer','-Wall','-Wextra','-Werror','-I',ROOT/'app/src/main/cpp',ROOT/'tests/u01_pdta_test.cpp','-o',out/'pdta-test'])
    print(run([out/'pdta-test'],out/'pdta.log').strip(),flush=True)
    print(run(['python3',ROOT/'tools/sf2_catalog/test_catalog.py','--metadata',Path(a.metadata).resolve()],out/'catalog.log').strip(),flush=True)
    run(['python3',ROOT/'tools/sf2_catalog/build_catalog.py','--metadata',Path(a.metadata).resolve(),'--out',out/'generated'],out/'generator.log')
    for name in ['catalog_manifest.json','voice_catalog_generated.h','drum_catalog_generated.h','catalog_report.md','catalog.json.gz','catalog_names.json.gz']:
        assert (out/'generated'/name).read_bytes()==(ROOT/'catalog/u01'/name).read_bytes(), 'generated catalog drift: '+name
    smoke=out/'catalog-smoke.cpp';smoke.write_text('#include "'+str(out/'generated/voice_catalog_generated.h')+'"\n#include "'+str(out/'generated/drum_catalog_generated.h')+'"\nint main(){return u01_voice::count+u01_drum::count==1148?0:1;}\n')
    run(['g++','-std=c++17','-Wall','-Wextra','-Werror',smoke,'-o',out/'catalog-smoke']);run([out/'catalog-smoke'])
    deps=Path(a.jvm_deps).resolve();manifest=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())
    jars=[]
    for entry in manifest['dependencies']:
        jar=deps/entry['filename'];assert hashlib.sha256(jar.read_bytes()).hexdigest()==entry['sha256'];jars.append(str(jar))
    cp=os.pathsep.join(jars)
    sources=[ROOT/'app/src/main/java/com/yourapp/style/StyleModel.kt',ROOT/'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleFidelityReport.kt',ROOT/'tests/StyleFidelityReportTest.kt']
    run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',out/'u0.jar',*sources])
    print(run(['java','-cp',str(out/'u0.jar')+os.pathsep+cp,'com.yourapp.yamahaarranger.arranger.StyleFidelityReportTestKt'],out/'kotlin.log').strip(),flush=True)
    inventory=[ROOT/'app/src/main/java/com/yourapp/audio/Sf2SemanticInventory.kt',ROOT/'app/src/test/java/com/yourapp/audio/Sf2SemanticInventoryTest.kt']
    run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',out/'inventory.jar',*inventory])
    print(run(['java','-cp',str(out/'inventory.jar')+os.pathsep+cp,'org.junit.runner.JUnitCore','com.yourapp.audio.Sf2SemanticInventoryTest'],out/'inventory.log').strip(),flush=True)

    # Compile original and modified production native code with the SAME recorder driver.
    # The baseline is an immutable archive, not a normalization of current source.
    with tempfile.TemporaryDirectory(prefix='u01-baseline-') as directory:
        baseline=Path(directory);archive=subprocess.check_output(['git','archive',BASE,'app/src/main/cpp','tests'],cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(baseline,filter='data')
        driver=(ROOT/'tests/native_resolver_test.cpp').read_text()
        extra='''    const auto u01SavedHistory=mock_bass::history;
    const auto u01SavedEvents=mock_bass::events;
    const auto u01Report=player.noteZoneReport();
    check(!u01Report.empty(),"diagnostic export available");
    check(u01SavedHistory==mock_bass::history && u01SavedEvents==mock_bass::events,"U0 export has no event/controller writes");
    for(const auto& e:mock_bass::history)std::cout<<"TRACE "<<std::get<0>(e)<<" "<<std::get<1>(e)<<" "<<std::get<2>(e)<<" "<<std::get<3>(e)<<"\\n";
    for(const auto& m:mock_bass::mappings)std::cout<<"MAP "<<m.font<<" "<<m.spreset<<" "<<m.sbank<<" "<<m.dpreset<<" "<<m.dbank<<" "<<m.dbanklsb<<" "<<m.minchan<<" "<<m.numchan<<"\\n";
'''
        driver=driver.replace('    std::cout << "PASS: " << checks',extra+'    std::cout << "PASS: " << checks')
        path=out/'native-driver.cpp';path.write_text(driver)
        fixture=out/'native-fixtures';fixture.mkdir(exist_ok=True);traces=[]
        for label,source in [('before',baseline),('after',ROOT)]:
            exe=out/('native-'+label)
            run(['g++','-std=c++17','-O2','-pthread','-I',source/'tests/mocks','-I',source/'app/src/main/cpp','-I',source/'tests',path,source/'app/src/main/cpp/bassmidi_player.cpp','-o',exe])
            text=run([exe,fixture],out/('native-'+label+'.log'));traces.append([l for l in text.splitlines() if l.startswith(('TRACE ','MAP '))])
        assert traces[0] and traces[0]==traces[1]
        print('U0 NATIVE DIFFERENTIAL PASS: '+str(len(traces[0]))+' event/mapping rows byte-identical; read-only export',flush=True)
    header=out/'protected-voice-resolver.h';header.write_bytes(subprocess.check_output(['git','show',PROTECTED+':app/src/main/cpp/voice_resolver.h'],cwd=ROOT))
    run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-DBASSMIDI_R1_BASELINE_HEADER="'+str(header)+'"',ROOT/'tools/bassmidi_r1_policy_test.cpp','-o',out/'policy'])
    manifest=json.loads((ROOT/'tests/fixtures/bassmidi_r1/manifest.json').read_text())
    def suite(name):
        args=[out/'policy',ROOT/'tests/fixtures/bassmidi_r1/full-presets.tsv',name]
        if name=='named':args.append(ROOT/'tests/fixtures/bassmidi_r1/named_inv.out')
        text=run(args,out/('policy-'+name+'.log'));assert text.splitlines()[-1]=='CONTRACT PASS 0'
        rows=[l+'\n' for l in text.splitlines() if l.startswith('AB\t')];assert len(rows)==manifest['counts'][name]
        if name in manifest['golden_ab_sha256']:assert hashlib.sha256(''.join(rows).encode()).hexdigest()==manifest['golden_ab_sha256'][name]
        return text.splitlines()[-2]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        for result in executor.map(suite,['main','nonzero','named','msb0']):print(result,flush=True)
    print('U0/U1 HOST PASS; all four original PDTA goldens verified; device gates remain PENDING',flush=True)

if __name__=='__main__':main()

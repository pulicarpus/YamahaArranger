#!/usr/bin/env python3
"""Run S6 controls + S7 isolated contract probes on the validated S5 JVM jar.
Android testDebugUnitTest discovers both new classes automatically. No source,
golden or workflow modifications; no skipped JUnit placeholder for native PCM.
"""
from f04_source_guard import historical_sha, pipeline_expected
import json, subprocess
from pathlib import Path
from audit_sff1_corpus import ROOT, file_sha, production_identity, require

def main():
    production_identity()
    base=ROOT/'build/sff1-jvm';out=ROOT/'build/s7-jvm';out.mkdir(parents=True,exist_ok=True)
    require(any(f'OK ({n} tests)' in (base/'junit.log').read_text() for n in (83,90)),'Run all S1-S5 host regression first')
    require((base/'sff1_pipeline_observed.tsv').read_bytes()==pipeline_expected(),'Golden mismatch')
    evidence=json.loads((ROOT/'tests/fixtures/s6_f03_evidence.json').read_text())
    for path,sha in evidence['s5_commit_files_sha256'].items():require(historical_sha(path)==sha,f'S5 evidence changed: {path}')
    s7=json.loads((ROOT/'tests/fixtures/s7_contract_evidence.json').read_text())
    for path,sha in s7['protected_s6_files_sha256'].items():require(historical_sha(path)==sha,f'S6 evidence changed: {path}')
    lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())
    jars=[base/'dependencies'/x['filename'] for x in lock['dependencies']]
    for item,path in zip(lock['dependencies'],jars):require(file_sha(path)==item['sha256'],'Dependency SHA mismatch')
    cp=':'.join(map(str,[base/'sff1-jvm-tests.jar',*jars]));jar=out/'s7-tests.jar'
    classes=['S6F03RootCauseTest','StyleMixFidelityS7BackendContractTest']
    sources=[ROOT/'app/src/test/java/com/yourapp/yamahaarranger/arranger'/f'{c}.kt' for c in classes]
    subprocess.run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
    command=['java','-Xmx1g',f'-javaagent:{base/"dependencies/byte-buddy-agent.jar"}','-cp',f'{jar}:{cp}','org.junit.runner.JUnitCore',*['com.yourapp.yamahaarranger.arranger.'+c for c in classes]]
    result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/'junit.log').write_text(result.stdout);print(result.stdout)
    require(result.returncode==0 and 'OK (11 tests)' in result.stdout,'S6/S7 diagnostics failed or did not execute')

if __name__=='__main__':main()

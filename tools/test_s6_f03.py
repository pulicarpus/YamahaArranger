#!/usr/bin/env python3
"""Add S6 diagnostic tests to an already validated S5 plain-JVM build.

Run test_sff1_pipeline.py --existing-regressions --s2 --s3 --s4 --s5 first.
Never updates golden captures or production code. Android picks up the class
normally through testDebugUnitTest; this runner supplies the host alternative.
"""
from f04_source_guard import historical_sha, pipeline_expected
import argparse
from pathlib import Path
import subprocess
from audit_sff1_corpus import ROOT, file_sha, require, production_identity
from summarize_f03_ownership import summarize
import json

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--baseline-build',type=Path,default=ROOT/'build/sff1-jvm')
    p.add_argument('--output',type=Path,default=ROOT/'build/s6-f03')
    a=p.parse_args();base=a.baseline_build.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    production_identity()
    evidence=json.loads((ROOT/'tests/fixtures/s6_f03_evidence.json').read_text())
    for path,expected in evidence['s5_commit_files_sha256'].items():
        require(historical_sha(path)==expected,f'S5 preservation drift: {path}')
    require(evidence['counts']==summarize()['counts_C_major'],'S6 inventory count drift')
    require(any(f'OK ({n} tests)' in (base/'junit.log').read_text() for n in (83,90)),'First run complete S5 host suite, 83 tests')
    require((base/'sff1_pipeline_observed.tsv').read_bytes()==pipeline_expected(),'Baseline golden digest mismatch')
    lock=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())
    jars=[base/'dependencies'/item['filename'] for item in lock['dependencies']]
    for item,path in zip(lock['dependencies'],jars):require(file_sha(path)==item['sha256'],'Untrusted dependency')
    cp=':'.join(map(str,[base/'sff1-jvm-tests.jar',*jars]));jar=out/'s6-f03-tests.jar'
    source=ROOT/'app/src/test/java/com/yourapp/yamahaarranger/arranger/S6F03RootCauseTest.kt'
    subprocess.run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar),str(source)],check=True)
    command=['java','-Xmx1g',f'-javaagent:{base/"dependencies/byte-buddy-agent.jar"}','-cp',f'{jar}:{cp}','org.junit.runner.JUnitCore','com.yourapp.yamahaarranger.arranger.S6F03RootCauseTest']
    result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/'junit.log').write_text(result.stdout);print(result.stdout)
    require(result.returncode==0 and 'OK (4 tests)' in result.stdout,'S6 tests did not complete')

if __name__=='__main__':main()

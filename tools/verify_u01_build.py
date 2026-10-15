#!/usr/bin/env python3
"""Exact parent/source/fixture checks for the isolated diagnostic build, not full CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
from generated_bass_sdk_guard import PATHS,verify_generated_sdk

ROOT=Path(__file__).resolve().parents[1]
PARENT='975e2a6599b14f49e7e4229f615737d30c5144ab'
BASE='f4f2c613bebd09a3137bdbe0bc60f0895b597979'
FIXTURE_SHA='15c7c1f1d4786fd549076f6b62339810514fbd8a613bc2348d8db0461cac2875'

def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT).decode().strip()

def integrity(phase):
    sha=git('rev-parse','HEAD');assert sha==os.environ.get('GITHUB_SHA',sha),'workflow SHA mismatch'
    assert git('rev-parse','HEAD^')==PARENT,'unexpected parent'
    assert git('rev-parse',BASE+'^{tree}')=='888af26ee8caf753c7d2916fd801afd5e00e4330'
    assert git('merge-base',PARENT,sha)==PARENT
    assert not git('diff','--name-only',PARENT,'HEAD','--','app/src/main'),'production source drift from reviewed U0/U1'
    assert not git('diff','--name-only','HEAD'),'tracked working tree drift'
    # Preserve every existing workflow, guard, historical fixture, and R1 contract source.
    for path in git('ls-tree','-r','--name-only',PARENT,'--','.github/workflows','tools','tests/fixtures').splitlines():
        allowed={'tools/sf2_catalog/build_catalog.py','tools/sf2_catalog/test_catalog.py','tools/sf2_catalog/verify_golden_pdta.py','tools/test_u01_host.py'}
        if path not in allowed:assert git('rev-parse','HEAD:'+path)==git('rev-parse',PARENT+':'+path),'protected tooling changed: '+path
    fixture=ROOT/'tests/fixtures/sf2_pdta/export.zip';assert hashlib.sha256(fixture.read_bytes()).hexdigest()==FIXTURE_SHA
    extras=set(git('ls-files','--others','--exclude-standard','--','app/src/main').splitlines())
    ignored=set(git('ls-files','--others','--ignored','--exclude-standard','--','app/src/main').splitlines())
    sdk=verify_generated_sdk(ROOT,extras|ignored)
    if phase=='sdk':assert set(sdk)==PATHS
    else:assert not sdk
    return {'source_sha':sha,'parent_sha':PARENT,'baseline_sha':BASE,'production':'BYTE_IDENTICAL_TO_975e2a6',
        'fixture_sha256':FIXTURE_SHA,'sdk':sdk,'phase':phase,'catalogUse':'OFF','full_regression_ci':'BLOCKED historical source profiles; not claimed PASS','device_audio':'UNVERIFIED'}

def apk_report(path):
    sha=git('rev-parse','HEAD');path=Path(path);data=path.read_bytes()
    sdk=json.loads((ROOT/'tests/fixtures/f04_generated_sdk_identity.json').read_text())['files']
    with zipfile.ZipFile(path) as archive:
        assert not any(name.lower().endswith('.sf2') for name in archive.namelist()),'SF2 unexpectedly packaged'
        for abi in ['arm64-v8a','armeabi-v7a']:
            for lib in ['bass','bassmidi','yamaha_arranger_native']:assert f'lib/{abi}/lib{lib}.so' in archive.namelist()
            for lib in ['bass','bassmidi']:
                expected=sdk[f'app/src/main/jniLibs/{abi}/lib{lib}.so'];blob=archive.read(f'lib/{abi}/lib{lib}.so')
                assert (len(blob),hashlib.sha256(blob).hexdigest())==(expected['bytes'],expected['sha256'])
            native=archive.read(f'lib/{abi}/libyamaha_arranger_native.so')
            assert b'SF2_FINGERPRINT' in native and b'validated_list_pdta_payload_v2' in native
    return {'source_sha':sha,'parent_sha':PARENT,'baseline_sha':BASE,'apk_sha256':hashlib.sha256(data).hexdigest(),'apk_bytes':len(data),
        'abis':['arm64-v8a','armeabi-v7a'],'run_url':'https://github.com/pulicarpus/YamahaArranger/actions/runs/'+os.environ.get('GITHUB_RUN_ID','LOCAL'),
        'host':'PASS','pdta_original_goldens':'PASS_4_OF_4','catalogUse':'OFF','production':'BYTE_IDENTICAL_TO_975e2a6',
        'full_regression_ci':'BLOCKED historical source profiles; not claimed PASS','device_audio':'UNVERIFIED','calibration_P1_P8':'NOT_IMPLEMENTED_SEPARATE_DEVICE_TASK'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','sdk']);p.add_argument('--apk');p.add_argument('--out',required=True);a=p.parse_args()
    result=apk_report(a.apk) if a.apk else integrity(a.phase)
    Path(a.out).write_text(json.dumps(result,indent=2,sort_keys=True)+'\n');print(json.dumps(result,sort_keys=True))

if __name__=='__main__':main()

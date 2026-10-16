#!/usr/bin/env python3
import argparse,os,json,zipfile
from pathlib import Path
from verify_r23_boundary import verify,ROOT,BASE,git
from generated_bass_sdk_guard import PATHS,verify_generated_sdk

def main():
 p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','sdk']);p.add_argument('--apk');p.add_argument('--out',required=True);a=p.parse_args()
 sha=git('rev-parse','HEAD').decode().strip();assert sha==os.environ.get('GITHUB_SHA',sha)
 assert os.environ.get('GITHUB_REF','refs/heads/experiment/bassmidi-r2-3-drum-ab')=='refs/heads/experiment/bassmidi-r2-3-drum-ab'
 assert git('rev-parse','HEAD^').decode().strip()==BASE,'R23 must be a direct child of mandatory R2-2'
 verify();assert not git('diff','--name-only','HEAD').strip(),'tracked source drift'
 extras=set(git('ls-files','--others','--exclude-standard','--','app/src/main').decode().splitlines())|set(git('ls-files','--others','--ignored','--exclude-standard','--','app/src/main').decode().splitlines())
 sdk=verify_generated_sdk(ROOT,extras)
 if a.phase=='before':assert not sdk
 else:assert set(sdk)==PATHS
 result={}
 if a.apk:
  from verify_u01_build import apk_report
  result.update(apk_report(a.apk))
  with zipfile.ZipFile(a.apk) as z:
   for name in ['kits.tsv.bin','kit_identity.json']:assert z.read('assets/r22/'+name)==(ROOT/'app/src/main/assets/r22'/name).read_bytes()
   for abi in ['arm64-v8a','armeabi-v7a']:
    raw=z.read(f'lib/{abi}/libyamaha_arranger_native.so')
    for sym in [b'nativeSetDrumABMode',b'nativeBindDrumAB',b'nativeDrumABReport',b'nativeMarkPcmSession']:assert sym in raw
 result.update(source_sha=sha,parent_sha=BASE,baseline_sha=BASE,production='R23_DIAGNOSTIC_DRUM_AB_DEFAULT_LEGACY',feature='R23_EXPLICIT_STOP_ONLY_AB',catalogUse='U1_OFF; R22_kit_identity_used_only_in_explicit_RESOLVED',full_regression_ci='BLOCKED historical profiles; not claimed PASS',deviceAudio='UNVERIFIED',phase=a.phase,sdk=sdk)
 Path(a.out).write_text(json.dumps(result,sort_keys=True,indent=2)+'\n');print(json.dumps(result,sort_keys=True))
if __name__=='__main__':main()

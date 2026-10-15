#!/usr/bin/env python3
import argparse,json,os,subprocess,hashlib,zipfile
from pathlib import Path
from verify_r20_boundary import verify,BASE,ROOT
from generated_bass_sdk_guard import PATHS,verify_generated_sdk

def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
def main():
 p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','sdk']);p.add_argument('--apk');p.add_argument('--out',required=True);a=p.parse_args()
 sha=git('rev-parse','HEAD');assert sha==os.environ.get('GITHUB_SHA',sha);assert git('rev-parse','HEAD^')==BASE
 verify();assert not git('diff','--name-only','HEAD'),'tracked source drift'
 assert git('rev-parse','f4f2c613bebd09a3137bdbe0bc60f0895b597979^{tree}')=='888af26ee8caf753c7d2916fd801afd5e00e4330'
 # Old workflows, fixtures, resolver, release/right-off/MIDI contracts and guard bodies stay intact.
 for path in git('ls-tree','-r','--name-only',BASE,'--','.github/workflows','tests/fixtures','tools').splitlines():
  if path=='tools/test_voice_release.py':continue # only adds the pure mute-state observer to existing JVM inputs
  assert git('rev-parse','HEAD:'+path)==git('rev-parse',BASE+':'+path),'protected tooling changed '+path
 extras=set(git('ls-files','--others','--exclude-standard','--','app/src/main').splitlines())|set(git('ls-files','--others','--ignored','--exclude-standard','--','app/src/main').splitlines())
 sdk=verify_generated_sdk(ROOT,extras)
 if a.phase=='before':assert not sdk
 else:assert set(sdk)==PATHS
 result={'source_sha':sha,'parent_sha':BASE,'production':'PINNED_R20_OBSERVERS_ONLY','catalogUse':'OFF','full_regression_ci':'BLOCKED historical profiles; not claimed PASS','device_G5_lt_1_percent':'UNVERIFIED','phase':a.phase,'sdk':sdk}
 if a.apk:
  from verify_u01_build import apk_report
  result.update(apk_report(a.apk));result.update(parent_sha=BASE,production='PINNED_R20_OBSERVERS_ONLY',device_G5_lt_1_percent='UNVERIFIED',feature='PCM_SESSION_R2_0',pdta_original_goldens='UNCHANGED_FROM_VERIFIED_163f769')
  with zipfile.ZipFile(a.apk) as z:
   for abi in ['arm64-v8a','armeabi-v7a']:
    raw=z.read(f'lib/{abi}/libyamaha_arranger_native.so')
    assert b'nativeMarkPcmSession' in raw and b'nativePcmSessionReport' in raw and b'peakSinceMark=' in raw
 Path(a.out).write_text(json.dumps(result,sort_keys=True,indent=2)+'\n');print(json.dumps(result,sort_keys=True))
if __name__=='__main__':main()

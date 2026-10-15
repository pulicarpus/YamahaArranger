#!/usr/bin/env python3
import argparse,os,json,subprocess,zipfile
from pathlib import Path
from verify_r22_boundary import verify,BASE,ROOT
from generated_bass_sdk_guard import PATHS,verify_generated_sdk
PARENT="1bd97e96b4e7d8e414a40a9265a62f817dbc5e08"

def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
def main():
 p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','sdk']);p.add_argument('--apk');p.add_argument('--out',required=True);a=p.parse_args()
 sha=git('rev-parse','HEAD');assert sha==os.environ.get('GITHUB_SHA',sha);assert git('rev-parse','HEAD^')==PARENT
 assert git('rev-parse',PARENT+'^')==BASE and git('rev-parse',PARENT+'^{tree}')=='0d18ce7db0bfbfbeb138d6a85bec054f720f6fe0'
 verify();assert not git('diff','--name-only','HEAD'),'tracked source drift'
 assert git('rev-parse',BASE+'^{tree}')=='acf73986fa93eec1a3d06ab7c619791594c46680'
 extras=set(git('ls-files','--others','--exclude-standard','--','app/src/main').splitlines())|set(git('ls-files','--others','--ignored','--exclude-standard','--','app/src/main').splitlines())
 sdk=verify_generated_sdk(ROOT,extras)
 if a.phase=='before':assert not sdk
 else:assert set(sdk)==PATHS
 result=dict(source_sha=sha,parent_sha=PARENT,baseline_sha=BASE,production='R22_METADATA_SHADOW_ONLY',catalogUse='OFF_FOR_PLAYBACK',full_regression_ci='BLOCKED historical profiles; not claimed PASS',deviceG1G6='UNVERIFIED',originalROSITAByteReplay='UNVERIFIED pending original style',phase=a.phase,sdk=sdk)
 if a.apk:
  from verify_u01_build import apk_report
  result.update(apk_report(a.apk));result.update(feature='R22_SHADOW_NO_AUDIO_CHANGE',parent_sha=PARENT,baseline_sha=BASE,production='R22_METADATA_SHADOW_ONLY',catalogUse='OFF_FOR_PLAYBACK')
  with zipfile.ZipFile(a.apk) as z:
   result['r22_assets']=[n for n in z.namelist() if n.startswith('assets/r22/')]
   print('R22 packaged asset inventory: '+json.dumps(result['r22_assets']))
   for name in ['kits.tsv.bin','kit_identity.json']:assert z.read('assets/r22/'+name)==(ROOT/'app/src/main/assets/r22'/name).read_bytes()
   for abi in ['arm64-v8a','armeabi-v7a']:
    raw=z.read(f'lib/{abi}/libyamaha_arranger_native.so');assert b'nativeDrumChannelSelection' in raw and b'nativeMarkPcmSession' in raw
 Path(a.out).write_text(json.dumps(result,sort_keys=True,indent=2)+'\n');print(json.dumps(result,sort_keys=True))
if __name__=='__main__':main()

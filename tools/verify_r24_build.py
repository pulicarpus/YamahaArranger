#!/usr/bin/env python3
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE='c75732f28fec58fbfbc7e626bc380b10a00722b8'
def git(*a):return subprocess.check_output(['git',*a],cwd=ROOT).decode().strip()
def verify():
 manifest=json.loads((ROOT/'tests/fixtures/r24/source_manifest.json').read_text());assert manifest['baseline']==BASE
 for path in git('ls-tree','-r','--name-only',BASE).splitlines():
  if path in manifest['production_sha256']:continue
  assert subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT)==(ROOT/path).read_bytes(),'unapproved baseline change '+path
 for path,h in manifest['production_sha256'].items():assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==h,path
 old=subprocess.check_output(['git','show',BASE+':app/src/main/cpp/bassmidi_player.cpp'],cwd=ROOT).decode();now=(ROOT/'app/src/main/cpp/bassmidi_player.cpp').read_text()
 # R23 resolver, mapping, loader/normalizer, CASM-independent code remains byte-identical.
 a=old.index('bool BassMidiPlayer::setDrumABMode(');b=now.index('bool BassMidiPlayer::setDrumABMode(')
 assert now[b:now.index('// R2-4:',b)].rstrip()==old[a:].rstrip(),'R23 resolver modified'
 # Strip only reviewed routing/cleanup hooks and appended implementation, compare all old code.
 prefix=now[:b]
 for hook in ['    clearDrumPunch();drumPunchMode_=0;\n','    if (sent) mirrorDrumPunch(channel,event,param);\n','    if (punchOff(channel,key)) return;\n','    if(drumPunchMode_)renderDrumPunch(out,numFrames);\n','    for(auto& lane:punchLanes_)if(lane.stream)BASS_ChannelSetAttribute(lane.stream,BASS_ATTRIB_MIDI_VOL,std::clamp(gain,0.0f,1.0f));\n']:
  assert prefix.count(hook)==1,hook;prefix=prefix.replace(hook,'')
 hook='    bool punchSent=false;\n    if(punchOn(channel,key,vel,punchSent)){pcmSessionNotes_.sent[channel]+=punchSent;logAudioPath(channel,key,vel,punchSent,punchSent?0:BASS_ErrorGetCode(),origin,"r24_isolated_target",0);return;}\n'
 assert prefix.count(hook)==1;prefix=prefix.replace(hook,'');assert prefix==old[:a],'old audio code changed beyond declared hooks'
 for path,start,end in [('app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt','    // Diagnostic admission only;','    private fun applyDrumAB'),('app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt','    fun setDrumPunch(mode:Int)','    fun setDrumABMode')]:
  x=(ROOT/path).read_text();x=x[:x.index(start)]+x[x.index(end,x.index(start)):];assert x==subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode(),'clock/lifecycle/REG body drift'
 from generated_bass_sdk_guard import PATHS
 original=set(git('ls-tree','-r','--name-only',BASE,'app/src/main').splitlines())
 actual={str(p.relative_to(ROOT)) for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}-PATHS
 assert actual==original|{'app/src/main/cpp/drum_punch.h'},'unexpected production file'
 return manifest

def main():
 p=argparse.ArgumentParser();p.add_argument('--phase',choices=['before','sdk']);p.add_argument('--apk');p.add_argument('--out',required=True);a=p.parse_args();verify()
 sha=git('rev-parse','HEAD');assert sha==os.environ.get('GITHUB_SHA',sha)
 parent=git('rev-parse','HEAD^');reviewed='bc710fabbd7e78a364dfca7fdbacd18872b207c5'
 assert parent in (BASE,reviewed),'unexpected R24 parent'
 if parent==reviewed:
  assert git('rev-parse',reviewed+'^')==BASE,'mandatory R23 ancestry mismatch'
  assert not git('diff','--name-only',reviewed,'HEAD','--','app/src/main'),'tooling follow-up changed production'
  assert set(git('diff','--name-only',reviewed,'HEAD').splitlines())=={'tools/test_r24_safety.py','tools/verify_r24_build.py','.github/workflows/bassmidi-r24-drum-punch.yml','docs/r24/IMPLEMENTATION.md'},'unreviewed tooling follow-up'
 assert os.environ.get('GITHUB_REF','refs/heads/experiment/bassmidi-r2-4-drum-punch')=='refs/heads/experiment/bassmidi-r2-4-drum-punch'
 assert not git('diff','--name-only','HEAD'),'tracked drift'
 from generated_bass_sdk_guard import verify_generated_sdk,PATHS
 extras=set(git('ls-files','--others','--exclude-standard','app/src/main').splitlines())|set(git('ls-files','--others','--ignored','--exclude-standard','app/src/main').splitlines());sdk=verify_generated_sdk(ROOT,extras)
 assert (not sdk) if a.phase=='before' else set(sdk)==PATHS
 result={}
 if a.apk:
  from verify_u01_build import apk_report
  result=apk_report(a.apk)
  with zipfile.ZipFile(a.apk) as z:
   for abi in ['arm64-v8a','armeabi-v7a']:
    lib=z.read('lib/'+abi+'/libyamaha_arranger_native.so');assert b'nativeSetDrumPunch' in lib and b'nativeDrumPunchReport' in lib
   for name in ['kits.tsv.bin','kit_identity.json']:assert z.read('assets/r22/'+name)==(ROOT/'app/src/main/assets/r22'/name).read_bytes()
 result.update(source_sha=sha,parent_sha=parent,baseline_sha=BASE,production='R24_DIAGNOSTIC_DEFAULT_ORIGINAL',catalogUse='U1_OFF_R23_RESOLVED_unchanged',historical_overhead_gate='NOT_RUN_SEPARATE_OFFICIAL_GATE_UNCHANGED',full_regression_ci='BLOCKED_historical_profiles',device_audio='UNVERIFIED',phase=a.phase,sdk=sdk)
 Path(a.out).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()

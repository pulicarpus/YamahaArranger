#!/usr/bin/env python3
"""Pin the reviewed observer delta; restore-and-compare all native playback code and style scheduler."""
from pathlib import Path
import hashlib,json,re,subprocess
ROOT=Path(__file__).resolve().parents[1];BASE='163f769bf056f0dd0593f78ab20fbbe700dcb56f'
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT)
def verify():
 manifest=json.loads((ROOT/'tests/fixtures/r20_source_manifest.json').read_text());assert manifest['base']==BASE
 original=set(subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main'],cwd=ROOT,text=True).splitlines())
 physical={str(p.relative_to(ROOT)):p for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}
 # Generated official SDK input identities are verified separately after host gates.
 from generated_bass_sdk_guard import PATHS
 physical={k:v for k,v in physical.items() if k not in PATHS}
 changed=set()
 for path in original|set(physical):
  before=old(path) if path in original else None;after=physical[path].read_bytes() if path in physical else None
  if before!=after:changed.add(path)
 assert changed==set(manifest['production_sha256']),('production scope drift',changed)
 for path,sha in manifest['production_sha256'].items():assert hashlib.sha256(physical[path].read_bytes()).hexdigest()==sha,'reviewed observer source drift '+path
 path='app/src/main/cpp/bassmidi_player.cpp';a=physical[path].read_text();b=old(path).decode()
 for line in ['    ++pcmSessionNotes_.attempts[std::clamp(channel,0,15)];\n','        pcmSessionNotes_.sent[channel]+=compatibleSent;\n','    pcmSessionNotes_.sent[channel]+=sent;\n','    pcmSession_.observe(headroom_.current.mix,headroom_.current.frame,fontMappingGeneration_);\n']:
  assert a.count(line)==1;a=a.replace(line,'')
 start=a.index('uint64_t BassMidiPlayer::markPcmSession(');end=a.index('std::string BassMidiPlayer::noteZoneReport()',start)
 added=a[start:end]
 assert not any(x in added for x in ['StreamGetChannel','StreamEvent(','GetData(','FontInit','applyFonts(','ensureEngine(']),'mark/export can alter engine'
 a=a[:start]+a[end:];assert a==b,'non-observer native playback delta'
 path='app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'
 a=physical[path].read_text();line='com.yourapp.yamahaarranger.audio.PcmSessionControls.observeMute(channel,override.muted);';assert a.count(line)==1
 assert a.replace(line,'')==old(path).decode(),'style scheduler/mixer behavior changed'
 a=(ROOT/'tools/test_voice_release.py').read_text();added="    sources += [ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/PcmSessionControls.kt']\n"
 assert a.count(added)==1 and a.replace(added,'')==old('tools/test_voice_release.py').decode(),'existing JVM assertions changed'
 print('R20 BOUNDARY PASS: native playback byte-identical after four counter/observer lines removed; style byte-identical after pure mute observation; all other production pinned')
if __name__=='__main__':verify()

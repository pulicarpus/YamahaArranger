#!/usr/bin/env python3
"""Pin the reviewed R2-3 delta; preserve every baseline guard, loader and protected subsystem."""
import hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE='d456d733c788b46ae06fe09c8b687eeb4915d554'
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT)
def verify():
 assert git('rev-parse',BASE+'^{tree}').decode().strip()=='7cac5145286892030ba88a22086c53bd4d0ed08b'
 manifest=json.loads((ROOT/'tests/fixtures/r23/source_manifest.json').read_text());assert manifest['baseline']==BASE
 paths=git('ls-tree','-r','--name-only',BASE,'--','app/src/main','app/src/test','tools','tests','catalog','.github/workflows').decode().splitlines()
 for path in paths:
  actual=(ROOT/path).read_bytes();previous=git('show',BASE+':'+path)
  if path in manifest.get('reviewed_fixture_sha256',{}):
   assert hashlib.sha256(actual).hexdigest()==manifest['reviewed_fixture_sha256'][path],path
   start='    @Test fun externalRecallWhileRealStyleSequencerIsPlayingKeepsItsNotesAndClock()'
   def assertions(raw):
    text=raw.decode();block=text[text.index(start):text.index('    @Test fun M4_',text.index(start))]
    return block[block.index('            assertEquals(RecallCompletion.COMPLETED'):].replace('finally { recallApplied.countDown();seq.stop();r.close() }','finally { seq.stop();r.close() }')
   assert assertions(actual)==assertions(previous),'recall assertions changed'
  elif path in manifest['production_sha256']:assert hashlib.sha256(actual).hexdigest()==manifest['production_sha256'][path],path
  else:assert actual==previous,'unapproved baseline change '+path
 from generated_bass_sdk_guard import PATHS
 original=set(p for p in paths if p.startswith('app/src/main/'))
 physical={str(p.relative_to(ROOT)) for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}-PATHS
 assert physical==original,'unapproved new/deleted production file'
 # Independently retain exact function bodies, not just a manifest of approved files.
 path='app/src/main/cpp/bassmidi_player.cpp';now=(ROOT/path).read_text();before=git('show',BASE+':'+path).decode()
 boundaries=[('bool BassMidiPlayer::loadRole(', 'bool BassMidiPlayer::load('),
 ('bool BassMidiPlayer::loadMelodyFallback(', 'void BassMidiPlayer::unload('),
 ('bool BassMidiPlayer::normalizeMelodySf2(', 'void BassMidiPlayer::rebuildDrumPresetCache('),
 ('void BassMidiPlayer::setChannelMixer(', 'void BassMidiPlayer::setChannelExpression('),
 ('void BassMidiPlayer::setChannelExpression(', 'std::string BassMidiPlayer::'),
 ('void BassMidiPlayer::render(', 'void BassMidiPlayer::armChordDiagnostic(')]
 for start,end in boundaries:
  if start not in before:raise AssertionError(start)
  a=before.index(start);b=before.find(end,a+len(start));assert b>a,(start,end)
  aa=now.index(start);bb=now.find(end,aa+len(start));assert now[aa:bb]==before[a:b],start
 return manifest
if __name__=='__main__':verify();print('R23 BOUNDARY PASS; historical full regression CI remains BLOCKED')

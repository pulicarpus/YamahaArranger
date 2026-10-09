#!/usr/bin/env python3
import hashlib,json,subprocess,tempfile
from pathlib import Path
from f12_source_guard import ROOT,MANIFEST,baseline_bytes
p=json.loads(MANIFEST.read_text())
assert p['baseline']=='a3dc588b3aea2622a020ccbc95800f9cfbc6723e'
expected={'app/src/main/cpp/audio_engine.cpp','app/src/main/cpp/bassmidi_player.cpp','app/src/main/cpp/chord_change_diagnostic.h','app/src/main/java/com/yourapp/yamahaarranger/arranger/ChordChangeDiagnostic.kt','app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'}
assert {f for f in p['changes'] if f.startswith('app/src/main/')}==expected
for f,sha in p['baseline_files'].items():
 raw=(ROOT/f).read_bytes();restored=baseline_bytes(f,raw)
 assert hashlib.sha256(restored).hexdigest()==sha,'Baseline drift: '+f
for f in p['changes']:
 try:baseline_bytes(f,(ROOT/f).read_bytes()+b'unapproved')
 except AssertionError:pass
 else:raise AssertionError('Mutation accepted: '+f)
with tempfile.TemporaryDirectory() as tmp:
 binary=Path(tmp)/'timing'
 subprocess.run(['g++','-std=c++17','-O2','-pthread','-I',str(ROOT/'app/src/main/cpp'),str(ROOT/'tests/f12_timing_test.cpp'),'-o',str(binary)],check=True)
 subprocess.run([str(binary)],check=True)
print('F12_SOURCE_GUARD PASS exact #811 recovery; all historical baseline tracked files protected; every overlay mutation rejected')

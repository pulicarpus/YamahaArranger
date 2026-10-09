#!/usr/bin/env python3
"""Real Linux synth OFF/ON parity, not Android timing/device proof."""
from pathlib import Path
import hashlib,subprocess,tempfile,json
from f12_source_guard import baseline_bytes, MANIFEST
ROOT=Path(__file__).resolve().parents[1]
bass=Path('/tmp/bass-linux');midi=Path('/tmp/bassmidi-linux')
assert (bass/'c/bass.h').is_file() and (midi/'c/bassmidi.h').is_file(),'Real Linux SDK unavailable'
with tempfile.TemporaryDirectory() as tmp:
 t=Path(tmp);source=t/'probe.cpp';s=(ROOT/'tests/role_pcm_real_bass_test.cpp').read_text()
 s=s.replace('BassMidiPlayer p;','BassMidiPlayer p; if(argc==3) f12::timing.arm();').replace('if(argc!=2)return 2;','if(argc!=2 && argc!=3)return 2;')
 s=s.rstrip()[:-1]+'\n f12::timing.stop(); return 0;\n}\n';source.write_text(s)
 binary=t/'probe';cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS=1']
 for include in [bass/'c',midi/'c',ROOT/'tests/mocks',ROOT/'app/src/main/cpp',ROOT/'tests']:cmd+=['-I',str(include)]
 cmd += [str(source),str(ROOT/'app/src/main/cpp/bassmidi_player.cpp')]
 for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
 cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-lbassmidi','-lbass','-o',str(binary)]
 subprocess.run(cmd,check=True);proof={}
 for mode in ['off','on']:
  folder=t/mode;folder.mkdir();subprocess.run([str(binary),str(folder)]+(['arm'] if mode=='on' else []),check=True)
  proof[mode]={n:hashlib.sha256((folder/n).read_bytes()).hexdigest() for n in ['pcm.bin','midi.bin']}
 baseline=t/'baseline-cpp';baseline.mkdir()
 for path in json.loads(MANIFEST.read_text())['baseline_files']:
  if path.startswith('app/src/main/cpp/'):
   dst=baseline/Path(path).relative_to('app/src/main/cpp');dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(baseline_bytes(path,(ROOT/path).read_bytes()))
 baselineSource=t/'baseline-probe.cpp';baselineSource.write_text(s.replace('if(argc==3) f12::timing.arm();','').replace('f12::timing.stop();',''))
 baselineBinary=t/'baseline-probe';baselineCmd=[str(baselineSource) if x==str(source) else str(baseline/'bassmidi_player.cpp') if x==str(ROOT/'app/src/main/cpp/bassmidi_player.cpp') else str(baseline) if x==str(ROOT/'app/src/main/cpp') else str(baselineBinary) if x==str(binary) else x for x in cmd]
 subprocess.run(baselineCmd,check=True);folder=t/'baseline';folder.mkdir();subprocess.run([str(baselineBinary),str(folder)],check=True)
 proof['baseline811']={n:hashlib.sha256((folder/n).read_bytes()).hexdigest() for n in ['pcm.bin','midi.bin']}
 assert proof['baseline811']==proof['off']==proof['on'],proof
 print('F12_LINUX_REAL_PCM PASS #811/OFF/ON PCM and MIDI BYTE_IDENTICAL',proof)

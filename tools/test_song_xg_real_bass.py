#!/usr/bin/env python3
"""Real BASSMIDI (Linux x86_64): MIDI SONG internal stream with Yamaha XG setup SysEx (vibrato depth, drum part). Usage: test_song_xg_real_bass.py /path/bass /path/bassmidi --out DIR"""
import argparse, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(); p.add_argument('bass'); p.add_argument('bassmidi'); p.add_argument('--out', required=True); a = p.parse_args()
bass, midi, out = Path(a.bass).resolve(), Path(a.bassmidi).resolve(), Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=True)
exe = out / 'song-xg-real'
cmd = ['g++', '-std=c++17', '-O2', '-I', str(bass / 'c'), '-I', str(midi / 'c'), '-I', str(ROOT / 'tests'), str(ROOT / 'tests/song_xg_real_bass_test.cpp')]
for sdk in (bass, midi): cmd += ['-L', str(sdk / 'libs/x86_64'), '-Wl,-rpath,' + str(sdk / 'libs/x86_64')]
subprocess.run(cmd + ['-lbassmidi', '-lbass', '-o', str(exe)], check=True)
ev = ROOT / 'build/recorder-evidence'   # V1C: recorded files from MidiRecorderTest / FullArrangerRecorderRtTest, if they ran before
res = subprocess.run([str(exe), str(out)] + ([str(ev)] if ev.is_dir() else []), capture_output=True, text=True)
(out / 'song-xg-real.log').write_text(res.stdout + res.stderr); print(res.stdout, res.stderr[-400:])
raise SystemExit(res.returncode)

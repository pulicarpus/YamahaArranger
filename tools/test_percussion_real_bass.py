#!/usr/bin/env python3
"""Test the actual production adapter with the Linux x86_64 BASS SDKs.

Usage: python3 tools/test_percussion_real_bass.py /path/to/bass /path/to/bassmidi
The roots contain c/bass*.h and libs/x86_64/libbass*.so. Uses synthetic SF2 PCM,
not tester samples; validates native routing, first-note readback and rendering.
"""
from pathlib import Path
import subprocess, sys, tempfile

root = Path(__file__).resolve().parents[1]
bass, midi = (Path(p).resolve() for p in sys.argv[1:])
with tempfile.TemporaryDirectory(prefix='yamaha-real-percussion-') as temp:
    temp = Path(temp)
    exe = temp / 'real-percussion'
    command = ['g++', '-std=c++17', '-O2', '-pthread', '-DYAMAHA_COMPATIBLE_PERCUSSION=1']
    for include in [bass/'c', midi/'c', root/'tests/mocks', root/'app/src/main/cpp', root/'tests']:
        command += ['-I', str(include)]
    command += [str(root/'tests/percussion_real_bass_test.cpp'), str(root/'app/src/main/cpp/bassmidi_player.cpp')]
    for sdk in [bass, midi]:
        command += ['-L', str(sdk/'libs/x86_64'), '-Wl,-rpath,'+str(sdk/'libs/x86_64')]
    command += ['-Wl,--wrap=BASS_MIDI_StreamCreate', '-Wl,--wrap=BASS_MIDI_StreamEvent', '-lbassmidi', '-lbass', '-o', str(exe)]
    subprocess.run(command, check=True)
    result = subprocess.run([str(exe), str(temp)], capture_output=True, text=True, check=True)
    output = root/'build/percussion-real-bass-proof.txt'
    output.parent.mkdir(exist_ok=True)
    output.write_text(result.stdout)
    print(result.stdout)

    response = temp/'mix-response'
    response_command = ['g++','-std=c++17','-O2','-I',str(bass/'c'),'-I',str(midi/'c'),'-I',str(root/'tests'),str(root/'tests/mix_response_real_bass_test.cpp')]
    for sdk in [bass,midi]:
        response_command += ['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
    response_command += ['-lbassmidi','-lbass','-o',str(response)]
    subprocess.run(response_command,check=True)
    measured = subprocess.run([str(response),str(temp)],check=True,capture_output=True,text=True)
    (root/'build/mix-response-proof.txt').write_text(measured.stdout)
    print(measured.stdout)

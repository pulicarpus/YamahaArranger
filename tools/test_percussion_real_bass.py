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

    comparison={}
    for enabled in [0,1]:
        directory=temp/('role-'+str(enabled));directory.mkdir()
        binary=directory/'role-pcm'
        cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS='+str(enabled)]
        for include in [bass/'c',midi/'c',root/'tests/mocks',root/'app/src/main/cpp',root/'tests']:cmd+=['-I',str(include)]
        cmd += [str(root/'tests/role_pcm_real_bass_test.cpp'),str(root/'app/src/main/cpp/bassmidi_player.cpp')]
        for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
        cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-lbassmidi','-lbass','-o',str(binary)]
        subprocess.run(cmd,check=True)
        proof=subprocess.run([str(binary),str(directory)],check=True,capture_output=True,text=True)
        comparison[enabled]={'pcm':(directory/'pcm.bin').read_bytes(),'result':proof.stdout,'midi':(directory/'midi.bin').read_bytes()}
        if enabled:(root/'build/role-pcm-real-report.txt').write_text((directory/'role-report.txt').read_text())
    import array,math
    before=array.array('f');before.frombytes(comparison[0]['pcm'])
    after=array.array('f');after.frombytes(comparison[1]['pcm'])
    assert len(before)==len(after) and all(math.isfinite(v) for v in before) and all(math.isfinite(v) for v in after)
    delta=[a-b for a,b in zip(before,after)]
    maximum=max(map(abs,delta));rmsError=math.sqrt(sum(v*v for v in delta)/len(delta))
    peak=max(max(map(abs,before)),max(map(abs,after)))
    epsilon=2**-23 # float32 resolution; read-only channel streams regroup floating-point sums.
    bound=4*epsilon*peak
    gainError=abs(math.sqrt(sum(v*v for v in after)/sum(v*v for v in before))-1)
    assert maximum<=bound and rmsError<=epsilon*peak and gainError<=epsilon,('PCM difference exceeds float32 rounding',maximum,rmsError,gainError,bound)
    assert comparison[0]['midi']==comparison[1]['midi'],'Meters changed production MIDI routing/controller events'
    result=comparison[0]['result']+comparison[1]['result']+f'ROLE_PCM_REAL PCM_BYTE_IDENTICAL={comparison[0]["pcm"]==comparison[1]["pcm"]} maxFloatDifference={maximum} rmsFloatDifference={rmsError} float32Bound={bound} rmsGainError={gainError} MIDI_trace_BYTE_IDENTICAL=true no_keyboard_or_drum_taps=true synthetic_not_device_balance\n'
    (root/'build/role-pcm-real-proof.txt').write_text(result)
    print(result)

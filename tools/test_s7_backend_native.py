#!/usr/bin/env python3
"""Isolated S7 probe. Never downloads SDKs or modifies production/goldens.

Always run production player with API recorder. Also run real Linux SDK +
synthetic SF2 if existing trusted SDK roots are available (CI prepares these).
Native result without those roots is BLOCKED, never a passing PCM test.
"""
import argparse, hashlib, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bass',type=Path,default=Path('/tmp/bass-linux'))
    p.add_argument('--bassmidi',type=Path,default=Path('/tmp/bassmidi-linux'))
    p.add_argument('--output',type=Path,default=ROOT/'build/s7-native')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    result={'api_recorder':'UNRUN','real_sdk':'UNRUN','device_audio':'UNRUN','native_voice_handles':'UNKNOWN'}
    def run(real,directory):
        exe=directory/('real' if real else 'recorder')
        cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        if real:
            cmd+=['-DS7_REAL_BASS=1','-I',str(a.bass/'c'),'-I',str(a.bassmidi/'c')]
        for inc in ('tests/mocks','app/src/main/cpp','tests'):cmd+=['-I',str(ROOT/inc)]
        cmd+=[str(ROOT/'tests/s7_backend_contract_test.cpp'),str(ROOT/'app/src/main/cpp/bassmidi_player.cpp')]
        if real:
            for sdk in (a.bass,a.bassmidi):cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
            cmd+=['-Wl,--wrap=BASS_MIDI_StreamCreate','-Wl,--wrap=BASS_MIDI_StreamEvent','-lbassmidi','-lbass']
        cmd+=['-o',str(exe)];subprocess.run(cmd,check=True)
        observed=subprocess.run([str(exe),str(directory)],text=True,capture_output=True,check=True)
        print(observed.stdout,end='');return observed.stdout
    try:
        with tempfile.TemporaryDirectory(prefix='s7-backend-') as tmp:
            result['recorder_output']=run(False,Path(tmp));result['api_recorder']='PASS'
            required=[a.bass/'c/bass.h',a.bassmidi/'c/bassmidi.h',a.bass/'libs/x86_64/libbass.so',a.bassmidi/'libs/x86_64/libbassmidi.so']
            if all(f.is_file() for f in required):
                result['sdk_sha256']={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in required}
                result['real_output']=run(True,Path(tmp));result['real_sdk']='PASS'
            else:
                result['real_sdk']='BLOCKED';result['reason']='Existing real Linux SDK headers/libraries unavailable; no download or network bypass'
                print('S7_REAL_BASS BLOCKED: real Linux SDK unavailable; pairing/PCM UNKNOWN')
    except subprocess.CalledProcessError as e:
        result['failure']=str(e);result['real_sdk' if result['api_recorder']=='PASS' else 'api_recorder']='FAIL'
        if e.stdout:print(e.stdout)
        if e.stderr:print(e.stderr)
        raise
    finally:
        (a.output/'proof.json').write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__':main()

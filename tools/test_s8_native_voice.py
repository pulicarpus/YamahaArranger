#!/usr/bin/env python3
"""Isolated S8 probe. Never downloads SDKs or modifies production/goldens.

Run unchanged production player with real Linux SDK +
synthetic signature SF2 if existing SDK roots are available (CI prepares these).
Native result without those roots is BLOCKED, never a passing PCM test.
"""
import argparse, hashlib, json, os, struct, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bass',type=Path,default=Path('/tmp/bass-linux'))
    p.add_argument('--bassmidi',type=Path,default=Path('/tmp/bassmidi-linux'))
    p.add_argument('--output',type=Path,default=ROOT/'build/s8-native')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    result={'api_recorder':'UNRUN','real_sdk':'UNRUN','device_audio':'UNRUN','native_voice_handles':'UNKNOWN'}
    def run(real,directory,source_name="s8_native_voice_test.cpp"):

        exe=directory/('real' if real else 'recorder')
        cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        if real:
            cmd+=['-DS8_REAL_BASS=1','-I',str(a.bass/'c'),'-I',str(a.bassmidi/'c')]
        for inc in ('tests/mocks','app/src/main/cpp','tests'):cmd+=['-I',str(ROOT/inc)]
        cmd+=[str(ROOT/'tests'/source_name),str(ROOT/'app/src/main/cpp/bassmidi_player.cpp')]
        if real:
            for sdk in (a.bass,a.bassmidi):cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
            cmd+=['-Wl,--wrap=BASS_MIDI_StreamCreate','-Wl,--wrap=BASS_MIDI_StreamEvent','-lbassmidi','-lbass']
        cmd+=['-o',str(exe)];subprocess.run(cmd,check=True)
        observed=subprocess.run([str(exe),str(directory)],text=True,capture_output=True,check=True)
        print(observed.stdout,end='');return observed.stdout
    try:
        with tempfile.TemporaryDirectory(prefix='s8-backend-') as tmp:
            result['api_recorder']='UNRUN' # S7 recorder remains covered separately
            directory=Path(tmp)
            source=directory/'fixture.cpp'
            source.write_text('#include "s8_signature_fixture.h"\nint main(int argc,char** argv){s8_fixture::write(argv[1],-12000);s8_fixture::write(argv[1],0);}')
            exe=directory/'fixture'
            subprocess.run(['g++','-std=c++17','-I',str(ROOT/'tests'),str(source),'-o',str(exe)],check=True)
            subprocess.run([str(exe),tmp],check=True)
            for font in directory.glob('*.sf2'):
                data=font.read_bytes();chunks={}
                def scan(start,end):
                    while start+8<=end:
                        tag=data[start:start+4];n=struct.unpack_from('<I',data,start+4)[0]
                        assert start+8+n<=end,'RIFF chunk out of bounds'
                        if tag==b'LIST':scan(start+12,start+8+n)
                        else:chunks[tag]=data[start+8:start+8+n]
                        start+=8+n+(n&1)
                scan(12,len(data))
                for bag,gen in [(b'pbag',b'pgen'),(b'ibag',b'igen')]:
                    index=struct.unpack_from('<H',chunks[bag],len(chunks[bag])-4)[0]
                    assert index==len(chunks[gen])//4-1,'Missing terminal generator record'
                assert len(chunks[b'shdr'])==138,'Expected two signatures + EOS'
            result['fixture_integrity']='PASS'
            print('S8_FIXTURE RIFF/terminal-generator bounds PASS; not SDK acceptance or PCM evidence')
            required=[a.bass/'c/bass.h',a.bassmidi/'c/bassmidi.h',a.bass/'libs/x86_64/libbass.so',a.bassmidi/'libs/x86_64/libbassmidi.so']
            if all(f.is_file() for f in required):
                result['sdk_sha256']={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in required}
                result['real_output']=run(True,Path(tmp));result['real_sdk']='PASS'
                result['s7_tail_control_output']=run(True,Path(tmp),'s8_s7_release_tail_test.cpp')
                assert 'S8_S7_TAIL' in result['s7_tail_control_output'],'Real tail control must execute'
                result['pcm_metrics']=[line for line in result['real_output'].splitlines() if line.startswith('S8_')]
                result['classification_note']='Signature evidence only; not a native handle or device test'
            else:
                result['real_sdk']='BLOCKED';result['reason']='Existing real Linux SDK headers/libraries unavailable; no download or network bypass'
                print('S8_REAL_BASS BLOCKED: real Linux SDK unavailable; pairing/PCM UNKNOWN')
                if os.environ.get('GITHUB_ACTIONS')=='true':
                    raise AssertionError('CI must provide real SDK roots prepared by its native step')
    except (subprocess.CalledProcessError, AssertionError) as e:
        result['failure']=str(e);result['real_sdk']='FAIL'
        if getattr(e,"stdout",None):print(e.stdout)
        if getattr(e,"stderr",None):print(e.stderr)
        raise
    finally:
        (a.output/'proof.json').write_text(json.dumps(result,indent=2)+'\n')
        # Public, commit-specific native evidence without changing workflow or
        # requiring log/API credentials. Only the official runner summary file.
        summary=os.environ.get('GITHUB_STEP_SUMMARY')
        if os.environ.get('GITHUB_ACTIONS')=='true' and summary:
            with Path(summary).open('a') as stream:
                stream.write('\n## S8 F03 isolated backend probe\n\n')
                stream.write('Real SDK synthetic PCM is separate from device audio and native voice handles.\n\n```json\n')
                stream.write(json.dumps(result,indent=2)+'\n```\n')

if __name__=='__main__':main()

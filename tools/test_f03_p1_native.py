#!/usr/bin/env python3
"""Isolated P1 Linux PCM controls, never Android/Yamaha certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'build/f03-p1-native')
    parser.add_argument('--bass', type=Path, default=Path('/tmp/bass-linux'))
    parser.add_argument('--bassmidi', type=Path, default=Path('/tmp/bassmidi-linux'))
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT/'tests/fixtures/f03_p1_baseline_sha256.json').read_text())
    def integrity():
        for path, expected in manifest['files'].items():
            if hashlib.sha256((ROOT/path).read_bytes()).hexdigest() != expected:
                raise AssertionError('Baseline changed: '+path)
    integrity()
    proof = {'probe_execution': 'UNRUN', 'oldest_release_observation': 'UNKNOWN',
             'yamaha_pairing': 'BLOCKED', 'android_runtime': 'UNRUN', 'psr_e343': 'UNRUN',
             'native_voice_handles': 'UNKNOWN', 'production_f03_patch': 'NO_GO',
             'baseline_commit': manifest['commit'], 'protected_files': len(manifest['files'])}
    required = [args.bass/'c/bass.h', args.bassmidi/'c/bassmidi.h',
                args.bass/'libs/x86_64/libbass.so', args.bassmidi/'libs/x86_64/libbassmidi.so']
    try:
        if not all(p.is_file() for p in required):
            proof['probe_execution'] = 'BLOCKED'
            proof['reason'] = 'Real Linux SDK headers/libraries unavailable; no simulated PCM substitute'
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                raise AssertionError('Existing CI native step must provide the real Linux SDK')
            return
        proof['sdk_sha256'] = {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in required}
        with tempfile.TemporaryDirectory(prefix='f03-p1-native-') as tmp:
            exe = Path(tmp)/'probe'
            command = ['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1',
                       '-I',str(args.bass/'c'),'-I',str(args.bassmidi/'c')]
            for inc in ('tests/mocks','app/src/main/cpp','tests'):
                command += ['-I',str(ROOT/inc)]
            command += [str(ROOT/'tests/f03_p1_native_contract_test.cpp'),
                        str(ROOT/'app/src/main/cpp/bassmidi_player.cpp')]
            for sdk in (args.bass,args.bassmidi):
                command += ['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
            command += ['-Wl,--wrap=BASS_MIDI_StreamCreate','-Wl,--wrap=BASS_MIDI_StreamEvent',
                        '-lbassmidi','-lbass','-o',str(exe)]
            subprocess.run(command,check=True)
            result = subprocess.run([str(exe),tmp],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            print(result.stdout);proof['native_stdout']=result.stdout
            (args.output/'native.log').write_text(result.stdout)
            if result.returncode: raise AssertionError('Native probe failed: '+str(result.returncode))
            rows = []
            for line in result.stdout.splitlines():
                if line.startswith('P1_PCM '): rows.append(dict(x.split('=',1) for x in line.split()[1:]))
                if line.startswith('P1_SUMMARY '): proof['summary']=dict(x.split('=',1) for x in line.split()[1:])
                if line.startswith('P1_SDK '): proof['sdk_versions']=dict(x.split('=',1) for x in line.split()[1:])
            summary=proof.get('summary',{})
            assert summary.get('trials')=='48' and summary.get('doubleOffTrials')=='48', 'Incomplete experiment'
            assert len(rows)==132, 'Missing PCM controls'
            assert summary.get('contradicted')=='0', 'Vendor contract mismatch'
            proof['probe_execution']='PASS'
            proof['pcm_rows']=rows
            proof['oldest_release_observation']='OBSERVED' if summary['unknown']=='0' else 'UNKNOWN'
            proof['scope']='Linux x86_64 pinned SDK, synthetic sustained two-zone SF2, two voices, 48kHz, short release'
    except Exception as error:
        proof['probe_execution']='FAIL';proof['failure']=str(error)
        raise
    finally:
        integrity();proof['baseline_integrity']='PASS'
        (args.output/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
        print('F03_P1_PROOF '+json.dumps({k:v for k,v in proof.items() if k not in ('native_stdout','pcm_rows','sdk_sha256')}))

if __name__=='__main__': main()

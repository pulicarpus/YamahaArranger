#!/usr/bin/env python3
"""Production player + JVM contracts; separate recorder, Linux PCM and Android evidence."""
import argparse, hashlib, json, re, subprocess, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT = 'c75122daf5d1077db55d50d1c261144fde59af4a'

def run(command, **kwargs):
    subprocess.run(command, cwd=ROOT, check=True, **kwargs)

def frozen_style():
    protected = ['app/src/main/java/com/yourapp/yamahaarranger/arranger',
                 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',
                 'app/src/main/java/com/yourapp/chord', 'app/src/main/java/com/yourapp/style',
                 'app/src/main/java/com/yourapp/yamahaarranger/style',
                 'app/src/main/cpp/style_parser.cpp', 'app/src/main/cpp/style_parser.h',
                 'app/src/main/cpp/smf_reader.cpp', 'app/src/main/cpp/smf_reader.h']
    review_path=ROOT/'tests/fixtures/right_off_brain_review.json'
    if review_path.exists():
        from right_off_style_guard import verify_brain
        review=json.loads(review_path.read_text())
        assert review['keyboard_checkpoint']=='313bc115bb9f7a8b6ed6a6bd5da192fda7b38d3f', 'wrong keyboard checkpoint'
        brain=review['brain_path']
        before=subprocess.check_output(['git','show',CHECKPOINT+':'+brain],cwd=ROOT)
        verify_brain(before,(ROOT/brain).read_bytes(),review)
        for path,digest in review['preserved_files'].items():
            original=subprocess.check_output(['git','show',review['keyboard_checkpoint']+':'+path],cwd=ROOT)
            assert hashlib.sha256(original).hexdigest()==digest, 'legacy guard/RELEASE review baseline changed: '+path
            assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest, 'legacy guard/RELEASE file changed: '+path
        protected.remove(brain) # whole-file reviewed hash AND style-region byte checks above
        run(['python3','tools/test_right_off_style_guard.py'])
        print('BRAIN keyboard-only reviewed SHA PASS; style paths/RELEASE/legacy guards byte-identical',flush=True)
    run(['git','diff','--exit-code',CHECKPOINT,'--',*protected])
    print('STYLE_CHECKPOINT byte-identical scheduler/CASM/clock/Fill/Intro/Ending and Brain style paths PASS', flush=True)

def jvm(output, fetch):
    deps = ROOT/'build/sff1-jvm/dependencies';deps.mkdir(parents=True,exist_ok=True)
    entries=json.loads((ROOT/'tests/fixtures/sff1_jvm_dependencies.json').read_text())['dependencies']
    jars=[]
    for entry in entries:
        path=deps/entry['filename']
        if not path.exists():
            if not fetch: raise RuntimeError('Missing pinned JVM dependencies; use --fetch-dependencies')
            run(['curl','-fsSL','--max-time','120',entry['url'],'-o',str(path)])
        assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256'], path.name
        jars.append(path)
    raw=(ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt').read_text()
    methods=re.findall(r'    external fun (.*?)(?=\n    external fun|\n})',raw,re.S)
    bridge=output/'NativeAudioBridge.kt'
    bridge.write_text('package com.yourapp.yamahaarranger.audio\nimport java.nio.ByteBuffer\nopen class NativeAudioBridge {\n'+
                      ''.join('    open fun '+x.strip()+' { error("unconfigured JNI boundary") }\n' for x in methods)+'}\n')
    log=output/'DebugLog.kt'
    log.write_text('package com.yourapp.yamahaarranger.ui\nobject DebugLog { '+
                   ';'.join('fun '+f+'(s:String){}' for f in ['add','setLongText','traceAudio','traceError','traceMidi'])+' }\n')
    sources=[ROOT/'app/src/main/java/com/yourapp/ui/CompactMixerLayout.kt', ROOT/'app/src/test/java/com/yourapp/ui/CompactMixerLayoutTest.kt',bridge,log,ROOT/'app/src/main/java/com/yourapp/audio/SampleProvider.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/SoundFontPresetCatalog.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/KeyboardPart.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/ReleaseControlState.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/RightVoiceDiag.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/MixerConsoleState.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/StyleMixerChannels.kt',
             ROOT/'app/src/main/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayGate.kt',
             ROOT/'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',
             ROOT/'app/src/main/java/com/yourapp/style/StyleModel.kt',
             ROOT/'app/src/main/java/com/yourapp/midi/MidiInputManager.kt',
             ROOT/'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt',
             ROOT/'app/src/test/java/com/yourapp/audio/MidiMixerOutputTest.kt',
             ROOT/'app/src/test/java/com/yourapp/audio/VoiceReleaseContractTest.kt',
             ROOT/'tests/readiness_jvm/ReadyToPlayEngineTest.kt',
             ROOT/'app/src/test/java/com/yourapp/yamahaarranger/lifecycle/ReadyToPlayBrainTest.kt']
    sources += [ROOT/'app/src/main/java/com/yourapp/yamahaarranger/audio/PcmSessionControls.kt']
    sources+=sorted((ROOT/'app/src/main/java/com/yourapp/chord').glob('*.kt'))
    arranger=ROOT/'app/src/main/java/com/yourapp/yamahaarranger/arranger'
    sources += [arranger/(name+'.kt') for name in ['StyleSequencer','CasmNoteTransformer','StyleAudioPathDiagnostic','StylePartPresence','ChordChangeDiagnostic']]
    sources += [p for p in sorted((ROOT/'tests/sff5_jvm_stubs').glob('*.kt')) if p.name not in ['AudioEngineManager.kt','DebugLog.kt']]
    cp=':'.join(map(str,jars));jar=output/'contracts.jar'
    run(['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
         '-nowarn','-jvm-target','17','-classpath',cp,'-d',str(jar),*map(str,sources)])
    run(['java','-javaagent:'+str(deps/'byte-buddy-agent.jar'),'-cp',str(jar)+':'+cp,'org.junit.runner.JUnitCore',
         'com.yourapp.audio.VoiceReleaseContractTest',
         'com.yourapp.audio.MidiMixerOutputTest',
         'com.yourapp.yamahaarranger.ui.CompactMixerLayoutTest',
         'com.yourapp.yamahaarranger.lifecycle.ReadyToPlayEngineTest',
         'com.yourapp.yamahaarranger.lifecycle.ReadyToPlayBrainTest'])

def native(output, real):
    with tempfile.TemporaryDirectory(prefix='voice-release-') as directory:
        executable=output/('native-real' if real else 'native-recorder')
        command=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        if real:
            command+=['-DVOICE_RELEASE_REAL_BASS=1','-I/tmp/bass-linux/c','-I/tmp/bassmidi-linux/c']
        command+=['-Itests/mocks','-Iapp/src/main/cpp','-Itests','tests/voice_release_native_test.cpp','app/src/main/cpp/bassmidi_player.cpp']
        if real:
            command+=['-L/tmp/bass-linux/libs/x86_64','-L/tmp/bassmidi-linux/libs/x86_64',
                      '-Wl,-rpath,/tmp/bass-linux/libs/x86_64','-Wl,-rpath,/tmp/bassmidi-linux/libs/x86_64','-lbassmidi','-lbass']
        run(command+['-o',str(executable)])
        observed=subprocess.run([str(executable),directory],cwd=ROOT,text=True,capture_output=True)
        print(observed.stdout,end='');print(observed.stderr,end='')
        (output/('linux-pcm.log' if real else 'recorder.log')).write_text(observed.stdout+observed.stderr)
        assert observed.returncode==0, 'native voice/release test failed'

def envelope(output):
    """Static SF2 release metadata interpretation; host-only, no BASS and no PCM claim."""
    with tempfile.TemporaryDirectory(prefix='release-envelope-') as directory:
        executable=output/'release-envelope'
        run(['g++','-std=c++17','-O2','-Iapp/src/main/cpp','-Itests',
             'tests/release_envelope_evidence_test.cpp','-o',str(executable)])
        run([str(executable),directory])

def regressions(output):
    with tempfile.TemporaryDirectory(prefix='voice-release-safety-') as directory:
        cases=['f03_native_lifecycle_test','native_resolver_test','bank_translation_readback_test',
               'accompaniment_presence_test','percussion_fidelity_test','role_pcm_native_test',
               'diagnostic_snapshot_liveness_test']
        for name in cases:
            executable=output/name
            run(['g++','-std=c++17','-O2','-pthread','-DYAMAHA_COMPATIBLE_PERCUSSION=1',
                 '-DYAMAHA_ROLE_PCM_METERS=1','-Itests/mocks','-Iapp/src/main/cpp','-Itests',
                 'tests/'+name+'.cpp','app/src/main/cpp/bassmidi_player.cpp','-o',str(executable)])
            run([str(executable),directory])

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real',action='store_true');parser.add_argument('--jvm',action='store_true')
    parser.add_argument('--fetch-dependencies',action='store_true')
    parser.add_argument('--regressions',action='store_true')
    args=parser.parse_args();output=ROOT/'build/voice-release';output.mkdir(parents=True,exist_ok=True)
    frozen_style()
    envelope(output)
    native(output,args.real)
    if args.regressions:regressions(output)
    if args.jvm:jvm(output,args.fetch_dependencies)

if __name__=='__main__': main()

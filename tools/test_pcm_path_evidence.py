"""Production callback plus real BASSMIDI; meter ON/OFF versus unchanged #787."""
from pathlib import Path
import subprocess,tempfile,sys
root=Path(__file__).resolve().parents[1]
if len(sys.argv)!=4:raise SystemExit('usage: test_pcm_path_evidence.py baseline787 bass-sdk bassmidi-sdk')
baseline,bass,midi=map(lambda x:Path(x).resolve(),sys.argv[1:])
with tempfile.TemporaryDirectory() as directory:
    traces=[]
    for source,name,flag in [(baseline,'baseline787',1),(root,'current_OFF',0),(root,'current_ON',1)]:
        folder=Path(directory)/name;folder.mkdir();exe=folder/'path-test'
        cmd=['g++','-std=c++17','-O2','-ffast-math','-pthread','-DYAMAHA_ROLE_PCM_METERS='+str(flag),'-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        for include in [bass/'c',midi/'c',root/'tests/mocks',source/'app/src/main/cpp',root/'tests']:cmd+=['-I',str(include)]
        # The baseline cannot assert evidence fields added by this patch.
        test=(root/'tests/pcm_path_real_bass_test.cpp').read_text()
        if source==baseline:
            test=test.replace('#if YAMAHA_ROLE_PCM_METERS','#if 0').replace('#else\n assert(report.find("AUDIO_OUTPUT")','#elif 0\n assert(report.find("AUDIO_OUTPUT")')
        testfile=folder/'test.cpp';testfile.write_text(test)
        cmd+=[str(testfile)]+[str(source/'app/src/main/cpp'/n) for n in ['audio_engine.cpp','bassmidi_player.cpp','voice.cpp']]
        for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
        cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-Wl,--wrap=BASS_ChannelGetData','-lbassmidi','-lbass','-o',str(exe)]
        subprocess.run(cmd,check=True)
        subprocess.run([str(exe),str(folder)],check=True)
        traces.append(((folder/'pcm.bin').read_bytes(),(folder/'midi.bin').read_bytes()))
        (root/'build').mkdir(exist_ok=True)
        for report in folder.glob('*-report.txt'):
            (root/'build'/('pcm-path-'+name+'-'+report.name)).write_bytes(report.read_bytes())
    assert traces[0]==traces[1]==traces[2],'Output PCM/MIDI changed'
    proof='PCM_PATH_PROOF PASS actual_production_AudioEngine_callback=true real_BASSMIDI=true platform_Oboe_simulated=true baseline787_vs_current_OFF_vs_current_ON_PCM_MIDI_BYTE_IDENTICAL=true stalled_decode_success_failure_lifecycle_gates=true no_device_root_cause_claim\n'
    (root/'build/pcm-path-proof.txt').write_text(proof);print(proof)

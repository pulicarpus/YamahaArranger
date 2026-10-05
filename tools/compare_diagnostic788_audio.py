"""Actual BASSMIDI: #787 versus snapshot-only patch, both ROLE_PCM enabled."""
from pathlib import Path
import subprocess,tempfile,sys
root=Path(__file__).resolve().parents[1]
if len(sys.argv)!=4:raise SystemExit('usage: compare_diagnostic788_audio.py baseline787 bass-sdk bassmidi-sdk')
baseline,bass,midi=map(lambda p:Path(p).resolve(),sys.argv[1:])
with tempfile.TemporaryDirectory() as directory:
    data=[]
    for source,name in [(baseline,'baseline787'),(root,'current788')]:
        folder=Path(directory)/name;folder.mkdir();exe=folder/'role-real'
        cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        for include in [bass/'c',midi/'c',root/'tests/mocks',source/'app/src/main/cpp',root/'tests']:cmd+=['-I',str(include)]
        cmd+=[str(root/'tests/role_pcm_real_bass_test.cpp'),str(source/'app/src/main/cpp/bassmidi_player.cpp')]
        for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
        cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-lbassmidi','-lbass','-o',str(exe)]
        subprocess.run(cmd,check=True)
        run=subprocess.run([str(exe),str(folder)],check=True,capture_output=True,text=True)
        data.append(((folder/'pcm.bin').read_bytes(),(folder/'midi.bin').read_bytes()))
        print(name,run.stdout.strip())
    assert data[0][0]==data[1][0],'#788 PCM differs from #787'
    assert data[0][1]==data[1][1],'#788 MIDI differs from #787'
    proof='DIAGNOSTIC788_REAL_BASS PCM_BYTE_IDENTICAL=true MIDI_trace_BYTE_IDENTICAL=true ROLE_PCM_enabled=true compatible_percussion_enabled=true multi_SF2=true no_gain_or_routing_change\n'
    (root/'build').mkdir(exist_ok=True);(root/'build/diagnostic788-audio-proof.txt').write_text(proof)
    print(proof)

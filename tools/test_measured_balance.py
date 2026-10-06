from pathlib import Path
import subprocess,sys,tempfile,math
root=Path(__file__).resolve().parents[1]
if len(sys.argv)!=3:raise SystemExit('usage: test_measured_balance.py bass-sdk bassmidi-sdk')
bass,midi=map(lambda p:Path(p).resolve(),sys.argv[1:])
with tempfile.TemporaryDirectory() as directory:
    results=[]
    for mode,meters in [('off',1),('on',1),('on_nometers',0)]:
        folder=Path(directory)/mode;folder.mkdir();exe=folder/'balance-real'
        cmd=['g++','-std=c++17','-O2','-pthread','-DYAMAHA_ROLE_PCM_METERS='+str(meters),'-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        for path in [bass/'c',midi/'c',root/'tests/mocks',root/'app/src/main/cpp',root/'tests']:cmd+=['-I',str(path)]
        cmd+=[str(root/'tests/measured_balance_real_bass_test.cpp'),str(root/'app/src/main/cpp/bassmidi_player.cpp')]
        for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
        cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-lbassmidi','-lbass','-o',str(exe)]
        subprocess.run(cmd,check=True);subprocess.run([str(exe),str(folder),'on' if mode.startswith('on') else 'off'],check=True)
        rows=[list(map(float,l.split())) for l in (folder/'response.tsv').read_text().splitlines()]
        results.append((rows,(folder/'midi.bin').read_bytes()))
        (root/'build').mkdir(exist_ok=True)
        for n in ['response.tsv','balance-report.txt']:(root/'build'/('balance791-'+mode+'-'+n)).write_bytes((folder/n).read_bytes())
    assert results[0][1]==results[1][1]==results[2][1],'Controller/NOTE trace changed'
    for a,b in zip(results[1][0],results[2][0]):
        assert a[:4]==b[:4]
        assert math.isclose(a[4],b[4],rel_tol=3e-5,abs_tol=1e-12),'Trim depends on diagnostic meter'
    count=0
    for before,after in zip(results[0][0],results[1][0]):
        assert before[:4]==after[:4]
        ch=int(before[0]);gain=10**(({11:-6,12:-6,13:12,14:12}.get(ch,0))/20)
        if before[4]==0:assert after[4]==0,'CC11=0 no longer silent'
        else:assert math.isclose(after[4]/before[4],gain,rel_tol=3e-5),(before,after,gain)
        count+=1
    proof=f'MEASURED_BALANCE_PROOF PASS cells={count} production_BassMidiPlayer=true real_BASSMIDI=true exact_static_gain_ratio=true authored_CC7_CC11_velocity_NOTE_trace_IDENTICAL=true keyboard_rhythm_unity=true independent_of_diagnostic_meter=true multi_SF2=true fixture_identity_injection_ONLY_no_device_after_claim\n'
    (root/'build/balance791-proof.txt').write_text(proof);print(proof)

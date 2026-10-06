"""Actual #791 production source vs observers OFF/ON, real BASSMIDI, calibrated bindings."""
from pathlib import Path
import subprocess,sys,tempfile,math,hashlib,json
from pcm_headroom_guard import strip_headroom
root=Path(__file__).resolve().parents[1]
if len(sys.argv)!=3:raise SystemExit('usage: test_pcm_headroom.py bass-sdk bassmidi-sdk')
bass,midi=map(lambda p:Path(p).resolve(),sys.argv[1:])
with tempfile.TemporaryDirectory() as directory:
    base=Path(directory)/'baseline791_source';base.mkdir()
    for name in ['bassmidi_player.cpp','bassmidi_player.h']:
        path='app/src/main/cpp/'+name
        (base/name).write_text(strip_headroom(path,(root/path).read_text()))
    unit=Path(directory)/'unit'
    subprocess.run(['g++','-std=c++17','-O2','-ffast-math','-I',str(root/'app/src/main/cpp'),str(root/'tests/pcm_headroom_test.cpp'),'-o',str(unit)],check=True)
    subprocess.run([str(unit)],check=True)
    captures=[]
    for mode,flag,source in [('baseline791',0,base),('current_OFF',0,root/'app/src/main/cpp'),('current_ON',1,root/'app/src/main/cpp')]:
        folder=Path(directory)/mode;folder.mkdir();exe=folder/'headroom-real'
        test=(root/'tests/measured_balance_real_bass_test.cpp').read_text()
        test=test.replace(' p.setMasterGain(.4f);', ' std::ofstream capture(dir+"/pcm.bin",std::ios::binary);\n auto rendered=[&](std::vector<float>& block){p.render(block.data(),256);capture.write(reinterpret_cast<const char*>(block.data()),block.size()*sizeof(float));};\n p.setMasterGain(.4f);')
        # Every production render result, including ramp, silence, teardown boundaries.
        test=test.replace('p.render(block.data(),256);','rendered(block);',test.count('p.render(block.data(),256);'))
        test=test.replace('auto rendered=[&](std::vector<float>& block){rendered(block);','auto rendered=[&](std::vector<float>& block){p.render(block.data(),256);')
        # Shared FX and four sustained simultaneous parts after response cells.
        marker=' auto before=eventTrace.size();'
        stress='''
 for(int ch=11;ch<=14;++ch)p.setChannelMixer(ch,81,64,110,60,30);
 for(int i=0;i<600;++i){if(i%17==0)for(int ch=11;ch<=14;++ch)for(int key:{48,52,55,60})p.noteOn(ch,key,.6f,AudioPathOrigin{ch,key,0,i,key});if(i%17==15)for(int ch=11;ch<=14;++ch)for(int key:{48,52,55,60})p.noteOff(ch,key);rendered(block);}
p.allNotesOff();p.setMasterGain(1);p.setChannelMixer(13,127,64,127,0,0);
 for(int i=0;i<64;++i)p.noteOn(13,60,1,AudioPathOrigin{13,60,0,i,i});
 for(int i=0;i<32;++i)rendered(block);
'''
        test=test.replace(marker,stress+marker)
        (folder/'test.cpp').write_text(test)
        cmd=['g++','-std=c++17','-O2','-ffast-math','-pthread','-DYAMAHA_ROLE_PCM_METERS=1','-DYAMAHA_PCM_HEADROOM='+str(flag),'-DYAMAHA_COMPATIBLE_PERCUSSION=1']
        for path in [bass/'c',midi/'c',root/'tests/mocks',source,root/'app/src/main/cpp',root/'tests']:cmd+=['-I',str(path)]
        cmd+=[str(folder/'test.cpp'),str(source/'bassmidi_player.cpp')]
        for sdk in [bass,midi]:cmd+=['-L',str(sdk/'libs/x86_64'),'-Wl,-rpath,'+str(sdk/'libs/x86_64')]
        cmd+=['-Wl,--wrap=BASS_MIDI_StreamEvent','-Wl,--wrap=BASS_MIDI_StreamGetChannel','-lbassmidi','-lbass','-o',str(exe)]
        subprocess.run(cmd,check=True);subprocess.run([str(exe),str(folder),'on'],check=True)
        captures.append(tuple(hashlib.sha256((folder/n).read_bytes()).hexdigest() for n in ['pcm.bin','midi.bin']))
        report=(folder/'balance-report.txt').read_text()
        (root/'build').mkdir(exist_ok=True);(root/'build'/('headroom-'+mode+'.txt')).write_text(report)
        if flag:
            rows={}
            for line in report.splitlines():
                if line.startswith('HEADROOM_ROLE '):
                    row=dict(x.split('=',1) for x in line.split()[1:]);rows[int(row['ch'])]=row
            for ch,db in [(11,-6),(12,-6),(13,12),(14,12)]:
                row=rows[ch];assert int(row['preSamples'])>0 and row['preSamples']==row['postSamples'] and row['missingPre']=='0'
                assert float(row['preRms'])>0
                assert math.isclose(float(row['postRms'])/float(row['preRms']),10**(db/20),rel_tol=4e-5),(ch,row)
            assert int(rows[13]['postOver'])>0,'High-polyphony fixture must exercise over-range PCM'
            assert 'HEADROOM_STAGE name=parent samples=0' not in report
            assert 'HEADROOM_WORST trigger=sum ch=-1 decode=0' not in report
        else:assert 'HEADROOM_' not in report
    assert captures[0]==captures[1]==captures[2],captures
    proof='HEADROOM_PROOF PASS baseline791_stripped_hash_verified=true production_BassMidiPlayer_real_BASSMIDI=true fast_math=true calibrated_Piano_Guitar_Strings=true pre_post_scalar_verified=true shared_FX_polyphony=true baseline791_vs_OFF_vs_ON_PCM_MIDI_BYTE_IDENTICAL=true no_device_peak_root_claim\n'
    (root/'build/headroom-proof.txt').write_text(proof);print(proof)

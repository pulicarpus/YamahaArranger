#pragma once
#include <bass.h>
#include <bassmidi.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <sstream>
#include <vector>

namespace keyboard_release_probe {
struct Result {
    std::vector<unsigned char> wav; std::string report, classification;
    std::array<int,3> tailMinus40Ms{}, tailMinus60Ms{}; // values 0, 64, 127
    int reverbTailMinus40Ms=-1;                         // value 0 with the live reverb/chorus sends
};

// Offline, isolated decoders sharing the already loaded font. No events, font
// mappings, gain or controller changes on the production stream or font.
// Last 10 ms block whose RMS stays above held*ratio, in ms after NOTE_OFF.
inline int lastAboveMs(const std::vector<float>& off,double held,double ratio,int rate) {
    int last=0;const size_t block=size_t(rate/50); // rate/50 interleaved stereo floats = 10 ms
    for(size_t start=0;start+block<=off.size();start+=block) {
        double energy=0;for(size_t i=start;i<start+block;++i)energy+=double(off[i])*off[i];
        if(std::sqrt(energy/block)>held*ratio)last=int((start+block)*500/rate);
    }
    return last;
}

// sends: {reverb, chorus} of the live keyboard channel. Envelope segments use 0/0;
// the extra report-only decode measures RELEASE 0 with those sends, including
// the dry signal. It does not measure reverb in isolation.
inline Result run(HSOUNDFONT font,int bank,int pc,bool drum,int key,int velocity,int rate,std::array<int,2> sends={0,0}) {
    Result result; std::ostringstream report; std::vector<float> combined;
    std::array<double,3> held{},tail{},early{},whole{};int index=0;
    for(int value:{0,64,127,-1}) {
        const bool reverbSegment=value<0; if(reverbSegment)value=0;
        const HSTREAM stream=BASS_MIDI_StreamCreate(1,BASS_STREAM_DECODE|BASS_SAMPLE_FLOAT|BASS_MIDI_NOTEOFF1,rate);
        if(!stream){result.report="RELEASE PCM FAIL decoder_create\n";return result;}
        struct Cleanup {HSTREAM s;~Cleanup(){BASS_StreamFree(s);}} cleanup{stream};
        BASS_MIDI_FONTEX2 mapping{};
        mapping.font=font;mapping.spreset=pc;mapping.sbank=bank;mapping.dpreset=0;
        mapping.dbank=drum?128:0;mapping.minchan=0;mapping.numchan=1;
        bool ok=BASS_MIDI_StreamSetFonts(stream,&mapping,1|BASS_MIDI_FONT_EX2);
        ok=BASS_ChannelSetAttribute(stream,BASS_ATTRIB_MIDI_SRC,2) && ok;
        auto event=[&](DWORD e,DWORD p){ok=BASS_MIDI_StreamEvent(stream,0,e,p)&&ok;};
        event(MIDI_EVENT_DRUMS,drum?1:0);event(MIDI_EVENT_BANK,0);event(MIDI_EVENT_BANK_LSB,0);
        event(MIDI_EVENT_PROGRAM,0);event(MIDI_EVENT_VOLUME,127);event(MIDI_EVENT_EXPRESSION,127);
        event(MIDI_EVENT_REVERB,reverbSegment?sends[0]:0);event(MIDI_EVENT_CHORUS,reverbSegment?sends[1]:0);event(MIDI_EVENT_RELEASE,value);
        const bool controllerVerified=BASS_MIDI_StreamGetEvent(stream,0,MIDI_EVENT_RELEASE)==DWORD(value);
        event(MIDI_EVENT_NOTE,key|(velocity<<8));
        BASS_MIDI_FONT actual{};
        const bool presetVerified=BASS_MIDI_StreamGetPreset(stream,0,&actual)&&actual.font==font&&actual.bank==bank&&actual.preset==pc;
        if(!ok||!controllerVerified||!presetVerified){result.report="RELEASE PCM FAIL event_or_preset_readback\n";return result;}
        auto decode=[&](int frames,std::vector<float>& pcm){
            pcm.resize(frames*2);size_t at=0;
            while(at<pcm.size()) {
                const DWORD wanted=DWORD(std::min<size_t>(1024,pcm.size()-at)*sizeof(float));
                const DWORD got=BASS_ChannelGetData(stream,pcm.data()+at,wanted|BASS_DATA_FLOAT);
                if(got==DWORD(-1)||!got||got>wanted||got%sizeof(float))return false;
                at+=got/sizeof(float);
            }
            return true;
        };
        auto rms=[](const std::vector<float>& pcm,size_t start,size_t count){
            double energy=0;for(size_t i=start;i<start+count;++i){if(!std::isfinite(pcm[i]))return -1.0;energy+=double(pcm[i])*pcm[i];}
            return std::sqrt(energy/count);
        };
        std::vector<float> on,off;
        if(!decode(rate/2,on)){result.report="RELEASE PCM FAIL held_decode\n";return result;}
        event(MIDI_EVENT_NOTE,key);
        if(!ok||!decode(rate*2,off)){result.report="RELEASE PCM FAIL note_off_decode\n";return result;}
        if(reverbSegment) {
            const double reverbHeld=rms(on,on.size()-rate/10,rate/10);
            result.reverbTailMinus40Ms=reverbHeld>0.00001?lastAboveMs(off,reverbHeld,0.01,rate):0;
            report<<"RELEASE PCM reverbTail value=0 reverbSend="<<sends[0]<<" chorusSend="<<sends[1]
                  <<" heldRms="<<reverbHeld<<" lastAboveMinus40dBMs="<<result.reverbTailMinus40Ms
                  <<" evidence=release0_with_live_sends_includes_dry_signal_report_only_not_in_wav\n";
            break;
        }
        held[index]=rms(on,on.size()-rate/10,rate/10); // last 50 ms of key hold
        tail[index]=rms(off,rate,rate); // 500..1000 ms after NOTE_OFF, stereo
        early[index]=rms(off,0,rate/5); // first 100 ms captures short-release presets
        whole[index]=rms(off,0,off.size());
        const int lastAbove=lastAboveMs(off,held[index],0.01,rate);
        result.tailMinus40Ms[index]=lastAbove;result.tailMinus60Ms[index]=lastAboveMs(off,held[index],0.001,rate);
        report<<"RELEASE PCM value="<<value<<" controllerVerified=1 presetVerified=1 heldRms="<<held[index]
              <<" tailRms0to100ms="<<early[index]<<" tailRms500to1000ms="<<tail[index]
              <<" tailRms0to2000ms="<<whole[index]<<" lastAboveMinus40dBMs="<<lastAbove
              <<" lastAboveMinus60dBMs="<<result.tailMinus60Ms[index]
              <<" key="<<key<<" velocity="<<velocity<<'\n';
        if(held[index]<0||tail[index]<0){result.report="RELEASE PCM FAIL nonfinite\n";return result;}
        combined.insert(combined.end(),on.begin(),on.end());combined.insert(combined.end(),off.begin(),off.end());
        combined.insert(combined.end(),rate/2,0); // 250 ms gap between values
        ++index;
    }
    const bool hasHeld=std::all_of(held.begin(),held.end(),[](double v){return v>0.00001;});
    const bool differs=hasHeld&&((tail[0]<tail[1]&&tail[1]<tail[2]&&tail[2]>tail[0]*1.5)||
        (whole[0]<whole[1]*0.9&&whole[1]<whole[2]*0.9));
    const auto limits=std::minmax_element(whole.begin(),whole.end());
    const bool otherChange=hasHeld&&*limits.second>*limits.first*1.5;
    result.classification=differs?"RESPONDS":otherChange?"TAIL_CHANGES_NONMONOTONIC":hasHeld?"NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW":"NO_HELD_PCM_sample_key_or_attack_limitation";
    report<<"RELEASE PCM classification="<<result.classification
          <<" evidence=isolated_actual_loaded_preset_PCM_not_live_mix wavOrder=0,64,127 holdMs=500 tailMs=2000 gapMs=250\n";
    result.report=report.str();result.wav.resize(44+combined.size()*2);
    auto word=[&](size_t p,uint16_t v){result.wav[p]=v&255;result.wav[p+1]=v>>8;};
    auto dword=[&](size_t p,uint32_t v){word(p,v&65535);word(p+2,v>>16);};
    std::memcpy(result.wav.data(),"RIFF",4);dword(4,result.wav.size()-8);std::memcpy(result.wav.data()+8,"WAVEfmt ",8);
    dword(16,16);word(20,1);word(22,2);dword(24,rate);dword(28,rate*4);word(32,4);word(34,16);
    std::memcpy(result.wav.data()+36,"data",4);dword(40,combined.size()*2);
    for(size_t n=0;n<combined.size();++n)word(44+n*2,uint16_t(int16_t(std::clamp(combined[n],-1.0f,1.0f)*32767)));
    return result;
}
}

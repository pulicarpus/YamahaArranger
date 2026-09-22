#include "bassmidi_player.h"
#include <android/log.h>
#include <algorithm>
#include <sstream>
#define LOG_TAG "BassMidiPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

BassMidiPlayer::BassMidiPlayer(){ ensureEngine(); }
BassMidiPlayer::~BassMidiPlayer(){ unload(); if(bassInitialized_){ BASS_Free(); bassInitialized_=false; } }

bool BassMidiPlayer::ensureEngine(){
    if(bassInitialized_ && stream_) return true;
    if(!bassInitialized_){
        if(!BASS_Init(0, sampleRate_, 0, nullptr, nullptr)){
            LOGE("BASS_Init failed error=%d",BASS_ErrorGetCode()); return false;
        }
        bassInitialized_=true;
        LOGI("BASS initialized on no-sound device");
    }
    if(!stream_){
        stream_=BASS_MIDI_StreamCreate(16,BASS_STREAM_DECODE|BASS_SAMPLE_FLOAT|BASS_MIDI_ASYNC,sampleRate_);
        if(!stream_){
            LOGE("BASS_MIDI_StreamCreate failed error=%d",BASS_ErrorGetCode()); return false;
        }
        BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_MIDI_PPQN,1920.0f);
        BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_MIDI_SRC,(float)interpolation_);
        BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_MIDI_VOICES,(float)voices_);
        BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,1.0f);
        BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_BUFFER,0.0f);
        LOGI("BASSMIDI stream ready: PPQN=1920 SRC=%d voices=%d",interpolation_,voices_);
    }
    return true;
}

bool BassMidiPlayer::applyFonts(){
    if(!stream_) return false;
    // Diagnostic path: keep melody mapping simple (all presets, bank 0).
    // Yamaha bank/LSB routing will be restored after PCM is proven.
    BASS_MIDI_FONT cfg[2]{};
    DWORD count=0;
    if(melodyFont_){
        cfg[count].font=melodyFont_;
        cfg[count].preset=-1;
        cfg[count].bank=0;
        ++count;
    }
    if(drumFont_){
        cfg[count].font=drumFont_;
        cfg[count].preset=-1;
        cfg[count].bank=128;
        ++count;
    }
    if(!count) return false;
    if(!BASS_MIDI_StreamSetFonts(stream_,cfg,count)){
        LOGE("StreamSetFonts(simple) failed error=%d",BASS_ErrorGetCode()); return false;
    }
    LOGI("BASSMIDI fonts applied: melody=%d drum=%d",melodyFont_!=0,drumFont_!=0);
    return true;
}
bool BassMidiPlayer::loadRole(const std::string& path,bool drum){
    std::lock_guard<std::mutex> lock(mutex_);
    if(!ensureEngine()) return false;
    HSOUNDFONT& target=drum?drumFont_:melodyFont_;
    if(target){BASS_MIDI_FontFree(target);target=0;}
    target=BASS_MIDI_FontInit(path.c_str(),0);
    if(!target){
        LOGE("FontInit failed role=%s error=%d",drum?"DRUM":"MELODY",BASS_ErrorGetCode()); return false;
    }
    if(!applyFonts()){BASS_MIDI_FontFree(target);target=0;return false;}
    BASS_MIDI_FONTINFO info{};
    if(BASS_MIDI_FontGetInfo(target,&info)){
        LOGI("BASSMIDI font info role=%s presets=%u samples=%u",
             drum?"DRUM":"MELODY", info.presets, info.samples);
    } else {
        LOGE("BASSMIDI FontGetInfo failed role=%s error=%d",
             drum?"DRUM":"MELODY", BASS_ErrorGetCode());
    }
    if(!BASS_MIDI_FontLoad(target, 0, drum ? 128 : 0)){
        LOGE("BASSMIDI FontLoad preset0 bank%d failed role=%s error=%d",
             drum?128:0, drum?"DRUM":"MELODY", BASS_ErrorGetCode());
    } else {
        LOGI("BASSMIDI FontLoad preset0 bank%d OK role=%s",
             drum?128:0, drum?"DRUM":"MELODY");
    }
    LOGI("BASSMIDI %s SF2 loaded: %s",drum?"DRUM":"MELODY",path.c_str());
    return true;
}
bool BassMidiPlayer::load(const std::string& path){return !isMelodyLoaded()?loadMelody(path):loadDrum(path);}
bool BassMidiPlayer::loadMelody(const std::string& path){return loadRole(path,false);}
bool BassMidiPlayer::loadDrum(const std::string& path){return loadRole(path,true);}

void BassMidiPlayer::unload(){
    std::lock_guard<std::mutex> lock(mutex_);
    if(stream_) for(int ch=0;ch<16;++ch) BASS_MIDI_StreamEvent(stream_,ch,MIDI_EVENT_NOTESOFF,0);
    if(melodyFont_){BASS_MIDI_FontFree(melodyFont_);melodyFont_=0;}
    if(drumFont_){BASS_MIDI_FontFree(drumFont_);drumFont_=0;}
    if(stream_){BASS_StreamFree(stream_);stream_=0;}
}
void BassMidiPlayer::send(int channel,DWORD event,DWORD param){
    if(!stream_) return;
    channel=std::max(0,std::min(15,channel));
    if(!BASS_MIDI_StreamEvent(stream_,(DWORD)channel,event,param))
        LOGE("MIDI event failed ch=%d event=%u param=%u error=%d",channel,event,param,BASS_ErrorGetCode());
}
void BassMidiPlayer::noteOn(int channel,int key,float velocity){
    std::lock_guard<std::mutex> lock(mutex_); if(!ensureEngine()) return;
    int vel=std::max(1,std::min(127,(int)(velocity*127.0f)));
    if(channel==0) {
        send(channel,MIDI_EVENT_BANK,0);
        send(channel,MIDI_EVENT_BANK_LSB,0);
        send(channel,MIDI_EVENT_PROGRAM,0);
    }
    send(channel,MIDI_EVENT_NOTE,(DWORD)(key|(vel<<8)));
}
void BassMidiPlayer::noteOff(int channel,int key){
    std::lock_guard<std::mutex> lock(mutex_); if(!stream_) return; send(channel,MIDI_EVENT_NOTE,(DWORD)key);
}
void BassMidiPlayer::allNotesOff(){
    std::lock_guard<std::mutex> lock(mutex_); if(!stream_) return;
    for(int ch=0;ch<16;++ch) send(ch,MIDI_EVENT_NOTESOFF,0);
}
void BassMidiPlayer::setChannelPreset(int channel,int bank,int program){
    std::lock_guard<std::mutex> lock(mutex_); if(!ensureEngine()) return;
    send(channel,MIDI_EVENT_BANK,(DWORD)std::clamp(bank,0,127));
    send(channel,MIDI_EVENT_PROGRAM,(DWORD)std::clamp(program,0,65535));
}
void BassMidiPlayer::setChannelMixer(int channel,int volume,int pan,int expression,int reverbSend,int chorusSend){
    std::lock_guard<std::mutex> lock(mutex_); if(!ensureEngine()) return;
    send(channel,MIDI_EVENT_VOLUME,std::clamp(volume,0,127));
    send(channel,MIDI_EVENT_PAN,std::clamp(pan,0,128));
    send(channel,MIDI_EVENT_EXPRESSION,std::clamp(expression,0,127));
    send(channel,MIDI_EVENT_REVERB,std::clamp(reverbSend,0,127));
    send(channel,MIDI_EVENT_CHORUS,std::clamp(chorusSend,0,127));
}
void BassMidiPlayer::setChannelExpression(int channel,int expression){
    std::lock_guard<std::mutex> lock(mutex_); if(!ensureEngine()) return;
    send(channel,MIDI_EVENT_EXPRESSION,std::clamp(expression,0,127));
}
void BassMidiPlayer::setMasterGain(float gain){
    std::lock_guard<std::mutex> lock(mutex_); if(!stream_) return;
    BASS_ChannelSetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,std::max(0.0f,std::min(2.0f,gain)));
}
std::string BassMidiPlayer::presetList() const{return {};}

void BassMidiPlayer::render(float* out,int numFrames){
    std::lock_guard<std::mutex> lock(mutex_);
    if(!stream_){std::fill(out,out+numFrames*2,0.0f);return;}
    DWORD wanted=(DWORD)(numFrames*2*sizeof(float));
    DWORD got=BASS_ChannelGetData(stream_,out,wanted|BASS_DATA_FLOAT);
    if(got!=(DWORD)-1){
        static int renderLogCount=0;
        if((++renderLogCount % 200)==1){
            float rawPeak=0.0f;
            for(int i=0;i<(int)(got/sizeof(float));++i)
                rawPeak=std::max(rawPeak,std::fabs(out[i]));
            LOGI("BASSMIDI PCM bytes=%u samples=%u rawPeak=%.6f",
                 got, got/sizeof(float), rawPeak);
        }
    }
    if(got==(DWORD)-1){
        LOGE("BASS_ChannelGetData failed error=%d",BASS_ErrorGetCode());
        std::fill(out,out+numFrames*2,0.0f); return;
    }
    int samples=(int)(got/sizeof(float));
    if(samples<numFrames*2) std::fill(out+samples,out+numFrames*2,0.0f);
}

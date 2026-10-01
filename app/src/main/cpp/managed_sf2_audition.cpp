#include "managed_sf2_audition.h"
#include "bass.h"
#include "bassmidi.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <sstream>
namespace managed_sf2_audition {
Result render(const std::string& path, int bank, int pc, int key, int velocity) {
    static std::mutex diagnosticMutex;
    std::lock_guard<std::mutex> lock(diagnosticMutex);
    if(path.empty() || bank<0 || bank>65535 || pc<0 || pc>127 || key<0 || key>127 || velocity<1 || velocity>127) return {};
    // BASS is already initialized by the app. Never init/free/reconfigure its shared device here.
    const HSOUNDFONT font=BASS_MIDI_FontInit(path.c_str(),0);
    if(!font) return {};
    struct FontRelease { HSOUNDFONT handle; ~FontRelease(){ BASS_MIDI_FontFree(handle); } } releaseFont{font};
    if(!BASS_MIDI_FontGetPreset(font,pc,bank)) return {};
    constexpr int rate=44100;
    const HSTREAM stream=BASS_MIDI_StreamCreate(1,BASS_SAMPLE_FLOAT|BASS_STREAM_DECODE,rate);
    if(!stream) return {};
    struct StreamRelease { HSTREAM handle; ~StreamRelease(){ BASS_StreamFree(handle); } } releaseStream{stream};
    BASS_MIDI_FONTEX2 mapping{};
    mapping.font=font; mapping.sbank=bank; mapping.spreset=pc;
    mapping.dbank=128; mapping.dpreset=pc; mapping.minchan=0; mapping.numchan=1;
    if(!BASS_MIDI_StreamSetFonts(stream,&mapping,1|BASS_MIDI_FONT_EX2) ||
       !BASS_MIDI_StreamEvent(stream,0,MIDI_EVENT_DRUMS,1) ||
       !BASS_MIDI_StreamEvent(stream,0,MIDI_EVENT_BANK,128) ||
       !BASS_MIDI_StreamEvent(stream,0,MIDI_EVENT_PROGRAM,pc)) return {};
    BASS_MIDI_FONT actual{};
    auto verified=[&] { return BASS_MIDI_StreamGetPreset(stream,0,&actual) && actual.font==font && actual.bank==bank && actual.preset==pc; };
    if(!verified()) return {}; // No note on a different/missing preset.
    if(!BASS_MIDI_StreamEvent(stream,0,MIDI_EVENT_NOTE,key|(velocity<<8)) || !verified()) return {};
    const size_t count=rate*2*2; // stereo, two seconds, one explicit diagnostic NOTE_ON
    std::vector<float> pcm(count);
    for(size_t offset=0; offset<count;) {
        const DWORD bytes=static_cast<DWORD>(std::min<size_t>(2048,count-offset)*sizeof(float));
        const DWORD received=BASS_ChannelGetData(stream,pcm.data()+offset,bytes|BASS_DATA_FLOAT);
        if(received==static_cast<DWORD>(-1) || !received || received>bytes || received%sizeof(float)) return {};
        offset+=received/sizeof(float);
    }
    Result result; result.wav.resize(44+count*2);
    auto word=[&](size_t p,uint16_t v) {result.wav[p]=v&255;result.wav[p+1]=v>>8;};
    auto dword=[&](size_t p,uint32_t v) {word(p,v&65535);word(p+2,v>>16);};
    std::memcpy(result.wav.data(),"RIFF",4);dword(4,result.wav.size()-8);
    std::memcpy(result.wav.data()+8,"WAVEfmt ",8);dword(16,16);word(20,1);word(22,2);
    dword(24,rate);dword(28,rate*4);word(32,4);word(34,16);
    std::memcpy(result.wav.data()+36,"data",4);dword(40,count*2);
    float peak=0;double energy=0;size_t clipped=0;
    for(size_t i=0;i<count;++i) {
        const float sample=std::isfinite(pcm[i])?pcm[i]:0;
        peak=std::max(peak,std::abs(sample));energy+=double(sample)*sample;if(std::abs(sample)>1)++clipped;
        word(44+i*2,static_cast<uint16_t>(static_cast<int16_t>(std::clamp(sample,-1.f,1.f)*32767)));
    }
    std::ostringstream evidence;
    evidence<<"actual fontHandle="<<actual.font<<" bank="<<actual.bank<<" rawPC="<<actual.preset
        <<" verified_before_and_after_note=true diagnosticFontHandle="<<font<<" diagnosticStreamHandle="<<stream
        <<" sourceKey="<<key<<" velocity="<<velocity<<" noteOnCount=1\n"
        <<"render sampleRate="<<rate<<" channels=2 durationMs=2000 controllers=isolated_stream_defaults fontVolume="<<BASS_MIDI_FontGetVolume(font)
        <<" peak="<<peak<<" rms="<<std::sqrt(energy/count)<<" clippedSamples="<<clipped<<"\n"
        <<"cleanup=RAII_stream_then_font; no_global_config_or_production_calls";
    result.evidence=evidence.str();return result;
}
}
#ifdef __ANDROID__
#include <jni.h>
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_yourapp_audio_ManagedSf2AuditionNative_render(JNIEnv* env,jobject,jstring snapshot,jint bank,jint pc,jint key,jint velocity) {
    const char* path=env->GetStringUTFChars(snapshot,nullptr);
    if(!path) return nullptr;
    struct StringRelease { JNIEnv* env; jstring source; const char* bytes;
        ~StringRelease() { env->ReleaseStringUTFChars(source,bytes); } } release{env,snapshot,path};
    try {
        const auto result=managed_sf2_audition::render(std::string(path),bank,pc,key,velocity);
        if(result.wav.empty()) return nullptr;
        jclass byteArrayClass=env->FindClass("[B");
        if(!byteArrayClass) return nullptr;
        jobjectArray output=env->NewObjectArray(2,byteArrayClass,nullptr);
        env->DeleteLocalRef(byteArrayClass);
        if(!output) return nullptr;
        auto wav=env->NewByteArray(result.wav.size());
        if(!wav) return nullptr;
        env->SetByteArrayRegion(wav,0,result.wav.size(),reinterpret_cast<const jbyte*>(result.wav.data()));
        env->SetObjectArrayElement(output,0,wav);env->DeleteLocalRef(wav);
        auto text=env->NewByteArray(result.evidence.size());
        if(!text) return nullptr;
        env->SetByteArrayRegion(text,0,result.evidence.size(),reinterpret_cast<const jbyte*>(result.evidence.data()));
        env->SetObjectArrayElement(output,1,text);env->DeleteLocalRef(text);return output;
    } catch(...) {return nullptr;}
}
#endif

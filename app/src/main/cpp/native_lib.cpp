#include <jni.h>
#include <memory>
#include <vector>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <cctype>
#include <algorithm>
#include "audio_engine.h"
#include "style_parser.h"
namespace { std::unique_ptr<AudioEngine> g_engine; std::unique_ptr<StyleParser> g_lastParsedStyle; }
JavaVM* g_jvm=nullptr; jclass g_debugLogClass=nullptr; jmethodID g_debugLogAddMethod=nullptr;
static std::string sanitizeUtf8ForJni(const char* input) {
    if (!input) return {};
    const unsigned char* s = reinterpret_cast<const unsigned char*>(input);
    const size_t len = std::strlen(input);
    std::string out;
    out.reserve(len);
    for (size_t i = 0; i < len;) {
        const unsigned char c = s[i];
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
            ++i;
            continue;
        }
        size_t n = 0;
        if (c >= 0xC2 && c <= 0xDF) n = 2;
        else if (c >= 0xE0 && c <= 0xEF) n = 3;
        else if (c >= 0xF0 && c <= 0xF4) n = 4;
        bool valid = n > 0;
        if (valid && i + n <= len) {
            for (size_t j = 1; j < n; ++j) {
                if (s[i + j] < 0x80 || s[i + j] > 0xBF) { valid = false; break; }
            }
            if (valid && n == 3 && c == 0xE0 && s[i + 1] < 0xA0) valid = false;
            if (valid && n == 3 && c == 0xED && s[i + 1] >= 0xA0) valid = false;
            if (valid && n == 4 && c == 0xF0 && s[i + 1] < 0x90) valid = false;
            if (valid && n == 4 && c == 0xF4 && s[i + 1] > 0x8F) valid = false;
        }
        if (valid) {
            out.append(reinterpret_cast<const char*>(s + i), n);
            i += n;
        } else {
            // Yamaha SF2 metadata is sometimes Latin-1/Windows-1252 rather
            // than UTF-8 (for example a raw 0xDC byte). Convert that byte to
            // its UTF-8 U+00xx representation instead of feeding invalid
            // bytes into JNI NewStringUTF/CheckJNI.
            const unsigned int cp = c;
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            ++i;
        }
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeInitLogger(JNIEnv* env,jobject){env->GetJavaVM(&g_jvm);jclass localClass=env->FindClass("com/yourapp/yamahaarranger/ui/DebugLog");if(localClass==nullptr)return;g_debugLogClass=static_cast<jclass>(env->NewGlobalRef(localClass));g_debugLogAddMethod=env->GetStaticMethodID(g_debugLogClass,"add","(Ljava/lang/String;)V");}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStart(JNIEnv*,jobject){if(!g_engine)g_engine=std::make_unique<AudioEngine>();return g_engine->start();}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStop(JNIEnv*,jobject){if(g_engine)g_engine->stop();}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOn(JNIEnv* env,jobject,jint midiNote,jint rootNote,jfloat velocity,jobject sampleByteBuffer,jint sampleFrames,jint sampleRateHz){if(!g_engine)return;auto*data=static_cast<float*>(env->GetDirectBufferAddress(sampleByteBuffer));if(!data)return;g_engine->noteOn(midiNote,rootNote,velocity,data,static_cast<size_t>(sampleFrames),sampleRateHz);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOff(JNIEnv*,jobject,jint midiNote){if(g_engine)g_engine->noteOff(midiNote);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeAllNotesOff(JNIEnv*,jobject){if(g_engine)g_engine->allNotesOff();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeLoadSoundFont(JNIEnv* env,jobject,jstring path){if(!g_engine)g_engine=std::make_unique<AudioEngine>();const char*cpath=env->GetStringUTFChars(path,nullptr);bool ok=g_engine->loadSoundFont(std::string(cpath));env->ReleaseStringUTFChars(path,cpath);return ok?JNI_TRUE:JNI_FALSE;}

extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeLoadMelodySoundFont(JNIEnv* env,jobject,jstring path){if(!g_engine)g_engine=std::make_unique<AudioEngine>();const char*cpath=env->GetStringUTFChars(path,nullptr);bool ok=g_engine->loadMelodySoundFont(std::string(cpath));env->ReleaseStringUTFChars(path,cpath);return ok?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeLoadDrumSoundFont(JNIEnv* env,jobject,jstring path){if(!g_engine)g_engine=std::make_unique<AudioEngine>();const char*cpath=env->GetStringUTFChars(path,nullptr);bool ok=g_engine->loadDrumSoundFont(std::string(cpath));env->ReleaseStringUTFChars(path,cpath);return ok?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeIsSoundFontLoaded(JNIEnv*,jobject){return(g_engine&&g_engine->isSoundFontLoaded())?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeUnloadSoundFont(JNIEnv*,jobject){if(g_engine)g_engine->unloadSoundFont();}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOn(JNIEnv*,jobject,jint midiNote,jfloat velocity){if(g_engine)g_engine->sfNoteOnChannel(0,midiNote,velocity);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOff(JNIEnv*,jobject,jint midiNote){if(g_engine)g_engine->sfNoteOffChannel(0,midiNote);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOnChannel(JNIEnv*,jobject,jint channel,jint midiNote,jfloat velocity){if(g_engine)g_engine->sfNoteOnChannel(channel,midiNote,velocity);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOffChannel(JNIEnv*,jobject,jint channel,jint midiNote){if(g_engine)g_engine->sfNoteOffChannel(channel,midiNote);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetChannelPreset(JNIEnv*,jobject,jint channel,jint bank,jint program){if(g_engine)g_engine->sfSetChannelPreset(channel,bank,program);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetChannelPresetWithName(JNIEnv* env,jobject,jint channel,jint bank,jint program,jstring voiceName){
    if(!g_engine) return;
    const char* name = voiceName ? env->GetStringUTFChars(voiceName, nullptr) : nullptr;
    g_engine->sfSetChannelPreset(channel, bank, program, name ? std::string(name) : std::string());
    if(name) env->ReleaseStringUTFChars(voiceName, name);
}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetChannelMixer(JNIEnv*,jobject,jint channel,jint volume,jint pan,jint expression,jint reverbSend,jint chorusSend){if(g_engine)g_engine->sfSetChannelMixer(channel,volume,pan,expression,reverbSend,chorusSend);}

extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetChannelExpression(JNIEnv*,jobject,jint channel,jint expression){if(g_engine)g_engine->sfSetChannelExpression(channel,expression);}
extern "C" JNIEXPORT void JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetMasterGain(JNIEnv*,jobject,jfloat gain){if(g_engine)g_engine->sfSetMasterGain(gain);}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeGetSoundFontPresets(JNIEnv* env,jobject){
    if(!g_engine) return env->NewStringUTF("");
    const std::string presets = g_engine->sfPresetList();
    const std::string safePresets = sanitizeUtf8ForJni(presets.c_str());
    return env->NewStringUTF(safePresets.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeParseStyle(JNIEnv* env,jobject,jbyteArray styBytes){if(styBytes==nullptr)return JNI_FALSE;jsize len=env->GetArrayLength(styBytes);std::vector<uint8_t>buf(len);env->GetByteArrayRegion(styBytes,0,len,reinterpret_cast<jbyte*>(buf.data()));g_lastParsedStyle=std::make_unique<StyleParser>();return g_lastParsedStyle->parse(buf.data(),buf.size())?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jint JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionCount(JNIEnv*,jobject){return g_lastParsedStyle?static_cast<jint>(g_lastParsedStyle->sections().size()):0;}
extern "C" JNIEXPORT jint JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPpq(JNIEnv*,jobject){return g_lastParsedStyle?g_lastParsedStyle->ppq():480;}
extern "C" JNIEXPORT jdouble JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetDefaultTempoBpm(JNIEnv*,jobject){return g_lastParsedStyle?g_lastParsedStyle->defaultTempoBpm():120.0;}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionNames(JNIEnv* env,jobject){jclass stringClass=env->FindClass("java/lang/String");if(!g_lastParsedStyle)return env->NewObjectArray(0,stringClass,nullptr);const auto&sections=g_lastParsedStyle->sections();jobjectArray result=env->NewObjectArray(static_cast<jsize>(sections.size()),stringClass,nullptr);int i=0;for(const auto&[sec,data]:sections)env->SetObjectArrayElement(result,i++,env->NewStringUTF(styleSectionToString(sec).c_str()));return result;}
extern "C" JNIEXPORT jint JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionLengthTicks(JNIEnv* env,jobject,jstring sectionName){if(!g_lastParsedStyle)return 0;const char*cstr=env->GetStringUTFChars(sectionName,nullptr);StyleSection sec=styleSectionFromString(cstr);env->ReleaseStringUTFChars(sectionName,cstr);auto it=g_lastParsedStyle->sections().find(sec);return it!=g_lastParsedStyle->sections().end()?static_cast<jint>(it->second.lengthTicks):0;}
extern "C" JNIEXPORT jint JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartCount(JNIEnv* env,jobject,jstring sectionName){if(!g_lastParsedStyle)return 0;const char*cstr=env->GetStringUTFChars(sectionName,nullptr);StyleSection sec=styleSectionFromString(cstr);env->ReleaseStringUTFChars(sectionName,cstr);auto it=g_lastParsedStyle->sections().find(sec);return it!=g_lastParsedStyle->sections().end()?static_cast<jint>(it->second.parts.size()):0;}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartName(JNIEnv* env,jobject,jstring sectionName,jint partIndex){if(!g_lastParsedStyle)return env->NewStringUTF("");const char*cstr=env->GetStringUTFChars(sectionName,nullptr);StyleSection sec=styleSectionFromString(cstr);env->ReleaseStringUTFChars(sectionName,cstr);auto it=g_lastParsedStyle->sections().find(sec);if(it==g_lastParsedStyle->sections().end()||partIndex<0||static_cast<size_t>(partIndex)>=it->second.parts.size())return env->NewStringUTF("");return env->NewStringUTF(it->second.parts[partIndex].name.c_str());}
// Packed event format: [tick,status,data1,data2,metaType,payloadLength,payloadByte...] repeated.
// Unlike the old implementation, this preserves CC, Program Change, Pitch Bend,
// Channel/Poly Pressure and SysEx instead of dropping everything except notes.
extern "C" JNIEXPORT jintArray JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartEvents(JNIEnv* env,jobject,jstring sectionName,jint partIndex){
    if(!g_lastParsedStyle)return env->NewIntArray(0);
    const char*cstr=env->GetStringUTFChars(sectionName,nullptr);StyleSection sec=styleSectionFromString(cstr);env->ReleaseStringUTFChars(sectionName,cstr);
    auto it=g_lastParsedStyle->sections().find(sec);
    if(it==g_lastParsedStyle->sections().end()||partIndex<0||static_cast<size_t>(partIndex)>=it->second.parts.size())return env->NewIntArray(0);
    const auto&events=it->second.parts[partIndex].events;
    std::vector<jint>flat;
    for(const auto&ev:events){
        flat.push_back(static_cast<jint>(ev.tick));
        flat.push_back(static_cast<jint>(ev.status));
        flat.push_back(static_cast<jint>(ev.data1));
        flat.push_back(static_cast<jint>(ev.data2));
        flat.push_back(static_cast<jint>(ev.metaType));
        flat.push_back(static_cast<jint>(ev.metaOrSysexData.size()));
        for(uint8_t b:ev.metaOrSysexData)flat.push_back(static_cast<jint>(b));
    }
    jintArray result=env->NewIntArray(static_cast<jsize>(flat.size()));
    if(!flat.empty())env->SetIntArrayRegion(result,0,static_cast<jsize>(flat.size()),flat.data());
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartCasm(JNIEnv* env,jobject,jstring sectionName,jint partIndex){if(!g_lastParsedStyle)return env->NewStringUTF("");const char*cstr=env->GetStringUTFChars(sectionName,nullptr);StyleSection sec=styleSectionFromString(cstr);env->ReleaseStringUTFChars(sectionName,cstr);auto it=g_lastParsedStyle->sections().find(sec);if(it==g_lastParsedStyle->sections().end()||partIndex<0||static_cast<size_t>(partIndex)>=it->second.parts.size())return env->NewStringUTF("");const auto&part=it->second.parts[partIndex];if(part.casmPolicies.empty())return env->NewStringUTF("");std::string out;for(size_t i=0;i<part.casmPolicies.size();++i){const auto&c=part.casmPolicies[i];if(i)out+=';';out+=std::to_string(c.sourceChannel)+"|"+std::to_string(c.destinationChannel)+"|"+c.voiceName+"|"+std::to_string(c.sourceChordRoot)+"|"+std::to_string(c.sourceChordType)+"|"+std::to_string(c.ntr)+"|"+std::to_string(c.ntt)+"|"+std::to_string(c.highKey)+"|"+std::to_string(c.noteLimitLow)+"|"+std::to_string(c.noteLimitHigh)+"|"+std::to_string(c.rtr)+"|"+std::to_string(c.bassOn?1:0)+"|"+std::to_string(c.chordMuteMask)+"|"+std::to_string(c.sourceNoteLow)+"|"+std::to_string(c.sourceNoteHigh);}return env->NewStringUTF(out.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeFindCasm(JNIEnv* env,jobject,jbyteArray styBytes){jsize len=env->GetArrayLength(styBytes);std::vector<uint8_t>buf(len);env->GetByteArrayRegion(styBytes,0,len,reinterpret_cast<jbyte*>(buf.data()));std::string result;for(size_t i=0;i+8<=buf.size();++i){if(buf[i]=='C'&&buf[i+1]=='A'&&buf[i+2]=='S'&&buf[i+3]=='M'){uint32_t casmLen=(uint32_t(buf[i+4])<<24)|(uint32_t(buf[i+5])<<16)|(uint32_t(buf[i+6])<<8)|buf[i+7];char header[128];snprintf(header,sizeof(header),"CASM@%zu len=%u",i,casmLen);result+=header;result+="\n";size_t casmEnd=std::min(buf.size(),i+8+size_t(casmLen));result+="Chunks: ";for(size_t j=i+8;j+4<=casmEnd;++j){if(buf[j]>='A'&&buf[j]<='Z'&&buf[j+1]>='A'&&buf[j+2]>='A'&&buf[j+3]>='A'&&buf[j+3]<='Z'){char chunk[24];snprintf(chunk,sizeof(chunk),"%.4s@%zu ",reinterpret_cast<const char*>(&buf[j]),j-i);result+=chunk;}}result+="\n";break;}}if(result.empty())result="CASM NOT FOUND";return env->NewStringUTF(result.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeExtractVoiceMap(JNIEnv* env,jobject,jbyteArray styBytes){jsize len=env->GetArrayLength(styBytes);std::vector<uint8_t>buf(len);env->GetByteArrayRegion(styBytes,0,len,reinterpret_cast<jbyte*>(buf.data()));std::string result;size_t casmStart=0;uint32_t casmLen=0;for(size_t i=0;i+8<=buf.size();++i){if(buf[i]=='C'&&buf[i+1]=='A'&&buf[i+2]=='S'&&buf[i+3]=='M'){casmStart=i;casmLen=(uint32_t(buf[i+4])<<24)|(uint32_t(buf[i+5])<<16)|(uint32_t(buf[i+6])<<8)|buf[i+7];break;}}if(casmLen==0)return env->NewStringUTF("");size_t casmEnd=std::min(buf.size(),casmStart+8+size_t(casmLen));for(size_t i=casmStart;i+4<casmEnd;){if(buf[i]=='C'&&buf[i+1]=='t'&&buf[i+2]=='b'&&buf[i+3]=='2'){i+=4;int skip=0;while(i<casmEnd&&skip<8&&buf[i]!='/'){i++;skip++;}if(i>=casmEnd||buf[i]!='/')continue;i++;if(i>=casmEnd)continue;int partNum=buf[i++];while(i<casmEnd&&(buf[i]==0||buf[i]==' '))i++;size_t nameStart=i;while(i<casmEnd&&(std::isalnum(buf[i])||buf[i]=='-'||buf[i]=='_'||buf[i]=='.'||buf[i]=='+'||buf[i]=='@'))i++;if(i>nameStart&&partNum>=1&&partNum<=16){std::string voiceName(reinterpret_cast<const char*>(buf.data()+nameStart),i-nameStart);bool allDigits=true;for(char c:voiceName){if(!std::isdigit((unsigned char)c)){allDigits=false;break;}}if(!allDigits&&voiceName.length()<32)result+=std::to_string(partNum)+":"+voiceName+";";}continue;}i++;}return env->NewStringUTF(result.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeDumpMarkers(JNIEnv* env,jobject,jbyteArray styBytes){jsize len=env->GetArrayLength(styBytes);std::vector<uint8_t>buf(len);env->GetByteArrayRegion(styBytes,0,len,reinterpret_cast<jbyte*>(buf.data()));std::string result;for(size_t i=0;i+3<buf.size();++i){if(buf[i]==0xFF&&(buf[i+1]==0x06||buf[i+1]==0x01)){uint8_t vlen=buf[i+2];if(vlen>0&&i+3+vlen<=buf.size()){std::string text(reinterpret_cast<const char*>(buf.data()+i+3),vlen);bool ok=true;for(char c:text){if(c<32||c>126){ok=false;break;}}if(ok&&text.size()>2&&text.size()<60)result+="["+text+"] ";}}}return env->NewStringUTF(result.c_str());}

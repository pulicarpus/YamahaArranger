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

namespace {
std::unique_ptr<AudioEngine> g_engine;
std::unique_ptr<StyleParser> g_lastParsedStyle;
}

JavaVM* g_jvm = nullptr;
jclass g_debugLogClass = nullptr;
jmethodID g_debugLogAddMethod = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeInitLogger(
    JNIEnv* env, jobject) {
    env->GetJavaVM(&g_jvm);
    jclass localClass = env->FindClass("com/yourapp/yamahaarranger/ui/DebugLog");
    if (localClass == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeLib", "Cannot find DebugLog class");
        return;
    }
    g_debugLogClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    g_debugLogAddMethod = env->GetStaticMethodID(
        g_debugLogClass, "add", "(Ljava/lang/String;)V");
    __android_log_print(ANDROID_LOG_INFO, "NativeLib", "Logger initialized");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStart(JNIEnv*, jobject) {
    if (!g_engine) g_engine = std::make_unique<AudioEngine>();
    return g_engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStop(JNIEnv*, jobject) {
    if (g_engine) g_engine->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOn(
    JNIEnv* env, jobject, jint midiNote, jint rootNote, jfloat velocity,
    jobject sampleByteBuffer, jint sampleFrames, jint sampleRateHz) {
    if (!g_engine) return;
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(sampleByteBuffer));
    if (!data) return;
    g_engine->noteOn(midiNote, rootNote, velocity, data,
                     static_cast<size_t>(sampleFrames), sampleRateHz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOff(
    JNIEnv*, jobject, jint midiNote) {
    if (g_engine) g_engine->noteOff(midiNote);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeAllNotesOff(JNIEnv*, jobject) {
    if (g_engine) g_engine->allNotesOff();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeLoadSoundFont(
    JNIEnv* env, jobject, jstring path) {
    if (!g_engine) g_engine = std::make_unique<AudioEngine>();
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = g_engine->loadSoundFont(std::string(cpath));
    env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeIsSoundFontLoaded(
    JNIEnv*, jobject) {
    return (g_engine && g_engine->isSoundFontLoaded()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeUnloadSoundFont(
    JNIEnv*, jobject) {
    if (g_engine) g_engine->unloadSoundFont();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOn(
    JNIEnv*, jobject, jint midiNote, jfloat velocity) {
    if (g_engine) g_engine->sfNoteOnChannel(0, midiNote, velocity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOff(
    JNIEnv*, jobject, jint midiNote) {
    if (g_engine) g_engine->sfNoteOffChannel(0, midiNote);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOnChannel(
    JNIEnv*, jobject, jint channel, jint midiNote, jfloat velocity) {
    if (g_engine) g_engine->sfNoteOnChannel(channel, midiNote, velocity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSfNoteOffChannel(
    JNIEnv*, jobject, jint channel, jint midiNote) {
    if (g_engine) g_engine->sfNoteOffChannel(channel, midiNote);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeSetChannelPreset(
    JNIEnv*, jobject, jint channel, jint bank, jint program) {
    if (g_engine) g_engine->sfSetChannelPreset(channel, bank, program);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeParseStyle(
    JNIEnv* env, jobject, jbyteArray styBytes) {
    jsize len = env->GetArrayLength(styBytes);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(styBytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    g_lastParsedStyle = std::make_unique<StyleParser>();
    return g_lastParsedStyle->parse(buf.data(), buf.size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionCount(JNIEnv*, jobject) {
    if (!g_lastParsedStyle) return 0;
    return static_cast<jint>(g_lastParsedStyle->sections().size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPpq(JNIEnv*, jobject) {
    return g_lastParsedStyle ? g_lastParsedStyle->ppq() : 480;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionNames(JNIEnv* env, jobject) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!g_lastParsedStyle) return env->NewObjectArray(0, stringClass, nullptr);
    const auto& sections = g_lastParsedStyle->sections();
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(sections.size()), stringClass, nullptr);
    int i = 0;
    for (const auto& [sec, data] : sections) {
        env->SetObjectArrayElement(result, i++, env->NewStringUTF(styleSectionToString(sec).c_str()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionLengthTicks(
    JNIEnv* env, jobject, jstring sectionName) {
    if (!g_lastParsedStyle) return 0;
    const char* cstr = env->GetStringUTFChars(sectionName, nullptr);
    StyleSection sec = styleSectionFromString(cstr);
    env->ReleaseStringUTFChars(sectionName, cstr);
    auto it = g_lastParsedStyle->sections().find(sec);
    return it != g_lastParsedStyle->sections().end() ? static_cast<jint>(it->second.lengthTicks) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartCount(
    JNIEnv* env, jobject, jstring sectionName) {
    if (!g_lastParsedStyle) return 0;
    const char* cstr = env->GetStringUTFChars(sectionName, nullptr);
    StyleSection sec = styleSectionFromString(cstr);
    env->ReleaseStringUTFChars(sectionName, cstr);
    auto it = g_lastParsedStyle->sections().find(sec);
    return it != g_lastParsedStyle->sections().end() ? static_cast<jint>(it->second.parts.size()) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartName(
    JNIEnv* env, jobject, jstring sectionName, jint partIndex) {
    if (!g_lastParsedStyle) return env->NewStringUTF("");
    const char* cstr = env->GetStringUTFChars(sectionName, nullptr);
    StyleSection sec = styleSectionFromString(cstr);
    env->ReleaseStringUTFChars(sectionName, cstr);
    auto it = g_lastParsedStyle->sections().find(sec);
    if (it == g_lastParsedStyle->sections().end() ||
        partIndex < 0 || static_cast<size_t>(partIndex) >= it->second.parts.size()) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(it->second.parts[partIndex].name.c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetPartEvents(
    JNIEnv* env, jobject, jstring sectionName, jint partIndex) {
    if (!g_lastParsedStyle) return env->NewIntArray(0);
    const char* cstr = env->GetStringUTFChars(sectionName, nullptr);
    StyleSection sec = styleSectionFromString(cstr);
    env->ReleaseStringUTFChars(sectionName, cstr);
    auto it = g_lastParsedStyle->sections().find(sec);
    if (it == g_lastParsedStyle->sections().end() ||
        partIndex < 0 || static_cast<size_t>(partIndex) >= it->second.parts.size()) {
        return env->NewIntArray(0);
    }
    const auto& events = it->second.parts[partIndex].events;
    std::vector<jint> flat;
    flat.reserve(events.size() * 4);
    for (const auto& ev : events) {
        uint8_t hi = ev.status & 0xF0;
        if (hi != 0x90 && hi != 0x80) continue;
        flat.push_back(static_cast<jint>(ev.tick));
        flat.push_back(ev.status);
        flat.push_back(ev.data1);
        flat.push_back(ev.data2);
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(flat.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

// ★★★ FUNGSI CASM FINDER v3 — dump 512 byte offset 100-500 ★★★
extern "C" JNIEXPORT jstring JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeFindCasm(
    JNIEnv* env, jobject, jbyteArray styBytes) {
    jsize len = env->GetArrayLength(styBytes);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(styBytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    std::string result;

    for (size_t i = 0; i + 8 <= buf.size(); ++i) {
        if (buf[i] == 'C' && buf[i+1] == 'A' && buf[i+2] == 'S' && buf[i+3] == 'M') {
            uint32_t casmLen = ((uint32_t)buf[i+4] << 24) |
                               ((uint32_t)buf[i+5] << 16) |
                               ((uint32_t)buf[i+6] << 8) |
                               ((uint32_t)buf[i+7]);

            char header[128];
            snprintf(header, sizeof(header), "CASM@%zu len=%u", i, casmLen);
            result += header;
            result += "\n";

            size_t casmEnd = i + 8 + casmLen;
            if (casmEnd > buf.size()) casmEnd = buf.size();

            result += "Chunks: ";
            for (size_t j = i + 8; j + 4 <= casmEnd; ++j) {
                if (buf[j] >= 'A' && buf[j] <= 'Z' &&
                    buf[j+1] >= 'A' && buf[j+1] <= 'Z' &&
                    buf[j+2] >= 'A' && buf[j+2] <= 'Z' &&
                    buf[j+3] >= 'A' && buf[j+3] <= 'Z') {
                    char chunk[24];
                    snprintf(chunk, sizeof(chunk), "%.4s@%zu ",
                             reinterpret_cast<const char*>(&buf[j]), j - i);
                    result += chunk;
                }
            }
            result += "\n";

            size_t dumpStart = i + 100;
            size_t dumpEnd = i + 500;
            if (dumpStart > casmEnd) dumpStart = casmEnd;
            if (dumpEnd > casmEnd) dumpEnd = casmEnd;

            for (size_t j = dumpStart; j < dumpEnd; j += 16) {
                char line[160];
                int pos = snprintf(line, sizeof(line), "%04zu: ", j - i);
                for (size_t k = 0; k < 16 && j + k < dumpEnd; ++k) {
                    pos += snprintf(line + pos, sizeof(line) - pos, "%02X ", buf[j + k]);
                }
                pos += snprintf(line + pos, sizeof(line) - pos, " | ");
                for (size_t k = 0; k < 16 && j + k < dumpEnd; ++k) {
                    uint8_t c = buf[j + k];
                    line[pos++] = (c >= 32 && c < 127) ? (char)c : '.';
                }
                line[pos] = 0;
                result += line;
                result += "\n";
            }

            break;
        }
    }

    if (result.empty()) result = "CASM NOT FOUND";
    return env->NewStringUTF(result.c_str());
}
// ═════════════════════════════════════════════════════
// ★★★ VOICE MAP EXTRACTOR ★★★
// ═════════════════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeExtractVoiceMap(
    JNIEnv* env, jobject, jbyteArray styBytes) {
    jsize len = env->GetArrayLength(styBytes);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(styBytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    std::string result;

    // Find CASM
    size_t casmStart = 0;
    uint32_t casmLen = 0;
    for (size_t i = 0; i + 8 <= buf.size(); ++i) {
        if (buf[i] == 'C' && buf[i+1] == 'A' && buf[i+2] == 'S' && buf[i+3] == 'M') {
            casmStart = i;
            casmLen = ((uint32_t)buf[i+4] << 24) |
                      ((uint32_t)buf[i+5] << 16) |
                      ((uint32_t)buf[i+6] << 8) |
                      ((uint32_t)buf[i+7]);
            break;
        }
    }
    if (casmLen == 0) return env->NewStringUTF("");

    size_t casmEnd = casmStart + 8 + casmLen;
    if (casmEnd > buf.size()) casmEnd = buf.size();

    // Scan for "Ctb2" markers
    size_t i = casmStart;
    while (i + 4 < casmEnd) {
        if (buf[i] == 'C' && buf[i+1] == 't' && buf[i+2] == 'b' && buf[i+3] == '2') {
            i += 4;

            // Skip up to 8 bytes to find '/'
            int skip = 0;
            while (i < casmEnd && skip < 8 && buf[i] != '/') { i++; skip++; }
            if (i >= casmEnd || buf[i] != '/') continue;
            i++;

            // Read part number (1 byte)
            if (i >= casmEnd) continue;
            int partNum = buf[i];
            i++;

            // Skip whitespace / nulls
            while (i < casmEnd && (buf[i] == 0 || buf[i] == ' ')) i++;

            // Read voice name (alphanumeric + dash + underscore)
            size_t nameStart = i;
            while (i < casmEnd &&
                   (std::isalnum(buf[i]) || buf[i] == '-' || buf[i] == '_')) {
                i++;
            }

            if (i > nameStart && partNum >= 1 && partNum <= 16) {
                std::string voiceName(
                    reinterpret_cast<const char*>(buf.data() + nameStart),
                    i - nameStart);

                // Skip if purely digits
                bool allDigits = true;
                for (char c : voiceName) {
                    if (!std::isdigit(static_cast<unsigned char>(c))) {
                        allDigits = false;
                        break;
                    }
                }

                if (!allDigits && voiceName.length() < 32) {
                    result += std::to_string(partNum) + ":" + voiceName + ";";
                }
            }
            continue;
        }
        i++;
    }

    return env->NewStringUTF(result.c_str());
}
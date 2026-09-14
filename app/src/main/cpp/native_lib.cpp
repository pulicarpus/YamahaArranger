#include <jni.h>
#include <memory>
#include "audio_engine.h"
#include "style_parser.h"

namespace {
std::unique_ptr<AudioEngine> g_engine;
std::unique_ptr<StyleParser> g_lastParsedStyle;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStart(JNIEnv*, jobject) {
    if (!g_engine) g_engine = std::make_unique<AudioEngine>();
    return g_engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStop(JNIEnv*, jobject) {
    if (g_engine) g_engine->stop();
}

// sampleData is a direct ByteBuffer of 32-bit floats owned by the Kotlin
// SoundFont/sample manager (kept alive there) — no copy across JNI.
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

// Returns true if the style could be parsed; section data is queried
// separately via nativeGetSectionPartCount/nativeGetSectionEvents (not
// shown in Phase 1 — see style/StyleRepository.kt TODO) once you need to
// surface it to Kotlin. For now this validates parseability early.
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

// Returns e.g. {"MainA", "MainB", "IntroA", ...} for every section this
// style actually contains (styles rarely have all of them).
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetSectionNames(JNIEnv* env, jobject) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (!g_lastParsedStyle) return env->NewObjectArray(0, stringClass, nullptr);

    const auto& sections = g_lastParsedStyle->sections();
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(sections.size()), stringClass, nullptr);
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

// Flattened as [tick, status, data1, data2] quadruples — kept as a single
// primitive array (no per-event object allocation) since a section can
// have hundreds of note events and this crosses JNI on every style load.
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
    // Only channel-voice note on/off events matter for playback; skip
    // CC/program-change here to keep the array small (Phase 2 scope).
    std::vector<jint> flat;
    flat.reserve(events.size() * 4);
    for (const auto& ev : events) {
        uint8_t hi = ev.status & 0xF0;
        if (hi != 0x90 && hi != 0x80) continue; // note on / note off only
        flat.push_back(static_cast<jint>(ev.tick));
        flat.push_back(ev.status);
        flat.push_back(ev.data1);
        flat.push_back(ev.data2);
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(flat.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

} // namespace

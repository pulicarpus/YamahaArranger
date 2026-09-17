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
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeInitLogger(JNIEnv* env, jobject) {
    env->GetJavaVM(&g_jvm);
    jclass localClass = env->FindClass("com/yourapp/yamahaarranger/ui/DebugLog");
    if (localClass == nullptr) return;
    g_debugLogClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    g_debugLogAddMethod = env->GetStaticMethodID(g_debugLogClass, "add", "(Ljava/lang/String;)V");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStart(JNIEnv*, jobject) {
    if (!g_engine) g_engine = std::make_unique<AudioEngine>();
    return g_engine->start();
}
extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeStop(JNIEnv*, jobject) { if (g_engine) g_engine->stop(); }
extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOn(JNIEnv* env, jobject, jint midiNote, jint rootNote, jfloat velocity, jobject sampleByteBuffer, jint sampleFrames, jint sampleRateHz) {
    if (!g_engine) return;
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(sampleByteBuffer));
    if (!data) return;
    g_engine->noteOn(midiNote, rootNote, velocity, data, static_cast<size_t>(sampleFrames), sampleRateHz);
}
extern "C" JNIEXPORT void JNICALL
Java_com_yourapp_yamahaarranger_audio_NativeAudioBridge_nativeNoteOff(JNIEnv*, jobject, jint midiNote) { if (g_engine) g_engine->noteOff(midiNote); }
extern "C" JNIEXPORT void JNICALL
Java_com_yourapp.yamahaarranger_audio_NativeAudioBridge_nativeAllNotesOff(JNIEnv*, jobject) { if (g_engine) g_engine->allNotesOff(); }

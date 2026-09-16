#include "soundfont_player.h"
#include <android/log.h>
#include <mutex>
#include <cstdarg>
#include <cstdio>
#include <jni.h>

#define LOG_TAG "FluidSynthPlayer"

extern JavaVM* g_jvm;
extern jclass g_debugLogClass;
extern jmethodID g_debugLogAddMethod;

static void uiLog(const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);

    if (g_jvm && g_debugLogClass && g_debugLogAddMethod) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }
        if (env) {
            jstring jmsg = env->NewStringUTF(buf);
            env->CallStaticVoidMethod(g_debugLogClass, g_debugLogAddMethod, jmsg);
            env->DeleteLocalRef(jmsg);
            if (attached) g_jvm->DetachCurrentThread();
        }
    }
}

#define LOGI(...) uiLog(__VA_ARGS__)
#define LOGE(...) uiLog(__VA_ARGS__)

static std::mutex g_synthMutex;

SoundFontPlayer::SoundFontPlayer() {
    LOGI("Creating FluidSynth settings...");
    settings_ = new_fluid_settings();
    if (!settings_) {
        LOGE("Failed to create settings");
        return;
    }

    fluid_settings_setnum(settings_, "synth.sample-rate", 48000.0);
    fluid_settings_setint(settings_, "synth.polyphony", 128);
    fluid_settings_setnum(settings_, "synth.gain", 1.0);
    fluid_settings_setint(settings_, "synth.reverb.active", 1);
    fluid_settings_setint(settings_, "synth.chorus.active", 1);
    fluid_settings_setstr(settings_, "audio.driver", "null");

    LOGI("Creating FluidSynth synth...");
    synth_ = new_fluid_synth(settings_);
    if (!synth_) {
        LOGE("Failed to create synth");
        delete_fluid_settings(settings_);
        settings_ = nullptr;
    } else {
        LOGI("FluidSynth synth created OK");
    }
}

SoundFontPlayer::~SoundFontPlayer() {
    unload();
    if (synth_) {
        delete_fluid_synth(synth_);
        synth_ = nullptr;
    }
    if (settings_) {
        delete_fluid_settings(settings_);
        settings_ = nullptr;
    }
}

bool SoundFontPlayer::load(const std::string& path) {
    if (!synth_) {
        LOGE("Cannot load SF2 - synth null");
        return false;
    }
    std::lock_guard<std::mutex> lock(g_synthMutex);

    LOGI("Loading SF2: %s", path.c_str());
    sfId_ = fluid_synth_sfload(synth_, path.c_str(), 1);
    if (sfId_ == FLUID_FAILED) {
        LOGE("fluid_synth_sfload FAILED");
        return false;
    }

    LOGI("SF2 loaded, id=%d", sfId_);

    // GM percussion is MIDI channel 10, zero-based channel 9. Explicitly
    // select the standard percussion bank so drum notes do not fall back to
    // the melodic program 0 on a SoundFont whose channel defaults are reset.
    fluid_synth_bank_select(synth_, 9, 128);
    fluid_synth_program_change(synth_, 9, 0);

    for (int ch = 0; ch < 16; ++ch) {
        if (ch == 9) continue;
        fluid_synth_bank_select(synth_, ch, 0);
        fluid_synth_program_change(synth_, ch, 0);
    }

    fluid_synth_cc(synth_, 0, 7, 127);
    fluid_synth_cc(synth_, 1, 7, 127);
    fluid_synth_cc(synth_, 2, 7, 127);
    fluid_synth_cc(synth_, 3, 7, 100);
    fluid_synth_cc(synth_, 4, 7, 100);
    fluid_synth_cc(synth_, 5, 7, 95);
    fluid_synth_cc(synth_, 6, 7, 115);
    fluid_synth_cc(synth_, 7, 7, 115);
    fluid_synth_cc(synth_, 8, 7, 127);
    fluid_synth_cc(synth_, 9, 7, 127);
    for (int ch = 10; ch < 16; ++ch) {
        fluid_synth_cc(synth_, ch, 7, 110);
    }

    LOGI("Channels assigned + volumes set; percussion ch9 bank=128");
    return true;
}

void SoundFontPlayer::unload() {
    if (synth_ && sfId_ >= 0) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        fluid_synth_sfunload(synth_, sfId_, 1);
        sfId_ = -1;
        LOGI("SF2 unloaded");
    }
}

void SoundFontPlayer::render(float* out, int numFrames) {
    if (!synth_) {
        for (int i = 0; i < numFrames * 2; ++i) out[i] = 0.0f;
        return;
    }
    std::lock_guard<std::mutex> lock(g_synthMutex);
    fluid_synth_write_float(synth_, numFrames, out, 0, 2, out, 1, 2);
}

void SoundFontPlayer::noteOn(int channel, int key, float velocity) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    int vel = (int)(velocity * 127.0f);
    if (vel < 1) vel = 1;
    if (vel > 127) vel = 127;
    fluid_synth_noteon(synth_, channel, key, vel);
}

void SoundFontPlayer::noteOff(int channel, int key) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    fluid_synth_noteoff(synth_, channel, key);
}

void SoundFontPlayer::allNotesOff() {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    for (int ch = 0; ch < 16; ++ch) {
        fluid_synth_all_notes_off(synth_, ch);
    }
}

void SoundFontPlayer::setChannelPreset(int channel, int bank, int program) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    if (channel == 9) bank = 128;
    fluid_synth_bank_select(synth_, channel, bank);
    fluid_synth_program_change(synth_, channel, program);
    LOGI("Ch %d to bank=%d prog=%d", channel, bank, program);
}

int SoundFontPlayer::presetCount() const {
    if (!synth_ || sfId_ < 0) return 0;
    fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth_, sfId_);
    if (!sfont) return 0;
    int count = 0;
    fluid_sfont_iteration_start(sfont);
    while (fluid_sfont_iteration_next(sfont)) count++;
    return count;
}

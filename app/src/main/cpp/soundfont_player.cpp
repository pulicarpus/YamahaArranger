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
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
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
    if (!settings_) { LOGE("Failed to create settings"); return; }
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
    if (synth_) { delete_fluid_synth(synth_); synth_ = nullptr; }
    if (settings_) { delete_fluid_settings(settings_); settings_ = nullptr; }
}

bool SoundFontPlayer::load(const std::string& path) {
    // Compatibility entry point used by the existing Android picker.
    // First SF2 becomes MELODY; second SF2 becomes DRUM. This lets the
    // existing single picker load two independent files without changing
    // the native JNI API yet.
    if (melodySfId_ < 0) return loadMelody(path);
    if (drumSfId_ < 0) return loadDrum(path);
    return loadMelody(path);
}

bool SoundFontPlayer::loadMelody(const std::string& path) { return loadRole(path, false); }
bool SoundFontPlayer::loadDrum(const std::string& path) { return loadRole(path, true); }

bool SoundFontPlayer::loadRole(const std::string& path, bool drum) {
    if (!synth_) { LOGE("Cannot load SF2 - synth null"); return false; }
    std::lock_guard<std::mutex> lock(g_synthMutex);
    int& targetId = drum ? drumSfId_ : melodySfId_;
    if (targetId >= 0) {
        fluid_synth_sfunload(synth_, targetId, 1);
        targetId = -1;
    }
    LOGI("Loading %s SF2: %s", drum ? "DRUM" : "MELODY", path.c_str());
    targetId = fluid_synth_sfload(synth_, path.c_str(), 1);
    if (targetId == FLUID_FAILED) {
        targetId = -1;
        LOGE("fluid_synth_sfload FAILED for %s", drum ? "DRUM" : "MELODY");
        return false;
    }
    LOGI("%s SF2 loaded, id=%d", drum ? "DRUM" : "MELODY", targetId);
    if (drum) {
        assignChannelToRole(9, true, 128, 0);
        fluid_synth_cc(synth_, 9, 7, 127);
    } else {
        for (int ch = 0; ch < 16; ++ch) {
            if (ch == 9) continue;
            assignChannelToRole(ch, false, 0, 0);
        }
        for (int ch = 0; ch < 16; ++ch) {
            if (ch == 9) continue;
            const int defaultVolume = (ch == 13) ? 98 : ((ch <= 2 || ch == 8) ? 127 : (ch <= 5 ? 100 : 115));
            fluid_synth_cc(synth_, ch, 7, defaultVolume);
        }
    }
    LOGI("SF2 role ready: %s", drum ? "DRUM bank=128 ch9" : "MELODY bank=0 channels=1-16 except ch10");
    return true;
}

void SoundFontPlayer::assignChannelToRole(int channel, bool drum, int bank, int program) {
    if (!synth_) return;
    const int sfId = drum ? (drumSfId_ >= 0 ? drumSfId_ : melodySfId_) : melodySfId_;
    if (sfId < 0) return;
    fluid_synth_program_select(synth_, channel, sfId, bank, program);
    LOGI("Ch %d -> %s SF2 id=%d bank=%d prog=%d", channel, drum ? "DRUM" : "MELODY", sfId, bank, program);
}

void SoundFontPlayer::unload() {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    for (int ch = 0; ch < 16; ++ch) fluid_synth_all_notes_off(synth_, ch);
    if (drumSfId_ >= 0) {
        fluid_synth_sfunload(synth_, drumSfId_, 1);
        drumSfId_ = -1;
        LOGI("DRUM SF2 unloaded");
    }
    if (melodySfId_ >= 0) {
        fluid_synth_sfunload(synth_, melodySfId_, 1);
        melodySfId_ = -1;
        LOGI("MELODY SF2 unloaded");
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
    int vel = static_cast<int>(velocity * 127.0f);
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
    for (int ch = 0; ch < 16; ++ch) fluid_synth_all_notes_off(synth_, ch);
}

void SoundFontPlayer::setChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    channel = std::max(0, std::min(15, channel));
    fluid_synth_cc(synth_, channel, 7, std::max(0, std::min(127, volume)));
    fluid_synth_cc(synth_, channel, 10, std::max(0, std::min(127, pan)));
    fluid_synth_cc(synth_, channel, 11, std::max(0, std::min(127, expression)));
    fluid_synth_cc(synth_, channel, 91, std::max(0, std::min(127, reverbSend)));
    fluid_synth_cc(synth_, channel, 93, std::max(0, std::min(127, chorusSend)));
}

void SoundFontPlayer::setChannelExpression(int channel, int expression) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    channel = std::max(0, std::min(15, channel));
    fluid_synth_cc(synth_, channel, 11, std::max(0, std::min(127, expression)));
}

void SoundFontPlayer::setMasterGain(float gain) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    fluid_synth_set_gain(synth_, std::max(0.0f, std::min(2.0f, gain)));
}

void SoundFontPlayer::setChannelPreset(int channel, int bank, int program) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    const bool drum = (channel == 9 || bank == 128);
    if (drum) bank = 128;
    const int sfId = drum ? (drumSfId_ >= 0 ? drumSfId_ : melodySfId_) : melodySfId_;
    if (sfId < 0) { LOGE("No SF2 loaded for channel %d", channel); return; }
    fluid_synth_program_select(synth_, channel, sfId, bank, program);
    LOGI("Ch %d -> %s SF2 id=%d bank=%d prog=%d", channel, drum ? "DRUM" : "MELODY", sfId, bank, program);
}

std::string SoundFontPlayer::presetList() const {
    if (!synth_) return "";
    std::lock_guard<std::mutex> lock(g_synthMutex);
    std::string out;
    auto appendRole = [&](int sfId, const char* role) {
        if (sfId < 0) return;
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth_, sfId);
        if (!sfont) return;
        fluid_sfont_iteration_start(sfont);
        fluid_preset_t* preset = nullptr;
        while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
            const char* name = fluid_preset_get_name(preset);
            const int bank = fluid_preset_get_banknum(preset);
            const int program = fluid_preset_get_num(preset);
            if (!name) name = "";
            // role|bank|program|name. Names are kept verbatim from the SF2.
            out += role;
            out += "|";
            out += std::to_string(bank);
            out += "|";
            out += std::to_string(program);
            out += "|";
            out += name;
            out += "\n";
        }
    };
    appendRole(melodySfId_, "MELODY");
    if (drumSfId_ != melodySfId_) appendRole(drumSfId_, "DRUM");
    return out;
}

int SoundFontPlayer::presetCount() const {
    if (!synth_) return 0;
    int count = 0;
    if (melodySfId_ >= 0) {
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth_, melodySfId_);
        if (sfont) { fluid_sfont_iteration_start(sfont); while (fluid_sfont_iteration_next(sfont)) count++; }
    }
    if (drumSfId_ >= 0 && drumSfId_ != melodySfId_) {
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth_, drumSfId_);
        if (sfont) { fluid_sfont_iteration_start(sfont); while (fluid_sfont_iteration_next(sfont)) count++; }
    }
    return count;
}
#include "soundfont_player.h"
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <cstdarg>
#include <cstdio>
#include <cctype>
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
    // Large Yamaha SF2 files can be hundreds of MB. Dynamic sample loading
    // keeps sample data on disk and loads it on demand instead of expanding
    // the whole SoundFont into native memory during sfload(). This is
    // especially important on Android where a 300+ MB SF2 can otherwise
    // terminate the process without a Kotlin exception/log.
    const int dynamicSampleLoading = fluid_settings_setint(
        settings_, "synth.dynamic-sample-loading", 1
    );
    LOGI("FluidSynth dynamic sample loading: %s",
         dynamicSampleLoading ? "enabled" : "unsupported/failed");
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
        // Channel assignments are restored explicitly after the new SF2 load.
        fluid_synth_sfunload(synth_, targetId, 0);
        targetId = -1;
    }
    LOGI("Loading %s SF2: %s", drum ? "DRUM" : "MELODY", path.c_str());
    // Do not ask FluidSynth to reset/reassign every MIDI channel while the
    // second SF2 is being added. We explicitly bind channels below instead.
    // This avoids a large preset re-evaluation during the startup load.
    targetId = fluid_synth_sfload(synth_, path.c_str(), 0);
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
        // Keyboard RIGHT 1/2/3 must never depend on bank=0/program=0
        // existing in the loaded Yamaha SF2. Pick a real melody preset,
        // preferring the normal GM Piano slot when it exists, otherwise
        // falling back to the first enumerated melody preset.
        int defaultBank = 0;
        int defaultProgram = 0;
        bool foundDefault = false;
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth_, melodySfId_);
        if (sfont) {
            fluid_sfont_iteration_start(sfont);
            fluid_preset_t* preset = nullptr;
            int firstBank = 0;
            int firstProgram = 0;
            bool foundFirst = false;
            int pianoBank = 0;
            int pianoProgram = 0;
            bool foundPiano = false;
            while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
                const int bank = fluid_preset_get_banknum(preset);
                const int program = fluid_preset_get_num(preset);
                const char* presetName = fluid_preset_get_name(preset);
                const std::string name = presetName ? presetName : "";
                if (!foundFirst) {
                    firstBank = bank;
                    firstProgram = program;
                    foundFirst = true;
                }
                if (!foundPiano) {
                    std::string lower = name;
                    for (char& ch : lower) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
                    if (lower.find("piano") != std::string::npos || lower.find("grand") != std::string::npos) {
                        pianoBank = bank;
                        pianoProgram = program;
                        foundPiano = true;
                    }
                }
                if (bank == 0 && program == 0) {
                    defaultBank = bank;
                    defaultProgram = program;
                    foundDefault = true;
                }
            }
            if (!foundDefault && foundPiano) {
                defaultBank = pianoBank;
                defaultProgram = pianoProgram;
            } else if (!foundDefault && foundFirst) {
                defaultBank = firstBank;
                defaultProgram = firstProgram;
            }
        }
        if (sfont) {
            LOGI("MELODY keyboard default preset bank=%d prog=%d%s",
                 defaultBank, defaultProgram,
                 foundDefault ? " (GM Piano slot)" : " (first valid preset)");
        } else {
            LOGE("MELODY SF2 preset enumeration failed; keeping bank=0 prog=0");
        }

        for (int ch = 0; ch < 16; ++ch) {
            if (ch == 9) continue;
            const int bank = (ch <= 2) ? defaultBank : 0;
            const int program = (ch <= 2) ? defaultProgram : 0;
            assignChannelToRole(ch, false, bank, program);
        }
        for (int ch = 0; ch < 16; ++ch) {
            if (ch == 9) continue;
            const int defaultVolume = (ch == 13) ? 98 : ((ch <= 2 || ch == 8) ? 127 : (ch <= 5 ? 100 : 115));
            fluid_synth_cc(synth_, ch, 7, defaultVolume);
            if (ch <= 2) fluid_synth_cc(synth_, ch, 11, 127);
        }
    }
    LOGI("SF2 role ready: %s", drum ? "DRUM bank=128 ch9" : "MELODY bank=0 channels=1-16 except ch10");
    return true;
}

void SoundFontPlayer::assignChannelToRole(int channel, bool drum, int bank, int program) {
    if (!synth_) return;
    const int sfId = drum ? (drumSfId_ >= 0 ? drumSfId_ : melodySfId_) : melodySfId_;
    if (sfId < 0) return;
    const int rc = fluid_synth_program_select(synth_, channel, sfId, bank, program);
    fluid_preset_t* active = fluid_synth_get_channel_preset(synth_, channel);
    const char* activeName = active ? fluid_preset_get_name(active) : nullptr;
    LOGI("Ch %d -> %s SF2 id=%d bank=%d prog=%d rc=%d active=%s", channel, drum ? "DRUM" : "MELODY", sfId, bank, program, rc, activeName ? activeName : "<NULL>");
}

void SoundFontPlayer::unload() {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    for (int ch = 0; ch < 16; ++ch) fluid_synth_all_notes_off(synth_, ch);
    if (drumSfId_ >= 0) {
        fluid_synth_sfunload(synth_, drumSfId_, 0);
        drumSfId_ = -1;
        LOGI("DRUM SF2 unloaded");
    }
    if (melodySfId_ >= 0) {
        fluid_synth_sfunload(synth_, melodySfId_, 0);
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

    const int rc = fluid_synth_write_float(synth_, numFrames, out, 0, 2, out, 1, 2);

    // Lightweight A/B diagnostic: verify that FluidSynth actually produced
    // non-zero PCM. Do not log every callback; only report transitions.
    static bool lastNonZero = false;
    static int callbackCount = 0;
    static float peak = 0.0f;
    for (int i = 0; i < numFrames * 2; ++i) {
        const float a = out[i] < 0.0f ? -out[i] : out[i];
        if (a > peak) peak = a;
    }
    const bool nonZero = peak > 0.000001f;
    ++callbackCount;
    if (nonZero != lastNonZero || (nonZero && callbackCount % 100 == 0)) {
        LOGI("SF RENDER rc=%d frames=%d peak=%.7f nonZero=%d", rc, numFrames, peak, nonZero ? 1 : 0);
        lastNonZero = nonZero;
    }
    peak = 0.0f;
}

void SoundFontPlayer::noteOn(int channel, int key, float velocity) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    int vel = static_cast<int>(velocity * 127.0f);
    if (vel < 1) vel = 1;
    if (vel > 127) vel = 127;
    const int rc = fluid_synth_noteon(synth_, channel, key, vel);
    fluid_preset_t* active = fluid_synth_get_channel_preset(synth_, channel);
    const char* activeName = active ? fluid_preset_get_name(active) : nullptr;
    int cc7 = -1;
    int cc11 = -1;
    fluid_synth_get_cc(synth_, channel, 7, &cc7);
    fluid_synth_get_cc(synth_, channel, 11, &cc11);
    LOGI("SF NOTE_ON ch=%d key=%d vel=%d rc=%d active=%s cc7=%d cc11=%d",
         channel, key, vel, rc, activeName ? activeName : "<NULL>", cc7, cc11);
}

void SoundFontPlayer::noteOff(int channel, int key) {
    if (!synth_) return;
    std::lock_guard<std::mutex> lock(g_synthMutex);
    const int rc = fluid_synth_noteoff(synth_, channel, key);
    LOGI("SF NOTE_OFF ch=%d key=%d rc=%d", channel, key, rc);
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
    const int rc = fluid_synth_program_select(synth_, channel, sfId, bank, program);
    fluid_preset_t* active = fluid_synth_get_channel_preset(synth_, channel);
    const char* activeName = active ? fluid_preset_get_name(active) : nullptr;
    int cc7 = -1;
    int cc11 = -1;
    fluid_synth_get_cc(synth_, channel, 7, &cc7);
    fluid_synth_get_cc(synth_, channel, 11, &cc11);
    LOGI("Ch %d -> %s SF2 id=%d bank=%d prog=%d rc=%d active=%s cc7=%d cc11=%d",
         channel, drum ? "DRUM" : "MELODY", sfId, bank, program, rc,
         activeName ? activeName : "<NULL>", cc7, cc11);
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
        // Preset refresh is a UI operation, not an audio operation. Large Yamaha
        // SF2s can contain many presets, and building an unbounded Java String
        // here can cause a native/Java memory spike immediately after import.
        // Keep the refresh bounded; the synth itself still retains the complete SF2.
        constexpr int kMaxPresetsPerRole = 512;
        constexpr size_t kMaxPresetTextBytes = 128 * 1024;
        int roleCount = 0;
        while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
            if (roleCount >= kMaxPresetsPerRole || out.size() >= kMaxPresetTextBytes) {
                LOGI("Preset enumeration capped: role=%s count=%d bytes=%zu", role, roleCount, out.size());
                break;
            }
            const char* name = fluid_preset_get_name(preset);
            const int bank = fluid_preset_get_banknum(preset);
            const int program = fluid_preset_get_num(preset);
            if (!name) name = "";
            std::string line = std::string(role) + "|" +
                               std::to_string(bank) + "|" +
                               std::to_string(program) + "|" + name + "\n";
            if (out.size() + line.size() > kMaxPresetTextBytes) {
                LOGI("Preset enumeration text cap reached: role=%s count=%d", role, roleCount);
                break;
            }
            out += line;
            ++roleCount;
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
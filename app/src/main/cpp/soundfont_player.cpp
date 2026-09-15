#include "soundfont_player.h"
#include <android/log.h>

#define LOG_TAG "FluidSynthPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

SoundFontPlayer::SoundFontPlayer() {
    settings_ = new_fluid_settings();
    if (!settings_) {
        LOGE("Failed to create settings");
        return;
    }

    fluid_settings_setnum(settings_, "synth.sample-rate", 48000.0);
    fluid_settings_setint(settings_, "synth.polyphony", 128);
    fluid_settings_setnum(settings_, "synth.gain", 0.7);
    fluid_settings_setint(settings_, "synth.reverb.active", 1);
    fluid_settings_setint(settings_, "synth.chorus.active", 1);

    fluid_settings_setstr(settings_, "audio.driver", "null");

    synth_ = new_fluid_synth(settings_);
    if (!synth_) {
        LOGE("Failed to create synth");
        delete_fluid_settings(settings_);
        settings_ = nullptr;
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
    if (!synth_) return false;

    LOGI("Loading SF2: %s", path.c_str());
    sfId_ = fluid_synth_sfload(synth_, path.c_str(), 1);
    if (sfId_ == FLUID_FAILED) {
        LOGE("Failed to load SF2");
        return false;
    }

    fluid_synth_bank_select(synth_, 9, 128);
    fluid_synth_program_change(synth_, 9, 0);

    for (int ch = 0; ch < 16; ++ch) {
        if (ch == 9) continue;
        fluid_synth_bank_select(synth_, ch, 0);
        fluid_synth_program_change(synth_, ch, 0);
    }

    LOGI("SF2 loaded OK, id=%d", sfId_);
    return true;
}

void SoundFontPlayer::unload() {
    if (synth_ && sfId_ >= 0) {
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
    fluid_synth_write_float(synth_, numFrames, out, 0, 2, out, 1, 2);
}

void SoundFontPlayer::noteOn(int channel, int key, float velocity) {
    if (!synth_) return;
    int vel = (int)(velocity * 127.0f);
    if (vel < 1) vel = 1;
    if (vel > 127) vel = 127;
    fluid_synth_noteon(synth_, channel, key, vel);
}

void SoundFontPlayer::noteOff(int channel, int key) {
    if (synth_) fluid_synth_noteoff(synth_, channel, key);
}

void SoundFontPlayer::allNotesOff() {
    if (!synth_) return;
    for (int ch = 0; ch < 16; ++ch) {
        fluid_synth_all_notes_off(synth_, ch);
    }
}

void SoundFontPlayer::setChannelPreset(int channel, int bank, int program) {
    if (!synth_) return;
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
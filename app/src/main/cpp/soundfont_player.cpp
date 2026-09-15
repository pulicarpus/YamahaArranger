#include "soundfont_player.h"
#include <android/log.h>

#define LOG_TAG "SoundFontPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool SoundFontPlayer::load(const std::string& path) {
    unload();
    LOGI("Loading SF2: %s", path.c_str());
    font_ = tsf_load_filename(path.c_str());
    if (!font_) {
        LOGE("Failed to load SF2: %s", path.c_str());
        return false;
    }

    tsf_set_output(font_, TSF_STEREO_INTERLEAVED, 48000, 0.0f);

    // Set channels: ch 9 = drum, lainnya = preset 0 (piano)
    int totalPresets = tsf_get_presetcount(font_);
    LOGI("Total presets: %d", totalPresets);

    for (int ch = 0; ch < 16; ++ch) {
        if (ch == 9) {
            // Drum kit: preset 0, bank 128 (drum bank GM)
            tsf_channel_set_bank_preset(font_, 9, 128, 0);
        } else {
            tsf_channel_set_bank_preset(font_, ch, 0, 0);
        }
    }
    tsf_set_volume(font_, 1.0f);

    LOGI("SF2 loaded OK");
    return true;
}

void SoundFontPlayer::unload() {
    if (font_) {
        tsf_close(font_);
        font_ = nullptr;
        LOGI("SF2 unloaded");
    }
}

void SoundFontPlayer::render(float* out, int numFrames) {
    if (!font_) {
        for (int i = 0; i < numFrames * 2; ++i) out[i] = 0.0f;
        return;
    }
    // ⚠️ PENTING: untuk STEREO interleaved, samples = frames * 2
    tsf_render_float(font_, out, numFrames * 2, 1);
}

void SoundFontPlayer::noteOn(int channel, int key, float velocity) {
    if (font_) tsf_channel_note_on(font_, channel, key, velocity);
}

void SoundFontPlayer::noteOff(int channel, int key) {
    if (font_) tsf_channel_note_off(font_, channel, key);
}

void SoundFontPlayer::allNotesOff() {
    if (!font_) return;
    for (int ch = 0; ch < 16; ++ch) {
        tsf_channel_sounds_off_all(font_, ch);
    }
}

int SoundFontPlayer::presetCount() const {
    return font_ ? tsf_get_presetcount(font_) : 0;
}
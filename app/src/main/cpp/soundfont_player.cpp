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

    // Setup output: stereo interleaved, 48kHz, gain 0dB
    tsf_set_output(font_, TSF_STEREO_INTERLEAVED, 48000, 0.0f);

    // ═══════════════════════════════════════════════════════
    // PENTING: Set preset untuk setiap channel MIDI
    // Tanpa ini, channel tidak punya instrument → SILENT
    // ═══════════════════════════════════════════════════════
    int totalPresets = tsf_get_presetcount(font_);
    LOGI("Total presets in SF2: %d", totalPresets);

    // Channel 9 = Drum Kit (MIDI standard)
    tsf_channel_set_presetnumber(font_, 9, 0, 1);

    // Channel 0-8, 10-15 = default ke Grand Piano (preset 0, bank 0)
    for (int ch = 0; ch < 16; ++ch) {
        if (ch == 9) continue;
        tsf_channel_set_presetnumber(font_, ch, 0, 0);
    }

    // Volume master
    tsf_set_volume(font_, 1.0f);

    LOGI("SF2 loaded OK: presets=%d, channels assigned", totalPresets);
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
    tsf_render_float(font_, out, numFrames, 1);
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
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

    // Volume master SF2 — turun dari 1.0 ke 0.7 untuk hindari clipping
    tsf_set_volume(font_, 0.7f);

    // Channel 0-8, 10-15: melodic (piano di program 0)
    tsf_channel_set_bank_preset(font_, 0, 0, 0);    // Piano
    tsf_channel_set_bank_preset(font_, 1, 0, 0);    // Piano
    tsf_channel_set_bank_preset(font_, 2, 0, 33);   // Finger Bass
    tsf_channel_set_bank_preset(font_, 3, 0, 0);    // Piano (chord1)
    tsf_channel_set_bank_preset(font_, 4, 0, 24);   // Nylon Guitar (chord2)
    tsf_channel_set_bank_preset(font_, 5, 0, 48);   // Strings (pad)
    tsf_channel_set_bank_preset(font_, 6, 0, 56);   // Trumpet (phrase1)
    tsf_channel_set_bank_preset(font_, 7, 0, 65);   // Alto Sax (phrase2)
    tsf_channel_set_bank_preset(font_, 8, 0, 0);    // Piano
    for (int ch = 10; ch < 16; ++ch) {
        tsf_channel_set_bank_preset(font_, ch, 0, 0);
    }

    // Channel 9: Drum. Cek apakah SF2 punya drum bank 128.
    // Kalau tidak ada, fallback ke melodic supaya tidak silent.
    int drumOk = tsf_channel_set_bank_preset(font_, 9, 128, 0);
    if (drumOk == 0) {
        LOGI("SF2 has no drum bank 128, ch9 fallback to melodic");
        tsf_channel_set_bank_preset(font_, 9, 0, 0);
    } else {
        LOGI("Drum bank 128 assigned to ch9");
    }

    LOGI("SF2 loaded OK, presets=%d", tsf_get_presetcount(font_));
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
    // STEREO interleaved → samples = frames * 2
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

void SoundFontPlayer::setChannelPreset(int channel, int bank, int program) {
    if (!font_) return;
    if (channel < 0 || channel > 15) return;
    tsf_channel_set_bank_preset(font_, channel, bank, program);
    LOGI("Ch %d → bank=%d program=%d", channel, bank, program);
}

int SoundFontPlayer::presetCount() const {
    return font_ ? tsf_get_presetcount(font_) : 0;
}
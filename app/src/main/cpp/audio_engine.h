#pragma once

#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <mutex>
#include <vector>
#include "voice.h"
#include "soundfont_player.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static constexpr int kMaxPolyphony = 64;

    bool start();
    void stop();

    bool loadSoundFont(const std::string& path);
    bool isSoundFontLoaded() const { return soundFont_.isLoaded(); }
    void unloadSoundFont();

    void noteOn(int midiNote, int rootNote, float velocity01,
                const float* sampleData, size_t sampleFrames, int sampleRateHz);
    void noteOff(int midiNote);
    void allNotesOff();

    void sfNoteOnChannel(int channel, int midiNote, float velocity01);
    void sfNoteOffChannel(int channel, int midiNote);
    void sfSetChannelPreset(int channel, int bank, int program);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                           void* audioData, int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<Voice, kMaxPolyphony> voices_;
    std::mutex voiceMutex_;
    int outputSampleRate_ = 48000;

    SoundFontPlayer soundFont_;

    // DC blocker state
    float dcLastInL_ = 0.0f;
    float dcLastOutL_ = 0.0f;
    float dcLastInR_ = 0.0f;
    float dcLastOutR_ = 0.0f;

    // Lowpass filter state
    float lpStateL_ = 0.0f;
    float lpStateR_ = 0.0f;
};
#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <mutex>
#include <vector>
#include "voice.h"

// Owns the Oboe output stream and a fixed pool of Voices (polyphony=64).
// noteOn/noteOff are called from the JNI/UI thread and only touch
// lock-free atomics / a pre-sized pool — no allocation happens on the
// audio callback path, per the "no GC / no alloc in the RT thread" rule.
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static constexpr int kMaxPolyphony = 64;

    bool start();
    void stop();

    // sampleData must stay valid for the lifetime of the note (owned by
    // the sample/SoundFont manager, not copied here).
    void noteOn(int midiNote, int rootNote, float velocity01,
                const float* sampleData, size_t sampleFrames, int sampleRateHz);
    void noteOff(int midiNote);
    void allNotesOff();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                           void* audioData, int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<Voice, kMaxPolyphony> voices_;
    std::mutex voiceMutex_; // guards start/stop of voices; render path only reads
    int outputSampleRate_ = 48000;
};

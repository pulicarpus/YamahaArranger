#include "audio_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Mono) // mixed mono; pan/stereo is a Phase 3 DSP task
           ->setSampleRate(48000)
           ->setDataCallback(this)
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Music);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    outputSampleRate_ = stream_->getSampleRate();
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2); // ~2 bursts of headroom

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("AudioEngine started: sr=%d burst=%d", outputSampleRate_, stream_->getFramesPerBurst());
    return true;
}

void AudioEngine::stop() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

void AudioEngine::noteOn(int midiNote, int rootNote, float velocity01,
                          const float* sampleData, size_t sampleFrames, int sampleRateHz) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    // Voice-stealing: prefer an idle voice; otherwise steal the oldest
    // active one on the same note, else just voice[0] as a last resort.
    for (auto& v : voices_) {
        if (!v.isActive()) {
            v.start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01);
            return;
        }
    }
    for (auto& v : voices_) {
        if (v.midiNote() == midiNote) {
            v.start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01);
            return;
        }
    }
    voices_[0].start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01);
}

void AudioEngine::noteOff(int midiNote) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (v.isActive() && v.midiNote() == midiNote) {
            v.release();
        }
    }
}

void AudioEngine::allNotesOff() {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) v.release();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/,
                                                    void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    std::memset(out, 0, sizeof(float) * numFrames);

    // NOTE: real-time thread — no locks that a non-RT thread could hold
    // for long. voiceMutex_ here only ever guards short pool-scan writes,
    // which is acceptable for MVP; Phase 2+ should move to a lock-free
    // ring/command-queue between noteOn/off and the callback instead.
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (v.isActive()) {
            v.renderAdditive(out, numFrames, outputSampleRate_);
        }
    }

    // Simple master limiter to avoid clipping when many voices sum.
    for (int i = 0; i < numFrames; ++i) {
        out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    }

    return oboe::DataCallbackResult::Continue;
}

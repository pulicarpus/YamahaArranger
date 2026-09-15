#include "audio_engine.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(48000)
        ->setDataCallback(this)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("LowLatency failed, retry default");
        builder.setPerformanceMode(oboe::PerformanceMode::None);
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream");
        return false;
    }

    outputSampleRate_ = stream_->getSampleRate();
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 8);

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream");
        return false;
    }

    LOGI("AudioEngine started: sr=%d", outputSampleRate_);
    return true;
}

void AudioEngine::stop() {
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

bool AudioEngine::loadSoundFont(const std::string& path) {
    return soundFont_.load(path);
}

void AudioEngine::unloadSoundFont() {
    soundFont_.unload();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    (void)stream;
    auto* out = static_cast<float*>(audioData);
    const int stereoFrames = numFrames * 2;
    std::memset(out, 0, sizeof(float) * stereoFrames);

    if (soundFont_.isLoaded()) {
        soundFont_.render(out, numFrames);

        for (int f = 0; f < numFrames; ++f) {
            const int iL = f * 2;
            const int iR = iL + 1;

            float inL = out[iL];
            float outL = inL - dcLastInL_ + 0.995f * dcLastOutL_;
            dcLastInL_ = inL;
            dcLastOutL_ = outL;
            out[iL] = outL;

            float inR = out[iR];
            float outR = inR - dcLastInR_ + 0.995f * dcLastOutR_;
            dcLastInR_ = inR;
            dcLastOutR_ = outR;
            out[iR] = outR;
        }

        return oboe::DataCallbackResult::Continue;
    }

    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (v.isActive()) {
            v.renderAdditive(out, numFrames, outputSampleRate_);
        }
    }
    for (int i = 0; i < stereoFrames; ++i) {
        out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::sfNoteOnChannel(int channel, int midiNote, float velocity01) {
    soundFont_.noteOn(channel, midiNote, velocity01);
}

void AudioEngine::sfNoteOffChannel(int channel, int midiNote) {
    soundFont_.noteOff(channel, midiNote);
}

void AudioEngine::sfSetChannelPreset(int channel, int bank, int program) {
    soundFont_.setChannelPreset(channel, bank, program);
}

void AudioEngine::noteOn(int midiNote, int rootNote, float velocity01,
                          const float* sampleData, size_t sampleFrames, int sampleRateHz) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
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
    soundFont_.allNotesOff();
}
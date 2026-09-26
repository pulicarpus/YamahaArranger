#include "audio_engine.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <jni.h>

#define LOG_TAG "AudioEngine"
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

bool AudioEngine::start() {
    LOGI("AudioEngine.start() called");

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
        LOGE("LowLatency/Exclusive failed (%s), retrying Shared/None",
             oboe::convertToText(result));
        stream_.reset();
        builder.setPerformanceMode(oboe::PerformanceMode::None)
            ->setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }

    if (result != oboe::Result::OK || !stream_) {
        LOGE("Failed to open audio stream: %s",
             oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    outputSampleRate_ = stream_->getSampleRate();
    LOGI("Stream opened: sr=%d ch=%d sharing=%d perf=%d api=%d",
         outputSampleRate_,
         stream_->getChannelCount(),
         static_cast<int>(stream_->getSharingMode()),
         static_cast<int>(stream_->getPerformanceMode()),
         static_cast<int>(stream_->getAudioApi()));

    // Voyager's release notes expose a configurable low-latency buffer and
    // recommend keeping it low for LIVE MIDI. Two device bursts is the
    // equivalent starting point here.
    const int burst = stream_->getFramesPerBurst();
    const int targetBufferFrames = burst * 2;
    const auto bufferResult = stream_->setBufferSizeInFrames(targetBufferFrames);
    if (bufferResult == oboe::Result::OK) {
        const double bufferMs =
            (1000.0 * stream_->getBufferSizeInFrames()) /
            static_cast<double>(stream_->getSampleRate());
        LOGI("Low-latency buffer: burst=%d frames=%d (~%.2f ms)",
             burst, stream_->getBufferSizeInFrames(), bufferMs);
    } else {
        LOGI("Buffer size adjustment skipped: target=%d error=%s",
             targetBufferFrames, bufferResult.error());
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        return false;
    }

    LOGI("AudioEngine started OK, burst=%d", stream_->getFramesPerBurst());
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
    LOGI("loadSoundFont (role auto): %s", path.c_str());
    const bool ok = soundFont_.load(path);
    LOGI("loadSoundFont result: %s (melody=%d drum=%d)",
         ok ? "OK" : "FAILED",
         soundFont_.isMelodyLoaded(),
         soundFont_.isDrumLoaded());
    return ok;
}

bool AudioEngine::loadMelodySoundFont(const std::string& path) {
    LOGI("loadMelodySoundFont: %s", path.c_str());
    const bool ok = soundFont_.loadMelody(path);
    LOGI("loadMelodySoundFont result: %s", ok ? "OK" : "FAILED");
    return ok;
}

bool AudioEngine::loadDrumSoundFont(const std::string& path) {
    LOGI("loadDrumSoundFont: %s", path.c_str());
    const bool ok = soundFont_.loadDrum(path);
    LOGI("loadDrumSoundFont result: %s", ok ? "OK" : "FAILED");
    return ok;
}

void AudioEngine::unloadSoundFont() {
    soundFont_.unload();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    (void)stream;

    auto* out = static_cast<float*>(audioData);
    const int sampleCount = numFrames * 2;
    std::memset(out, 0, sizeof(float) * sampleCount);

    if (soundFont_.isLoaded()) {
        // Do not add a second compressor/soft-clip/DC filter here. MIDI
        // Voyager leaves BASS/BASSMIDI's synth dynamics intact; the SF2
        // itself is normally operated around 90% volume to avoid overload.
        soundFont_.render(out, numFrames);

        for (int i = 0; i < sampleCount; ++i) {
            out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
        }
        return oboe::DataCallbackResult::Continue;
    }

    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (v.isActive()) {
            v.renderAdditive(out, numFrames, outputSampleRate_);
        }
    }

    for (int i = 0; i < sampleCount; ++i) {
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

void AudioEngine::sfSetChannelPresetWithName(int channel, int bank, int program, const std::string& voiceName) {
    soundFont_.setChannelPreset(channel, bank, program, voiceName);
}

void AudioEngine::sfSetChannelMixer(int channel, int volume, int pan,
                                    int expression, int reverbSend,
                                    int chorusSend) {
    soundFont_.setChannelMixer(
        channel, volume, pan, expression, reverbSend, chorusSend);
}

std::string AudioEngine::sfPresetList() const {
    return soundFont_.presetList();
}

void AudioEngine::sfSetChannelExpression(int channel, int expression) {
    soundFont_.setChannelExpression(channel, expression);
}
void AudioEngine::sfSetKeyboardSustain(bool enabled) {
    soundFont_.setKeyboardSustain(enabled);
}

void AudioEngine::sfSetMasterGain(float gain) {
    soundFont_.setMasterGain(gain);
}

void AudioEngine::noteOn(int midiNote, int rootNote, float velocity01,
                         const float* sampleData, size_t sampleFrames,
                         int sampleRateHz) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (!v.isActive()) {
            v.start(sampleData, sampleFrames, sampleRateHz,
                    midiNote, rootNote, velocity01);
            return;
        }
    }
    for (auto& v : voices_) {
        if (v.midiNote() == midiNote) {
            v.start(sampleData, sampleFrames, sampleRateHz,
                    midiNote, rootNote, velocity01);
            return;
        }
    }
    voices_[0].start(sampleData, sampleFrames, sampleRateHz,
                     midiNote, rootNote, velocity01);
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
    for (auto& v : voices_) {
        v.release();
    }
    soundFont_.allNotesOff();
}

#include "audio_engine.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <cmath>
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
        LOGE("LowLatency/Exclusive failed (%s), retrying Shared/None", oboe::convertToText(result));
        stream_.reset();
        builder.setPerformanceMode(oboe::PerformanceMode::None)->setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK || !stream_) {
        LOGE("Failed to open audio stream: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }
    outputSampleRate_ = stream_->getSampleRate();
    LOGI("Stream opened: sr=%d ch=%d sharing=%d perf=%d api=%d", outputSampleRate_, stream_->getChannelCount(), static_cast<int>(stream_->getSharingMode()), static_cast<int>(stream_->getPerformanceMode()), static_cast<int>(stream_->getAudioApi()));
    auto bufferResult = stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 8);
    if (bufferResult != oboe::Result::OK) LOGI("Buffer size adjustment skipped: %s", bufferResult.error());
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
    if (stream_) { stream_->requestStop(); stream_->close(); stream_.reset(); }
}

bool AudioEngine::loadSoundFont(const std::string& path) {
    LOGI("loadSoundFont (role auto): %s", path.c_str());
    bool ok = soundFont_.load(path);
    LOGI("loadSoundFont result: %s (melody=%d drum=%d)", ok ? "OK" : "FAILED", soundFont_.isMelodyLoaded(), soundFont_.isDrumLoaded());
    return ok;
}

bool AudioEngine::loadMelodySoundFont(const std::string& path) {
    LOGI("loadMelodySoundFont: %s", path.c_str());
    bool ok = soundFont_.loadMelody(path);
    LOGI("loadMelodySoundFont result: %s", ok ? "OK" : "FAILED");
    return ok;
}

bool AudioEngine::loadDrumSoundFont(const std::string& path) {
    LOGI("loadDrumSoundFont: %s", path.c_str());
    bool ok = soundFont_.loadDrum(path);
    LOGI("loadDrumSoundFont result: %s", ok ? "OK" : "FAILED");
    return ok;
}

void AudioEngine::unloadSoundFont() { soundFont_.unload(); }

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    (void)stream;
    static int callbackCount = 0;
    callbackCount++;
    bool logNow = (callbackCount % 200 == 1);
    auto* out = static_cast<float*>(audioData);
    const int stereoFrames = numFrames * 2;
    std::memset(out, 0, sizeof(float) * stereoFrames);
    if (soundFont_.isLoaded()) {
        soundFont_.render(out, numFrames);
        for (int i = 0; i < stereoFrames; ++i) {
            float x = out[i] * 0.7f;
            if (x > 0.95f) x = 0.95f + (x - 0.95f) * 0.05f;
            else if (x < -0.95f) x = -0.95f + (x + 0.95f) * 0.05f;
            out[i] = std::max(-1.0f, std::min(1.0f, x));
        }
        for (int f = 0; f < numFrames; ++f) {
            const int iL = f * 2, iR = iL + 1;
            float inL = out[iL];
            float outL = inL - dcLastInL_ + 0.995f * dcLastOutL_;
            dcLastInL_ = inL; dcLastOutL_ = outL; out[iL] = outL;
            float inR = out[iR];
            float outR = inR - dcLastInR_ + 0.995f * dcLastOutR_;
            dcLastInR_ = inR; dcLastOutR_ = outR; out[iR] = outR;
        }
        if (logNow) {
            float peak = 0.0f;
            for (int i = 0; i < stereoFrames; ++i) peak = std::max(peak, std::fabs(out[i]));
            LOGI("onAudioReady #%d peak=%.5f", callbackCount, peak);
        }
        return oboe::DataCallbackResult::Continue;
    }
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) if (v.isActive()) v.renderAdditive(out, numFrames, outputSampleRate_);
    for (int i = 0; i < stereoFrames; ++i) out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::sfNoteOnChannel(int channel, int midiNote, float velocity01) { soundFont_.noteOn(channel, midiNote, velocity01); }
void AudioEngine::sfNoteOffChannel(int channel, int midiNote) { soundFont_.noteOff(channel, midiNote); }
void AudioEngine::sfSetChannelPreset(int channel, int bank, int program) { soundFont_.setChannelPreset(channel, bank, program); }
void AudioEngine::sfSetChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend) { soundFont_.setChannelMixer(channel, volume, pan, expression, reverbSend, chorusSend); }
std::string AudioEngine::sfPresetList() const { return soundFont_.presetList(); }
void AudioEngine::sfSetChannelExpression(int channel, int expression) { soundFont_.setChannelExpression(channel, expression); }
void AudioEngine::sfSetMasterGain(float gain) { soundFont_.setMasterGain(gain); }

void AudioEngine::noteOn(int midiNote, int rootNote, float velocity01, const float* sampleData, size_t sampleFrames, int sampleRateHz) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) if (!v.isActive()) { v.start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01); return; }
    for (auto& v : voices_) if (v.midiNote() == midiNote) { v.start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01); return; }
    voices_[0].start(sampleData, sampleFrames, sampleRateHz, midiNote, rootNote, velocity01);
}
void AudioEngine::noteOff(int midiNote) {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) if (v.isActive() && v.midiNote() == midiNote) v.release();
}
void AudioEngine::allNotesOff() {
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) v.release();
    soundFont_.allNotesOff();
}
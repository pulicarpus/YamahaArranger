#include "audio_engine.h"
#include <android/log.h>
#include <cstring>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <jni.h>
#include <sstream>

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
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.startAttempt();
#endif
// PCM_PATH_OBSERVER_END

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
    streamInfo_ = {};
    if (result != oboe::Result::OK) {
        streamInfo_.fallbackShared = true; // LATENCY_PROBE: Exclusive/LowLatency was refused
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

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        pcmOutput_.startResult(int(result));
#endif
// PCM_PATH_OBSERVER_END
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
    const int targetBufferFrames = buffer_policy::framesFor(bufferMode_.load(), burst, stream_->getBufferCapacityInFrames());
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

    streamInfo_.sampleRate = stream_->getSampleRate();
    streamInfo_.channels = stream_->getChannelCount();
    streamInfo_.sharing = static_cast<int>(stream_->getSharingMode());
    streamInfo_.perf = static_cast<int>(stream_->getPerformanceMode());
    streamInfo_.api = static_cast<int>(stream_->getAudioApi());
    streamInfo_.burst = stream_->getFramesPerBurst();
    streamInfo_.capacity = stream_->getBufferCapacityInFrames();
    streamInfo_.bufferSize = stream_->getBufferSizeInFrames();
    streamInfo_.framesPerCallback = stream_->getFramesPerDataCallback();
    streamInfo_.deviceId = stream_->getDeviceId();
    latency_probe::probe.reset();

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        pcmOutput_.startResult(int(result));
#endif
// PCM_PATH_OBSERVER_END
        return false;
    }

    LOGI("AudioEngine started OK, burst=%d", stream_->getFramesPerBurst());

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.startResult(0);
#endif
// PCM_PATH_OBSERVER_END
    return true;
}

void AudioEngine::stop() {
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.stop();
#endif
// PCM_PATH_OBSERVER_END

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

bool AudioEngine::loadMelodyFallbackSoundFont(const std::string& path) {
    LOGI("loadMelodyFallbackSoundFont: %s", path.c_str());
    const bool ok = soundFont_.loadMelodyFallback(path);
    LOGI("loadMelodyFallbackSoundFont result: %s", ok ? "OK" : "FAILED");
    return ok;
}

bool AudioEngine::loadMelodyManualSoundFont(const std::string& path) {
    LOGI("loadMelodyManualSoundFont: %s", path.c_str());
    const bool ok = soundFont_.loadMelodyFallback(path, true);
    LOGI("loadMelodyManualSoundFont result: %s", ok ? "OK" : "FAILED");
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
    // LATENCY_PROBE: whole-callback duration; RAII so every return path is covered.
    struct CallbackProbe {
        uint64_t start;
        explicit CallbackProbe(int frames, int rate) : start(latency_probe::probe.on() ? latency_probe::now() : 0) {
            if (start && rate > 0) latency_probe::probe.budgetNs.store(uint64_t(frames) * 1000000000ull / uint64_t(rate), std::memory_order_relaxed);
        }
        ~CallbackProbe() { if (start) latency_probe::probe.callbackDone(start, latency_probe::now()); }
    } callbackProbe(numFrames, outputSampleRate_);
    f12::Callback f12Callback(numFrames,outputSampleRate_);
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.callback(numFrames);
#endif
// PCM_PATH_OBSERVER_END


    auto* out = static_cast<float*>(audioData);
    const int sampleCount = numFrames * 2;
    std::memset(out, 0, sizeof(float) * sampleCount);

    if (soundFont_.isLoaded()) {
        // Do not add a second compressor/soft-clip/DC filter here. MIDI
        // Voyager leaves BASS/BASSMIDI's synth dynamics intact; the SF2
        // itself is normally operated around 90% volume to avoid overload.

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        pcmOutput_.synth();
#endif
// PCM_PATH_OBSERVER_END
        soundFont_.render(out, numFrames);
        internalGain_.process(out, sampleCount);

        for (int i = 0; i < sampleCount; ++i) {
            out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
        }
        // METRONOME: after BASSMIDI and the clamp; one atomic load when off. Never reaches BASSMIDI, MIDI OUT or any note state.
        if (metronome_.active() && metronome_.render(out, numFrames, outputSampleRate_, latency_probe::now())) {
            for (int i = 0; i < sampleCount; ++i) out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
        }

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        pcmOutput_.returned();
#endif
// PCM_PATH_OBSERVER_END
        return oboe::DataCallbackResult::Continue;
    }


// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.fallback();
#endif
// PCM_PATH_OBSERVER_END
    std::lock_guard<std::mutex> lock(voiceMutex_);
    for (auto& v : voices_) {
        if (v.isActive()) {
            v.renderAdditive(out, numFrames, outputSampleRate_);
        }
    }

    for (int i = 0; i < sampleCount; ++i) {
        out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    }
    if (metronome_.active() && metronome_.render(out, numFrames, outputSampleRate_, latency_probe::now())) {
        for (int i = 0; i < sampleCount; ++i) out[i] = std::max(-1.0f, std::min(1.0f, out[i]));
    }

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcmOutput_.returned();
#endif
// PCM_PATH_OBSERVER_END
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::sfNoteOnChannel(int channel, int midiNote, float velocity01) {
    soundFont_.noteOn(channel, midiNote, velocity01);
}

void AudioEngine::sfNoteOnStyleChannel(int channel, int midiNote, float velocity01, const AudioPathOrigin& origin) {
    soundFont_.noteOn(channel, midiNote, velocity01, origin);
}

void AudioEngine::sfNoteOffChannel(int channel, int midiNote) {
    soundFont_.noteOff(channel, midiNote);
}

bool AudioEngine::sfSetKeyboardPreset(int channel, uint64_t fontId, int bank, int program) {
    return soundFont_.setKeyboardPreset(channel, fontId, bank, program);
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
std::vector<unsigned char> AudioEngine::sfDiagnosticDrumWav(int bank,int pc,int key,int velocity) {
    return soundFont_.diagnosticDrumWav(bank,pc,key,velocity);
}
std::string AudioEngine::sfNoteZoneReport() const {
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    return pcmOutput_.report() + soundFont_.noteZoneReport();
#endif
// PCM_PATH_OBSERVER_END

    return soundFont_.noteZoneReport();
}
std::string AudioEngine::sfDrumKitCoverage(const std::vector<drum_audit::Hit>& hits) const {
    return soundFont_.drumKitCoverage(hits);
}
void AudioEngine::sfSetKeyboardSustain(bool enabled) {
    soundFont_.setKeyboardSustain(enabled);
}

bool AudioEngine::sfSetKeyboardReleaseTime(int releaseTime) {
    return soundFont_.setKeyboardReleaseTime(releaseTime);
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



void AudioEngine::sfNoteOffStyleChannel(int channel,int note,const AudioPathOrigin& origin) { soundFont_.noteOff(channel,note,origin); }
void AudioEngine::sfArmChordDiagnostic() { soundFont_.armChordDiagnostic(); }
void AudioEngine::sfStopChordDiagnostic() { soundFont_.stopChordDiagnostic(); }
std::string AudioEngine::sfChordDiagnosticReport() const { return soundFont_.chordDiagnosticReport(); }

void AudioEngine::sfMarkChordDiagnostic(int64_t id) { soundFont_.markChordDiagnostic(id); }
std::string AudioEngine::sfCompactChordDiagnosticReport() const { return soundFont_.compactChordDiagnosticReport(); }

std::string AudioEngine::sfDrumCompatibilityReport(const std::vector<drum_compat::Demand>& demand, const std::vector<std::pair<int,int>>& comparisonKits) const {
    return soundFont_.drumCompatibilityReport(demand,comparisonKits);
}

// ---- Output-buffer A/B (control thread only; the audio callback is untouched) ----
int AudioEngine::setBufferMode(int mode) {
    mode = buffer_policy::clampMode(mode);
    bufferMode_.store(mode);
    auto stream = stream_;
    if (!stream) return -1;
    const auto xr = stream->getXRunCount();
    xrunBase_.store(xr ? long(xr.value()) : 0);
    xrunLastTick_.store(xrunBase_.load());
    const int frames = buffer_policy::framesFor(mode, stream->getFramesPerBurst(), stream->getBufferCapacityInFrames());
    const auto result = stream->setBufferSizeInFrames(frames);
    LOGI("BUFFER A/B mode=%d requested=%d result=%s now=%d", mode, frames,
         result ? "OK" : oboe::convertToText(result.error()), stream->getBufferSizeInFrames());
    return stream->getBufferSizeInFrames();
}

void AudioEngine::bufferTick() {
    if (bufferMode_.load() != buffer_policy::ADAPTIVE) return;
    auto stream = stream_;
    if (!stream) return;
    const auto xr = stream->getXRunCount();
    if (!xr) return;
    const long total = xr.value();
    const long delta = total - xrunLastTick_.exchange(total);
    const int burst = stream->getFramesPerBurst();
    const int current = stream->getBufferSizeInFrames();
    const int next = buffer_policy::nextAdaptive(current, burst, delta, stream->getBufferCapacityInFrames());
    if (next != current) {
        stream->setBufferSizeInFrames(next);
        LOGI("BUFFER ADAPTIVE xrunDelta=%ld buffer %d -> %d", delta, current, stream->getBufferSizeInFrames());
    }
}

std::string AudioEngine::bufferStatus() {
    std::ostringstream out;
    auto stream = stream_;
    const int mode = bufferMode_.load();
    static const char* const names[] = {"A:BASELINE_2xBURST", "B:1.5xBURST", "C:1xBURST", "D:ADAPTIVE"};
    out << "BUFFER mode=" << names[buffer_policy::clampMode(mode)];
    if (!stream) { out << " streamOpen=0\n"; return out.str(); }
    const int rate = stream->getSampleRate() > 0 ? stream->getSampleRate() : 48000;
    const auto xr = stream->getXRunCount();
    const auto lat = stream->calculateLatencyMillis();
    out << " streamOpen=1 sharing=" << int(stream->getSharingMode()) << " perf=" << int(stream->getPerformanceMode())
        << " burst=" << stream->getFramesPerBurst() << " capacity=" << stream->getBufferCapacityInFrames()
        << " bufferNow=" << stream->getBufferSizeInFrames() << " (" << (1000.0 * stream->getBufferSizeInFrames() / rate) << "ms)"
        << " xrunsTotal=" << (xr ? long(xr.value()) : -1L)
        << " xrunsSinceModeSet=" << (xr ? long(xr.value()) - xrunBase_.load() : -1L)
        << " oboeEstimatedOutputLatencyMs=";
    if (lat) out << lat.value(); else out << "unavailable";
    out << " (Oboe estimate, not acoustic)\n";
    return out.str();
}

// LATENCY_PROBE: report built outside the audio callback.
std::string AudioEngine::latencyReport() {
    std::ostringstream out;
    out << "LATENCY_OUTPUT streamOpen=" << (stream_ ? 1 : 0) << " sampleRate=" << streamInfo_.sampleRate
        << " channels=" << streamInfo_.channels << " sharing=" << streamInfo_.sharing << "(0=Exclusive,1=Shared)"
        << " perf=" << streamInfo_.perf << "(10=None,11=PowerSaving,12=LowLatency)"
        << " api=" << streamInfo_.api << "(1=OpenSLES,2=AAudio)"
        << " fallbackToShared=" << (streamInfo_.fallbackShared ? 1 : 0)
        << " framesPerBurst=" << streamInfo_.burst << " framesPerCallback=" << streamInfo_.framesPerCallback
        << " bufferCapacity=" << streamInfo_.capacity << " bufferSizeAtStart=" << streamInfo_.bufferSize << "\n";
    if (auto stream = stream_) {
        const int rate = stream->getSampleRate() > 0 ? stream->getSampleRate() : 48000;
        out << "LATENCY_OUTPUT_LIVE bufferSizeNow=" << stream->getBufferSizeInFrames() << " (="
            << (1000.0 * stream->getBufferSizeInFrames() / rate) << "ms) xruns=";
        const auto xruns = stream->getXRunCount();
        if (xruns) out << xruns.value(); else out << "unavailable";
        const auto latency = stream->calculateLatencyMillis();
        if (latency) out << " estimatedOutputLatencyMs=" << latency.value() << " (Oboe estimate from the stream clock, not an acoustic measurement)";
        else out << " estimatedOutputLatencyMs=unavailable(" << oboe::convertToText(latency.error()) << ")";
        out << "\n";
    }
    out << latency_probe::probe.report();
    return out.str();
}

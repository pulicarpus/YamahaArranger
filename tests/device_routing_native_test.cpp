// DEVICE ROUTING V1 native contract on the host (mock Oboe + mock BASS): output device id reaches the Oboe builder, 0 = System Default
// (no setDeviceId), the error callback raises the disconnect flag and a reopen clears it, internal mute silences the synth signal only.
// HOST evidence: says nothing about which physical device Android routes to.
#include <jni.h>
#include "audio_engine.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>
JavaVM* g_jvm = nullptr; jclass g_debugLogClass = nullptr; jmethodID g_debugLogAddMethod = nullptr;

static float peak(AudioEngine& e) {
    std::vector<float> buf(256 * 2, 0.0f);
    e.onAudioReady(nullptr, buf.data(), 256);
    float p = 0; for (float v : buf) p = std::max(p, std::fabs(v)); return p;
}

int main() {
    AudioEngine e;
    // System Default: the builder is never given a device
    oboe::lastRequestedDeviceId = -1;
    assert(e.start());
    assert(oboe::lastRequestedDeviceId == -1);
    assert(oboe::lastErrorCallback != nullptr);
    assert(!e.outputDisconnected());
    e.stop();
    // A chosen device id reaches the builder; the stream reports the device it opened on
    e.setPreferredDeviceId(5);
    assert(e.start());
    assert(oboe::lastRequestedDeviceId == 5);
    assert(e.actualDeviceId() == 7);   // mock stream device: the app shows what really opened, not what it asked for
    // Device lost: the error callback only raises a flag (no reopen on the Oboe thread); the next start clears it
    oboe::lastErrorCallback->onErrorAfterClose(nullptr, oboe::Result::ErrorDisconnected);
    assert(e.outputDisconnected());
    assert(e.lastStreamError() == static_cast<int>(oboe::Result::ErrorDisconnected));
    e.stop();
    e.setPreferredDeviceId(0);
    oboe::lastRequestedDeviceId = -1;
    assert(e.start());
    assert(!e.outputDisconnected());
    assert(oboe::lastRequestedDeviceId == -1);
    // Internal mute: the synth keeps its state but the stream gets silence; unmuting brings the same voice back
    std::vector<float> sample(48000, 0.5f);
    e.noteOn(60, 60, 1.0f, sample.data(), sample.size(), 48000);
    const float on = peak(e);
    assert(on > 0.01f);
    e.setInternalMuted(true);
    assert(peak(e) == 0.0f);
    e.setInternalMuted(false);
    assert(peak(e) > 0.01f);   // the voice was not stopped by the mute
    e.stop();
    std::printf("DEVICE ROUTING NATIVE PASS default=no-device chosen=5 actual=7 disconnectFlag=ok mute=silence unmute=voice-kept peak=%.3f\n", on);
    return 0;
}

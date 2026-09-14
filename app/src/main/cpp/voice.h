#pragma once
#include <cstdint>
#include <vector>

// A single polyphonic voice: plays back a mono float sample at an
// arbitrary pitch (via linear-interpolated resampling) and shapes it
// with a simple ADSR envelope. 64 of these are pooled by AudioEngine.
// Kept allocation-free once started so it is safe to call from the
// realtime audio callback.
class Voice {
public:
    enum class Stage { Idle, Attack, Decay, Sustain, Release };

    void start(const float* sampleData, size_t sampleFrames, int sampleRateHz,
               int midiNote, int rootNote, float velocity01);
    void release();
    // Renders `numFrames` mono frames, *adding* into `out` (already
    // zeroed by the caller). Returns false once the voice becomes idle.
    bool renderAdditive(float* out, int numFrames, int outputSampleRateHz);

    bool isActive() const { return stage_ != Stage::Idle; }
    int midiNote() const { return midiNote_; }

private:
    const float* sample_ = nullptr;
    size_t sampleFrames_ = 0;
    int sampleRateHz_ = 44100;

    double playbackPos_ = 0.0;   // fractional read position into `sample_`
    double playbackRate_ = 1.0;  // pitch ratio (source_sr adjustment folded in)

    int midiNote_ = -1;
    float velocity_ = 1.0f;
    float envLevel_ = 0.0f;
    Stage stage_ = Stage::Idle;

    // Simple fixed ADSR in seconds; tune per-instrument in a later phase
    // (this should eventually come from the SoundFont's generators).
    static constexpr float kAttack = 0.005f;
    static constexpr float kDecay = 0.08f;
    static constexpr float kSustainLevel = 0.75f;
    static constexpr float kRelease = 0.25f;
};

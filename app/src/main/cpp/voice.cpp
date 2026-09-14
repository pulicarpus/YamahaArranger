#include "voice.h"
#include <cmath>
#include <algorithm>

void Voice::start(const float* sampleData, size_t sampleFrames, int sampleRateHz,
                   int midiNote, int rootNote, float velocity01) {
    sample_ = sampleData;
    sampleFrames_ = sampleFrames;
    sampleRateHz_ = sampleRateHz;
    midiNote_ = midiNote;
    velocity_ = std::clamp(velocity01, 0.0f, 1.0f);
    playbackPos_ = 0.0;

    // Semitone difference from the sample's recorded root note.
    double semitoneDelta = midiNote - rootNote;
    playbackRate_ = std::pow(2.0, semitoneDelta / 12.0);

    envLevel_ = 0.0f;
    stage_ = Stage::Attack;
}

void Voice::release() {
    if (stage_ != Stage::Idle) stage_ = Stage::Release;
}

bool Voice::renderAdditive(float* out, int numFrames, int outputSampleRateHz) {
    if (stage_ == Stage::Idle || sample_ == nullptr) return false;

    // Resampling ratio also accounts for source-vs-output sample rate.
    const double srRatio = static_cast<double>(sampleRateHz_) / outputSampleRateHz;
    const double step = playbackRate_ * srRatio;

    const float attackInc  = 1.0f / (kAttack  * outputSampleRateHz + 1.0f);
    const float decayDec   = (1.0f - kSustainLevel) / (kDecay * outputSampleRateHz + 1.0f);
    const float releaseDec = kSustainLevel / (kRelease * outputSampleRateHz + 1.0f);

    for (int i = 0; i < numFrames; ++i) {
        if (static_cast<size_t>(playbackPos_) >= sampleFrames_ - 1) {
            stage_ = Stage::Idle;
            return false;
        }

        // Linear interpolation between the two nearest sample frames.
        size_t idx = static_cast<size_t>(playbackPos_);
        double frac = playbackPos_ - idx;
        float s0 = sample_[idx];
        float s1 = sample_[idx + 1];
        float sampleVal = static_cast<float>(s0 + (s1 - s0) * frac);

        switch (stage_) {
            case Stage::Attack:
                envLevel_ += attackInc;
                if (envLevel_ >= 1.0f) { envLevel_ = 1.0f; stage_ = Stage::Decay; }
                break;
            case Stage::Decay:
                envLevel_ -= decayDec;
                if (envLevel_ <= kSustainLevel) { envLevel_ = kSustainLevel; stage_ = Stage::Sustain; }
                break;
            case Stage::Sustain:
                break;
            case Stage::Release:
                envLevel_ -= releaseDec;
                if (envLevel_ <= 0.0f) { envLevel_ = 0.0f; stage_ = Stage::Idle; }
                break;
            default:
                break;
        }

        out[i] += sampleVal * envLevel_ * velocity_;
        playbackPos_ += step;

        if (stage_ == Stage::Idle) return false;
    }
    return true;
}

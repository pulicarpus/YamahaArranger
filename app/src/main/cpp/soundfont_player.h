#pragma once

#include <string>
#include "tsf.h"

class SoundFontPlayer {
public:
    bool load(const std::string& path);
    void unload();
    bool isLoaded() const { return font_ != nullptr; }

    // Render stereo interleaved float output
    void render(float* out, int numFrames);

    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();

    int presetCount() const;

private:
    tsf* font_ = nullptr;
};
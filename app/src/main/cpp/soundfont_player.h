#pragma once

#include <string>
#include <fluidsynth.h>

class SoundFontPlayer {
public:
    SoundFontPlayer();
    ~SoundFontPlayer();

    bool load(const std::string& path);
    void unload();
    bool isLoaded() const { return synth_ != nullptr && sfId_ >= 0; }

    void render(float* out, int numFrames);

    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();

    void setChannelPreset(int channel, int bank, int program);
    int presetCount() const;

private:
    fluid_settings_t* settings_ = nullptr;
    fluid_synth_t* synth_ = nullptr;
    int sfId_ = -1;
};
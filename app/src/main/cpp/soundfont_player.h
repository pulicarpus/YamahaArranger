#pragma once

#include <string>
#include <vector>
#include <fluidsynth.h>

class SoundFontPlayer {
public:
    SoundFontPlayer();
    ~SoundFontPlayer();

    bool load(const std::string& path);
    bool addSoundFont(const std::string& path);
    void unload();
    bool isLoaded() const { return synth_ != nullptr && !sfIds_.empty(); }

    void render(float* out, int numFrames);

    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();

    void setChannelPreset(int channel, int bank, int program);
    int presetCount() const;
    int soundFontCount() const { return static_cast<int>(sfIds_.size()); }

private:
    fluid_settings_t* settings_ = nullptr;
    fluid_synth_t* synth_ = nullptr;
    std::vector<int> sfIds_;

    int findDrumFontIdLocked() const;
    void applyDefaultChannelPresetsLocked();
};
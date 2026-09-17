#pragma once

#include <string>
#include <fluidsynth.h>

class SoundFontPlayer {
public:
    SoundFontPlayer();
    ~SoundFontPlayer();

    bool load(const std::string& path);
    bool loadMelody(const std::string& path);
    bool loadDrum(const std::string& path);
    void unload();
    bool isLoaded() const { return synth_ != nullptr && (melodySfId_ >= 0 || drumSfId_ >= 0); }
    bool isMelodyLoaded() const { return synth_ != nullptr && melodySfId_ >= 0; }
    bool isDrumLoaded() const { return synth_ != nullptr && drumSfId_ >= 0; }

    void render(float* out, int numFrames);

    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();

    void setChannelPreset(int channel, int bank, int program);
    int presetCount() const;

private:
    bool loadRole(const std::string& path, bool drum);
    void assignChannelToRole(int channel, bool drum, int bank, int program);

    fluid_settings_t* settings_ = nullptr;
    fluid_synth_t* synth_ = nullptr;
    int melodySfId_ = -1;
    int drumSfId_ = -1;
};
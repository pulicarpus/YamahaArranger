#pragma once
#include <string>
#include <mutex>
#include <bass.h>
#include <bassmidi.h>

class BassMidiPlayer {
public:
    BassMidiPlayer();
    ~BassMidiPlayer();
    bool load(const std::string& path);
    bool loadMelody(const std::string& path);
    bool loadDrum(const std::string& path);
    void unload();
    bool isLoaded() const { return stream_ != 0 && (melodyFont_ != 0 || drumFont_ != 0); }
    bool isMelodyLoaded() const { return stream_ != 0 && melodyFont_ != 0; }
    bool isDrumLoaded() const { return stream_ != 0 && drumFont_ != 0; }
    void render(float* out, int numFrames);
    void noteOn(int channel, int key, float velocity);
    void noteOff(int channel, int key);
    void allNotesOff();
    void setChannelPreset(int channel, int bank, int program);
    void setChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend);
    void setChannelExpression(int channel, int expression);
    void setMasterGain(float gain);
    std::string presetList() const;
private:
    bool ensureEngine();
    bool loadRole(const std::string& path, bool drum);
    bool applyFonts();
    void send(int channel, DWORD event, DWORD param);
    HSTREAM stream_ = 0;
    HSOUNDFONT melodyFont_ = 0;
    HSOUNDFONT drumFont_ = 0;
    bool bassInitialized_ = false;
    mutable std::mutex mutex_;
    int sampleRate_ = 48000;
    int interpolation_ = 2;
    int voices_ = 128;
};

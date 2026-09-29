#pragma once
#include <string>
#include <mutex>
#include <array>
#include <vector>
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
    void setChannelPreset(int channel, int bank, int program, const std::string& voiceName = {});
    void setChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend);
    void setChannelExpression(int channel, int expression);
    void setKeyboardSustain(bool enabled);
    void setMasterGain(float gain);
    std::string presetList() const;
private:
    struct ChannelState {
        int bankMsb = 0;
        int bankLsb = 0;
        int program = 0;
        bool drum = false;
        bool initialized = false;
    };
    struct DrumPresetEntry {
        int bank = 0;
        int program = 0;
    };
    struct NormalizedBankMap {
        int rawBank = 0;
        int virtualBank = 0;
        int midiMsb = 0;
        int midiLsb = 0;
    };
    struct MelodicPresetEntry {
        int bank = 0;
        int program = 0;
        std::string name;
    };
    bool ensureEngine();
    bool loadRole(const std::string& path, bool drum);
    bool applyFonts();
    bool normalizeMelodySf2(const std::string& sourcePath, const std::string& outputPath);
    void send(int channel, DWORD event, DWORD param);
    void preloadCurrentPreset(int channel);
    void rebuildDrumPresetCache(const std::string& path,
                                std::vector<DrumPresetEntry>& cache);
    void rebuildMelodyPresetCache(const std::string& path);
    bool findMelodicPreset(const std::string& path, int requestedBank, int requestedProgram,
                           const std::string& voiceName, int& sourceBank, int& sourceProgram,
                           std::string& matchedName) const;
    bool findDrumPreset(const std::string& path, int requestedProgram,
                        int& sourceBank, int& sourceProgram) const;
    HSTREAM stream_ = 0;
    HSOUNDFONT melodyFont_ = 0;
    HSOUNDFONT drumFont_ = 0;
    bool bassInitialized_ = false;
    mutable std::mutex mutex_;
    int sampleRate_ = 48000;
    int interpolation_ = 2;
    int voices_ = 1000;
    float soundFontVolume_ = 0.90f;
    std::array<ChannelState, 16> channels_{};
    std::vector<DrumPresetEntry> melodyDrumPresetCache_;
    std::vector<DrumPresetEntry> drumDrumPresetCache_;
    std::vector<MelodicPresetEntry> melodyPresetCache_;
    std::vector<NormalizedBankMap> normalizedBanks_;
    std::string melodyPath_;
    std::string melodyBassPath_;
    std::string drumPath_;
};

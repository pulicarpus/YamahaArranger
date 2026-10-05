#pragma once
#include <string>
#include <mutex>
#include <array>
#include <vector>
#include <memory>
#include "audio_path_diagnostic.h"
#include "chord_change_diagnostic.h"
#include "sf2_zone_diagnostic.h"
#include "drum_kit_audit.h"
#include "percussion_fidelity_policy.h"
#include "drum_compatibility_report.h"
#include "drum_compatibility_comparison.h"
#include <bass.h>
#include <bassmidi.h>

class BassMidiPlayer {
public:
    BassMidiPlayer();
    ~BassMidiPlayer();
    bool load(const std::string& path);
    bool loadMelody(const std::string& path);
    bool loadMelodyFallback(const std::string& path);
    bool loadDrum(const std::string& path);
    void unload();
    bool isLoaded() const { return stream_ != 0 && (melodyFont_ != 0 || drumFont_ != 0); }
    bool isMelodyLoaded() const { return stream_ != 0 && melodyFont_ != 0; }
    bool isDrumLoaded() const { return stream_ != 0 && drumFont_ != 0; }
    void render(float* out, int numFrames);
    void noteOn(int channel, int key, float velocity, const AudioPathOrigin& origin = {});
    void noteOff(int channel, int key, const AudioPathOrigin& origin = {});
    void armChordDiagnostic();
    void stopChordDiagnostic();
    void markChordDiagnostic(int64_t id);
    std::string compactChordDiagnosticReport() const;
    std::string chordDiagnosticReport() const;
    void allNotesOff();
    void setChannelPreset(int channel, int bank, int program, const std::string& voiceName = {});
    void setChannelMixer(int channel, int volume, int pan, int expression, int reverbSend, int chorusSend);
    void setChannelExpression(int channel, int expression);
    void setKeyboardSustain(bool enabled);
    void setKeyboardReleaseTime(int releaseTime);
    void setMasterGain(float gain);
    std::string presetList() const;
    std::string shadowDrumSnapshot() const;
    std::vector<unsigned char> diagnosticDrumWav(int bank,int pc,int key,int velocity);
    std::string noteZoneReport() const;
    std::string drumKitCoverage(const std::vector<drum_audit::Hit>& hits) const;
    std::string drumCompatibilityReport(const std::vector<drum_compat::Demand>& demand, const std::vector<std::pair<int,int>>& comparisonKits = {}) const;
private:
    struct ChannelState {
        int bankMsb = 0;
        int bankLsb = 0;
        int melodySource = 0;
        int melodySourceBank = -1;
        int melodySourceProgram = -1;
        std::string melodySourceName;
        std::string requestedVoiceName;
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
    struct SecondaryMelody {
        HSOUNDFONT font = 0;
        std::string path;
        std::string bassPath;
        std::vector<MelodicPresetEntry> presets;
        std::vector<NormalizedBankMap> banks;
    };
    std::vector<SecondaryMelody> secondaryMelodies_;
    bool ensureEngine();
    bool loadRole(const std::string& path, bool drum);
    bool applyFonts();
    bool publishedFontTableIntact() const;
    std::vector<BASS_MIDI_FONTEX2> publishedFontMaps_;
    struct PartPresence {
        uint64_t attempts=0, sent=0, familyOrMapRejected=0, engineUnavailable=0, sendFailed=0, zeroController=0, presetMapFailures=0;
        int lastKey=-1, lastVelocity=-1;
    };
    std::array<PartPresence,16> partPresence_{};
    // Passive measurements: no voice isolation, no calibration or gain writes.
    struct VelocityEvidence {uint64_t count=0,sum=0,squares=0;int minimum=128,maximum=0;};
    std::array<VelocityEvidence,16> styleVelocityEvidence_{};
    uint64_t pcmSamples_=0,pcmClipped_=0,pcmNonfinite_=0;
    double pcmEnergy_=0;float pcmPeak_=0;

    void refreshMelodicChannels();
    void invalidateMelodicChannels();
    bool normalizeMelodySf2(const std::string& sourcePath, const std::string& outputPath);
    bool send(int channel, DWORD event, DWORD param);
    void logAudioPath(int channel, int key, int velocity, bool sent, int error,
                      const AudioPathOrigin& origin, const char* reason, uint64_t now);
    std::array<audio_path::Channel, 16> audioDiagnostics_{};
    uint64_t fontMappingGeneration_ = 0;
    chord_diagnostic::Capture chordCapture_;
    uint64_t chordMidiOrder_=0;
    void captureChordState(const char* stage,int channel,int key,int velocity,int sent,int error,
                           const AudioPathOrigin& origin={},DWORD event=0,DWORD param=0);
    std::map<std::string, sf2_zones::Inventory> drumZoneInventories_;
    std::array<std::array<std::string, 128>, 2> drumZoneSignatures_{};
    void auditNoteZone(int channel,int key,int velocity,bool sent,const AudioPathOrigin& origin);
    struct NoteZoneObservation {
        std::string header;
        std::shared_ptr<const sf2_zones::Inventory> inventory;
        int bank=0,pc=0,key=0,velocity=0;
    };
    std::map<std::string, std::shared_ptr<const sf2_zones::Inventory>> melodicZoneInventories_;
    std::map<std::string,NoteZoneObservation> noteZoneRows_;
    bool noteZoneLimit_=false;
    void preloadCurrentPreset(int channel);
    void rebuildDrumPresetCache(const std::string& path,
                                std::vector<DrumPresetEntry>& cache);
    void rebuildMelodyPresetCache(const std::string& path);
    bool findMelodicPreset(int requestedBank, int requestedProgram,
                           const std::string& voiceName, int& sourceBank, int& sourceProgram,
                           std::string& matchedName, int& sourceFont) const;
    bool findDrumPreset(const std::string& path, int requestedProgram,
                        int& sourceBank, int& sourceProgram) const;
    // New bounded, drum-only compatible substitutions. Old Stage 3 remains absent.
    struct PercussionLane {
        HSTREAM stream=0; uint64_t generation=0; int requestedPc=-1;
        std::array<percussion_fidelity::Candidate,128> routes{};
        std::array<int,128> groups{};
        std::array<bool,128> actualVerified{};
        std::array<uint64_t,128> legacyLifetimeFrames{};
        std::array<percussion_fidelity::Owners,128> owners{};
        uint64_t mappedOns=0,legacyOns=0,failedOns=0,overflowOns=0,chokes=0;
    };
    std::array<PercussionLane,2> percussionLanes_{};
    std::vector<percussion_fidelity::Candidate> percussionCandidates_;
    std::vector<percussion_fidelity::Candidate> percussionAllCandidates_;
    std::map<std::string,std::string> percussionFingerprints_;
    uint64_t percussionGeneration_=0,percussionRenderedFrames_=0;
    void retirePercussionStreams();
    void rebuildPercussionCatalog(); // only on paused SF2 load/import
    void preparePercussionLane(int channel,int requestedPc);
    void mirrorPercussionController(int channel,DWORD event,DWORD param);
    bool percussionOn(int channel,int key,int velocity,const AudioPathOrigin& origin,bool& accepted);
    bool percussionOff(int channel,int key,const AudioPathOrigin& origin);
    void renderPercussion(float* out,int frames);
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

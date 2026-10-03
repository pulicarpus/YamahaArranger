#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <mutex>
#include <string>
#include "voice.h"
#include "bassmidi_player.h"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static constexpr int kMaxPolyphony=64;
    bool start(); void stop();
    bool loadSoundFont(const std::string& path);
    bool loadMelodySoundFont(const std::string& path);
    bool loadMelodyFallbackSoundFont(const std::string& path);
    bool loadDrumSoundFont(const std::string& path);
    bool isSoundFontLoaded() const{return soundFont_.isLoaded();}
    bool isMelodySoundFontLoaded() const{return soundFont_.isMelodyLoaded();}
    bool isDrumSoundFontLoaded() const{return soundFont_.isDrumLoaded();}
    void unloadSoundFont();
    void noteOn(int midiNote,int rootNote,float velocity01,const float* sampleData,size_t sampleFrames,int sampleRateHz);
    void noteOff(int midiNote); void allNotesOff();
    void sfNoteOnChannel(int channel,int midiNote,float velocity01);
    void sfNoteOnStyleChannel(int channel,int midiNote,float velocity01,const AudioPathOrigin& origin);
    void sfNoteOffChannel(int channel,int midiNote);
    void sfNoteOffStyleChannel(int channel,int midiNote,const AudioPathOrigin& origin);
    void sfArmChordDiagnostic();
    void sfStopChordDiagnostic();
    void sfMarkChordDiagnostic(int64_t id);
    std::string sfCompactChordDiagnosticReport() const;
    std::string sfChordDiagnosticReport() const;
    void sfSetChannelPreset(int channel,int bank,int program);
    void sfSetChannelPresetWithName(int channel,int bank,int program,const std::string& voiceName);
    void sfSetChannelMixer(int channel,int volume,int pan,int expression,int reverbSend,int chorusSend);
    void sfSetChannelExpression(int channel,int expression);
    void sfSetKeyboardSustain(bool enabled);
    void sfSetKeyboardReleaseTime(int releaseTime);
    void sfSetMasterGain(float gain);
    std::string sfPresetList() const;
    int prepareExperimentalDrum(const std::string& path,const std::string& sha,int bank,int pc,int key,int rhythm,uint64_t gen) {return soundFont_.prepareExperimentalDrum(path,sha,bank,pc,key,rhythm,gen);}
    bool enableExperimentalDrum(bool enabled) {return soundFont_.enableExperimentalDrum(enabled);}
    void clearExperimentalDrum() {soundFont_.clearExperimentalDrum();}
    uint64_t experimentalDrumOn(int route,int rhythm,int velocity,int rawPc) {return soundFont_.experimentalDrumOn(route,rhythm,velocity,rawPc);}
    bool experimentalDrumOff(uint64_t token) {return soundFont_.experimentalDrumOff(token);}
    std::string experimentalDrumReport() const {return soundFont_.experimentalDrumReport();}
    std::string sfShadowDrumSnapshot() const { return soundFont_.shadowDrumSnapshot(); }
    std::vector<unsigned char> sfDiagnosticDrumWav(int bank,int pc,int key,int velocity);
    std::string sfNoteZoneReport() const;
    std::string sfDrumKitCoverage(const std::vector<drum_audit::Hit>& hits) const;
    std::string sfDrumCompatibilityReport(const std::vector<drum_compat::Demand>& demand, const std::vector<std::pair<int,int>>& comparisonKits = {}) const;
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void*,int32_t) override;
private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<Voice,kMaxPolyphony> voices_;
    std::mutex voiceMutex_;
    int outputSampleRate_=48000;
    BassMidiPlayer soundFont_;
    float dcLastInL_=0,dcLastOutL_=0,dcLastInR_=0,dcLastOutR_=0;
};



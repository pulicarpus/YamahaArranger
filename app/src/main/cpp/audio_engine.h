#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <mutex>
#include <string>
#include "voice.h"
#include "bassmidi_player.h"
#include "latency_probe.h"
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
#include "pcm_path_evidence.h"
#endif
// PCM_PATH_OBSERVER_END


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
    bool sfStopKeyboardPart(int channel) { return soundFont_.stopKeyboardPart(channel); }
    void sfNoteOffStyleChannel(int channel,int midiNote,const AudioPathOrigin& origin);
    void sfArmChordDiagnostic();
    void sfStopChordDiagnostic();
    void sfMarkChordDiagnostic(int64_t id);
    std::string sfCompactChordDiagnosticReport() const;
    std::string sfChordDiagnosticReport() const;
    bool sfSetKeyboardPreset(int channel,uint64_t fontId,int bank,int program);
    void sfSetChannelPreset(int channel,int bank,int program);
    void sfSetChannelPresetWithName(int channel,int bank,int program,const std::string& voiceName);
    void sfSetChannelMixer(int channel,int volume,int pan,int expression,int reverbSend,int chorusSend);
    void sfSetChannelExpression(int channel,int expression);
    void sfSetKeyboardSustain(bool enabled);
    bool sfSetKeyboardReleaseTime(int releaseTime);
    void sfSetMasterGain(float gain);
    std::string sfPresetList() const;
    std::string sfKeyboardVoiceSnapshot() const { return soundFont_.keyboardVoiceSnapshot(); }
    std::vector<unsigned char> sfDiagnosticKeyboardReleaseWav(int ch,int key,int velocity) { return soundFont_.diagnosticKeyboardReleaseWav(ch,key,velocity); }
    std::string sfKeyboardReleaseProbeReport() const { return soundFont_.keyboardReleaseProbeReport(); }
    std::string drumChannelSelection(int ch)const{return soundFont_.drumChannelSelection(ch);}
    bool setDrumABMode(bool r){return soundFont_.setDrumABMode(r);}
    bool bindDrumAB(int ch,const std::string& fp,int bank,int pc,const std::string& section){return soundFont_.bindDrumAB(ch,fp,bank,pc,section);}
    std::string drumABReport()const{return soundFont_.drumABReport();}
    std::string channelBinding(int ch)const{return soundFont_.channelBinding(ch);}
    void setSourceTiered(bool t){soundFont_.setSourceTiered(t);}
    bool pinChannelPreset(int ch,uint64_t id,int bank,int pc){return soundFont_.pinChannelPreset(ch,id,bank,pc);}
    std::string fontPdta(uint64_t id)const{return soundFont_.fontPdta(id);}
    std::string sfShadowDrumSnapshot() const { return soundFont_.shadowDrumSnapshot(); }
    std::vector<unsigned char> sfDiagnosticDrumWav(int bank,int pc,int key,int velocity);
    std::string sfNoteZoneReport() const;
    uint64_t markPcmSession(pcm_session::Controls c){return soundFont_.markPcmSession(c);}
    std::string pcmSessionReport(pcm_session::Controls c)const{return soundFont_.pcmSessionReport(c);}
    std::string sfDrumKitCoverage(const std::vector<drum_audit::Hit>& hits) const;
    std::string sfDrumCompatibilityReport(const std::vector<drum_compat::Demand>& demand, const std::vector<std::pair<int,int>>& comparisonKits = {}) const;
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void*,int32_t) override;
    // LATENCY_PROBE: statistics plus a one-shot description of the output stream.
    std::string latencyReport();
    void latencyReset(){latency_probe::probe.reset();}
    void latencySetEnabled(bool e){latency_probe::probe.enabled.store(e?1:0);}
private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::array<Voice,kMaxPolyphony> voices_;
    std::mutex voiceMutex_;
    int outputSampleRate_=48000;
    // Captured once at start(); read from the report thread only after start() returned.
    struct StreamInfo {int sampleRate=0,channels=0,sharing=-1,perf=-1,api=-1,burst=0,capacity=0,bufferSize=0,framesPerCallback=0,deviceId=0;bool fallbackShared=false;} streamInfo_;
    BassMidiPlayer soundFont_;
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcm_path::Output pcmOutput_;
#endif
// PCM_PATH_OBSERVER_END

    float dcLastInL_=0,dcLastOutL_=0,dcLastInR_=0,dcLastOutR_=0;
};



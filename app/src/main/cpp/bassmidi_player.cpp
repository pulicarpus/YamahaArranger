#include "bassmidi_player.h"
#include <android/log.h>
#include <algorithm>
#include <fstream>
#include <unordered_set>
#include <cmath>
#include <sstream>
#include <vector>

#define LOG_TAG "BassMidiPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

BassMidiPlayer::BassMidiPlayer() {
    ensureEngine();
}

BassMidiPlayer::~BassMidiPlayer() {
    unload();
    if (bassInitialized_) {
        BASS_Free();
        bassInitialized_ = false;
    }
}

bool BassMidiPlayer::ensureEngine() {
    if (bassInitialized_ && stream_) return true;

    if (!bassInitialized_) {
        // MIDI Voyager uses BASS as the synth/output foundation. We keep the
        // no-sound BASS device here because YamahaArranger owns the Android
        // output stream (Oboe) and pulls PCM from the BASSMIDI decoder.
        if (!BASS_Init(0, sampleRate_, 0, nullptr, nullptr)) {
            LOGE("BASS_Init failed error=%d", BASS_ErrorGetCode());
            return false;
        }
        bassInitialized_ = true;

        // Voyager allows up to 1000 simultaneous MIDI voices and its release
        // notes describe a default 10 ms update period. Disable automatic
        // soundfont compacting so already-used samples are not unexpectedly
        // compacted between style/section changes.
        BASS_SetConfig(BASS_CONFIG_MIDI_VOICES, voices_);
        BASS_SetConfig(BASS_CONFIG_MIDI_COMPACT, 0);
        BASS_SetConfig(BASS_CONFIG_UPDATEPERIOD, 10);

        LOGI("BASS initialized: MIDI voices=%d compact=0 updatePeriod=10ms", voices_);
    }

    if (!stream_) {
        stream_ = BASS_MIDI_StreamCreate(
            16,
            BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT,
            sampleRate_);

        if (!stream_) {
            LOGE("BASS_MIDI_StreamCreate failed error=%d", BASS_ErrorGetCode());
            return false;
        }

        // Yamaha style timing is PPQ 1920. This is mostly relevant if later
        // we feed tick-timed event batches; realtime note events remain
        // immediate because BASS_MIDI_ASYNC is deliberately not enabled.
        BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_PPQN, 1920.0f);

        // 16-point sinc is the highest BASSMIDI SRC quality available on
        // ARM/NEON and is the quality path we want for an arranger.
        BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_SRC,
                                 static_cast<float>(interpolation_));

        BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_VOICES,
                                 static_cast<float>(voices_));
        BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_VOL, 1.0f);
        BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_BUFFER, 0.0f);

        // Channel 10 is the standard percussion channel for a 16-channel
        // BASSMIDI realtime stream.
        BASS_MIDI_StreamEvent(stream_, 9, MIDI_EVENT_DRUMS, 1);

        LOGI("BASSMIDI stream ready: PPQN=1920 SRC=%d voices=%d",
             interpolation_, voices_);
    }

    return true;
}

bool BassMidiPlayer::applyFonts() {
    if (!stream_) return false;

    // MIDI Voyager supports multiple soundfonts and bank-aware preset
    // selection. Use the newer FONTEX2 form so the same SF2 can expose all
    // melodic banks while the drum font is restricted to channel 10.
    //
    // Priority matters: the dedicated drum font is installed first for the
    // destination drum bank, then the general melodic font handles normal
    // banks. BASSMIDI searches these mappings when a program/bank is first
    // used.
    BASS_MIDI_FONTEX2 cfg[5]{};
    DWORD count = 0;

    if (drumFont_) {
        // Standard SF2 drum bank 128 -> MIDI drum bank 128.
        cfg[count].font = drumFont_;
        cfg[count].spreset = -1;
        cfg[count].sbank = 128;
        cfg[count].dpreset = -1;
        cfg[count].dbank = 128;
        cfg[count].dbanklsb = 0;
        cfg[count].minchan = 9;
        cfg[count].numchan = 1;
        ++count;

        // Yamaha/XG drum fonts are commonly stored in bank 127.
        cfg[count].font = drumFont_;
        cfg[count].spreset = -1;
        cfg[count].sbank = 127;
        cfg[count].dpreset = -1;
        cfg[count].dbank = 128;
        cfg[count].dbanklsb = 0;
        cfg[count].minchan = 9;
        cfg[count].numchan = 1;
        ++count;

        // Some single-kit SF2 files use bank 0. Expose that kit as the
        // standard drum destination without affecting melodic channels.
        cfg[count].font = drumFont_;
        cfg[count].spreset = -1;
        cfg[count].sbank = 0;
        cfg[count].dpreset = -1;
        cfg[count].dbank = 128;
        cfg[count].dbanklsb = 0;
        cfg[count].minchan = 9;
        cfg[count].numchan = 1;
        ++count;
    }

    if (melodyFont_) {
        cfg[count].font = melodyFont_;
        cfg[count].spreset = -1;
        cfg[count].sbank = -1;
        cfg[count].dpreset = -1;
        cfg[count].dbank = 0;
        cfg[count].dbanklsb = 0;
        cfg[count].minchan = 0;
        cfg[count].numchan = 0;
        ++count;
    }

    if (!count) return false;

    if (!BASS_MIDI_StreamSetFonts(stream_, cfg, count)) {
        LOGE("StreamSetFonts(FONTEX2) failed error=%d", BASS_ErrorGetCode());
        return false;
    }

    if (melodyFont_) {
        BASS_MIDI_FontSetVolume(melodyFont_, soundFontVolume_);
    }
    if (drumFont_) {
        BASS_MIDI_FontSetVolume(drumFont_, soundFontVolume_);
    }

    LOGI("BASSMIDI fonts applied: FONTEX2 entries=%u melody=%d drum=%d volume=%.2f",
         static_cast<unsigned>(count), melodyFont_ != 0, drumFont_ != 0,
         soundFontVolume_);
    return true;
}

bool BassMidiPlayer::loadRole(const std::string& path, bool drum) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return false;

    HSOUNDFONT& target = drum ? drumFont_ : melodyFont_;
    if (target) {
        BASS_MIDI_FontFree(target);
        target = 0;
    }

    target = BASS_MIDI_FontInit(path.c_str(), 0);
    if (!target) {
        LOGE("FontInit failed role=%s error=%d",
             drum ? "DRUM" : "MELODY", BASS_ErrorGetCode());
        return false;
    }

    BASS_MIDI_FONTINFO info{};
    if (BASS_MIDI_FontGetInfo(target, &info)) {
        LOGI("BASSMIDI font role=%s presets=%u samsize=%llu samload=%llu samtype=%u name=%s",
             drum ? "DRUM" : "MELODY",
             static_cast<unsigned>(info.presets),
             static_cast<unsigned long long>(info.samsize),
             static_cast<unsigned long long>(info.samload),
             static_cast<unsigned>(info.samtype),
             info.name ? info.name : "");
    }

    if (!applyFonts()) {
        BASS_MIDI_FontFree(target);
        target = 0;
        return false;
    }

    // Restore channel routing after a font replacement. This matters when a
    // style was already running and a user swaps SF2 files.
    for (int ch = 0; ch < 16; ++ch) {
        if (channels_[ch].initialized) {
            send(ch, MIDI_EVENT_BANK, static_cast<DWORD>(channels_[ch].bankMsb));
            send(ch, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(channels_[ch].bankLsb));
            send(ch, MIDI_EVENT_PROGRAM, static_cast<DWORD>(channels_[ch].program));
        }
    }

    if (!drum) melodyPath_ = path;

    LOGI("BASSMIDI %s SF2 loaded: %s",
         drum ? "DRUM" : "MELODY", path.c_str());
    return true;
}

bool BassMidiPlayer::load(const std::string& path) {
    return !isMelodyLoaded() ? loadMelody(path) : loadDrum(path);
}

bool BassMidiPlayer::loadMelody(const std::string& path) {
    return loadRole(path, false);
}

bool BassMidiPlayer::loadDrum(const std::string& path) {
    return loadRole(path, true);
}

void BassMidiPlayer::unload() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (stream_) {
        for (int ch = 0; ch < 16; ++ch) {
            BASS_MIDI_StreamEvent(stream_, ch, MIDI_EVENT_NOTESOFF, 0);
        }
    }

    if (melodyFont_) {
        BASS_MIDI_FontFree(melodyFont_);
        melodyFont_ = 0;
    }
    if (drumFont_) {
        BASS_MIDI_FontFree(drumFont_);
        drumFont_ = 0;
    }
    if (stream_) {
        BASS_StreamFree(stream_);
        stream_ = 0;
    }

    for (auto& ch : channels_) ch = ChannelState{};
}

void BassMidiPlayer::send(int channel, DWORD event, DWORD param) {
    if (!stream_) return;
    channel = std::max(0, std::min(15, channel));

    if (!BASS_MIDI_StreamEvent(stream_, static_cast<DWORD>(channel), event, param)) {
        LOGE("MIDI event failed ch=%d event=%u param=%u error=%d",
             channel, static_cast<unsigned>(event),
             static_cast<unsigned>(param), BASS_ErrorGetCode());
    }
}

void BassMidiPlayer::preloadCurrentPreset(int channel) {
    if (!stream_ || channel < 0 || channel >= 16) return;

    const ChannelState& state = channels_[channel];
    HSOUNDFONT font = state.drum ? drumFont_ : melodyFont_;
    if (!font) return;

    // MIDI Voyager normally preloads only the samples required by the
    // currently loaded MIDI file. For the realtime arranger we know the
    // active program at each style setup event, so preload that exact preset
    // when it is selected instead of loading the whole SF2.
    int sourceBank = state.drum ? 128 : state.bankMsb;
    if (state.drum) {
        if (!BASS_MIDI_FontLoad(font, state.program, sourceBank)) {
            const int err = BASS_ErrorGetCode();
            if (!BASS_MIDI_FontLoad(font, state.program, 127)) {
                if (!BASS_MIDI_FontLoad(font, state.program, 0)) {
                    LOGI("BASSMIDI preload skipped ch=%d bank=%d prog=%d err=%d",
                         channel, sourceBank, state.program, err);
                    return;
                }
            }
        }
    } else {
        if (!BASS_MIDI_FontLoad(font, state.program, sourceBank)) {
            LOGI("BASSMIDI preload skipped ch=%d bank=%d prog=%d err=%d",
                 channel, sourceBank, state.program, BASS_ErrorGetCode());
            return;
        }
    }

    LOGI("BASSMIDI preload ch=%d bank=%d lsb=%d prog=%d",
         channel, state.bankMsb, state.bankLsb, state.program);
}

void BassMidiPlayer::noteOn(int channel, int key, float velocity) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    key = std::max(0, std::min(127, key));
    const int vel = std::max(1, std::min(127,
        static_cast<int>(std::lround(velocity * 127.0f))));

    // Never overwrite the channel's program/bank here. MIDI Voyager keeps
    // instrument state separate from note events; doing a forced Program 0
    // on every note was one of the diagnostic build's major correctness bugs.
    send(channel, MIDI_EVENT_NOTE,
         static_cast<DWORD>(key | (vel << 8)));
}

void BassMidiPlayer::noteOff(int channel, int key) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return;

    channel = std::max(0, std::min(15, channel));
    key = std::max(0, std::min(127, key));
    send(channel, MIDI_EVENT_NOTE, static_cast<DWORD>(key));
}

void BassMidiPlayer::allNotesOff() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return;
    for (int ch = 0; ch < 16; ++ch) {
        send(ch, MIDI_EVENT_NOTESOFF, 0);
    }
}

void BassMidiPlayer::setChannelPreset(int channel, int bank, int program) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    bank = std::max(0, std::min(127, bank));
    program = std::max(0, std::min(65535, program));

    ChannelState& state = channels_[channel];
    state.bankMsb = bank;
    state.bankLsb = 0;
    state.program = program;
    state.drum = (channel == 9 || bank >= 127);
    state.initialized = true;

    send(channel, MIDI_EVENT_BANK, static_cast<DWORD>(state.bankMsb));
    send(channel, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(state.bankLsb));
    send(channel, MIDI_EVENT_PROGRAM, static_cast<DWORD>(state.program));

    if (state.drum) {
        send(channel, MIDI_EVENT_DRUMS, 1);
    } else {
        send(channel, MIDI_EVENT_DRUMS, 0);
    }

    preloadCurrentPreset(channel);
}

void BassMidiPlayer::setChannelMixer(int channel, int volume, int pan,
                                     int expression, int reverbSend,
                                     int chorusSend) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    send(channel, MIDI_EVENT_VOLUME, std::clamp(volume, 0, 127));
    send(channel, MIDI_EVENT_PAN, std::clamp(pan, 0, 128));
    send(channel, MIDI_EVENT_EXPRESSION, std::clamp(expression, 0, 127));
    send(channel, MIDI_EVENT_REVERB, std::clamp(reverbSend, 0, 127));
    send(channel, MIDI_EVENT_CHORUS, std::clamp(chorusSend, 0, 127));
}

void BassMidiPlayer::setChannelExpression(int channel, int expression) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return;
    send(channel, MIDI_EVENT_EXPRESSION, std::clamp(expression, 0, 127));
}

void BassMidiPlayer::setMasterGain(float gain) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return;
    BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_VOL,
                             std::max(0.0f, std::min(1.0f, gain)));
}

std::string BassMidiPlayer::presetList() const {
    std::lock_guard<std::mutex> lock(mutex_);

    // BASSMIDI can report FONTINFO.presets=0 for some large/legacy Yamaha
    // SF2s even though the RIFF SoundFont contains a valid pdta/phdr table.
    // The Voice Browser must not depend on BASSMIDI's optional enumeration
    // metadata. Read the SF2 preset headers directly from the loaded file.
    struct Preset {
        int bank = 0;
        int program = 0;
        std::string name;
    };
    std::vector<Preset> found;

    if (!melodyPath_.empty()) {
        std::ifstream file(melodyPath_, std::ios::binary);
        if (file) {
            std::vector<unsigned char> data(
                (std::istreambuf_iterator<char>(file)),
                std::istreambuf_iterator<char>());

            // SF2 is RIFF/LIST based. Locate the pdta/phdr chunk. Each
            // preset header is 38 bytes and the final record is the terminal
            // EOP marker, which is not a selectable preset.
            const char phdr[] = {'p','h','d','r'};
            for (size_t i = 0; i + 8 <= data.size(); ++i) {
                if (std::memcmp(data.data() + i, phdr, 4) != 0) continue;
                const uint32_t size =
                    static_cast<uint32_t>(data[i + 4]) |
                    (static_cast<uint32_t>(data[i + 5]) << 8) |
                    (static_cast<uint32_t>(data[i + 6]) << 16) |
                    (static_cast<uint32_t>(data[i + 7]) << 24);
                if (size < 38 || size % 38 != 0 || i + 8 + size > data.size())
                    continue;

                const size_t count = size / 38;
                for (size_t n = 0; n + 1 < count; ++n) {
                    const unsigned char* rec = data.data() + i + 8 + n * 38;
                    size_t nameLen = 0;
                    while (nameLen < 20 && rec[nameLen] != 0) ++nameLen;
                    std::string name(
                        reinterpret_cast<const char*>(rec), nameLen);
                    const int program = static_cast<int>(rec[20]) |
                        (static_cast<int>(rec[21]) << 8);
                    const int bank = static_cast<int>(rec[22]) |
                        (static_cast<int>(rec[23]) << 8);

                    // Ignore malformed/empty headers, but retain program 0
                    // because it is a legitimate piano/GM preset.
                    if (name.empty()) name = "Preset " +
                        std::to_string(bank) + ":" + std::to_string(program);

                    found.push_back({bank, program, name});
                }
                if (!found.empty()) break;
            }
        }
    }

    if (!found.empty()) {
        std::sort(found.begin(), found.end(),
                  [](const Preset& a, const Preset& b) {
                      if (a.bank != b.bank) return a.bank < b.bank;
                      if (a.program != b.program) return a.program < b.program;
                      return a.name < b.name;
                  });

        std::unordered_set<std::string> seen;
        std::ostringstream out;
        for (const auto& p : found) {
            const std::string key = std::to_string(p.bank) + ":" +
                                    std::to_string(p.program);
            if (!seen.insert(key).second) continue;
            // Contract with AudioEngineManager.loadedSoundFontPresets():
            // one preset per line, role|bank|program|name.
            out << "MELODY|" << p.bank << '|' << p.program << '|' << p.name << '\n';
        }
        LOGI("SF2 phdr preset scan found %u entries from %s",
             static_cast<unsigned>(seen.size()), melodyPath_.c_str());
        return out.str();
    }

    if (!melodyFont_) return {};

    // Last-resort BASSMIDI enumeration for SF2/SFZ formats where direct
    // SoundFont headers are unavailable.
    BASS_MIDI_FONTINFO info{};
    const bool infoOk = BASS_MIDI_FontGetInfo(melodyFont_, &info);
    LOGI("BASSMIDI preset scan: infoOk=%d presets=%u name=%s",
         infoOk ? 1 : 0,
         infoOk ? static_cast<unsigned>(info.presets) : 0u,
         (infoOk && info.name) ? info.name : "");

    std::vector<DWORD> presets;
    if (infoOk && info.presets > 0) {
        presets.resize(info.presets);
        if (!BASS_MIDI_FontGetPresets(melodyFont_, presets.data())) {
            LOGE("BASS_MIDI_FontGetPresets failed error=%d", BASS_ErrorGetCode());
            presets.clear();
        }
    }
    if (presets.empty()) {
        LOGI("BASSMIDI preset scan: using FontGetPreset fallback scan");
        presets.reserve(256);
        for (int bank = 0; bank <= 128; ++bank) {
            for (int program = 0; program < 128; ++program) {
                const char* name = BASS_MIDI_FontGetPreset(
                    melodyFont_, program, bank);
                if (name) {
                    presets.push_back(
                        static_cast<DWORD>((bank << 16) | (program & 0xffff)));
                }
            }
        }
        LOGI("BASSMIDI preset fallback found %u entries",
             static_cast<unsigned>(presets.size()));
    }

    std::ostringstream out;
    for (DWORD p : presets) {
        const int program = static_cast<int>(LOWORD(p));
        const int bank = static_cast<int>(HIWORD(p));
        const char* name = BASS_MIDI_FontGetPreset(melodyFont_, program, bank);
        if (!name) name = "";
        out << "MELODY|" << bank << '|' << program << '|' << name << '\n';
    }
    return out.str();
}

void BassMidiPlayer::render(float* out, int numFrames) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) {
        std::fill(out, out + numFrames * 2, 0.0f);
        return;
    }

    const DWORD wanted =
        static_cast<DWORD>(numFrames * 2 * sizeof(float));
    const DWORD got = BASS_ChannelGetData(
        stream_, out, wanted | BASS_DATA_FLOAT);

    if (got == static_cast<DWORD>(-1)) {
        LOGE("BASS_ChannelGetData failed error=%d", BASS_ErrorGetCode());
        std::fill(out, out + numFrames * 2, 0.0f);
        return;
    }

    const int samples = static_cast<int>(got / sizeof(float));
    if (samples < numFrames * 2) {
        std::fill(out + samples, out + numFrames * 2, 0.0f);
    }
}

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

        // Yamaha styles can use both Rhythm channels 9 and 10
        // (zero-based MIDI channels 8 and 9). Keep both as percussion.
        BASS_MIDI_StreamEvent(stream_, 8, MIDI_EVENT_DRUMS, 1);
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
    // FONTEX2 carries the bank LSB separately from the bank MSB. A single
    // melodic mapping with dbanklsb=0 would silently hide every Yamaha
    // preset selected with a non-zero Bank LSB. Install one mapping per LSB
    // so the same melody SF2 remains available across the full 14-bit
    // Yamaha bank space while the actual SF2 bank remains the MSB-sized
    // 0..127 bank.
    std::vector<BASS_MIDI_FONTEX2> cfg;
    // Voyager treats both SF2 banks 127 and 128 as drum banks. Keep those
    // source banks explicit instead of restricting the drum mapping to 128.
    cfg.reserve((drumFont_ ? 2 : 0) + (!drumFont_ && melodyFont_ ? 2 : 0) +
                (melodyFont_ ? 256 : 0));

    auto addDrumMapping = [&](HSOUNDFONT font, int sourceBank) {
        BASS_MIDI_FONTEX2 drum{};
        drum.font = font;
        drum.spreset = -1;
        drum.sbank = sourceBank;
        drum.dpreset = -1;
        drum.dbank = 128;
        drum.dbanklsb = 0;
        drum.minchan = 8;
        drum.numchan = 2;
        cfg.push_back(drum);
    };

    if (drumFont_) {
        addDrumMapping(drumFont_, 127);
        addDrumMapping(drumFont_, 128);
    }

    if (!drumFont_ && melodyFont_) {
        // Single-SF2 mode: Voyager treats banks 127 and 128 as drum banks.
        // Expose both source banks from the same SF2 on Yamaha Rhythm 1/2.
        addDrumMapping(melodyFont_, 127);
        addDrumMapping(melodyFont_, 128);
    }

    if (melodyFont_) {
        // Keep the melody mappings off MIDI channel 10 (zero-based 9).
        // numchan=0 means "all channels" in BASSMIDI, which can overlap the
        // dedicated drum mapping above and cause a loaded drum SF2 to be
        // bypassed. Cover melodic channels as two ranges: 0..8 and 10..15.
        for (int lsb = 0; lsb < 128; ++lsb) {
            BASS_MIDI_FONTEX2 melodyA{};
            melodyA.font = melodyFont_;
            melodyA.spreset = -1;
            melodyA.sbank = -1;
            melodyA.dpreset = -1;
            melodyA.dbank = 0;
            melodyA.dbanklsb = lsb;
            // Keep the melody mapping off both Yamaha rhythm channels:
            // zero-based 8 and 9 are reserved for the dedicated drum mapping.
            melodyA.minchan = 0;
            melodyA.numchan = 8;
            cfg.push_back(melodyA);

            BASS_MIDI_FONTEX2 melodyB{};
            melodyB.font = melodyFont_;
            melodyB.spreset = -1;
            melodyB.sbank = -1;
            melodyB.dpreset = -1;
            melodyB.dbank = 0;
            melodyB.dbanklsb = lsb;
            melodyB.minchan = 10;
            melodyB.numchan = 6;
            cfg.push_back(melodyB);
        }
    }

    const DWORD count = static_cast<DWORD>(cfg.size());
    if (!count) return false;

    if (!BASS_MIDI_StreamSetFonts(stream_, cfg.data(), count)) {
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
    if (drum) drumPath_.clear();
    else melodyPath_.clear();
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

    // Restore the complete channel routing after a font replacement.
    // Voyager's drum-channel fix relies on percussion state surviving a
    // soundfont reload; restoring only bank/program can silently turn a
    // rhythm channel back into a melodic channel.
    for (int ch = 0; ch < 16; ++ch) {
        if (channels_[ch].initialized) {
            send(ch, MIDI_EVENT_DRUMS, channels_[ch].drum ? 1 : 0);
            send(ch, MIDI_EVENT_BANK, static_cast<DWORD>(channels_[ch].bankMsb));
            send(ch, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(channels_[ch].bankLsb));
            send(ch, MIDI_EVENT_PROGRAM, static_cast<DWORD>(channels_[ch].program));
        }
    }

    if (!drum) melodyPath_ = path;
    else drumPath_ = path;

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
    melodyPath_.clear();
    drumPath_.clear();
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

bool BassMidiPlayer::findDrumPreset(const std::string& path,
                                     int requestedProgram,
                                     int& sourceBank, int& sourceProgram) const {
    if (path.empty()) return false;
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    std::vector<unsigned char> data(
        (std::istreambuf_iterator<char>(file)),
        std::istreambuf_iterator<char>());

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

        int firstBank = -1, firstProgram = -1;
        const size_t count = size / 38;
        for (size_t n = 0; n + 1 < count; ++n) {
            const unsigned char* rec = data.data() + i + 8 + n * 38;
            const int program = static_cast<int>(rec[20]) |
                                (static_cast<int>(rec[21]) << 8);
            const int bank = static_cast<int>(rec[22]) |
                             (static_cast<int>(rec[23]) << 8);
            if (bank != 127 && bank != 128) continue;
            if (firstBank < 0) {
                firstBank = bank;
                firstProgram = program;
            }
            if (program == requestedProgram) {
                sourceBank = bank;
                sourceProgram = program;
                return true;
            }
        }
        if (firstBank >= 0) {
            sourceBank = firstBank;
            sourceProgram = firstProgram;
            return true;
        }
        return false;
    }
    return false;
}

void BassMidiPlayer::preloadCurrentPreset(int channel) {
    if (!stream_ || channel < 0 || channel >= 16) return;

    const ChannelState& state = channels_[channel];
    HSOUNDFONT font = state.drum ? drumFont_ : melodyFont_;
    const std::string& path = state.drum
        ? (drumFont_ ? drumPath_ : melodyPath_)
        : melodyPath_;
    if (!font) return;

    // Resolve drum program against the actual SF2 drum banks before loading.
    // This mirrors Voyager's default-drumkit fallback instead of attempting
    // to preload a drum program that is not present.
    int sourceBank = state.drum ? 128 : state.bankMsb;
    int sourceProgram = state.program;
    if (state.drum) {
        if (!findDrumPreset(path, state.program, sourceBank, sourceProgram)) {
            LOGI("BASSMIDI no drum preset found ch=%d requested=%d",
                 channel, state.program);
            return;
        }
        if (!BASS_MIDI_FontLoad(font, sourceProgram, sourceBank)) {
            LOGI("BASSMIDI drum preload skipped ch=%d bank=%d prog=%d err=%d",
                 channel, sourceBank, sourceProgram, BASS_ErrorGetCode());
            return;
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
    const int requestedBank = bank;
    program = std::max(0, std::min(65535, program));

    // The Kotlin side represents Yamaha bank select as one 14-bit value:
    // MSB * 128 + LSB. BASSMIDI keeps the two MIDI controllers separate,
    // so split the value here instead of collapsing every melodic bank to
    // LSB 0.
    // Yamaha Rhythm 1/2 are MIDI channels 9/10 (zero-based 8/9).
    // Keep both channels in percussion mode even when a style omits an
    // explicit Bank Select event.
    const bool wantDrum = (channel == 8 || channel == 9 || bank >= 128);
    if (wantDrum) {
        bank = 128;
    } else {
        bank = std::max(0, std::min(16383, bank));
    }

    ChannelState& state = channels_[channel];
    state.bankMsb = bank >= 128 ? 128 : bank / 128;
    state.bankLsb = bank >= 128 ? 0 : bank % 128;
    state.program = program;
    state.drum = wantDrum;
    state.initialized = true;

    if (state.drum) {
        // Voyager keeps drum program numbers intact when they exist, but
        // allows a default drumkit when the requested kit is unavailable.
        // Resolve that against the actual loaded SF2 before sending the
        // program change.
        int sourceBank = 128;
        int sourceProgram = program;
        const std::string& path = drumFont_ ? drumPath_ : melodyPath_;
        if (findDrumPreset(path, program, sourceBank, sourceProgram)) {
            state.bankMsb = 128;
            state.bankLsb = 0;
            state.program = sourceProgram;
            LOGI("DRUM PRESET RESOLVE ch=%d requested=%d -> bank=%d prog=%d",
                 channel, program, sourceBank, sourceProgram);
        }
    }

    LOGI("SET PRESET ch=%d requestedBank=%d effectiveBank=%d prog=%d drum=%d",
         channel, requestedBank, state.bankMsb, state.program, state.drum ? 1 : 0);

    if (state.drum) {
        send(channel, MIDI_EVENT_DRUMS, 1);
    } else {
        send(channel, MIDI_EVENT_DRUMS, 0);
    }

    send(channel, MIDI_EVENT_BANK, static_cast<DWORD>(state.bankMsb));
    send(channel, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(state.bankLsb));
    send(channel, MIDI_EVENT_PROGRAM, static_cast<DWORD>(state.program));

    LOGI("SET PRESET APPLIED ch=%d bank=%d lsb=%d prog=%d drum=%d",
         channel, state.bankMsb, state.bankLsb, state.program,
         state.drum ? 1 : 0);

    // Do not synchronously preload drum kits here. BASS_MIDI_FontLoad() can
    // block for a large drum SF2 while sample data is prepared. This method is
    // called from the style transition/program-change path, so blocking here
    // starves the Oboe render callback and makes fills/section changes sound
    // chopped or disappear. BASSMIDI can resolve/load the selected preset on
    // demand when the first note arrives; keep the program/bank event cheap.
    if (!state.drum) {
        preloadCurrentPreset(channel);
    }
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

    struct Preset {
        std::string role;
        int bank = 0;
        int program = 0;
        std::string name;
    };
    std::vector<Preset> found;

    auto scanSf2 = [&](const std::string& path, const char* role) {
        if (path.empty()) return;

        std::ifstream file(path, std::ios::binary);
        if (!file) {
            LOGI("SF2 %s preset scan: cannot open %s", role, path.c_str());
            return;
        }

        std::vector<unsigned char> data(
            (std::istreambuf_iterator<char>(file)),
            std::istreambuf_iterator<char>());

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
            size_t added = 0;
            for (size_t n = 0; n + 1 < count; ++n) {
                const unsigned char* rec = data.data() + i + 8 + n * 38;
                size_t nameLen = 0;
                while (nameLen < 20 && rec[nameLen] != 0) ++nameLen;

                std::string name(reinterpret_cast<const char*>(rec), nameLen);
                const int program = static_cast<int>(rec[20]) |
                    (static_cast<int>(rec[21]) << 8);
                const int bank = static_cast<int>(rec[22]) |
                    (static_cast<int>(rec[23]) << 8);

                // The separators are part of the native/Kotlin contract.
                // Sanitize control characters so a broken SF2 name can never
                // corrupt the record framing.
                for (char& ch : name) {
                    if (ch == '\r' || ch == '\n' || ch == '\x1e' || ch == '\x1f')
                        ch = ' ';
                }
                if (name.empty()) {
                    name = "Preset " + std::to_string(bank) + ":" +
                           std::to_string(program);
                }

                found.push_back({role, bank, program, name});
                ++added;
            }

            if (added > 0) {
                LOGI("SF2 %s phdr scan found %u entries from %s",
                     role, static_cast<unsigned>(added), path.c_str());
                return;
            }
        }

        LOGI("SF2 %s phdr scan found no selectable presets from %s",
             role, path.c_str());
    };

    // Enumerate both loaded fonts. Melody and drum SF2s are separate assets,
    // so the Voice Browser can show the real drum kits on CH9 instead of the
    // GM fallback list.
    scanSf2(melodyPath_, "MELODY");
    scanSf2(drumPath_, "DRUM");

    if (!found.empty()) {
        std::sort(found.begin(), found.end(),
                  [](const Preset& a, const Preset& b) {
                      if (a.role != b.role) return a.role < b.role;
                      if (a.bank != b.bank) return a.bank < b.bank;
                      if (a.program != b.program) return a.program < b.program;
                      return a.name < b.name;
                  });

        std::unordered_set<std::string> seen;
        std::ostringstream out;

        // Use ASCII record/field separators rather than newline/pipe framing.
        // This survives JNI/UI transport even if a native layer normalizes
        // line endings, and names are sanitized above.
        constexpr char FIELD = '\x1f';
        constexpr char RECORD = '\x1e';

        for (const auto& p : found) {
            const std::string key = p.role + ":" + std::to_string(p.bank) +
                                    ":" + std::to_string(p.program);
            if (!seen.insert(key).second) continue;

            out << p.role << FIELD << p.bank << FIELD << p.program
                << FIELD << p.name << RECORD;
        }

        LOGI("SF2 preset scan total=%u melodyPath=%s drumPath=%s",
             static_cast<unsigned>(seen.size()),
             melodyPath_.c_str(), drumPath_.c_str());
        return out.str();
    }

    // Last-resort BASSMIDI enumeration. This path is melody-only because
    // direct SF2 header scanning is the reliable path for the Yamaha fonts
    // used by the arranger.
    if (!melodyFont_) return {};

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
    }

    std::ostringstream out;
    for (DWORD p : presets) {
        const int program = static_cast<int>(LOWORD(p));
        const int bank = static_cast<int>(HIWORD(p));
        const char* name = BASS_MIDI_FontGetPreset(melodyFont_, program, bank);
        if (!name) name = "";
        out << "MELODY\x1f" << bank << '\x1f' << program
            << '\x1f' << name << '\x1e';
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

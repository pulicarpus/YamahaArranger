#include "bassmidi_player.h"
#include "voice_resolver.h"
#include <android/log.h>
#include <algorithm>
#include <fstream>
#include <unordered_set>
#include <unordered_map>
#include <set>
#include <cmath>
#include <chrono>
#include <sstream>
#include <vector>
#include <cctype>
#include <cstring>
#include <cstdarg>
#include <cstdio>
#include <jni.h>

#define LOG_TAG "BassMidiPlayer"

// BassMidiPlayer is the layer that knows the real SF2/BASSMIDI mapping.
// Route its diagnostics through the same DebugLog bridge as AudioEngine so
// the in-app exported log can prove what BASSMIDI actually loaded/applied.
extern JavaVM* g_jvm;
extern jclass g_debugLogClass;
extern jmethodID g_debugLogAddMethod;

static void uiLog(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
    if (g_jvm && g_debugLogClass && g_debugLogAddMethod) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env) {
            jstring jmsg = env->NewStringUTF(buf);
            env->CallStaticVoidMethod(g_debugLogClass, g_debugLogAddMethod, jmsg);
            env->DeleteLocalRef(jmsg);
            if (attached) g_jvm->DetachCurrentThread();
        }
    }
}
#define LOGI(...) uiLog(__VA_ARGS__)
#define LOGE(...) uiLog(__VA_ARGS__)

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
        // Rapid playing can create overlapping instances of the same MIDI note
        // (especially with layered String voices). BASSMIDI 2.4.16 provides
        // BASS_MIDI_NOTEOFF1 specifically for this case: each NOTE_OFF releases
        // the oldest matching instance instead of releasing every overlapping
        // instance. Without it, a fast NOTE_ON/NOTE_OFF sequence can leave the
        // note-instance bookkeeping out of sync and produce a stuck String note.
        stream_ = BASS_MIDI_StreamCreate(
            16,
            BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT | BASS_MIDI_NOTEOFF1,
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
                (melodyFont_ ? 256 : 0) + secondaryMelodies_.size() * 256);

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

    // Channel-specific resolver mappings are intentionally placed before
    // generic all-preset mappings. BASSMIDI gives earlier FONTEX/EX2 entries
    // priority when multiple soundfonts provide the same destination.
    bool validMelodicMappings = true;
    auto addResolvedChannelMappings = [&]() {
        auto toVirtualBank = [](const std::vector<NormalizedBankMap>& maps, int rawBank) {
            for (const auto& m : maps) {
                if (m.rawBank == rawBank) return m.virtualBank;
            }
            return -1; // An absent normalization entry cannot install a safe mapping.
        };

        for (int ch = 0; ch < 16; ++ch) {
            const ChannelState& state = channels_[ch];
            if (!state.initialized || state.drum ||
                state.melodySourceBank < 0 || state.melodySourceProgram < 0) {
                continue;
            }

            HSOUNDFONT font = 0;
            const std::vector<NormalizedBankMap>* maps = nullptr;
            const char* role = "PRIMARY";
            if (state.melodySource > 0 &&
                static_cast<size_t>(state.melodySource) <= secondaryMelodies_.size()) {
                const auto& secondary = secondaryMelodies_[state.melodySource - 1];
                font = secondary.font;
                maps = &secondary.banks;
                role = secondary.path.c_str();
            } else if (state.melodySource == 0 && melodyFont_) {
                font = melodyFont_;
                maps = &normalizedBanks_;
            }
            if (!font || !maps) { validMelodicMappings = false; continue; }

            const int virtualBank = toVirtualBank(*maps, state.melodySourceBank);
            if (virtualBank < 0) {
                LOGI("VOICE MAP skipped ch=%d role=%s rawSourceBank=%d sourceProg=%d",
                     ch, role, state.melodySourceBank, state.melodySourceProgram);
                validMelodicMappings = false;
                continue;
            }

            BASS_MIDI_FONTEX2 selected{};
            selected.font = font;
            selected.spreset = state.melodySourceProgram;
            selected.sbank = virtualBank;
            selected.dpreset = state.program;
            selected.dbank = state.bankMsb;
            selected.dbanklsb = state.bankLsb;
            selected.minchan = ch;
            selected.numchan = 1;
            cfg.push_back(selected);

            LOGI("VOICE MAP ch=%d role=%s srcBank=%d srcProg=%d srcName='%s' -> dstBank=%d:%d dstProg=%d",
                 ch, role, state.melodySourceBank, state.melodySourceProgram,
                 state.melodySourceName.c_str(), state.bankMsb, state.bankLsb, state.program);
        }
    };

    addResolvedChannelMappings();
    if (!validMelodicMappings) return false;

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
        // The melody SF2 has been normalized so every original Yamaha bank
        // occupies one legal BASSMIDI source bank. Map each virtual source
        // bank back to its Yamaha MSB/LSB destination.
        for (const auto& m : normalizedBanks_) {
            BASS_MIDI_FONTEX2 melody{};
            melody.font = melodyFont_;
            melody.spreset = -1;
            melody.sbank = m.virtualBank;
            melody.dpreset = -1;
            melody.dbank = m.midiMsb;
            melody.dbanklsb = m.midiLsb;
            melody.minchan = 0;
            melody.numchan = 8;
            cfg.push_back(melody);

            BASS_MIDI_FONTEX2 melodyB = melody;
            melodyB.minchan = 10;
            melodyB.numchan = 6;
            cfg.push_back(melodyB);
        }
        LOGI("BASSMIDI normalized melody FONTEX2 mappings=%u",
             static_cast<unsigned>(normalizedBanks_.size()));
    }

    for (const auto& secondary : secondaryMelodies_) {
        for (const auto& m : secondary.banks) {
            BASS_MIDI_FONTEX2 fallback{};
            fallback.font = secondary.font;
            fallback.spreset = -1;
            fallback.sbank = m.virtualBank;
            fallback.dpreset = -1;
            fallback.dbank = m.midiMsb;
            fallback.dbanklsb = m.midiLsb;
            fallback.minchan = 0;
            fallback.numchan = 8;
            cfg.push_back(fallback);
            BASS_MIDI_FONTEX2 fallbackB = fallback;
            fallbackB.minchan = 10;
            fallbackB.numchan = 6;
            cfg.push_back(fallbackB);
        }
        LOGI("BASSMIDI secondary FONTEX2 sf2='%s' mappings=%u",
             secondary.path.c_str(), static_cast<unsigned>(secondary.banks.size()));
    }

    const DWORD count = static_cast<DWORD>(cfg.size());
    if (!count) return false;

    // IMPORTANT: cfg contains BASS_MIDI_FONTEX2 records. The EX2 flag is
    // part of the numfonts argument; without it BASSMIDI interprets the
    // array as the older BASS_MIDI_FONT layout and silently ignores
    // dbanklsb/minchan/numchan. That would make Yamaha 8:1 (1025), 8:2
    // (1026), etc. fall back to the virtual source bank number instead of
    // the intended Yamaha destination bank.
    const DWORD flags = count | BASS_MIDI_FONT_EX2;
    if (!BASS_MIDI_StreamSetFonts(stream_, cfg.data(), flags)) {
        LOGE("StreamSetFonts(FONTEX2) failed flags=%u error=%d",
             static_cast<unsigned>(flags), BASS_ErrorGetCode());
        return false;
    }
    LOGI("BASSMIDI FONTEX2 applied: entries=%u flags=%u",
         static_cast<unsigned>(count), static_cast<unsigned>(flags));

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
    if (drum) {
        drumPath_.clear();
        drumDrumPresetCache_.clear();
    } else {
        melodyPath_.clear();
        melodyDrumPresetCache_.clear();
        melodyPresetCache_.clear();
        // Replacing the primary melody font invalidates any previous
        // secondary layer; the caller can attach a new controlled fallback.
        for (const auto& secondary : secondaryMelodies_) BASS_MIDI_FontFree(secondary.font);
        secondaryMelodies_.clear();
        invalidateMelodicChannels();
    }
    if (target) {
        BASS_MIDI_FontFree(target);
        target = 0;
    }

    std::string fontPath = path;
    if (!drum) {
        melodyBassPath_ = path + ".bassmidi-normalized.sf2";
        if (!normalizeMelodySf2(path, melodyBassPath_)) {
            LOGE("BASSMIDI melody normalization failed; refusing packed Yamaha banks");
            melodyBassPath_.clear();
            return false;
        }
        fontPath = melodyBassPath_;
    }

    target = BASS_MIDI_FontInit(fontPath.c_str(), 0);
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

    if (!drum) {
        melodyPath_ = path;
        rebuildMelodyPresetCache(melodyPath_);
        rebuildDrumPresetCache(melodyPath_, melodyDrumPresetCache_);
        refreshMelodicChannels();
    } else {
        drumPath_ = path;
        rebuildDrumPresetCache(drumPath_, drumDrumPresetCache_);
    }

    if (!applyFonts()) {
        invalidateMelodicChannels();
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

bool BassMidiPlayer::loadMelodyFallback(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine() || path.empty() || !melodyFont_) return false;
    for (const auto& secondary : secondaryMelodies_) if (secondary.path == path) return true;
    // Bounded pool: primary Yamaha + Colombo + optional Tyros. Optional
    // allocation failure cannot discard the established melody/drum pair.
    if (secondaryMelodies_.size() >= 2) return false;
    SecondaryMelody secondary;
    secondary.path = path;
    secondary.bassPath = path + ".bassmidi-normalized.sf2";
    const auto primaryBanks = normalizedBanks_;
    normalizedBanks_.clear();
    if (!normalizeMelodySf2(path, secondary.bassPath)) {
        normalizedBanks_ = primaryBanks;
        LOGE("BASSMIDI secondary normalization failed sf2='%s'", path.c_str());
        return false;
    }
    secondary.banks = normalizedBanks_;
    normalizedBanks_ = primaryBanks;
    const auto primaryCache = melodyPresetCache_;
    melodyPresetCache_.clear();
    rebuildMelodyPresetCache(path);
    secondary.presets = melodyPresetCache_;
    melodyPresetCache_ = primaryCache;
    secondary.font = BASS_MIDI_FontInit(secondary.bassPath.c_str(), 0);
    if (!secondary.font) {
        LOGE("BASSMIDI secondary FontInit failed sf2='%s' error=%d", path.c_str(), BASS_ErrorGetCode());
        return false;
    }
    BASS_MIDI_FontSetVolume(secondary.font, soundFontVolume_);
    const auto previousChannels = channels_;
    secondaryMelodies_.push_back(std::move(secondary));
    refreshMelodicChannels();
    if (!applyFonts()) {
        BASS_MIDI_FontFree(secondaryMelodies_.back().font);
        secondaryMelodies_.pop_back();
        channels_ = previousChannels;
        if (!applyFonts()) invalidateMelodicChannels(); // Fail closed if restoring mappings fails.
        return false;
    }
    for (int ch = 0; ch < 16; ++ch) {
        const auto& state = channels_[ch];
        if (!state.initialized || state.drum) continue;
        send(ch, MIDI_EVENT_BANK, static_cast<DWORD>(state.bankMsb));
        send(ch, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(state.bankLsb));
        send(ch, MIDI_EVENT_PROGRAM, static_cast<DWORD>(state.program));
        preloadCurrentPreset(ch);
    }
    LOGI("VOICE INVENTORY secondary=%u sf2='%s' melodicPresets=%u",
         static_cast<unsigned>(secondaryMelodies_.size()), path.c_str(),
         static_cast<unsigned>(secondaryMelodies_.back().presets.size()));
    return true;
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
    for (const auto& secondary : secondaryMelodies_) BASS_MIDI_FontFree(secondary.font);
    secondaryMelodies_.clear();
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
    melodyBassPath_.clear();
    drumPath_.clear();
    melodyPresetCache_.clear();
    melodyDrumPresetCache_.clear();
    normalizedBanks_.clear();
    drumDrumPresetCache_.clear();
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

namespace {

// Walk the SF2 RIFF structure to locate the real preset-header chunk.
// Never scan raw sample data byte-by-byte: the PCM in "sdta" can contain
// the four ASCII bytes "phdr" by coincidence.  The preset table is the
// "phdr" sub-chunk of the "pdta" LIST.
bool findPhdrChunk(const std::vector<unsigned char>& data,
                   const unsigned char*& phdrData, uint32_t& phdrSize) {
    phdrData = nullptr;
    phdrSize = 0;
    if (data.size() < 12) return false;
    if (std::memcmp(data.data(), "RIFF", 4) != 0) return false;
    if (std::memcmp(data.data() + 8, "sfbk", 4) != 0) return false;

    size_t pos = 12;
    while (pos + 8 <= data.size()) {
        const uint32_t chunkSize =
            static_cast<uint32_t>(data[pos + 4]) |
            (static_cast<uint32_t>(data[pos + 5]) << 8) |
            (static_cast<uint32_t>(data[pos + 6]) << 16) |
            (static_cast<uint32_t>(data[pos + 7]) << 24);
        const size_t chunkDataStart = pos + 8;
        if (chunkDataStart > data.size() ||
            chunkSize > data.size() - chunkDataStart) {
            return false;
        }

        if (std::memcmp(data.data() + pos, "LIST", 4) == 0 &&
            chunkSize >= 4 &&
            std::memcmp(data.data() + chunkDataStart, "pdta", 4) == 0) {
            const size_t listEnd = chunkDataStart + chunkSize;
            size_t subPos = chunkDataStart + 4;
            while (subPos + 8 <= listEnd) {
                const uint32_t subSize =
                    static_cast<uint32_t>(data[subPos + 4]) |
                    (static_cast<uint32_t>(data[subPos + 5]) << 8) |
                    (static_cast<uint32_t>(data[subPos + 6]) << 16) |
                    (static_cast<uint32_t>(data[subPos + 7]) << 24);
                const size_t subDataStart = subPos + 8;
                if (subDataStart > listEnd ||
                    subSize > listEnd - subDataStart) {
                    return false;
                }
                if (std::memcmp(data.data() + subPos, "phdr", 4) == 0) {
                    phdrData = data.data() + subDataStart;
                    phdrSize = subSize;
                    return true;
                }
                subPos = subDataStart + subSize + (subSize & 1u);
            }
            return false;
        }

        pos = chunkDataStart + chunkSize + (chunkSize & 1u);
    }
    return false;
}

} // namespace

// BASSMIDI's FONTEX source bank is limited to the 128 SF2 banks. Yamaha
// variation banks such as 1025 (= MSB 8, LSB 1) therefore cannot be passed
// directly as sbank.  SF2 itself has no Bank-LSB field either.  Normalize the
// preset-header bank numbers into unique legal SF2 banks while retaining a
// side-table that maps each normalized bank back to Yamaha MSB/LSB.
bool BassMidiPlayer::normalizeMelodySf2(const std::string& sourcePath,
                                        const std::string& outputPath) {
    std::ifstream file(sourcePath, std::ios::binary);
    if (!file) {
        LOGE("SF2 normalize: cannot open %s", sourcePath.c_str());
        return false;
    }
    std::vector<unsigned char> data(
        (std::istreambuf_iterator<char>(file)),
        std::istreambuf_iterator<char>());

    const unsigned char* phdrData = nullptr;
    uint32_t phdrSize = 0;
    if (!findPhdrChunk(data, phdrData, phdrSize) ||
        phdrSize < 38 || phdrSize % 38 != 0) {
        LOGE("SF2 normalize: invalid phdr in %s", sourcePath.c_str());
        return false;
    }

    // Collect every melodic source bank. Bank 127/128 are reserved for drums.
    std::set<int> rawBanks;
    const size_t count = phdrSize / 38;
    for (size_t n = 0; n + 1 < count; ++n) {
        const unsigned char* rec = phdrData + n * 38;
        const int bank = static_cast<int>(rec[22]) |
                         (static_cast<int>(rec[23]) << 8);
        if (bank != 127 && bank != 128) rawBanks.insert(bank);
    }

    if (rawBanks.size() > 127) {
        LOGE("SF2 normalize: %u melodic banks exceed BASSMIDI's 127 usable source banks",
             static_cast<unsigned>(rawBanks.size()));
        return false;
    }

    normalizedBanks_.clear();
    int nextVirtual = 0;
    std::unordered_map<int, int> rawToVirtual;
    for (int rawBank : rawBanks) {
        // Reserve 0 for the first bank, then use 1..126. This avoids bank
        // 127/128 which BASSMIDI treats specially for percussion/XG.
        if (nextVirtual == 127) ++nextVirtual;
        if (nextVirtual > 126) {
            LOGE("SF2 normalize: no legal virtual source bank for raw=%d", rawBank);
            normalizedBanks_.clear();
            return false;
        }
        rawToVirtual[rawBank] = nextVirtual;
        normalizedBanks_.push_back({rawBank, nextVirtual,
                                    rawBank >= 128 ? rawBank / 128 : rawBank,
                                    rawBank >= 128 ? rawBank % 128 : 0});
        ++nextVirtual;
    }

    // Rewrite only phdr bank fields. All preset bags, instruments, samples,
    // modulators, generators and sample data remain byte-for-byte unchanged.
    // Because every raw bank gets its own virtual bank, programs from
    // different Yamaha LSB banks can never collide.
    const uintptr_t phdrOffset =
        static_cast<uintptr_t>(phdrData - data.data());
    unsigned char* mutablePhdr = data.data() + phdrOffset;
    for (size_t n = 0; n + 1 < count; ++n) {
        unsigned char* rec = mutablePhdr + n * 38;
        const int rawBank = static_cast<int>(rec[22]) |
                            (static_cast<int>(rec[23]) << 8);
        if (rawBank == 127 || rawBank == 128) continue;
        const auto it = rawToVirtual.find(rawBank);
        if (it == rawToVirtual.end()) return false;
        const int virtualBank = it->second;
        rec[22] = static_cast<unsigned char>(virtualBank & 0xff);
        rec[23] = static_cast<unsigned char>((virtualBank >> 8) & 0xff);
    }

    std::ofstream out(outputPath, std::ios::binary | std::ios::trunc);
    if (!out) {
        LOGE("SF2 normalize: cannot create %s", outputPath.c_str());
        return false;
    }
    out.write(reinterpret_cast<const char*>(data.data()),
              static_cast<std::streamsize>(data.size()));
    if (!out.good()) {
        LOGE("SF2 normalize: write failed %s", outputPath.c_str());
        return false;
    }

    LOGI("SF2 normalize OK: %s -> %s melodicBanks=%u",
         sourcePath.c_str(), outputPath.c_str(),
         static_cast<unsigned>(normalizedBanks_.size()));
    for (const auto& m : normalizedBanks_) {
        LOGI("SF2 bank map raw=%d -> virtual=%d -> MIDI=%d:%d",
             m.rawBank, m.virtualBank, m.midiMsb, m.midiLsb);
    }
    return true;
}

void BassMidiPlayer::rebuildDrumPresetCache(
    const std::string& path,
    std::vector<DrumPresetEntry>& cache) {
    cache.clear();
    if (path.empty()) return;

    std::ifstream file(path, std::ios::binary);
    if (!file) {
        LOGI("SF2 drum preset cache: cannot open %s", path.c_str());
        return;
    }

    std::vector<unsigned char> data(
        (std::istreambuf_iterator<char>(file)),
        std::istreambuf_iterator<char>());

    const unsigned char* phdrData = nullptr;
    uint32_t phdrSize = 0;
    if (!findPhdrChunk(data, phdrData, phdrSize) ||
        phdrSize < 38 || phdrSize % 38 != 0) {
        LOGI("SF2 drum preset cache: phdr not found path=%s", path.c_str());
        return;
    }

    const size_t count = phdrSize / 38;
    for (size_t n = 0; n + 1 < count; ++n) {
        const unsigned char* rec = phdrData + n * 38;
        const int program = static_cast<int>(rec[20]) |
                            (static_cast<int>(rec[21]) << 8);
        const int bank = static_cast<int>(rec[22]) |
                         (static_cast<int>(rec[23]) << 8);
        if (bank != 127 && bank != 128) continue;
        cache.push_back({bank, program});
    }

    if (!cache.empty()) {
        LOGI("SF2 drum preset cache built: entries=%u path=%s",
             static_cast<unsigned>(cache.size()), path.c_str());
    } else {
        LOGI("SF2 drum preset cache built: no bank 127/128 presets path=%s",
             path.c_str());
    }
}

bool BassMidiPlayer::findDrumPreset(const std::string& path,
                                     int requestedProgram,
                                     int& sourceBank, int& sourceProgram) const {
    if (path.empty()) return false;

    const std::vector<DrumPresetEntry>* cache = nullptr;
    if (!drumPath_.empty() && path == drumPath_) {
        cache = &drumDrumPresetCache_;
    } else if (!melodyPath_.empty() && path == melodyPath_) {
        cache = &melodyDrumPresetCache_;
    }
    if (!cache) return false;

    int firstBank = -1;
    int firstProgram = -1;
    for (const auto& preset : *cache) {
        if (firstBank < 0) {
            firstBank = preset.bank;
            firstProgram = preset.program;
        }
        if (preset.program == requestedProgram) {
            sourceBank = preset.bank;
            sourceProgram = preset.program;
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

void BassMidiPlayer::rebuildMelodyPresetCache(const std::string& path) {
    melodyPresetCache_.clear();
    if (path.empty()) return;
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        LOGI("SF2 melodic preset cache: cannot open %s", path.c_str());
        return;
    }
    std::vector<unsigned char> data(
        (std::istreambuf_iterator<char>(file)),
        std::istreambuf_iterator<char>());

    const unsigned char* phdrData = nullptr;
    uint32_t phdrSize = 0;
    if (!findPhdrChunk(data, phdrData, phdrSize) ||
        phdrSize < 38 || phdrSize % 38 != 0) {
        LOGI("SF2 melodic preset cache: phdr not found path=%s", path.c_str());
        return;
    }

    const size_t count = phdrSize / 38;
    for (size_t n = 0; n + 1 < count; ++n) {
        const unsigned char* rec = phdrData + n * 38;
        size_t nameLen = 0;
        while (nameLen < 20 && rec[nameLen] != 0) ++nameLen;
        std::string name(reinterpret_cast<const char*>(rec), nameLen);
        const int program = static_cast<int>(rec[20]) |
                            (static_cast<int>(rec[21]) << 8);
        const int sourceBank = static_cast<int>(rec[22]) |
                               (static_cast<int>(rec[23]) << 8);
        if (sourceBank == 127 || sourceBank == 128) continue;
        melodyPresetCache_.push_back({sourceBank, program, name});
    }
    LOGI("SF2 melodic preset cache built: entries=%u path=%s",
         static_cast<unsigned>(melodyPresetCache_.size()), path.c_str());
}

void BassMidiPlayer::invalidateMelodicChannels() {
    for (auto& state : channels_) {
        if (!state.initialized || state.drum) continue;
        state.melodySource = -1;
        state.melodySourceBank = -1;
        state.melodySourceProgram = -1;
        state.melodySourceName.clear();
    }
}

void BassMidiPlayer::refreshMelodicChannels() {
    for (auto& state : channels_) {
        if (!state.initialized || state.drum) continue;
        state.melodySourceBank = -1;
        state.melodySourceProgram = -1;
        state.melodySourceName.clear();
        findMelodicPreset(state.bankMsb * 128 + state.bankLsb, state.program,
                          state.requestedVoiceName, state.melodySourceBank,
                          state.melodySourceProgram, state.melodySourceName, state.melodySource);
    }
}

bool BassMidiPlayer::findMelodicPreset(
    int requestedBank, int requestedProgram, const std::string& voiceName,
    int& sourceBank, int& sourceProgram, std::string& matchedName, int& sourceFont) const {
    using namespace voice_resolver;
    std::vector<Candidate> candidates;
    auto collect = [&](int source, const std::string& path, const std::vector<MelodicPresetEntry>& cache) {
        const bool yamaha = isYamahaSource(path);
        const bool gm = isGmSource(path);
        const auto sf2 = path.substr(path.find_last_of("/\\") + 1);
        for (const auto& p : cache) candidates.push_back({source, sf2, p.bank, p.program, p.name, yamaha, gm});
    };
    if (melodyFont_) collect(0, melodyPath_, melodyPresetCache_);
    for (size_t i = 0; i < secondaryMelodies_.size(); ++i) {
        const auto& secondary = secondaryMelodies_[i];
        collect(static_cast<int>(i + 1), secondary.path, secondary.presets);
    }
    const Request input{requestedBank, requestedProgram, voiceName};
    const auto request = decodeRequest(input, candidates);
    LOGI("VOICE REQUEST bank=%d msb=%d lsb=%d pc=%d name='%s' decodedName='%s' role=MELODY family=%s decode=%s",
         requestedBank, requestedBank / 128, requestedBank % 128, requestedProgram,
         voiceName.c_str(), request.name.c_str(), familyName(request.family()),
         request.name != input.name ? "consistent_yamaha_identity" : "name_or_standard_gm");
    for (size_t source = 0; source <= secondaryMelodies_.size(); ++source) {
        unsigned total = 0, accepted = 0, rejected = 0;
        int best = -1;
        Decision bestDecision;
        std::string sf2;
        for (size_t i = 0; i < candidates.size(); ++i) {
            const auto& c = candidates[i];
            if (c.source != static_cast<int>(source)) continue;
            sf2 = c.sf2; ++total;
            const auto d = evaluate(request, c);
            if (d.accepted) {
                ++accepted;
                if (best < 0 || d.score > bestDecision.score) {
                    best = static_cast<int>(i); bestDecision = d;
                }
            } else {
                ++rejected;
                // Show rejected numeric collisions; aggregate other rejects
                // to avoid thousands of JNI log calls per Program Change.
                if (c.program == requestedProgram) {
                    LOGI("VOICE CANDIDATE REJECT source=%d sf2='%s' bank=%d pc=%d name='%s' family=%s reason=%s score=-1",
                         c.source, c.sf2.c_str(), c.bank, c.program, c.name.c_str(),
                         familyName(d.family), d.reason.c_str());
                }
            }
        }
        LOGI("VOICE POOL source=%u sf2='%s' total=%u accepted=%u rejected=%u rule=hard_role_family_gate",
             static_cast<unsigned>(source), sf2.c_str(), total, accepted, rejected);
        if (best >= 0) {
            const auto& c = candidates[best];
            LOGI("VOICE CANDIDATE ACCEPT source=%d sf2='%s' bank=%d pc=%d name='%s' family=%s reason=%s score=%d bestInSource=1",
                 c.source, c.sf2.c_str(), c.bank, c.program, c.name.c_str(), familyName(bestDecision.family),
                 bestDecision.reason.c_str(), bestDecision.score);
        }
    }
    const int selected = select(request, candidates);
    if (selected < 0) {
        sourceFont = -1;
        LOGI("VOICE FINAL REJECT bank=%d pc=%d name='%s' family=%s reason=no_compatible_candidate action=suppress_new_notes",
             requestedBank, requestedProgram, voiceName.c_str(), familyName(request.family()));
        return false;
    }
    const auto& c = candidates[selected];
    const auto d = evaluate(request, c);
    sourceFont = c.source; sourceBank = c.bank; sourceProgram = c.program; matchedName = c.name;
    LOGI("VOICE FINAL SELECT requested='%s' family=%s source=%d sf2='%s' bank=%d pc=%d name='%s' candidateFamily=%s score=%d reason=%s",
         voiceName.c_str(), familyName(request.family()), sourceFont, c.sf2.c_str(), sourceBank,
         sourceProgram, matchedName.c_str(), familyName(d.family), d.score, d.reason.c_str());
    return true;
}

void BassMidiPlayer::preloadCurrentPreset(int channel) {
    if (!stream_ || channel < 0 || channel >= 16) return;

    const ChannelState& state = channels_[channel];
    const bool useFallbackMelody = !state.drum && state.melodySource > 0 &&
        static_cast<size_t>(state.melodySource) <= secondaryMelodies_.size();
    const SecondaryMelody* secondary = useFallbackMelody ? &secondaryMelodies_[state.melodySource - 1] : nullptr;
    HSOUNDFONT font = state.drum ? drumFont_ : (secondary ? secondary->font : melodyFont_);
    const std::string& path = state.drum
        ? (drumFont_ ? drumPath_ : melodyPath_)
        : (secondary ? secondary->path : melodyPath_);
    const auto& bankMaps = secondary ? secondary->banks : normalizedBanks_;
    if (!state.drum && state.initialized && state.melodySourceProgram < 0) return;
    if (!font) return;

    // Resolve drum program against the actual SF2 drum banks before loading.
    // This mirrors Voyager's default-drumkit fallback instead of attempting
    // to preload a drum program that is not present.
    // Use the actual SF2 source bank when it is available. Yamaha/XG fonts
    // may store 8:1 as packed SF2 bank 1025, while other fonts store bank 8
    // and let MIDI_EVENT_BANK_LSB select the variation through FONTEX2.
    int sourceBank = state.drum ? 128 : state.melodySourceBank;
    int sourceProgram = state.drum ? state.program : state.melodySourceProgram;

    if (!state.drum) {
        if (sourceBank < 0) sourceBank = state.bankMsb;
        if (sourceProgram < 0) sourceProgram = state.program;

        const int rawSourceBank = sourceBank;
        for (const auto& m : bankMaps) {
            if (m.rawBank == rawSourceBank) {
                sourceBank = m.virtualBank;
                break;
            }
        }
    }
    // Preload asynchronously. BASSMIDI normally loads samples on demand,
    // which can cause a CPU spike exactly when a new voice is first heard.
    // The synchronous BASS_MIDI_FontLoad() path is unsafe for this arranger:
    // setChannelPreset() is called while the Oboe callback is rendering, and
    // the old synchronous preload could hold mutex_ long enough to starve the
    // realtime callback and produce a chopped/missing fill or section.
    //
    // NOWAIT lets BASSMIDI prepare the preset in its own loading path while
    // playback continues. The first notes can still render from samples that
    // are already ready, while any remainder is loaded as needed.
    if (state.drum) {
        if (!findDrumPreset(path, state.program, sourceBank, sourceProgram)) {
            LOGI("BASSMIDI no drum preset found ch=%d requested=%d",
                 channel, state.program);
            return;
        }
        if (!BASS_MIDI_FontLoadEx(
                font, sourceProgram, sourceBank, 0,
                BASS_MIDI_FONTLOAD_NOWAIT)) {
            LOGI("BASSMIDI async drum preload skipped ch=%d bank=%d prog=%d err=%d",
                 channel, sourceBank, sourceProgram, BASS_ErrorGetCode());
            return;
        }
    } else {
        if (!BASS_MIDI_FontLoadEx(
                font, sourceProgram, sourceBank, 0,
                BASS_MIDI_FONTLOAD_NOWAIT)) {
            LOGI("BASSMIDI async preload skipped ch=%d bank=%d prog=%d err=%d",
                 channel, sourceBank, sourceProgram, BASS_ErrorGetCode());
            return;
        }
    }

    LOGI("BASSMIDI preload ch=%d role=%s sf2bank=%d rawYamahaBank=%d midi=%d:%d srcProg=%d srcName='%s' dstProg=%d",
         channel, state.drum ? "DRUM" : (useFallbackMelody ? "FALLBACK" : "PRIMARY"), sourceBank,
         state.drum ? 128 : (state.bankMsb * 128 + state.bankLsb),
         state.bankMsb, state.bankLsb, sourceProgram, state.melodySourceName.c_str(), state.program);
}

void BassMidiPlayer::noteOn(int channel, int key, float velocity) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    key = std::max(0, std::min(127, key));
    const int vel = std::max(1, std::min(127,
        static_cast<int>(std::lround(velocity * 127.0f))));

    // A failed family gate must not fall through to BASSMIDI's generic font
    // mappings/default Piano. Dedicated Rhythm channels keep their old path.
    if (channel != 8 && channel != 9 && channels_[channel].initialized &&
        channels_[channel].melodySourceProgram < 0) return;

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

void BassMidiPlayer::setChannelPreset(int channel, int bank, int program, const std::string& voiceName) {
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
    // Bank 128+ is NOT sufficient to classify a Yamaha melodic variation
    // bank as percussion. Yamaha packed variation banks such as 1025 (8:1),
    // 1026 (8:2), and 1029 (8:5) are normal melodic banks. Percussion is
    // determined by the actual Rhythm channels (9/10 in MIDI numbering,
    // zero-based 8/9), not by the packed 14-bit bank value.
    const bool wantDrum = (channel == 8 || channel == 9);
    if (wantDrum) {
        bank = 128;
    } else {
        bank = std::max(0, std::min(16383, bank));
    }

    ChannelState& state = channels_[channel];
    // Yamaha packs Bank MSB/LSB into one 14-bit integer:
    //   bank = MSB * 128 + LSB.
    // Only Rhythm channels are percussion. A melodic variation such as
    // 1025 (8:1), 1026 (8:2), or 1029 (8:5) must remain a melodic bank.
    // A repeated identity-only call (e.g. dynamic setup) may keep its known
    // name; a changed identity must decode independently, never inherit a family.
    if (!voiceName.empty() || state.bankMsb != bank / 128 ||
        state.bankLsb != bank % 128 || state.program != program) state.requestedVoiceName = voiceName;
    state.bankMsb = wantDrum ? 128 : bank / 128;
    state.bankLsb = wantDrum ? 0 : bank % 128;
    state.program = program; // requested destination program
    state.drum = wantDrum;
    state.melodySource = 0;
    state.melodySourceBank = -1;
    state.melodySourceProgram = -1;
    state.melodySourceName.clear();
    state.initialized = true;

    if (state.drum) {
        int sourceBank = 128;
        int sourceProgram = program;
        const std::string& path = drumFont_ ? drumPath_ : melodyPath_;
        const bool resolved = findDrumPreset(path, program, sourceBank, sourceProgram);
        LOGI("DRUM PRESET RESOLVE ch=%d requested=%d -> bank=%d prog=%d found=%d",
             channel, program, sourceBank, sourceProgram, resolved ? 1 : 0);
        if (resolved) {
            state.bankMsb = 128; state.bankLsb = 0; state.program = sourceProgram;
        }
    } else {
        const int requestedProgram = program;
        const int requestedBank14 = bank;
        const int requestedSourceBank =
            (requestedBank14 >= 128) ? (requestedBank14 / 128) : requestedBank14;
        int sourceBank = state.bankMsb;
        int sourceProgram = requestedProgram;
        std::string matchedName;
        int sourceFont = 0;
        const bool resolved = findMelodicPreset(requestedBank14, requestedProgram, state.requestedVoiceName,
                                               sourceBank, sourceProgram, matchedName, sourceFont);
        state.melodySource = sourceFont;
        if (resolved) {
            state.melodySourceBank = sourceBank;
            state.melodySourceProgram = sourceProgram;
            state.melodySourceName = matchedName;
            if (sourceBank != requestedSourceBank || sourceProgram != requestedProgram) {
                LOGI("VOICE RESOLVE ch=%d requested bank=%d prog=%d name='%s' -> sourceRole=%d bank=%d prog=%d '%s'",
                     channel, requestedBank14, requestedProgram, voiceName.c_str(),
                     sourceFont, sourceBank, sourceProgram, matchedName.c_str());
            } else {
                LOGI("VOICE RESOLVE ch=%d exact bank=%d prog=%d '%s' sourceRole=%d",
                     channel, requestedBank14, requestedProgram, matchedName.c_str(), sourceFont);
            }
        } else {
            LOGI("VOICE RESOLVE ch=%d rejected bank=%d prog=%d name='%s'; no generic/Piano fallback",
                 channel, requestedBank14, requestedProgram, voiceName.c_str());
        }
    }

    LOGI("SET PRESET ch=%d requestedBank=%d effectiveBank=%d lsb=%d prog=%d drum=%d voice='%s' sourceRole=%d sourceBank=%d sourceProg=%d sourceName='%s'",
         channel, requestedBank, state.bankMsb, state.bankLsb, state.program,
         state.drum ? 1 : 0, voiceName.c_str(), state.melodySource,
         state.melodySourceBank, state.melodySourceProgram, state.melodySourceName.c_str());

    if (!state.drum && !applyFonts()) {
        invalidateMelodicChannels();
        LOGE("VOICE MAP apply failed ch=%d; suppressing melodic notes until compatible mappings restored", channel);
    }

    if (state.drum) send(channel, MIDI_EVENT_DRUMS, 1);
    else send(channel, MIDI_EVENT_DRUMS, 0);
    send(channel, MIDI_EVENT_BANK, static_cast<DWORD>(state.bankMsb));
    send(channel, MIDI_EVENT_BANK_LSB, static_cast<DWORD>(state.bankLsb));
    send(channel, MIDI_EVENT_PROGRAM, static_cast<DWORD>(state.program));

    LOGI("SET PRESET APPLIED ch=%d bank=%d lsb=%d prog=%d drum=%d",
         channel, state.bankMsb, state.bankLsb, state.program,
         state.drum ? 1 : 0);

    // Apply the proven neighbor fix: preload BOTH melodic and drum presets.
    // The drum path used to be skipped here, leaving a newly selected kit
    // dependent on lazy first-note loading. That can make style transitions
    // (especially Fill -> Main / section changes) sound empty even though the
    // MIDI NOTE events are already correct. preloadCurrentPreset() uses the
    // non-blocking BASS_MIDI_FONTLOAD_NOWAIT path, so enabling it for drums
    // does not reintroduce the old synchronous render-thread stall.
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

void BassMidiPlayer::setKeyboardSustain(bool enabled) {
    // Keep the existing panel-SUSTAIN ledger behavior in ArrangerBrain.
    // The Yamaha-style release parameter is controlled independently below.
    LOGI("BASSMIDI panel sustain=%s; release time remains independently controlled",
         enabled ? "ON" : "OFF");
}

void BassMidiPlayer::setKeyboardReleaseTime(int releaseTime) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!stream_) return;

    const int value = std::max(0, std::min(127, releaseTime));
    // Yamaha E-series Release Time is a continuous 0..127 parameter with
    // 64 as the normal/default center. CC72 is explicitly Release Time,
    // not the CC64 sustain pedal. Apply it only to RIGHT 1/2/3.
    for (int channel = 0; channel <= 2; ++channel) {
        BASS_MIDI_StreamEvent(
            stream_, static_cast<DWORD>(channel), MIDI_EVENT_RELEASE,
            static_cast<DWORD>(value));
    }

    LOGI("BASSMIDI RELEASE TIME=%d channels=0..2; LEFT/ACMP untouched", value);
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
    for (size_t i = 0; i < secondaryMelodies_.size(); ++i) {
        const std::string role = "MELODY_SECONDARY_" + std::to_string(i + 1);
        scanSf2(secondaryMelodies_[i].path, role.c_str());
    }
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

#include "bassmidi_player.h"
#include "keyboard_release_probe.h"
#include "release_envelope_evidence.h"
#include "voice_resolver.h"
#include "latency_probe.h"
#include "sf2_fingerprint.h"
#include "onset_probe.h"
#ifndef YAMAHA_COMPATIBLE_PERCUSSION
#define YAMAHA_COMPATIBLE_PERCUSSION 0
#endif
#ifndef YAMAHA_ROLE_PCM_METERS
#define YAMAHA_ROLE_PCM_METERS 0
#endif
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
#ifndef BASS_ATTRIB_MIDI_VOICES_ACTIVE
#define BASS_ATTRIB_MIDI_VOICES_ACTIVE 0x12004 /* host mock headers only; the real header defines it */
#endif

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

        applyKeyboardRelease(); // Restore the retained UI value on every new decoder.

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
    // Exact keyboard mappings have one-channel scope. No style destination,
    // family resolver or generic source priority is changed by this browser.
    for (int ch = 0; ch < 4; ++ch) {
        const auto* preset = keyboardPreset(keyboardPresets_[ch]);
        if (!preset) continue;
        BASS_MIDI_FONTEX2 map{};
        map.font = preset->font;
        map.spreset = preset->program;
        map.sbank = preset->nativeBank;
        map.dpreset = 0;
        map.dbank = (preset->bank == 127 || preset->bank == 128) ? 128 : 0;
        map.dbanklsb = 0;
        map.minchan = ch;
        map.numchan = 1;
        cfg.push_back(map);
    }

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
            if ((ch < 4 && keyboardPresets_[ch].fontId) || !state.initialized || state.drum ||
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

    // R23 exact drum bindings precede generic mappings, scoped to one channel.
    if(drumABResolved_) for(int ch=8;ch<=9;++ch) {
        const auto& b=drumABBindings_[ch-8];
        if(!b.font)continue;
        BASS_MIDI_FONTEX2 map{};
        map.font=b.font;map.spreset=b.pc;map.sbank=b.nativeBank;
        map.dpreset=b.pc;map.dbank=128;map.dbanklsb=0;map.minchan=ch;map.numchan=1;
        cfg.push_back(map);
    }
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
            if (m.midiMsb > 127) continue; // Exact browser mapping still exposes this raw SF2 bank.
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
        // Manual-only fonts are reached only through channel-specific mappings (keyboard preset, pin, drum bind),
        // never through the generic all-preset mapping the automatic path falls back to.
        if (secondary.manualOnly) continue;
        for (const auto& m : secondary.banks) {
            if (m.midiMsb > 127) continue;
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
    publishedFontMaps_ = cfg;
    ++fontMappingGeneration_;
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

// Error-path verification only. A failed install may preserve the old table;
// do not assume that. Inspect the complete native table before retaining routes.
bool BassMidiPlayer::publishedFontTableIntact() const {
    if (!stream_ || publishedFontMaps_.empty()) return false;
    const DWORD count = BASS_MIDI_StreamGetFonts(stream_, nullptr, 0);
    if (count != publishedFontMaps_.size()) return false;
    std::vector<BASS_MIDI_FONTEX2> live(count);
    if (BASS_MIDI_StreamGetFonts(stream_, live.data(), count | BASS_MIDI_FONT_EX2) != count) return false;
    for (size_t i=0;i<live.size();++i) {
        const auto& a=live[i]; const auto& b=publishedFontMaps_[i];
        if (a.font!=b.font || a.spreset!=b.spreset || a.sbank!=b.sbank ||
            a.dpreset!=b.dpreset || a.dbank!=b.dbank || a.dbanklsb!=b.dbanklsb ||
            a.minchan!=b.minchan || a.numchan!=b.numchan) return false;
    }
    return true;
}

bool BassMidiPlayer::loadRole(const std::string& path, bool drum) {
    std::lock_guard<std::mutex> lock(mutex_);
    latency_probe::Hold latencyHold_(latency_probe::HoldFonts);
    if (!ensureEngine()) return false;

    closeRolePcmWindows(); // measurement boundary only; no MIDI/resource routing writes
    retirePercussionStreams(); // auxiliary streams only; never clears the production stream
    HSOUNDFONT& target = drum ? drumFont_ : melodyFont_;
    if (drum) {
        drumPath_.clear();
        drumDrumPresetCache_.clear();
    } else {
        melodyPath_.clear();
        melodyDrumPresetCache_.clear();
        melodyPresetCache_.clear();
        melodicZoneInventories_.clear(); noteZoneRows_.clear(); noteZoneLimit_=false;
        // Replacing the primary melody font invalidates any previous
        // secondary layer; the caller can attach a new controlled fallback.
        for (const auto& secondary : secondaryMelodies_) BASS_MIDI_FontFree(secondary.font);
        secondaryMelodies_.clear();
        invalidateMelodicChannels();
        channelPins_ = {}; // font ids are about to change; callers re-pin after load
    }
    if (target) {
        BASS_MIDI_FontFree(target);
        target = 0;
    }

    refreshBrowserPresets();
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
    (drum ? drumFontId_ : melodyFontId_) = target ? nextFontId_++ : 0;
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

    refreshBrowserPresets();
    if (!applyFonts()) {
        invalidateMelodicChannels();
        BASS_MIDI_FontFree(target);
        target = 0;
        refreshBrowserPresets();
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

    restoreKeyboardPresets();
    applyKeyboardRelease();
    rebuildPercussionCatalog();
    for(int ch=10;ch<16;++ch) updateRolePcmMeter(ch);
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

bool BassMidiPlayer::loadMelodyFallback(const std::string& path, bool manualOnly) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ensureEngine() || path.empty() || !melodyFont_) return false;
    for (const auto& secondary : secondaryMelodies_) if (secondary.path == path) return true;
    // Bounded pool: primary Yamaha + Colombo + optional Tyros. Optional
    // allocation failure cannot discard the established melody/drum pair.
    // Bounded pool: Tyros fallback plus up to three manual-only SoundFonts (SX Prime Melody/Drum, custom). The Kotlin
    // ManagedSf2Policy decides which files are attached and enforces the byte budget.
    if (secondaryMelodies_.size() >= 4) return false;
    closeRolePcmWindows(); // prevent attribution across an SF2 resource change
    SecondaryMelody secondary;
    secondary.path = path;
    secondary.manualOnly = manualOnly;
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
    secondary.fontId = nextFontId_++;
    const auto previousChannels = channels_;
    secondaryMelodies_.push_back(std::move(secondary));
    refreshBrowserPresets();
    refreshMelodicChannels();
    if (!applyFonts()) {
        BASS_MIDI_FontFree(secondaryMelodies_.back().font);
        secondaryMelodies_.pop_back();
        refreshBrowserPresets();
        channels_ = previousChannels;
        if (!applyFonts()) invalidateMelodicChannels(); // Fail closed if restoring mappings fails.
        else { restoreKeyboardPresets(); applyKeyboardRelease(); }
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
    restoreKeyboardPresets();
    applyKeyboardRelease();
    retirePercussionStreams();
    rebuildPercussionCatalog();
    for(int ch=10;ch<16;++ch) updateRolePcmMeter(ch);
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
    drumABBindings_={};++drumABGeneration_;
    clearRolePcmMeters(); // remove read-only child DSPs before freeing their parent
// MEASURED_BALANCE_BEGIN
    clearMeasuredBalance();
// MEASURED_BALANCE_END

    retirePercussionStreams();
    percussionCandidates_.clear();percussionAllCandidates_.clear();
    percussionLanes_ = {};

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

    browserPresets_.clear();
    for (auto& selection : keyboardPresets_) selection.verified = false;
    publishedFontMaps_.clear();
    for (auto& ch : channels_) ch = ChannelState{};
    drumSelections_={}; ++drumSelectionGeneration_; // R22 observation invalidation only
    channelPins_ = {};
    audioDiagnostics_ = {};
    drumZoneInventories_.clear();
    melodicZoneInventories_.clear(); noteZoneRows_.clear(); noteZoneLimit_=false;
    drumZoneSignatures_ = {};
    melodyPath_.clear();
    melodyBassPath_.clear();
    drumPath_.clear();
    melodyPresetCache_.clear();
    melodyDrumPresetCache_.clear();
    normalizedBanks_.clear();
    drumDrumPresetCache_.clear();
}

bool BassMidiPlayer::send(int channel, DWORD event, DWORD param) {
    if (!stream_) return false;
    channel = std::max(0, std::min(15, channel));

    ++chordMidiOrder_; // Observation order under the existing synth mutex.
    const bool sent = BASS_MIDI_StreamEvent(stream_, static_cast<DWORD>(channel), event, param);
    const int eventError = sent ? 0 : BASS_ErrorGetCode();
    if (event == MIDI_EVENT_BANK || event == MIDI_EVENT_BANK_LSB || event == MIDI_EVENT_PROGRAM ||
        event == MIDI_EVENT_DRUMS || event == MIDI_EVENT_NOTESOFF)
        captureChordState("CONTROL_POST",channel,-1,0,sent ? 1 : 0,eventError,{},event,param);
    if (!sent) {
        LOGE("MIDI event failed ch=%d event=%u param=%u error=%d",
             channel, static_cast<unsigned>(event),
             static_cast<unsigned>(param), eventError);
    }
    if (sent) mirrorPercussionController(channel,event,param);
    if (sent) observeRolePcmController(channel,event,param); // counters only; controller event unchanged
    if (sent && channel >= 4) {
        auto& d = audioDiagnostics_[channel];
        int* tracked = event == MIDI_EVENT_VOLUME ? &d.cc7 : event == MIDI_EVENT_EXPRESSION ? &d.cc11 : nullptr;
        if (tracked && *tracked != static_cast<int>(param)) {
            *tracked = static_cast<int>(param);
            LOGI("AUDIO CC ch=%d cc=%d value=%u sent=1", channel,
                 event == MIDI_EVENT_VOLUME ? 7 : 11, static_cast<unsigned>(param));
        }
    }
    return sent;
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
    drumZoneInventories_.erase(path);
    drumZoneSignatures_ = {};
    if (path.empty()) return;

    std::ifstream file(path, std::ios::binary);
    if (!file) {
        LOGI("SF2 drum preset cache: cannot open %s", path.c_str());
        return;
    }

    std::vector<unsigned char> data(
        (std::istreambuf_iterator<char>(file)),
        std::istreambuf_iterator<char>());

    // Reuse the bytes already read during font loading. No file I/O or SF2
    // parsing in noteOn/render; immutable metadata is also used to precompile
    // the independent, conservative percussion catalog at paused font load.
    drumZoneInventories_[path] = sf2_zones::parse(data);
    if(YAMAHA_COMPATIBLE_PERCUSSION || YAMAHA_ROLE_PCM_METERS)percussionFingerprints_[path]=sf2_fingerprint::sha256(data);
    // U1 observation only: fingerprint original import bytes, never the normalized font.
    pdtaFingerprints_[path]=sf2_pdta::fingerprint(data);
    LOGI("DRUM ZONE CACHE valid=%d reason=%s presets=%u path=%s",
         drumZoneInventories_[path].valid ? 1 : 0, drumZoneInventories_[path].reason.c_str(),
         static_cast<unsigned>(drumZoneInventories_[path].presets.size()), path.c_str());

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
    melodicZoneInventories_.erase(path);
    noteZoneRows_.clear(); noteZoneLimit_=false;
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

    melodicZoneInventories_[path] = std::make_shared<const sf2_zones::Inventory>(sf2_zones::parse(data,true));
    if(YAMAHA_COMPATIBLE_PERCUSSION || YAMAHA_ROLE_PCM_METERS)percussionFingerprints_[path]=sf2_fingerprint::sha256(data);
    // U1 observation only: fingerprint original import bytes, never the normalized font.
    pdtaFingerprints_[path]=sf2_pdta::fingerprint(data);
    noteZoneRows_.clear(); noteZoneLimit_=false;
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
        state.resolverReason="font_binding_invalidated";
    }
}

void BassMidiPlayer::refreshMelodicChannels() {
    for (size_t ch = 0; ch < channels_.size(); ++ch) {
        auto& state = channels_[ch];
        if (!state.initialized || state.drum || channelPins_[ch].active) continue;
        state.melodySourceBank = -1;
        state.melodySourceProgram = -1;
        state.melodySourceName.clear();
        findMelodicPreset(state.bankMsb * 128 + state.bankLsb, state.program,
                          state.requestedVoiceName, state.melodySourceBank,
                          state.melodySourceProgram, state.melodySourceName, state.melodySource, &state.resolverReason);
    }
}

bool BassMidiPlayer::findMelodicPreset(
    int requestedBank, int requestedProgram, const std::string& voiceName,
    int& sourceBank, int& sourceProgram, std::string& matchedName, int& sourceFont, std::string* diagnosticReason) const {
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
        // Manual-only fonts are attached for explicit selection (Voice Selector, Style Voice Editor pins);
        // the automatic resolver never considers them, so automatic results are unchanged.
        if (secondary.manualOnly) continue;
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
    const int selected = sourceTiered_ ? selectTiered(request, candidates) : select(request, candidates);
    if (selected < 0) {
        if(diagnosticReason)*diagnosticReason="no_compatible_candidate";
        sourceFont = -1;
        LOGI("VOICE FINAL REJECT bank=%d pc=%d name='%s' family=%s reason=no_compatible_candidate action=suppress_new_notes",
             requestedBank, requestedProgram, voiceName.c_str(), familyName(request.family()));
        return false;
    }
    const auto& c = candidates[selected];
    const auto d = evaluate(request, c);
    if(diagnosticReason)*diagnosticReason=d.reason;
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

void BassMidiPlayer::logAudioPath(int channel, int key, int velocity, bool sent, int error,
                                  const AudioPathOrigin& origin, const char* reason, uint64_t now) {
    captureChordState("NOTE_POST",channel,key,velocity,sent ? 1 : 0,error,origin);
    if (channel < 4 || channel > 15) return; // Existing keyboard diagnostics are untouched.
    if (!now) now = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    auto& d = audioDiagnostics_[channel];
    const int cc7 = static_cast<int>(BASS_MIDI_StreamGetEvent(stream_, channel, MIDI_EVENT_VOLUME));
    const int cc11 = static_cast<int>(BASS_MIDI_StreamGetEvent(stream_, channel, MIDI_EVENT_EXPRESSION));
    d.on(key, velocity, sent, cc7, cc11, now, channel == 8 || channel == 9);
    observeRolePcmNote(channel,key,velocity,sent,origin,cc7,cc11);
    if (origin.sourceChannel >= 0 && channel >= 8) {
        auto& presence=partPresence_[channel];
        ++presence.attempts; presence.sent+=sent; presence.zeroController+=(cc7==0 || cc11==0);
        presence.lastKey=key; presence.lastVelocity=velocity;
        if (!sent) {
            if (std::strcmp(reason,"engine_unavailable")==0) ++presence.engineUnavailable;
            else if (std::strcmp(reason,"family_or_mapping_reject")==0) ++presence.familyOrMapRejected;
            else ++presence.sendFailed;
        }
    }
    const auto& state = channels_[channel];
    const bool drum = channel == 8 || channel == 9;
    if (drum) {
        const std::string& path = drumFont_ ? drumPath_ : melodyPath_;
        int bank=128, pc=state.program;
        findDrumPreset(path, state.program, bank, pc);
        const auto inventory=drumZoneInventories_.find(path);
        if(inventory!=drumZoneInventories_.end()) {
            const auto coverage=sf2_zones::match(inventory->second,bank,pc,key,velocity);
            const auto signature=path+":"+std::to_string(bank)+":"+std::to_string(pc)+":"+
                std::to_string(coverage.known)+":"+std::to_string(coverage.zones)+":"+coverage.names;
            auto& previous=drumZoneSignatures_[channel-8][key];
            if(previous!=signature) {
                previous=signature;
                BASS_MIDI_FONT live{};
                const bool liveOk=BASS_MIDI_StreamGetPreset(stream_,channel,&live);
                const bool verified=liveOk && live.font==(drumFont_?drumFont_:melodyFont_) && live.bank==bank && live.preset==pc;
                LOGI("DRUM ZONE ch=%d key=%d vel=%d requestedKitPC=%d bank=%d pc=%d liveVerified=%d metadataKnown=%d matchingZones=%u names='%.230s' NOTE_ON_SENT=%d reason=%s evidence=key_velocity_zone_metadata_not_pcm_or_XG_semantics",
                     channel,key,velocity,d.requestedPc,bank,pc,verified?1:0,coverage.known?1:0,
                     static_cast<unsigned>(coverage.zones),coverage.names.c_str(),sent?1:0,inventory->second.reason.c_str());
            }
        }
    }
    auditNoteZone(channel,key,velocity,sent,origin);
    const bool special = drum && (audio_path::snare(key) || audio_path::snare(origin.sourceNote));
    if (d.sample(now, special) || origin.sampled) {
        HSOUNDFONT expected = drum ? (drumFont_ ? drumFont_ : melodyFont_) : melodyFont_;
        const std::vector<NormalizedBankMap>* banks = &normalizedBanks_;
        std::string sourcePath = drum ? (drumFont_ ? drumPath_ : melodyPath_) : melodyPath_;
        if (!drum && state.melodySource > 0 && static_cast<size_t>(state.melodySource) <= secondaryMelodies_.size()) {
            const auto& secondary = secondaryMelodies_[state.melodySource - 1];
            expected = secondary.font; banks = &secondary.banks; sourcePath = secondary.path;
        }
        int expectedBank = state.melodySourceBank;
        int expectedPc = state.melodySourceProgram;
        if (drum) {
            expectedBank = 128; expectedPc = state.program;
            findDrumPreset(sourcePath, state.program, expectedBank, expectedPc); // Read the existing cache only.
        }
        else for (const auto& b : *banks) if (b.rawBank == expectedBank) { expectedBank = b.virtualBank; break; }
        BASS_MIDI_FONT live{};
        const bool liveOk = BASS_MIDI_StreamGetPreset(stream_, channel, &live);
        const char* liveName = liveOk ? BASS_MIDI_FontGetPreset(live.font, live.preset, live.bank) : nullptr;
        std::string livePath = live.font == melodyFont_ ? melodyPath_ : live.font == drumFont_ ? drumPath_ : "UNAVAILABLE";
        for (const auto& secondary : secondaryMelodies_) if (live.font == secondary.font) livePath = secondary.path;
        const auto liveSf2 = livePath.substr(livePath.find_last_of("/\\") + 1);
        BASS_MIDI_FONTINFO info{};
        const bool infoOk = liveOk && BASS_MIDI_FontGetInfo(live.font, &info);
        const bool mappingMatch = liveOk && expected == live.font && expectedPc == live.preset &&
            expectedBank == live.bank;
        const auto sf2 = sourcePath.substr(sourcePath.find_last_of("/\\") + 1);
        LOGI("AUDIO PATH id=%lld ch=%d role=%s voice='%.48s' sourceSF2='%.70s' selected='%.48s' requestedPC=%d sourceRawBank=%d srcBank=%d srcPC=%d dstBank=%d:%d dstPC=%d note=%d vel=%d CC7=%d CC11=%d NOTE_ON_SENT=%d error=%d reason=%s",
             static_cast<long long>(origin.id), channel, drum ? "DRUM" : voice_resolver::familyName(voice_resolver::namedFamily(state.melodySourceName)),
             state.requestedVoiceName.c_str(), sf2.c_str(), drum ? (liveName ? liveName : "UNAVAILABLE") : state.melodySourceName.c_str(),
             d.requestedPc, drum ? live.bank : state.melodySourceBank, expectedBank, expectedPc, state.bankMsb, state.bankLsb, state.program,
             key, velocity, cc7, cc11, sent ? 1 : 0, error, reason);
        LOGI("AUDIO LIVE id=%lld ch=%d mapGen=%llu expectedFont=%u liveOk=%d liveFont=%u liveBank=%d livePC=%d liveSF2='%.70s' preset='%.64s' mappingMatch=%d src=%d original=%d output=%d styleBank=%d:%d tick=%lld samload=%llu samsize=%llu infoOk=%d evidence=event_and_preset_not_audibility",
             static_cast<long long>(origin.id), channel, static_cast<unsigned long long>(fontMappingGeneration_),
             static_cast<unsigned>(expected), liveOk ? 1 : 0, static_cast<unsigned>(live.font), live.bank, live.preset,
             liveSf2.c_str(), liveName ? liveName : "UNAVAILABLE", mappingMatch ? 1 : 0, origin.sourceChannel, origin.sourceNote,
             key, origin.styleBank < 0 ? -1 : origin.styleBank / 128, origin.styleBank < 0 ? -1 : origin.styleBank % 128,
             static_cast<long long>(origin.tick), static_cast<unsigned long long>(info.samload),
             static_cast<unsigned long long>(info.samsize), infoOk ? 1 : 0);
        if (drum) LOGI("DRUM PATH id=%lld ch=%d kit='%.64s' requestedKitPC=%d kitFallback=%d note=%d expected=%s original=%d remapped=%d liveBank=%d livePC=%d NOTE_ON_SENT=%d expectation=GM_label_only_XG_sample_unverified",
             static_cast<long long>(origin.id), channel, liveName ? liveName : "UNAVAILABLE", d.requestedPc, liveOk && d.requestedPc >= 0 && live.preset != d.requestedPc ? 1 : 0, key,
             audio_path::drumName(key), origin.sourceNote, origin.sourceNote >= 0 && origin.sourceNote != key ? 1 : 0,
             live.bank, live.preset, sent ? 1 : 0);
    }
    if (d.summaryDue(now)) {
        const auto& s = d.stats;
        LOGI("AUDIO SUMMARY ch=%d attempts=%llu sent=%llu rejected=%llu velMin=%d velMean=%.1f velMax=%d CC7=%d CC11=%d zeroCC=%llu controlProxyMean=%.4f snare38=%llu/%llu snare40=%llu/%llu offs=%llu shortOff_lt80ms=%llu heldMean_ms=%.1f orphanOff=%llu pendingSentOns=%u ledgerOverflow=%llu window=about2s proxy=not_audio_energy",
             channel, static_cast<unsigned long long>(s.attempts), static_cast<unsigned long long>(s.sent), static_cast<unsigned long long>(s.rejected),
             s.attempts ? s.velocityMin : -1, s.attempts ? double(s.velocitySum) / s.attempts : 0.0, s.velocityMax, cc7, cc11,
             static_cast<unsigned long long>(s.ccZero), s.attempts ? s.controlLevelSum / s.attempts : 0.0,
             static_cast<unsigned long long>(s.snare38Sent), static_cast<unsigned long long>(s.snare38),
             static_cast<unsigned long long>(s.snare40Sent), static_cast<unsigned long long>(s.snare40),
             static_cast<unsigned long long>(s.offs), static_cast<unsigned long long>(s.shortOffs),
             s.durations ? double(s.durationSum) / s.durations : 0.0, static_cast<unsigned long long>(s.orphanOffs),
             static_cast<unsigned>(d.pending()), static_cast<unsigned long long>(s.ledgerOverflow));
        d.resetSummary();
    }
}

void BassMidiPlayer::noteOn(int channel, int key, float velocity, const AudioPathOrigin& origin) {
    const uint64_t probeEnter = latency_probe::probe.on() ? latency_probe::now() : 0; // LATENCY_PROBE
    f12::SynthLock lock(mutex_,f12::NoteOn);
    latency_probe::Hold latencyHold_(channel >= 0 && channel < 4 ? latency_probe::HoldNoteKey : latency_probe::HoldNoteStyle);
    const uint64_t probeLocked = probeEnter ? latency_probe::now() : 0; // LATENCY_PROBE
    ++pcmSessionNotes_.attempts[std::clamp(channel,0,15)];
    if (!ensureEngine()) {
        logAudioPath(std::clamp(channel, 0, 15), std::clamp(key, 0, 127),
                     std::clamp(static_cast<int>(std::lround(velocity * 127.0f)), 1, 127),
                     false, BASS_ErrorGetCode(), origin, "engine_unavailable", 0);
        return;
    }

    channel = std::max(0, std::min(15, channel));
    key = std::max(0, std::min(127, key));
    const int vel = std::max(1, std::min(127,
        static_cast<int>(std::lround(velocity * 127.0f))));

    if(origin.sourceChannel>=0 && origin.styleBank>=0 && channel>=8) {
        auto& measured=styleVelocityEvidence_[channel];++measured.count;measured.sum+=vel;measured.squares+=vel*vel;
        measured.minimum=std::min(measured.minimum,vel);measured.maximum=std::max(measured.maximum,vel);
    }

    captureChordState("NOTE_PRE",channel,key,vel,-1,0,origin);

    if (channel < 4 && keyboardPresets_[channel].fontId &&
        !keyboardPresets_[channel].verified) return; // Removed/failed exact selection stays silent.

    // A failed family gate must not fall through to BASSMIDI's generic font
    // mappings/default Piano. Dedicated Rhythm channels keep their old path.
    if (!(channel < 4 && keyboardPresets_[channel].fontId) &&
        channel != 8 && channel != 9 && channels_[channel].initialized &&
        channels_[channel].melodySourceProgram < 0) {
        logAudioPath(channel, key, vel, false, 0, origin, "family_or_mapping_reject", 0);
        return;
    }

    // No metadata evaluation, scanning or mapping work in the note/render path.
    if(drumABResolved_ && (channel==8 || channel==9)) {
        auto& b=drumABBindings_[channel-8];if(!b.verified)return;BASS_MIDI_FONT live{};
        b.verified=b.font && BASS_MIDI_StreamGetPreset(stream_,channel,&live) &&
            live.font==b.font && live.bank==b.nativeBank && live.preset==b.pc;
        if(!b.verified)return; // never fall through to Prime or a stale map
    }
    bool compatibleSent=false;
    if (!drumABResolved_ && percussionOn(channel,key,vel,origin,compatibleSent)) {
        pcmSessionNotes_.sent[channel]+=compatibleSent;
        return; // exactly one NOTE_ON path; no production-channel program/font mutation
    }

    // Never overwrite the channel's program/bank here. MIDI Voyager keeps
    // instrument state separate from note events; doing a forced Program 0
    // on every note was one of the diagnostic build's major correctness bugs.
    const bool sent = send(channel, MIDI_EVENT_NOTE,
                           static_cast<DWORD>(key | (vel << 8)));
    if (probeEnter && sent) latency_probe::probe.noteQueued(channel < 4, probeEnter, probeLocked, latency_probe::now()); // LATENCY_PROBE
    pcmSessionNotes_.sent[channel]+=sent;
    if (channel < 4) {
        auto& evidence=keyboardEvidence_[channel];
        ++evidence.ons;evidence.sent+=sent;evidence.key=key;evidence.velocity=vel;
        if (channel<3 && sent) rightForwardedHist_[channel].add(vel); // histogram of velocity actually sent to BASSMIDI
        if (channel<3 && sent && liveCapture_.load(std::memory_order_relaxed)==1) beginLiveCapture(channel,key,vel);
        if (sent && evidence.needsReadback) {
            evidence.notePresetVerified=BASS_MIDI_StreamGetPreset(stream_,channel,&evidence.actual);
            evidence.release=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_RELEASE);
            evidence.needsReadback=false;
        }
    }
    const int error = sent ? 0 : BASS_ErrorGetCode();
    logAudioPath(channel, key, vel, sent, error, origin, sent ? "bass_event_accepted" : "bass_event_failed", 0);
}

void BassMidiPlayer::noteOff(int channel, int key, const AudioPathOrigin& origin) {
    f12::SynthLock lock(mutex_,f12::NoteOff);
    latency_probe::Hold latencyHold_(latency_probe::HoldNoteOff);
    if (!stream_) return;

    channel = std::max(0, std::min(15, channel));
    key = std::max(0, std::min(127, key));
    if (percussionOff(channel,key,origin)) return;
    captureChordState("OFF_PRE",channel,key,0,-1,0,origin);
    const bool sent = send(channel, MIDI_EVENT_NOTE, static_cast<DWORD>(key));
    if (channel < 4 && sent) ++keyboardEvidence_[channel].offs;
    const int offError = sent ? 0 : BASS_ErrorGetCode();
    captureChordState("OFF_POST",channel,key,0,sent ? 1 : 0,offError,origin);
    if (channel >= 4) {
        const auto now = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());
        auto& d = audioDiagnostics_[channel];
        const auto held = d.off(key, sent, now);
        if (d.sample(now, (channel == 8 || channel == 9) && audio_path::snare(key)))
            LOGI("AUDIO OFF ch=%d note=%d NOTE_OFF_SENT=%d held_ms=%lld pendingSentOns=%u interpretation=diagnostic_ledger_not_synth_voices",
                 channel, key, sent ? 1 : 0, static_cast<long long>(held), static_cast<unsigned>(d.pending()));
    }
}

bool BassMidiPlayer::stopKeyboardPart(int channel) {
    if (channel < 0 || channel > 2) return false;
    f12::SynthLock lock(mutex_, f12::NoteOff);
    latency_probe::Hold latencyHold_(latency_probe::HoldNoteOff);
    if (!stream_) return true;
    // Channel-scoped stop covers held notes and release tails without resetting controllers.
    return send(channel, MIDI_EVENT_SOUNDOFF, 0);
}

void BassMidiPlayer::allNotesOff() {
    f12::SynthLock lock(mutex_,f12::Other);
    if (!stream_) return;
    for(auto& lane:percussionLanes_) {
        if(lane.stream) for(int key=0;key<128;++key) BASS_MIDI_StreamEvent(lane.stream,key,MIDI_EVENT_SOUNDOFF,0);
        lane.owners={};
    }
    for (int ch = 0; ch < 16; ++ch) {
        const bool sent = send(ch, MIDI_EVENT_NOTESOFF, 0);
        if (ch >= 4 && sent) audioDiagnostics_[ch].allOff();
    }
}

void BassMidiPlayer::setChannelPreset(int channel, int bank, int program, const std::string& voiceName) {
    f12::SynthLock lock(mutex_,f12::Preset);
    latency_probe::Hold latencyHold_(latency_probe::HoldPreset);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    if(drumABResolved_ && (channel==8 || channel==9)) return;
    if (channelPins_[channel].active) {
        LOGI("SET PRESET ignored ch=%d bank=%d prog=%d reason=manual_override_pinned", channel, bank, program);
        return;
    }
    if (channel < 4) keyboardPresets_[channel] = {};
    const int requestedBank = bank;
    program = std::max(0, std::min(65535, program));
    if (channel >= 4) {
        audioDiagnostics_[channel].requestedBank = requestedBank;
        audioDiagnostics_[channel].requestedPc = program;
    }

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
    state.resolverReason.clear();
    state.initialized = true;

    if (state.drum) {
        int sourceBank = 128;
        int sourceProgram = program;
        const std::string& path = drumFont_ ? drumPath_ : melodyPath_;
        const bool resolved = findDrumPreset(path, program, sourceBank, sourceProgram);
        state.resolverReason=!resolved?"no_cached_drum_preset":sourceProgram==program?"legacy_same_pc_first_matching_cache_entry":"legacy_first_cached_kit_fallback";
        // R22_SELECTION_OBSERVER_BEGIN
        auto& observation=drumSelections_[channel];
        if(observation.bank!=sourceBank || observation.pc!=sourceProgram || observation.path!=path || observation.reason!=state.resolverReason)
            observation.generation=++drumSelectionGeneration_;
        observation.bank=resolved?sourceBank:-1;observation.pc=resolved?sourceProgram:-1;
        observation.path=path;observation.reason=state.resolverReason;observation.name.clear();observation.pdta.clear();
        const auto fingerprint=pdtaFingerprints_.find(path);
        if(fingerprint!=pdtaFingerprints_.end() && fingerprint->second.valid)observation.pdta=fingerprint->second.sha256;
        const auto inventory=drumZoneInventories_.find(path);
        if(inventory!=drumZoneInventories_.end()) {
            const auto name=inventory->second.presetNames.find({sourceBank,sourceProgram});
            if(name!=inventory->second.presetNames.end())observation.name=name->second;
        }
        // R22_SELECTION_OBSERVER_END
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
                                               sourceBank, sourceProgram, matchedName, sourceFont, &state.resolverReason);
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
        // Only this channel's identity changed. The previous FONTEX2 table
        // still serves the other channels and no resource was freed here.
        // Font replacement failures retain the separate global invalidation.
        ++partPresence_[channel].presetMapFailures;
        if (publishedFontTableIntact()) {
            state.melodySource = -1;
            state.melodySourceBank = -1;
            state.melodySourceProgram = -1;
            state.melodySourceName.clear();
            LOGE("VOICE MAP apply failed ch=%d; native table unchanged, suppressing only this channel", channel);
        } else {
            invalidateMelodicChannels();
            LOGE("VOICE MAP apply failed ch=%d; native table unverified, retaining global fail-closed behavior", channel);
        }
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
    if(wantDrum) preparePercussionLane(channel, audioDiagnostics_[channel].requestedPc);
    if (channel < 3) applyKeyboardRelease(channel);
    updateRolePcmMeter(channel); // read-only PCM observation; MIDI selection unchanged
}

void BassMidiPlayer::setChannelMixer(int channel, int volume, int pan,
                                     int expression, int reverbSend,
                                     int chorusSend) {
    f12::SynthLock lock(mutex_,f12::Mixer);
    latency_probe::Hold latencyHold_(latency_probe::HoldMixer);
    if (!ensureEngine()) return;

    channel = std::max(0, std::min(15, channel));
    send(channel, MIDI_EVENT_VOLUME, std::clamp(volume, 0, 127));
    send(channel, MIDI_EVENT_PAN, std::clamp(pan, 0, 128));
    send(channel, MIDI_EVENT_EXPRESSION, std::clamp(expression, 0, 127));
    send(channel, MIDI_EVENT_REVERB, std::clamp(reverbSend, 0, 127));
    send(channel, MIDI_EVENT_CHORUS, std::clamp(chorusSend, 0, 127));
}

void BassMidiPlayer::setChannelExpression(int channel, int expression) {
    f12::SynthLock lock(mutex_,f12::Other);
    latency_probe::Hold latencyHold_(latency_probe::HoldMixer);
    if (!ensureEngine()) return;
    send(channel, MIDI_EVENT_EXPRESSION, std::clamp(expression, 0, 127));
}

void BassMidiPlayer::setKeyboardSustain(bool enabled) {
    // Keep the existing panel-SUSTAIN ledger behavior in ArrangerBrain.
    // The Yamaha-style release parameter is controlled independently below.
    LOGI("BASSMIDI panel sustain=%s; release time remains independently controlled",
         enabled ? "ON" : "OFF");
}

bool BassMidiPlayer::applyKeyboardRelease(int onlyChannel) {
    if (!stream_) return false;
    bool ok = true;
    for (int channel = 0; channel < 3; ++channel) {
        if (onlyChannel >= 0 && channel != onlyChannel) continue;
        keyboardEvidence_[channel].needsReadback=true;
        const bool sent = BASS_MIDI_StreamEvent(stream_, channel, MIDI_EVENT_RELEASE, keyboardReleaseTime_);
        const auto actual = BASS_MIDI_StreamGetEvent(stream_, channel, MIDI_EVENT_RELEASE);
        ok = sent && actual == static_cast<DWORD>(keyboardReleaseTime_) && ok;
    }
    return ok;
}

bool BassMidiPlayer::setKeyboardReleaseTime(int releaseTime) {
    f12::SynthLock lock(mutex_,f12::Other);
    keyboardReleaseTime_ = std::clamp(releaseTime, 0, 127);
    // BASSMIDI CC72/NRPN 166h: 0=-64, 64=normal, 127=+63.
    // Retain the setting even while unloaded; ensureEngine restores it.
    const bool ok = !stream_ || applyKeyboardRelease();
    LOGI("BASSMIDI RELEASE value=%d acceptedAndReadback=%d deferred=%d channels=0..2",
         keyboardReleaseTime_, ok ? 1 : 0, stream_ ? 0 : 1);
    return ok;
}


void BassMidiPlayer::setMasterGain(float gain) {
    f12::SynthLock lock(mutex_,f12::Other);
    if (!stream_) return;
    BASS_ChannelSetAttribute(stream_, BASS_ATTRIB_MIDI_VOL,
                             std::max(0.0f, std::min(1.0f, gain)));
    for(const auto& lane:percussionLanes_) if(lane.stream)
        BASS_ChannelSetAttribute(lane.stream,BASS_ATTRIB_MIDI_VOL,std::clamp(gain,0.0f,1.0f));
}

void BassMidiPlayer::auditNoteZone(int channel,int key,int velocity,bool sent,const AudioPathOrigin& origin) {
    if(channel!=10 && channel!=13 && channel!=14) return;
    BASS_MIDI_FONT live{};
    const bool liveOk=stream_ && BASS_MIDI_StreamGetPreset(stream_,channel,&live);
    std::string path; int rawBank=live.bank;
    const std::vector<NormalizedBankMap>* banks=nullptr;
    if(liveOk && live.font==melodyFont_) { path=melodyPath_; banks=&normalizedBanks_; }
    for(const auto& secondary:secondaryMelodies_) if(liveOk && live.font==secondary.font) {
        path=secondary.path; banks=&secondary.banks;
    }
    bool bankKnown=false;
    if(banks) for(const auto& bank:*banks) if(bank.virtualBank==live.bank) { rawBank=bank.rawBank; bankKnown=true; break; }
    const int cc7=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_VOLUME);
    const int cc11=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_EXPRESSION);
    const auto signature=std::to_string(channel)+":"+path+":"+std::to_string(rawBank)+":"+std::to_string(live.preset)+":"+
        std::to_string(key)+":"+std::to_string(velocity)+":"+std::to_string(cc7)+":"+std::to_string(cc11)+":"+std::to_string(sent);
    if(noteZoneRows_.count(signature)) return;
    if(noteZoneRows_.size()>=512) { noteZoneLimit_=true; return; }
    std::ostringstream row;
    row << "NOTE ZONE id=" << origin.id << " ch=" << channel << " key=" << key << " velocity=" << velocity
        << " NOTE_ON_SENT=" << sent << " liveVerified=" << (liveOk && bankKnown && !path.empty())
        << " sourceSF2='" << path << "' rawBank=" << rawBank << " normalizedBank=" << live.bank << " PC=" << live.preset
        << " CC7=" << cc7 << " CC11=" << cc11 << " pan=" << BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_PAN)
        << " reverb=" << BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_REVERB)
        << " chorus=" << BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_CHORUS)
        << " releaseController=" << BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_RELEASE);
    BASS_MIDI_FONTINFO info{};
    float streamVolume=0;
    const bool attrOk=stream_ && BASS_ChannelGetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,&streamVolume);
    const bool infoOk=liveOk && BASS_MIDI_FontGetInfo(live.font,&info);
    row << " fontVolume=" << (liveOk?BASS_MIDI_FontGetVolume(live.font):-1) << " streamMidiVolume=" << streamVolume
        << " attrOk=" << attrOk << " fontInfoOk=" << infoOk << " wholeFontSamload=" << info.samload
        << " wholeFontSamsize=" << info.samsize << '\n';
    const auto it=melodicZoneInventories_.find(path);
    const auto metadata=(liveOk && bankKnown && it!=melodicZoneInventories_.end())?it->second:nullptr;
    // Only capture immutable metadata identity here. Zone scan/format occurs at explicit export.
    noteZoneRows_[signature]={row.str(),metadata,rawBank,live.preset,key,velocity};
    LOGI("NOTE ZONE OBSERVED id=%lld ch=%d key=%d velocity=%d liveVerified=%d CC7=%d CC11=%d fullEvidence=Inspector_SAVE_REPORT",
        static_cast<long long>(origin.id),channel,key,velocity,liveOk && bankKnown && !path.empty(),cc7,cc11);
}

// Explicit STOP-only audition caller. Separate decode stream; never touches arranger stream/channel state.
std::vector<unsigned char> BassMidiPlayer::diagnosticDrumWav(int bank,int pc,int key,int velocity) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(!drumFont_ || pc<0 || pc>127 || key<0 || key>127 || velocity<1 || velocity>127) return {};
    const auto it=drumZoneInventories_.find(drumPath_);
    if(it==drumZoneInventories_.end() || !sf2_zones::match(it->second,bank,pc,key,velocity).zones) {
        LOGI("DRUM AUDITION refused bank=%d PC=%d key=%d velocity=%d reason=no_verified_metadata_zone",bank,pc,key,velocity);
        return {};
    }
    const HSTREAM audition=BASS_MIDI_StreamCreate(1,BASS_SAMPLE_FLOAT|BASS_STREAM_DECODE,sampleRate_);
    if(!audition) return {};
    struct Release { HSTREAM stream; ~Release() { BASS_StreamFree(stream); } } release{audition};
    BASS_MIDI_FONTEX2 mapping{};
    mapping.font=drumFont_; mapping.sbank=bank; mapping.spreset=pc;
    mapping.dbank=128; mapping.dpreset=pc; mapping.minchan=0; mapping.numchan=1;
    if(!BASS_MIDI_StreamSetFonts(audition,&mapping,1|BASS_MIDI_FONT_EX2) ||
       !BASS_MIDI_StreamEvent(audition,0,MIDI_EVENT_DRUMS,1) ||
       !BASS_MIDI_StreamEvent(audition,0,MIDI_EVENT_BANK,128) ||
       !BASS_MIDI_StreamEvent(audition,0,MIDI_EVENT_PROGRAM,pc) ||
       !BASS_MIDI_StreamEvent(audition,0,MIDI_EVENT_NOTE,key|(velocity<<8))) return {};
    BASS_MIDI_FONT live{};
    if(!BASS_MIDI_StreamGetPreset(audition,0,&live) || live.font!=drumFont_ || live.bank!=bank || live.preset!=pc) {
        LOGI("DRUM AUDITION refused reason=live_preset_mismatch"); return {};
    }
    // 2 seconds at native rate. Default controllers, existing font volume; no normalization/gain/FX.
    const size_t samples=static_cast<size_t>(sampleRate_)*2*2;
    std::vector<float> pcm(samples);
    size_t offset=0;
    while(offset<samples) {
        const DWORD requested=static_cast<DWORD>(std::min<size_t>(2048,samples-offset)*sizeof(float));
        const DWORD received=BASS_ChannelGetData(audition,pcm.data()+offset,requested|BASS_DATA_FLOAT);
        if(received==static_cast<DWORD>(-1) || !received || received>requested || received%sizeof(float)) return {};
        offset+=received/sizeof(float);
    }
    std::vector<unsigned char> wav(44+samples*2);
    auto word=[&](size_t p,uint16_t value) { wav[p]=value&255; wav[p+1]=value>>8; };
    auto dword=[&](size_t p,uint32_t value) { word(p,value&65535); word(p+2,value>>16); };
    std::memcpy(wav.data(),"RIFF",4); dword(4,wav.size()-8); std::memcpy(wav.data()+8,"WAVEfmt ",8);
    dword(16,16); word(20,1); word(22,2); dword(24,sampleRate_); dword(28,sampleRate_*4);
    word(32,4); word(34,16); std::memcpy(wav.data()+36,"data",4); dword(40,samples*2);
    double energy=0; float peak=0;
    for(size_t n=0;n<samples;++n) {
        const float sample=std::isfinite(pcm[n])?pcm[n]:0;
        peak=std::max(peak,std::abs(sample)); energy+=double(sample)*sample;
        word(44+n*2,static_cast<uint16_t>(static_cast<int16_t>(std::clamp(sample,-1.0f,1.0f)*32767)));
    }
    LOGI("DRUM AUDITION bank=%d PC=%d key=%d velocity=%d liveVerified=1 peak=%.6f rms=%.6f durationMs=2000 evidence=isolated_decode_not_style_mix",bank,pc,key,velocity,peak,std::sqrt(energy/samples));
    return wav;
}

uint64_t BassMidiPlayer::markPcmSession(pcm_session::Controls controls) {
    std::lock_guard<std::mutex> lock(mutex_);
#if YAMAHA_PCM_HEADROOM
    auto counts=pcmSessionNotes_;
    for(unsigned ch=0;ch<16;++ch)counts.styleVelocity[ch]=styleVelocityEvidence_[ch].count;
    const auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    pcmSession_.mark(pcmSamples_/2,ns,fontMappingGeneration_,headroom_.totals,counts,controls);
    for(unsigned ch=0;ch<16;++ch){
        pcmSession_.reverb[ch]=stream_?int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_REVERB)):-1;
        pcmSession_.chorus[ch]=stream_?int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_CHORUS)):-1;
    }
    return pcmSession_.id;
#else
    (void)controls;return 0;
#endif
}

std::string BassMidiPlayer::pcmSessionReport(pcm_session::Controls controls) const {
#if YAMAHA_PCM_HEADROOM
    pcm_session::Meter session;pcm_headroom::Block totals;pcm_session::Counters counts;
    std::array<int,16> reverb,chorus;int rate;uint64_t generation,ns;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        session=pcmSession_;totals=headroom_.totals;counts=pcmSessionNotes_;rate=sampleRate_;generation=fontMappingGeneration_;
        for(unsigned ch=0;ch<16;++ch){counts.styleVelocity[ch]=styleVelocityEvidence_[ch].count;
            reverb[ch]=stream_?int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_REVERB)):-1;
            chorus[ch]=stream_?int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_CHORUS)):-1;
        }
        ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    return session.report(totals,counts,controls,reverb,chorus,rate,generation,ns);
#else
    (void)controls;return "PCM_SESSION status=UNAVAILABLE debug_HEADROOM_required\n";
#endif
}

std::string BassMidiPlayer::noteZoneReport() const {
    std::map<std::string,NoteZoneObservation> snapshot; bool limited;
// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    pcm_headroom::Meter headroom;float snapshotMaster=0;bool masterOk=false;
#endif
// HEADROOM_OBSERVER_END

    std::array<RolePcmMeter,6> meters;
// MEASURED_BALANCE_BEGIN
    std::array<MeasuredBalance,6> balance;
// MEASURED_BALANCE_END

    std::map<std::string,std::shared_ptr<const sf2_zones::Inventory>> inventories;
    std::array<VelocityEvidence,16> velocities;
    uint64_t generation,samples,clipped,nonfinite;double energy;float peak;
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    pcm_path::Decode decode;
#endif
// PCM_PATH_OBSERVER_END

    struct RouteRow {percussion_fidelity::Candidate route;int key,group;bool verified,attempted;};
    struct LaneRow {int requestedPc;HSTREAM stream;uint64_t generation,mappedOns,legacyOns,failedOns,overflowOns,chokes;
        bool active;std::vector<RouteRow> routes;};
    std::array<LaneRow,2> lanes;
    struct NativeRow {PartPresence n;ChannelState state;int requestedBank,requestedPc,rawBank,cc7,cc11,pan,reverb,chorus;
        BASS_MIDI_FONT live{};bool liveOk;std::string name,path;std::shared_ptr<const sf2_zones::Inventory> inventory;};
    std::array<NativeRow,8> native;
    std::map<std::string,sf2_pdta::Result> pdta;
    std::map<std::string,std::string> fileFingerprints;
    {
        std::lock_guard<std::mutex> lock(mutex_);

// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
        headroom=headroom_;masterOk=stream_ && BASS_ChannelGetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,&snapshotMaster);
#endif
// HEADROOM_OBSERVER_END
        snapshot=noteZoneRows_;limited=noteZoneLimit_;meters=rolePcmMeters_;inventories=melodicZoneInventories_;
        for(const auto& entry:pdtaFingerprints_) {
            bool loaded=(melodyFont_ && entry.first==melodyPath_) || (drumFont_ && entry.first==drumPath_);
            for(const auto& f:secondaryMelodies_)loaded=loaded || (f.font && entry.first==f.path);
            if(loaded) {pdta.insert(entry);const auto it=percussionFingerprints_.find(entry.first);if(it!=percussionFingerprints_.end())fileFingerprints.insert(*it);}
        }
// MEASURED_BALANCE_BEGIN
        balance=measuredBalance_;
// MEASURED_BALANCE_END

        velocities=styleVelocityEvidence_;generation=fontMappingGeneration_;samples=pcmSamples_;
        clipped=pcmClipped_;nonfinite=pcmNonfinite_;energy=pcmEnergy_;peak=pcmPeak_;
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        decode=pcmDecode_;
#endif
// PCM_PATH_OBSERVER_END

        for(int part=0;part<2;++part) {
            const auto& lane=percussionLanes_[part];auto& row=lanes[part];
            row.requestedPc=lane.requestedPc;row.stream=lane.stream;row.generation=lane.generation;
            row.mappedOns=lane.mappedOns;row.legacyOns=lane.legacyOns;row.failedOns=lane.failedOns;row.overflowOns=lane.overflowOns;row.chokes=lane.chokes;
            row.active=lane.stream && lane.requestedPc==audioDiagnostics_[part+8].requestedPc && lane.generation==percussionGeneration_;
            for(int key=0;key<128;++key)if(lane.stream && lane.routes[key].font) {
                const auto& c=lane.routes[key];BASS_MIDI_FONT actual{};
                const bool verified=lane.actualVerified[key] && BASS_MIDI_StreamGetPreset(lane.stream,key,&actual) && actual.font==c.font && actual.bank==c.nativeBank && actual.preset==c.pc;
                row.routes.push_back({c,key,lane.groups[key],verified,lane.actualVerified[key]});
            }
        }
        for (int ch=8;ch<16;++ch) {
            auto& row=native[ch-8];
            const auto& n=partPresence_[ch]; const auto& state=channels_[ch];
            BASS_MIDI_FONT live{};
            const bool liveOk=stream_ && BASS_MIDI_StreamGetPreset(stream_,ch,&live);
            const char* name=liveOk?BASS_MIDI_FontGetPreset(live.font,live.preset,live.bank):nullptr;
            std::string path; int rawBank=live.bank; bool bankKnown=state.drum;
            const std::vector<NormalizedBankMap>* maps=nullptr;
            if (liveOk && live.font==melodyFont_) { path=melodyPath_; maps=&normalizedBanks_; }
            if (liveOk && live.font==drumFont_) path=drumPath_;
            for (const auto& f:secondaryMelodies_) if (liveOk && f.font==live.font) { path=f.path; maps=&f.banks; }
            if (maps) for (const auto& b:*maps) if (b.virtualBank==live.bank) { rawBank=b.rawBank; bankKnown=true; break; }
            const sf2_zones::Inventory* inventory=nullptr;
            const auto melodic=melodicZoneInventories_.find(path);
            const auto drum=drumZoneInventories_.find(path);
            if (bankKnown && melodic!=melodicZoneInventories_.end()) inventory=melodic->second.get();
            if (state.drum && drum!=drumZoneInventories_.end()) inventory=&drum->second;
            row.n=n;row.state=state;row.requestedBank=audioDiagnostics_[ch].requestedBank;row.requestedPc=audioDiagnostics_[ch].requestedPc;
            row.live=live;row.liveOk=liveOk;row.name=name?name:"UNAVAILABLE";row.path=path;row.rawBank=rawBank;
            row.cc7=stream_?BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_VOLUME):0;
            row.cc11=stream_?BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_EXPRESSION):0;
            row.pan=stream_?BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_PAN):-1;
            row.reverb=stream_?BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_REVERB):-1;
            row.chorus=stream_?BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_CHORUS):-1;
            // Shared melodic inventories survive unload; copy only the needed
            // drum preset zones (two channels), never the complete SF2 inventory.
            if(inventory && liveOk && n.lastKey>=0) {
                if(!state.drum)row.inventory=melodicZoneInventories_.at(path);
                else {auto copy=std::make_shared<sf2_zones::Inventory>();copy->valid=inventory->valid;copy->reason=inventory->reason;
                    const auto preset=inventory->presets.find({rawBank,live.preset});
                    if(preset!=inventory->presets.end())copy->presets.emplace(preset->first,preset->second);
                    row.inventory=std::move(copy);}
            }
        }

    } // No render/UI synth lock during formatting, layer scans, or detailedMatch.
    std::ostringstream presence;
// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    presence << headroom.report(snapshotMaster,masterOk);
#endif
// HEADROOM_OBSERVER_END

        presence << "=== ACCOMPANIMENT PART PRESENCE / NATIVE ===\n"
            << "counters=process_lifetime explicit_style_origins_only; last binding and current mappingGeneration=" << generation
            << "; accepted_event_not_audible_PCM; snapshot_never_sends_MIDI\n";

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        presence << decode.report();
#endif
// PCM_PATH_OBSERVER_END
        presence << "PCM_MIX samples=" << samples << " peak=" << peak
            << " rms=" << (samples?std::sqrt(energy/samples):0)
            << " clippedSamples=" << clipped << " nonfiniteSamples=" << nonfinite
            << " scope=post_sum_pre_existing_output_clamp no_per_part_PCM_claim no_gain_adjustment\n";
        for(int channel=8;channel<16;++channel) {
            const auto& v=velocities[channel];
            presence << "STYLE_VELOCITY ch=" << channel << " count=" << v.count
                << " min=" << (v.count?v.minimum:-1) << " max=" << (v.count?v.maximum:-1)
                << " mean=" << (v.count?double(v.sum)/v.count:0)
                << " rms=" << (v.count?std::sqrt(double(v.squares)/v.count):0)
                << " scope=MIDI_input_not_SF2_response_or_PCM\n";
        }

// MEASURED_BALANCE_BEGIN
        for(int ch=10;ch<16;++ch){const auto& b=balance[ch-10];
            presence << "MEASURED_BALANCE ch=" << ch << " active=" << bool(b.dsp && b.db!=0)
                << " fingerprint=" << b.fingerprint << " trimDb=" << b.db << " currentGain=" << b.ramp.current
                << " targetGain=" << b.ramp.target << " error=" << b.error << " evidence=" << b.evidence
                << " scope=fixed_binding_response_trim_not_CC_velocity_or_Yamaha_exact\n";
        }
// MEASURED_BALANCE_END
        presence << rolePcmReport(meters,inventories,samples);
        for(int part=0;part<2;++part) {
            const auto& lane=lanes[part];
            presence << "PERCUSSION_FIDELITY enabled=" << YAMAHA_COMPATIBLE_PERCUSSION << " ch=" << part+8 << " pc=" << lane.requestedPc
                << " stream=" << lane.stream << " generation=" << lane.generation
                << " activeForOn=" << lane.active
                << " compatibleOns=" << lane.mappedOns << " legacyOns=" << lane.legacyOns
                << " failedOns=" << lane.failedOns << " overflowDropped=" << lane.overflowOns << " chokes=" << lane.chokes << '\n';
            for(const auto& route:lane.routes) {
                const auto& c=route.route;const int key=route.key;const bool verified=route.verified;
                presence << "COMPATIBLE_ROUTE ch=" << part+8 << " sourceKey=" << key << " candidateKey=" << c.key
                    << " sf2='" << c.path << "' bank=" << c.bank << " nativeBank=" << c.nativeBank << " rawPC=" << c.pc
                    << " fingerprint=" << c.fingerprint << " audition770=" << c.auditioned
                    << " preset='" << c.preset << "' samples='" << c.samples << "' layers=" << c.layers
                    << " sourceGroup=" << route.group << " candidateExclusive=" << c.exclusive
                    << " velocities=1:127 pitchOffset=0 controllerLane=" << key << " actualVerified=" << verified
                    << " actualReadbackStage=" << (route.attempted?"FIRST_REAL_NOTE_VERIFIED":"DEFERRED_UNTIL_REAL_NOTE")
                    << " sourceIdentity='" << (percussion_fidelity::sourceIdentity(127,0,lane.requestedPc,key)?percussion_fidelity::sourceIdentity(127,0,lane.requestedPc,key)->identity:"UNKNOWN")
                    << "' sampleBundle=" << c.sampleIdentity
                    << " actualVoiceSampleId=UNAVAILABLE semantic=COMPATIBLE_not_EXACT variantMatch=UNPROVEN\n";
            }
        }
    for(int ch=8;ch<16;++ch) {
        const auto& row=native[ch-8];const auto& n=row.n;const auto& state=row.state;
        const auto& live=row.live;const bool liveOk=row.liveOk;const char* name=row.name.c_str();const auto& path=row.path;
        sf2_zones::Match eligible{};
        std::string keysPresent="UNAVAILABLE";
        if(row.inventory && row.inventory->valid && liveOk) {
            const auto preset=row.inventory->presets.find({row.rawBank,live.preset});
            if(preset!=row.inventory->presets.end()) {
                std::array<bool,128> keys{};
                for(const auto& zone:preset->second) if(zone.velLow<=zone.velHigh)
                    for(int key=std::max(0,zone.keyLow);key<=std::min(127,zone.keyHigh);++key)keys[key]=true;
                keysPresent.clear();for(int key=0;key<128;++key)if(keys[key]) {if(!keysPresent.empty())keysPresent+=",";keysPresent+=std::to_string(key);}
                if(keysPresent.empty())keysPresent="NONE";
            }
        }
        if(row.inventory && liveOk && n.lastKey>=0)eligible=sf2_zones::match(*row.inventory,row.rawBank,live.preset,n.lastKey,n.lastVelocity);
            presence << "NATIVE ch=" << ch << " midiChannel=" << ch+1 << " attempts=" << n.attempts << " BASS_NOTE_ON_SENT=" << n.sent
                << " familyOrMapRejected=" << n.familyOrMapRejected << " engineUnavailable=" << n.engineUnavailable << " sendFailed=" << n.sendFailed
                << " zeroController=" << n.zeroController << " presetMapFailures=" << n.presetMapFailures
                << " requestedBank=" << row.requestedBank << " rawPC=" << row.requestedPc
                << " effectiveBank=" << state.bankMsb << ':' << state.bankLsb << " effectivePC=" << state.program
                << " selectedRawBank=" << state.melodySourceBank << " selectedPC=" << state.melodySourceProgram
                << " resolverReason=" << (state.resolverReason.empty()?"UNOBSERVED":state.resolverReason)
                << " initialized=" << state.initialized << " admitted=" << (state.drum || state.melodySourceProgram>=0)
                << " liveAvailable=" << liveOk << " font=" << live.font << " liveBank=" << live.bank << " livePC=" << live.preset
                << " preset='" << (name?name:"UNAVAILABLE") << "' SF2='" << path << "' CC7="
                << row.cc7 << " CC11="
                << row.cc11
                << " CC10=" << row.pan << " CC91=" << row.reverb << " CC93=" << row.chorus
                << " keysPresent=" << keysPresent << " coverageScope=eligible_zone_union_not_all_velocities"
                << " lastKey=" << n.lastKey << " lastVelocity=" << n.lastVelocity << " lastNoteMetadataKnown=" << eligible.known
                << " lastNoteEligibleZones=" << eligible.zones << " last_note_only_not_all_velocity_proof\n";
    }
    std::ostringstream report;
    report << "=== SF2 FINGERPRINT MANIFEST v1 ===\n";
    for(const auto& entry:pdta) {
        const auto file=fileFingerprints.find(entry.first);
        report << "SF2_FINGERPRINT pdtaSha256=" << (entry.second.valid?entry.second.sha256:"UNAVAILABLE")
            << " fileSha256=" << (file!=fileFingerprints.end()?file->second:"UNAVAILABLE")
            << " pdtaBytes=" << entry.second.bytes << " hashingNs=" << entry.second.elapsedNs
            << " status=UNVERIFIED catalogUse=OFF reason=" << entry.second.reason << '\n';
    }
    report << presence.str();
    report << "=== ACTUAL BASS / STRINGS NOTE ZONES ===\nuniqueObservations=" << snapshot.size()
        << " limitReached=" << limited << " cap=512 cached_at_font_load no_PCM_or_voice_sample_ID\n";
    if(snapshot.empty()) report << "unavailable: play Bass/Strings after all fonts finish loading\n";
    for(const auto& row:snapshot) {
        const auto& observation=row.second;
        report << observation.header;
        if(observation.inventory) report << sf2_zones::detailedMatch(*observation.inventory,observation.bank,observation.pc,observation.key,observation.velocity);
        else report << "metadataKnown=0 reason=live_or_cache_unavailable\n";
    }
    return report.str();
}

std::string BassMidiPlayer::drumKitCoverage(const std::vector<drum_audit::Hit>& hits) const {
    sf2_zones::Inventory snapshot;
    std::string source;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!drumFont_ || drumPath_.empty())
            return "DRUM KIT AUDIT unavailable: dedicated drum SF2 not loaded\n";
        const auto it = drumZoneInventories_.find(drumPath_);
        if (it == drumZoneInventories_.end())
            return "DRUM KIT AUDIT unavailable: dedicated metadata cache absent\n";
        snapshot = it->second;
        source = drumPath_;
    }
    // Format outside the synth mutex; never switch FONTEX2 or send test notes.
    return "Dedicated sourceSF2='" + source + "'\n" + drum_audit::report(snapshot, hits);
}

std::string BassMidiPlayer::drumCompatibilityReport(const std::vector<drum_compat::Demand>& demand, const std::vector<std::pair<int,int>>& comparisonKits) const {
    sf2_zones::Inventory snapshot;
    std::string source;
    std::vector<drum_compat::Live> lives;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        source=drumPath_;
        const auto metadata=drumZoneInventories_.find(source);
        if(drumFont_ && metadata!=drumZoneInventories_.end()) snapshot=metadata->second;
        else snapshot.reason=drumFont_?"dedicated_metadata_cache_absent":"dedicated_drum_SF2_not_loaded";
        for(int ch=8;ch<=9;++ch) {
            drum_compat::Live row;
            row.channel=ch; row.generation=fontMappingGeneration_;
            row.initialized=channels_[ch].initialized;
            row.requestBank=audioDiagnostics_[ch].requestedBank;
            row.requestPc=audioDiagnostics_[ch].requestedPc;
            row.effectivePc=row.initialized?channels_[ch].program:-1;
            BASS_MIDI_FONT live{};
            row.verified=stream_ && BASS_MIDI_StreamGetPreset(stream_,ch,&live);
            if(row.verified) {
                row.bank=live.bank; row.pc=live.preset;
                row.inCandidateSource=drumFont_ && live.font==drumFont_;
                row.source=row.inCandidateSource?drumPath_:live.font==melodyFont_?melodyPath_:"unknown_font";
                for(const auto& secondary:secondaryMelodies_) if(live.font==secondary.font) row.source=secondary.path;
                const char* name=BASS_MIDI_FontGetPreset(live.font,live.preset,live.bank);
                row.name=name?name:"unknown_name";
            }
            const std::string& expectedPath=drumFont_?drumPath_:melodyPath_;
            int expectedBank=-1,expectedPc=-1;
            // Consult existing immutable cache ONLY to explain the established fallback.
            const bool known=row.initialized && row.requestPc>=0 && findDrumPreset(expectedPath,row.requestPc,expectedBank,expectedPc);
            if(!row.initialized) row.reason="unknown_channel_not_initialized";
            else if(!row.verified) row.reason="unknown_live_preset_unavailable";
            else if(!known) row.reason="unknown_existing_drum_cache_unresolved";
            else if(live.font!=(drumFont_?drumFont_:melodyFont_) || live.bank!=expectedBank || live.preset!=expectedPc)
                row.reason="unknown_actual_differs_from_existing_cache_result";
            else row.reason=expectedPc==row.requestPc?"same_PC_in_existing_cache_not_Yamaha_identity_proof":"requested_PC_absent_existing_first_available";
            lives.push_back(row);
        }
    }
    // Formatting/cache scanning outside mutex, no StreamEvent/SetFonts/FontLoad calls.
    if(!comparisonKits.empty()) return drum_compat::comparisonReport(snapshot,source,demand,lives,comparisonKits);
    return drum_compat::report(snapshot,source,demand,lives);
}

const BassMidiPlayer::BrowserPreset* BassMidiPlayer::keyboardPreset(const KeyboardPreset& selection) const {
    for (const auto& p : browserPresets_)
        if (p.fontId == selection.fontId && p.bank == selection.bank && p.program == selection.program) return &p;
    return nullptr;
}

void BassMidiPlayer::refreshBrowserPresets() {
    browserPresets_.clear();
    auto collect = [&](HSOUNDFONT font, uint64_t id, const std::string& path,
                       const std::string& role, const std::vector<NormalizedBankMap>* banks) {
        if (!font || !id || path.empty()) return;
        BASS_MIDI_FONTINFO info{};
        if (!BASS_MIDI_FontGetInfo(font, &info) || !info.presets) return;
        std::vector<DWORD> presets(info.presets);
        if (!BASS_MIDI_FontGetPresets(font, presets.data())) {
            LOGE("VOICE INVENTORY unavailable fontId=%llu role=%s error=%d",
                 static_cast<unsigned long long>(id), role.c_str(), BASS_ErrorGetCode());
            return;
        }
        auto safeField = [](std::string value) {
            for (auto& c : value) if (c == '\x1e' || c == '\x1f') c = ' ';
            return value;
        };
        const auto file = safeField(path.substr(path.find_last_of("/\\") + 1));
        std::set<std::pair<int,int>> seen;
        for (const DWORD preset : presets) {
            const int nativeBank = HIWORD(preset), program = LOWORD(preset);
            const char* name = BASS_MIDI_FontGetPreset(font, program, nativeBank);
            if (!name || !seen.emplace(nativeBank, program).second) continue;
            int rawBank = nativeBank;
            if (banks && nativeBank != 127 && nativeBank != 128) {
                const auto it = std::find_if(banks->begin(), banks->end(),
                    [&](const NormalizedBankMap& b) { return b.virtualBank == nativeBank; });
                if (it == banks->end()) continue; // Unknown normalization cannot be selected safely.
                rawBank = it->rawBank;
            }
            browserPresets_.push_back({id,font,role,file,safeField(name),rawBank,nativeBank,program});
        }
        LOGI("VOICE INVENTORY fontId=%llu role=%s sf2='%s' available=%u",
             static_cast<unsigned long long>(id), role.c_str(), file.c_str(), static_cast<unsigned>(seen.size()));
    };
    collect(melodyFont_, melodyFontId_, melodyPath_, "MELODY", &normalizedBanks_);
    for (size_t i = 0; i < secondaryMelodies_.size(); ++i) {
        const auto& s = secondaryMelodies_[i];
        collect(s.font, s.fontId, s.path, "MELODY_SECONDARY_" + std::to_string(i+1), &s.banks);
    }
    collect(drumFont_, drumFontId_, drumPath_, "DRUM", nullptr);
    for (auto& s : keyboardPresets_) s.verified = false;
}

bool BassMidiPlayer::restoreKeyboardPresets(int onlyChannel) {
    bool allOk = true;
    for (int ch = 0; ch < 4; ++ch) {
        if (onlyChannel >= 0 && ch != onlyChannel) continue;
        auto& selection = keyboardPresets_[ch];
        if (!selection.fontId) continue;
        selection.verified = false;
        keyboardEvidence_[ch].needsReadback=true;
        const auto* p = keyboardPreset(selection);
        if (!p) { allOk = false; continue; }
        bool ok = send(ch, MIDI_EVENT_DRUMS, (p->bank == 127 || p->bank == 128) ? 1 : 0);
        ok = send(ch, MIDI_EVENT_BANK, 0) && ok;
        ok = send(ch, MIDI_EVENT_BANK_LSB, 0) && ok;
        ok = send(ch, MIDI_EVENT_PROGRAM, 0) && ok;
        BASS_MIDI_FONT actual{};
        const bool readback = BASS_MIDI_StreamGetPreset(stream_, ch, &actual);
        selection.verified = ok && readback && actual.font == p->font &&
            actual.bank == p->nativeBank && actual.preset == p->program;
        if (selection.verified)
            BASS_MIDI_FontLoadEx(p->font, p->program, p->nativeBank, 0, BASS_MIDI_FONTLOAD_NOWAIT);
        LOGI("VOICE SELECT ch=%d fontId=%llu sf2='%s' bank=%d nativeBank=%d pc=%d verified=%d",
             ch, static_cast<unsigned long long>(p->fontId), p->file.c_str(), p->bank,
             p->nativeBank, p->program, selection.verified ? 1 : 0);
        allOk = selection.verified && allOk;
    }
    return allOk;
}

bool BassMidiPlayer::setKeyboardPreset(int channel, uint64_t fontId, int bank, int program) {
    f12::SynthLock lock(mutex_,f12::Preset);
    latency_probe::Hold latencyHold_(latency_probe::HoldPreset);
    if (channel < 0 || channel > 3 || !fontId || !ensureEngine()) return false;
    const KeyboardPreset requested{fontId,bank,program,false};
    if (!keyboardPreset(requested)) return false; // Never enter the style/family fallback resolver.
    const auto previous = keyboardPresets_[channel];
    // Retire only this keyboard part's previous sound before changing its source.
    send(channel, MIDI_EVENT_SOUNDOFF, 0);
    keyboardPresets_[channel] = requested;
    if (!applyFonts()) {
        if (publishedFontTableIntact()) keyboardPresets_[channel] = previous;
        else {
            for (auto& s : keyboardPresets_) s.verified = false;
            invalidateMelodicChannels();
        }
        return false;
    }
    restoreKeyboardPresets(channel);
    const bool releaseOk = channel >= 3 || applyKeyboardRelease(channel);
    if (!keyboardPresets_[channel].verified || !releaseOk) {
        keyboardPresets_[channel] = previous;
        if (applyFonts()) restoreKeyboardPresets(channel);
        else {
            for (auto& s : keyboardPresets_) s.verified = false;
            if (!publishedFontTableIntact()) invalidateMelodicChannels();
        }
        return false;
    }
    return keyboardPresets_[channel].verified;
}

std::string BassMidiPlayer::presetList() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    for (const auto& p : browserPresets_)
        out << p.role << '\x1f' << p.fontId << '\x1f' << p.file << '\x1f'
            << p.bank << '\x1f' << p.program << '\x1f' << p.name << '\x1e';
    return out.str();
}

std::string BassMidiPlayer::keyboardVoiceSnapshot() const {
    struct Row {KeyboardPreset request;KeyboardEvidence evidence;BASS_MIDI_FONT actual{};bool ok=false;int release=64;std::string file,name;uint64_t id=0;};
    std::array<Row,4> rows;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        latency_probe::Hold latencyHold_(latency_probe::HoldDiagnostic);
        for(int ch=0;ch<4;++ch) {
            auto& r=rows[ch];r.request=keyboardPresets_[ch];r.evidence=keyboardEvidence_[ch];
            if(stream_) {r.ok=BASS_MIDI_StreamGetPreset(stream_,ch,&r.actual);r.release=BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_RELEASE);}
            for(const auto& p:browserPresets_) if(r.ok&&p.font==r.actual.font&&p.nativeBank==r.actual.bank&&p.program==r.actual.preset) {
                r.file=p.file;r.name=p.name;r.id=p.fontId;break;
            }
        }
    }
    std::ostringstream out;
    for(int ch=0;ch<4;++ch) {
        const auto& r=rows[ch];const auto& e=r.evidence;
        out<<"KEYBOARD VOICE part="<<(ch==3?"LEFT":"RIGHT"+std::to_string(ch+1))<<" channel="<<ch
           <<" requestedFontId="<<r.request.fontId<<" requestedBank="<<r.request.bank<<" requestedPC="<<r.request.program
           <<" selectionVerified="<<r.request.verified<<" actualFontId="<<r.id<<" actualSF2='"<<r.file<<"' actualPreset='"<<r.name
           <<"' nativeBank="<<r.actual.bank<<" nativePC="<<r.actual.preset<<" liveReadback="<<r.ok<<" release="<<r.release
           <<" noteOns="<<e.ons<<" sent="<<e.sent<<" noteOffs="<<e.offs<<" lastKey="<<e.key<<" lastVelocity="<<e.velocity
           <<" atNoteFont="<<e.actual.font<<" atNoteBank="<<e.actual.bank<<" atNotePC="<<e.actual.preset
           <<" atNoteReadback="<<e.notePresetVerified<<" atNoteRelease="<<e.release<<'\n';
    }
    return out.str();
}

void BassMidiPlayer::resetRightVelocityStats() {
    std::lock_guard<std::mutex> lock(mutex_);
    for(auto& h:rightForwardedHist_) h.reset();
}

bool BassMidiPlayer::armLiveOnset() {
    std::lock_guard<std::mutex> lock(mutex_);
    latency_probe::Hold latencyHold_(latency_probe::HoldDiagnostic);
    if(!stream_) return false;
    liveBuf_.assign(size_t(sampleRate_)/2*2,0.0f);
    liveFrames_=0;liveChannel_=-1;liveKey_=-1;liveVelocity_=0;liveReport_.clear();
    liveCapture_.store(1,std::memory_order_release);
    return true;
}

// Called from noteOn under mutex_: the armed capture starts with the next RIGHT NOTE_ON that BASSMIDI accepted.
void BassMidiPlayer::beginLiveCapture(int channel,int key,int velocity) {
    liveChannel_=channel;liveKey_=key;liveVelocity_=velocity;
    liveCtl_.cc7=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_VOLUME));
    liveCtl_.cc11=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_EXPRESSION));
    liveCtl_.reverb=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_REVERB));
    liveCtl_.chorus=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_CHORUS));
    liveCtl_.pan=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_PAN));
    liveCtl_.release=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_RELEASE));
    liveFrames_=0;
    liveCapture_.store(2,std::memory_order_release);
}

// Called from render() under mutex_ while capturing. Bounded copy into a buffer sized at arm time; no allocation.
void BassMidiPlayer::appendLiveCapture(const float* out,int numFrames) {
    const size_t capacity=liveBuf_.size()/2;
    const size_t take=std::min<size_t>(size_t(numFrames),capacity-liveFrames_);
    std::memcpy(liveBuf_.data()+liveFrames_*2,out,take*2*sizeof(float));
    liveFrames_+=take;
    if(liveFrames_>=capacity) liveCapture_.store(3,std::memory_order_release);
}

std::string BassMidiPlayer::liveOnsetStatus() const {
    switch(liveCapture_.load(std::memory_order_acquire)) {
        case 1: return "armed: play ONE RIGHT note now (style stopped)";
        case 2: return "capturing the live render...";
        case 3: return "ready";
        default: return "idle";
    }
}

std::string BassMidiPlayer::liveOnsetReport() {
    std::lock_guard<std::mutex> lock(mutex_);
    latency_probe::Hold latencyHold_(latency_probe::HoldDiagnostic);
    const int state=liveCapture_.load(std::memory_order_acquire);
    if(state!=3) return "LIVE ONSET not ready (state="+std::string(state==0?"idle":state==1?"armed":"capturing")+")\n";
    if(!liveReport_.empty()) return liveReport_;
    const int channel=liveChannel_;
    const auto& selection=keyboardPresets_[channel];const auto* p=keyboardPreset(selection);
    BASS_MIDI_FONT actual{};
    if(!p||!selection.verified||!BASS_MIDI_StreamGetPreset(stream_,channel,&actual)||
       actual.font!=p->font||actual.bank!=p->nativeBank||actual.preset!=p->program) {
        liveReport_="LIVE ONSET unavailable: RIGHT preset not verified by BASS readback\n";return liveReport_;
    }
    const bool drum=p->bank==127||p->bank==128;
    const auto live=onset_probe::analyze(liveBuf_,sampleRate_);
    const auto same=onset_probe::render(p->font,p->nativeBank,p->program,drum,liveKey_,liveVelocity_,sampleRate_,liveCtl_);
    const auto neutral=onset_probe::render(p->font,p->nativeBank,p->program,drum,liveKey_,liveVelocity_,sampleRate_,{});
    std::ostringstream out;
    out<<"LIVE ONSET part=RIGHT"<<(channel+1)<<" sf2='"<<p->file<<"' preset='"<<p->name<<"' key="<<liveKey_
       <<" velocity="<<liveVelocity_<<" rate="<<sampleRate_<<" captureStartsAtRenderBlockThatConsumedTheNoteOn=1\n"
       <<"LIVE ONSET liveControllers cc7="<<liveCtl_.cc7<<" cc11="<<liveCtl_.cc11<<" reverb="<<liveCtl_.reverb
       <<" chorus="<<liveCtl_.chorus<<" pan="<<liveCtl_.pan<<" release="<<liveCtl_.release<<" (read back from the live stream)\n"
       <<"PATH A live render            "<<onset_probe::line(liveVelocity_,live)<<" riseMs10to90="<<onset_probe::riseMs(liveBuf_,sampleRate_)<<"\n";
    if(same.empty()||neutral.empty()) out<<"PATH B isolated decode FAILED\n";
    else {
        const auto sameOnset=onset_probe::analyze(same,sampleRate_),neutralOnset=onset_probe::analyze(neutral,sampleRate_);
        const auto vsSame=onset_probe::compare(liveBuf_,same,sampleRate_,100),vsNeutral=onset_probe::compare(liveBuf_,neutral,sampleRate_,100);
        out<<"PATH B isolated, same ctl     "<<onset_probe::line(liveVelocity_,sameOnset)<<" riseMs10to90="<<onset_probe::riseMs(same,sampleRate_)<<"\n"
           <<"PATH B isolated, neutral ctl  "<<onset_probe::line(liveVelocity_,neutralOnset)<<" riseMs10to90="<<onset_probe::riseMs(neutral,sampleRate_)<<"\n"
           <<"COMPARE live vs isolated(same ctl) first100ms errorDb="<<vsSame.errorDb<<" correlation="<<vsSame.correlation
           <<" verdict="<<onset_probe::compareVerdict(vsSame)<<"\n"
           <<"COMPARE live vs isolated(neutral ctl) first100ms errorDb="<<vsNeutral.errorDb<<" correlation="<<vsNeutral.correlation
           <<" verdict="<<onset_probe::compareVerdict(vsNeutral)<<"\n";
    }
    out<<"LIVE ONSET evidence=real_BASSMIDI_live_stream_PCM_vs_isolated_decode;excludes_midi_transport_and_output_latency;style_must_be_stopped_for_a_clean_comparison\n";
    liveReport_=out.str();return liveReport_;
}

std::string BassMidiPlayer::onsetReport(int channel,int key) {
    std::lock_guard<std::mutex> lock(mutex_);
    latency_probe::Hold latencyHold_(latency_probe::HoldDiagnostic);
    if(channel<0||channel>2||key<0||key>127||!stream_) return "ONSET unavailable: select a RIGHT part first\n";
    const auto& selection=keyboardPresets_[channel];const auto* p=keyboardPreset(selection);
    BASS_MIDI_FONT actual{};
    if(!p||!selection.verified||!BASS_MIDI_StreamGetPreset(stream_,channel,&actual)||
       actual.font!=p->font||actual.bank!=p->nativeBank||actual.preset!=p->program)
        return "ONSET unavailable: RIGHT preset not verified by BASS readback\n";
    std::ostringstream head;
    head<<"ONSET part=RIGHT"<<(channel+1)<<" sf2='"<<p->file<<"' preset='"<<p->name<<"' bank="<<p->bank<<" pc="<<p->program
        <<" key="<<key<<" rate="<<sampleRate_<<'\n';
    return head.str()+onset_probe::run(p->font,p->nativeBank,p->program,p->bank==127||p->bank==128,key,sampleRate_);
}

// Read-only. Every field is labelled by origin: BASS = read back from the live stream/font handle,
// FILE = independent parse of the original SF2, APP = state held by the app. Nothing here writes to BASS.
std::string BassMidiPlayer::rightVoiceDiagnostic() const {
    struct Row {
        KeyboardPreset request;BASS_MIDI_FONT live{};bool liveOk=false;
        bool havePreset=false;BrowserPreset preset{};
        std::string livePath,liveName,infoName;bool infoOk=false;DWORD infoPresets=0;
        int cc7=-1,cc11=-1,cc91=-1,cc93=-1,cc71=-1,cc74=-1,release=-1,pan=-1;
        float fontVolume=-1;uint64_t liveFontId=0;
        right_voice_diag::VelocityHist fwd;uint64_t noteOns=0;
        bool tableIntact=false;
    };
    std::array<Row,3> rows;float streamVol=-1;bool streamVolOk=false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        latency_probe::Hold latencyHold_(latency_probe::HoldDiagnostic);
        const bool tableIntact=publishedFontTableIntact();
        for(int ch=0;ch<3;++ch) {
            auto& r=rows[ch];r.request=keyboardPresets_[ch];r.fwd=rightForwardedHist_[ch];
            r.noteOns=keyboardEvidence_[ch].ons;r.tableIntact=tableIntact;
            if(const auto* p=keyboardPreset(r.request)) {r.havePreset=true;r.preset=*p;}
            if(!stream_) continue;
            r.liveOk=BASS_MIDI_StreamGetPreset(stream_,ch,&r.live);
            r.cc7=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_VOLUME));
            r.cc11=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_EXPRESSION));
            r.cc91=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_REVERB));
            r.cc93=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_CHORUS));
            r.pan=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_PAN));
            r.release=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_RELEASE));
#ifdef MIDI_EVENT_RESONANCE
            r.cc71=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_RESONANCE));
#endif
#ifdef MIDI_EVENT_CUTOFF
            r.cc74=int(BASS_MIDI_StreamGetEvent(stream_,ch,MIDI_EVENT_CUTOFF));
#endif
            if(r.liveOk) {
                const char* n=BASS_MIDI_FontGetPreset(r.live.font,r.live.preset,r.live.bank);
                r.liveName=n?n:"";
                BASS_MIDI_FONTINFO info{};
                r.infoOk=BASS_MIDI_FontGetInfo(r.live.font,&info);
                if(r.infoOk) {r.infoName=info.name?info.name:"";r.infoPresets=info.presets;}
                r.fontVolume=BASS_MIDI_FontGetVolume(r.live.font);
                if(r.live.font==melodyFont_) {r.livePath=melodyPath_;r.liveFontId=melodyFontId_;}
                else if(r.live.font==drumFont_) {r.livePath=drumPath_;r.liveFontId=drumFontId_;}
                for(const auto& s:secondaryMelodies_) if(r.live.font==s.font) {r.livePath=s.path;r.liveFontId=s.fontId;}
            }
        }
        streamVolOk=stream_ && BASS_ChannelGetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,&streamVol);
    }
    std::ostringstream out;
    for(int ch=0;ch<3;++ch) {
        const auto& r=rows[ch];
        right_voice_diag::IdentityInputs in;
        in.requested=r.havePreset;in.liveOk=r.liveOk;in.selectionVerified=r.request.verified;
        std::string requestedPath,requestedSf2,actualSf2="UNAVAILABLE";
        if(r.havePreset) {
            requestedSf2=r.preset.file;
            in.fontHandleMatches=r.liveOk && r.live.font==r.preset.font;
            in.triple=r.liveOk && r.live.bank==r.preset.nativeBank && r.live.preset==r.preset.program;
            in.bassPresetNameMatches=r.liveOk && !r.liveName.empty() && r.liveName==r.preset.name;
        }
        if(!r.livePath.empty()) actualSf2=right_voice_diag::baseName(r.livePath);
        right_voice_diag::Sf2Info file;
        if(!r.livePath.empty()) file=right_voice_diag::cachedSf2(r.livePath);
        in.infoOk=r.infoOk;in.fileInfoOk=file.ok;
        in.inamMatches=file.ok && r.infoOk && file.name==r.infoName;
        std::string reason;
        const char* status=right_voice_diag::status(in,reason);
        if(std::string(status)=="MATCH" && !right_voice_diag::baseName(r.livePath).empty() &&
           right_voice_diag::baseName(r.livePath)!=requestedSf2) {status="MISMATCH";reason="actual_file_differs_from_requested_file";}
        out<<"RVD part=RIGHT"<<(ch+1)<<" ch="<<ch
           <<" reqFontId="<<r.request.fontId<<" reqSf2='"<<requestedSf2<<"' reqLabel="<<(requestedSf2.empty()?"NONE":right_voice_diag::fileLabel(requestedSf2))
           <<" reqBank="<<r.request.bank<<" reqPC="<<r.request.program<<" reqName='"<<(r.havePreset?r.preset.name:"")<<"'"
           <<" selVerified="<<r.request.verified
           <<" liveOk="<<r.liveOk<<" liveFont="<<unsigned(r.live.font)<<" liveBank="<<r.live.bank<<" livePC="<<r.live.preset
           <<" liveName='"<<r.liveName<<"' actualFontId="<<r.liveFontId<<" actualSf2='"<<actualSf2<<"' actualLabel="
           <<(r.livePath.empty()?"UNKNOWN":right_voice_diag::fileLabel(r.livePath))
           <<" bassFontName='"<<r.infoName<<"' bassFontPresets="<<r.infoPresets
           <<" fileInam='"<<file.name<<"' filePresets="<<file.presets<<" presetCountAgree="<<(file.presets<0?-1:(int(r.infoPresets)==file.presets))
           <<" tableIntact="<<r.tableIntact
           <<" status="<<status<<" reason="<<reason
           <<" cc7="<<r.cc7<<" cc11="<<r.cc11<<" cc91="<<r.cc91<<" cc93="<<r.cc93<<" pan="<<r.pan<<" release="<<r.release
           <<" cc71="<<r.cc71<<" cc74="<<r.cc74
           <<" fontVolume="<<r.fontVolume<<" streamMidiVol="<<(streamVolOk?streamVol:-1.0f)
           <<" noteOns="<<r.noteOns<<" fwdCount="<<r.fwd.count<<" fwdLast="<<r.fwd.last
           <<" fwdMin="<<r.fwd.minimum()<<" fwdMed="<<r.fwd.median()<<" fwdMax="<<r.fwd.maximum()<<'\n';
    }
    return out.str();
}

std::vector<unsigned char> BassMidiPlayer::diagnosticKeyboardReleaseWav(int channel,int key,int velocity) {
    std::lock_guard<std::mutex> lock(mutex_);
    keyboardReleaseProbeReport_="RELEASE PCM unavailable: select a verified keyboard preset first\n";
    if(channel<0||channel>3||key<0||key>127||velocity<1||velocity>127||!stream_)return {};
    const auto& selection=keyboardPresets_[channel];const auto* p=keyboardPreset(selection);
    BASS_MIDI_FONT actual{};
    if(!p||!selection.verified||!BASS_MIDI_StreamGetPreset(stream_,channel,&actual)||
       actual.font!=p->font||actual.bank!=p->nativeBank||actual.preset!=p->program)return {};
    const std::array<int,2> sends{int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_REVERB)),
                                  int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_CHORUS))};
    auto result=keyboard_release_probe::run(p->font,p->nativeBank,p->program,p->bank==127||p->bank==128,key,velocity,sampleRate_,sends);
    // Live production state is read only: the knob value must be the controller
    // the next NOTE_OFF of this part will use, and BASS-level CC64 must be off.
    const int liveRelease=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_RELEASE));
    const int liveSustain=int(BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_SUSTAIN));
    const int expectedRelease=channel<3?keyboardReleaseTime_:64;
    std::ostringstream live;
    live<<"RELEASE LIVE part="<<(channel==3?std::string("LEFT"):"RIGHT"+std::to_string(channel+1))
        <<" knob="<<keyboardReleaseTime_<<" liveController="<<liveRelease<<" expected="<<expectedRelease
        <<" delivered="<<(liveRelease==expectedRelease)<<" bassSustainCC64="<<liveSustain
        <<" reverbSend="<<sends[0]<<" chorusSend="<<sends[1]<<'\n';
    // Original-bank zone metadata of the exact selected source (never the normalized copy).
    std::string path;
    if(p->fontId==melodyFontId_)path=melodyPath_;
    else if(p->fontId==drumFontId_)path=drumPath_;
    else for(const auto& secondary:secondaryMelodies_) if(secondary.fontId==p->fontId)path=secondary.path;
    release_envelope::Summary envelope;
    if(const auto melodic=melodicZoneInventories_.find(path);melodic!=melodicZoneInventories_.end()&&melodic->second)
        envelope=release_envelope::summarize(*melodic->second,p->bank,p->program,key,velocity,0.5);
    if(!envelope.metadataKnown)
        if(const auto drum=drumZoneInventories_.find(path);drum!=drumZoneInventories_.end())
            envelope=release_envelope::summarize(drum->second,p->bank,p->program,key,velocity,0.5);
    std::ostringstream verdict;
    verdict<<"RELEASE DIAGNOSIS="<<(liveRelease!=expectedRelease?std::string("LIVE_CONTROLLER_NOT_DELIVERED")
                                    :liveSustain>=64?std::string("BASS_SUSTAIN_HOLDS_NOTES")
                                    :release_envelope::diagnose(envelope,result.classification,result.tailMinus40Ms[2]-result.tailMinus40Ms[0]))
           <<" pcmClass="<<(result.classification.empty()?"FAIL":result.classification)
           <<" sf2Verdict="<<envelope.verdict<<" tailMinus40Ms="<<result.tailMinus40Ms[0]<<','<<result.tailMinus40Ms[1]
           <<','<<result.tailMinus40Ms[2]<<" liveSendsTailMinus40Ms="<<result.reverbTailMinus40Ms
           <<" liveEffectsTailMayMaskRelease="<<(result.reverbTailMinus40Ms>0&&result.reverbTailMinus40Ms>=result.tailMinus40Ms[2])<<'\n';
    keyboardReleaseProbeReport_="RELEASE PCM part="+(channel==3?std::string("LEFT"):"RIGHT"+std::to_string(channel+1))+
        " channel="+std::to_string(channel)+" fontId="+std::to_string(p->fontId)+" sf2='"+p->file+"' preset='"+p->name+
        "' bank="+std::to_string(p->bank)+" nativeBank="+std::to_string(p->nativeBank)+" pc="+std::to_string(p->program)+"\n"+
        result.report+live.str()+release_envelope::describe(envelope,p->bank,p->program,key,velocity)+verdict.str();
    return result.wav;
}

std::string BassMidiPlayer::keyboardReleaseProbeReport() const {
    std::lock_guard<std::mutex> lock(mutex_);return keyboardReleaseProbeReport_;
}

void BassMidiPlayer::render(float* out, int numFrames) {
    const uint64_t probeEnter = latency_probe::probe.on() ? latency_probe::now() : 0; // LATENCY_PROBE
    f12::SynthLock lock(mutex_,f12::Render);
    latency_probe::Hold latencyHold_(latency_probe::HoldRender);
    const uint64_t probeLocked = probeEnter ? latency_probe::now() : 0; // LATENCY_PROBE
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    ++pcmDecode_.calls;
#endif
// PCM_PATH_OBSERVER_END

    if (!stream_) {
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        ++pcmDecode_.noStream;
#endif
// PCM_PATH_OBSERVER_END

        std::fill(out, out + numFrames * 2, 0.0f);
        return;
    }


// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    headroom_.begin(pcmDecode_.calls,pcmSamples_/2,fontMappingGeneration_);
    for(unsigned i=0;i<6;++i){rolePcmMeters_[i].blockPost={};auto& b=measuredBalance_[i];b.blockPre={};b.blockGainMin=b.blockGainMax=b.ramp.current;}
#endif
// HEADROOM_OBSERVER_END
    const DWORD wanted =
        static_cast<DWORD>(numFrames * 2 * sizeof(float));
    const DWORD got = BASS_ChannelGetData(
        stream_, out, wanted | BASS_DATA_FLOAT);
    if (probeEnter) { float active = 0; if (BASS_ChannelGetAttribute(stream_, BASS_ATTRIB_MIDI_VOICES_ACTIVE, &active) && active >= 0) latency_probe::probe.voices(uint32_t(active)); } // LATENCY_PROBE (cheap getter, probe armed only)

    if (got == static_cast<DWORD>(-1)) {
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
        ++pcmDecode_.failed;pcmDecode_.lastError=BASS_ErrorGetCode();
#endif
// PCM_PATH_OBSERVER_END

        LOGE("BASS_ChannelGetData failed error=%d", BASS_ErrorGetCode());
        std::fill(out, out + numFrames * 2, 0.0f);
        return;
    }


// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    ++pcmDecode_.success;pcmDecode_.bytes+=got;pcmDecode_.shortReads+=(got<wanted);
#endif
// PCM_PATH_OBSERVER_END
    const int samples = static_cast<int>(got / sizeof(float));
    if (samples < numFrames * 2) {
        std::fill(out + samples, out + numFrames * 2, 0.0f);
    }

// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    for(unsigned i=0;i<6;++i){const auto& b=measuredBalance_[i];headroom_.role(i,b.blockPre,rolePcmMeters_[i].blockPost,bool(b.dsp),b.blockGainMin,b.blockGainMax);}
    headroom_.current.parent.consume(out,numFrames*2);
#endif
// HEADROOM_OBSERVER_END
    renderPercussion(out,numFrames);
    if (liveCapture_.load(std::memory_order_relaxed)==2) appendLiveCapture(out,numFrames); // diagnostic tap, armed only
    if (probeEnter) latency_probe::probe.renderDone(probeEnter, probeLocked, latency_probe::now()); // LATENCY_PROBE
// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    headroom_.finish(out,numFrames*2);
    pcmSession_.observe(headroom_.current.mix,headroom_.current.frame,fontMappingGeneration_);
#endif
// HEADROOM_OBSERVER_END

    // Sum before AudioEngine's existing output clamp. O(n) PCM only, no MIDI,
    // allocation/logging or synth-state change; exports never render extra notes.
    for(int i=0;i<numFrames*2;++i) {
        const float sample=out[i];++pcmSamples_;
        if(!std::isfinite(sample)){++pcmNonfinite_;continue;}
        pcmEnergy_+=double(sample)*sample;pcmPeak_=std::max(pcmPeak_,std::abs(sample));
        if(std::abs(sample)>1.0f)++pcmClipped_;
    }
}


void BassMidiPlayer::armChordDiagnostic() {
    std::lock_guard<std::mutex> lock(mutex_);
    chordCapture_.arm();
    f12::timing.arm();
    // The focused capture keeps ch11 baseline and short chord windows.
    captureChordState("BASELINE",11,-1,0,-1,0);
}
void BassMidiPlayer::stopChordDiagnostic() {
    std::lock_guard<std::mutex> lock(mutex_); chordCapture_.stop(); f12::timing.stop();
}
void BassMidiPlayer::captureChordState(const char* stage,int channel,int key,int velocity,int sent,int error,
                                     const AudioPathOrigin& origin,DWORD event,DWORD param) {
    if(!chordCapture_.armed) return;
    const auto now=chord_diagnostic::monoNs();
    if(!chordCapture_.active(now)) { chordCapture_.stop(); f12::timing.stop(); return; }
    if(channel<0 || channel>15) return;
    if(!chordCapture_.interested(channel,origin,event!=0,now)) return;
    if(chordCapture_.rows.size()==chord_diagnostic::Capture::cap) { ++chordCapture_.dropped; return; }
    chord_diagnostic::Row row; row.stage=stage; row.origin=origin;
    row.mono=now; row.wall=chord_diagnostic::wallMs(); row.midiOrder=chordMidiOrder_;
    row.channel=channel; row.key=key; row.velocity=velocity; row.sent=sent; row.error=error;
    row.event=event; row.param=param; row.mapGeneration=fontMappingGeneration_;
    row.controlType=event==MIDI_EVENT_BANK ? "BANK_MSB" : event==MIDI_EVENT_BANK_LSB ? "BANK_LSB" :
        event==MIDI_EVENT_PROGRAM ? "PROGRAM" : event==MIDI_EVENT_DRUMS ? "DRUM_MODE" : "NOTES_OFF";
    const auto& state=channels_[channel]; const auto& request=audioDiagnostics_[channel];
    row.initialized=state.initialized; row.drum=state.drum;
    row.requestedBank=request.requestedBank>=0 ? request.requestedBank :
        state.initialized ? state.bankMsb*128+state.bankLsb : -1;
    row.requestedPc=request.requestedPc>=0 ? request.requestedPc : state.initialized ? state.program : -1;
    row.requestedName=state.requestedVoiceName; row.selectedName=state.melodySourceName;
    row.sourceRawBank=state.melodySourceBank; row.sourcePc=state.melodySourceProgram;
    HSOUNDFONT expected=state.drum ? (drumFont_ ? drumFont_ : melodyFont_) : melodyFont_;
    const auto* banks=&normalizedBanks_;
    if(!state.drum && state.melodySource>0 && size_t(state.melodySource)<=secondaryMelodies_.size()) {
        const auto& source=secondaryMelodies_[state.melodySource-1]; expected=source.font; banks=&source.banks;
    }
    row.expectedFont=expected; row.expectedBank=state.melodySourceBank;
    if(state.drum) {
        row.expectedBank=128; row.sourcePc=state.program;
        findDrumPreset(drumFont_ ? drumPath_ : melodyPath_,state.program,row.expectedBank,row.sourcePc);
        row.sourceRawBank=row.expectedBank;
    } else for(const auto& bank:*banks) if(bank.rawBank==row.expectedBank) { row.expectedBank=bank.virtualBank; break; }
    if(stream_) {
        row.dstMsb=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_BANK);
        row.dstLsb=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_BANK_LSB);
        row.dstPc=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_PROGRAM);
        row.cc7=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_VOLUME);
        row.cc11=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_EXPRESSION);
        if(channel==11) row.cc64=BASS_MIDI_StreamGetEvent(stream_,channel,MIDI_EVENT_SUSTAIN);
        BASS_MIDI_FONT live{}; row.liveOk=BASS_MIDI_StreamGetPreset(stream_,channel,&live);
        if(row.liveOk) {
            row.liveFont=live.font; row.liveBank=live.bank; row.livePc=live.preset;
            const char* name=BASS_MIDI_FontGetPreset(live.font,live.preset,live.bank);
            row.liveName=name ? name : "UNAVAILABLE";
            std::string path="UNAVAILABLE";
            if(live.font==melodyFont_) path=melodyPath_;
            if(live.font==drumFont_) path=drumPath_;
            for(const auto& source:secondaryMelodies_) if(live.font==source.font) path=source.path;
            row.liveSf2=path.substr(path.find_last_of("/\\")+1);
            row.mappingMatch=state.initialized && row.sourcePc>=0 && live.font==expected &&
                live.preset==row.sourcePc && live.bank==row.expectedBank;
        }
    }
    chordCapture_.append(std::move(row));
}
std::string BassMidiPlayer::chordDiagnosticReport() const {
    chord_diagnostic::Capture snapshot;
    { std::lock_guard<std::mutex> lock(mutex_); snapshot=chordCapture_; }
    std::ostringstream out;
    out << "=== CHORD NATIVE PRESET CAPTURE ===\nrows=" << snapshot.rows.size()
        << " cap=" << chord_diagnostic::Capture::cap << " dropped=" << snapshot.dropped
        << " active=" << snapshot.active() << " timedOut=" << (snapshot.deadline && chord_diagnostic::monoNs()>=snapshot.deadline)
        << " durationLimitMs=60000 channelNumbers=zero_based\n"
        << "NOTE_PRE follows ensureEngine and precedes family gate/send; POST follows exact send under same mutex. midiOrder counts all send() attempts. "
        << "CONTROL_POST event uses BASS SDK numeric enum plus labelled MIDI type. live preset is font identity, not per-voice sample/PCM proof.\n";
    for(const auto& row:snapshot.rows) {
        const char* midiType=row.event==MIDI_EVENT_BANK ? "BANK_MSB" : row.event==MIDI_EVENT_BANK_LSB ? "BANK_LSB" :
            row.event==MIDI_EVENT_PROGRAM ? "PROGRAM" : row.event==MIDI_EVENT_DRUMS ? "DRUM_MODE" :
            row.event==MIDI_EVENT_NOTESOFF ? "NOTES_OFF" : "NOTE_OR_BASELINE";
        out << "NATIVE order=" << row.captureOrder << " midiOrder=" << row.midiOrder << " wallMs=" << row.wall
            << " monoNs=" << row.mono << " id=" << row.origin.id << " chordId=" << row.origin.chordId
            << " op=" << (row.origin.operation==1 ? "RETARGET" : "SCHEDULED_OR_LEGACY")
            << " stage=" << row.stage << " ch=" << row.channel << " src=" << row.origin.sourceChannel
            << " original=" << row.origin.sourceNote << " output=" << row.key << " velocity=" << row.velocity
            << " styleBank=" << row.origin.styleBank << " tick=" << row.origin.tick
            << " sent=" << row.sent << " error=" << row.error << " midiType=" << midiType << " param=" << row.param
            << " mapGen=" << row.mapGeneration << " initialized=" << row.initialized
            << " requestBank=" << row.requestedBank << " requestPC=" << row.requestedPc << " requestVoice='" << row.requestedName
            << "' requestFamily=" << voice_resolver::familyName(voice_resolver::Request{row.requestedBank,row.requestedPc,row.requestedName}.family())
            << " selected='" << row.selectedName << "' selectedFamily=" << voice_resolver::familyName(voice_resolver::namedFamily(row.selectedName))
            << " sourceRawBank=" << row.sourceRawBank << " sourcePC=" << row.sourcePc
            << " expectedFont=" << row.expectedFont << " expectedBank=" << row.expectedBank
            << " dstBank=" << row.dstMsb << ':' << row.dstLsb << " dstPC=" << row.dstPc
            << " liveOk=" << row.liveOk << " liveFont=" << row.liveFont << " liveBank=" << row.liveBank << " livePC=" << row.livePc
            << " liveSF2='" << row.liveSf2 << "' livePreset='" << row.liveName << "' liveFamilyByName="
            << voice_resolver::familyName(voice_resolver::namedFamily(row.liveName)) << " mappingMatch=" << row.mappingMatch
            << " CC7=" << row.cc7 << " CC11=" << row.cc11 << '\n';
    }
    return out.str();
}

void BassMidiPlayer::markChordDiagnostic(int64_t id) {
    f12::SynthLock lock(mutex_,f12::Other);
    if(!chordCapture_.active() || !id) return;
    chordCapture_.mark(id);
    f12::timing.chord.store(uint32_t(id),std::memory_order_relaxed);
    f12::timing.record(f12::Chord,f12::timing.begin(),0,0,true);
    captureChordState("BASELINE",11,-1,0,-1,0);
}
std::string BassMidiPlayer::compactChordDiagnosticReport() const {
    chord_diagnostic::Capture snapshot;
    { std::lock_guard<std::mutex> lock(mutex_); snapshot=chordCapture_; }
    return f12::timing.report()+chord_diagnostic::compactReport(snapshot);
}

void BassMidiPlayer::retirePercussionStreams() {
    ++percussionGeneration_;
    percussionCandidates_.clear();percussionAllCandidates_.clear();
    for(auto& lane:percussionLanes_) {
        if(lane.stream) BASS_StreamFree(lane.stream);
        lane.stream=0;lane.requestedPc=-1;lane.routes={};lane.groups={};lane.actualVerified={};
        // Consume later OFFs from retired routed ONs, never send them to a new font/stream.
        for(auto& queue:lane.owners) for(unsigned i=0;i<queue.size;++i) {
            auto& o=queue.entries[(queue.head+i)%queue.entries.size()]; if(o.routed)o.choked=true;
        }
    }
}

void BassMidiPlayer::rebuildPercussionCatalog() {
    using namespace percussion_fidelity;
    percussionCandidates_.clear();
    if(!YAMAHA_COMPATIBLE_PERCUSSION)return;
    std::vector<Candidate> candidates;
    auto append=[&](HSOUNDFONT font,const std::string& path,const std::vector<NormalizedBankMap>* banks) {
        const sf2_zones::Inventory* inventory=nullptr;
        auto m=melodicZoneInventories_.find(path);if(m!=melodicZoneInventories_.end())inventory=m->second.get();
        if(!inventory) {auto d=drumZoneInventories_.find(path);if(d!=drumZoneInventories_.end())inventory=&d->second;}
        if(!inventory || !font)return;
        for(auto c:catalog(*inventory,font,path)) {
            c.fingerprint=percussionFingerprints_[path];
            for(const auto& evidence:auditionEvidence)
                if(c.fingerprint==evidence.fingerprint && c.bank==evidence.bank && c.pc==evidence.pc && c.key==evidence.key)c.auditioned=true;
            if(banks && c.bank<127) { c.nativeBank=-1; for(const auto& b:*banks)if(b.rawBank==c.bank)c.nativeBank=b.virtualBank; }
            if(c.nativeBank>=0 && BASS_MIDI_FontGetPreset(font,c.pc,c.nativeBank))candidates.push_back(std::move(c));
        }
    };
    append(drumFont_,drumPath_,nullptr);append(melodyFont_,melodyPath_,&normalizedBanks_);
    for(const auto& f:secondaryMelodies_)append(f.font,f.path,&f.banks);
    percussionAllCandidates_=candidates;
    // Full loading is confined to paused import. The established legacy NOWAIT
    // preload path stays unchanged. Failure/budget exhaustion simply leaves legacy.
    std::set<std::tuple<unsigned,int,int>> ready,failed;
    for(const auto& source:yamahaNotes) {
        const auto f=family(source.identity);if(!supported(f))continue;
        const auto* best=choose(candidates,f,source.key,source.identity);if(!best)continue;
        const auto binding=std::make_tuple(best->font,best->nativeBank,best->pc);
        if(ready.count(binding) || failed.count(binding))continue;
        if(ready.size()>=8 || !BASS_MIDI_FontLoadEx(best->font,best->pc,best->nativeBank,0,0))failed.insert(binding);
        else ready.insert(binding);
    }
    for(auto& c:candidates) if(ready.count({c.font,c.nativeBank,c.pc}))percussionCandidates_.push_back(std::move(c));
    LOGI("PERCUSSION catalog compatibleBundles=%u preloadedPresets=%u rejectedOrBudget=%u legacyNativeUnchanged=1",
         unsigned(percussionCandidates_.size()),unsigned(ready.size()),unsigned(failed.size()));
}

void BassMidiPlayer::preparePercussionLane(int channel,int requestedPc) {
    using namespace percussion_fidelity;
    if(!YAMAHA_COMPATIBLE_PERCUSSION)return;
    auto& lane=percussionLanes_[channel-8];
    if(lane.requestedPc==requestedPc && lane.generation==percussionGeneration_)return;
    // A program transition cannot retarget a held owner. Until it drains, new
    // requests use legacy; no production all-notes-off, reset or generation churn.
    for(auto& q:lane.owners) {q.expire(percussionRenderedFrames_);if(q.pending())return;}
    if(lane.stream)BASS_StreamFree(lane.stream);
    lane.stream=0;lane.routes={};lane.groups={};lane.actualVerified={};lane.requestedPc=requestedPc;lane.generation=percussionGeneration_;
    HSOUNDFONT legacyFont=drumFont_?drumFont_:melodyFont_;
    int exactBank=-1,exactPc=-1;
    if(findDrumPreset(drumFont_?drumPath_:melodyPath_,requestedPc,exactBank,exactPc) && exactPc==requestedPc)return;
    if(percussionCandidates_.empty())return;
    // Finite sample-duration upper bounds are independent of semantic identity.
    // An unproved loop/modulator stays pinned until explicit STOP; no guessed release.
    lane.legacyLifetimeFrames.fill(UINT64_MAX);
    const auto legacyInventory=drumZoneInventories_.find(drumFont_?drumPath_:melodyPath_);
    if(legacyInventory!=drumZoneInventories_.end() && legacyInventory->second.valid) {
        const auto p=legacyInventory->second.presets.find({exactBank,exactPc});
        if(p!=legacyInventory->second.presets.end())for(int key=0;key<128;++key) {
            double seconds=0;bool bounded=true;
            for(const auto& z:p->second)if(z.keyLow<=key && key<=z.keyHigh) {
                if(!z.sampleRate || z.start>=z.end || sf2_zones::effective(z,54)!=0 ||
                   sf2_zones::effective(z,0)!=0 || sf2_zones::effective(z,1)!=0 ||
                   sf2_zones::effective(z,4)!=0 || sf2_zones::effective(z,12)!=0 ||
                   sf2_zones::effective(z,5)!=0 || sf2_zones::effective(z,6)!=0 || sf2_zones::effective(z,7)!=0 ||
                   !z.presetGenerators.modsKnown || !z.instrumentGenerators.modsKnown ||
                   !z.presetGenerators.mods.empty() || !z.instrumentGenerators.mods.empty()) {bounded=false;break;}
                const int played=sf2_zones::effective(z,46)>=0?sf2_zones::effective(z,46):key;
                const int cents=(played-root(z))*sf2_zones::effective(z,56)+sf2_zones::effective(z,51)*100+sf2_zones::effective(z,52);
                const double duration=double(z.end-z.start)/z.sampleRate*std::exp2((std::abs(cents)+std::abs(z.pitchCorrection))/1200.0);
                seconds=std::max(seconds,duration);
            }
            if(bounded && seconds<3600)lane.legacyLifetimeFrames[key]=uint64_t(std::ceil((seconds+5)*sampleRate_));
        }
    }
    const auto planned=distinctPlan(percussionCandidates_,127,0,requestedPc);
    for(const auto& n:yamahaNotes) if(n.msb==127 && n.lsb==0 && n.pc==requestedPc) {
        lane.groups[n.key]=n.group;
        const auto* c=planned[n.key].font?&planned[n.key]:nullptr;
        if(!c || !supported(family(n.identity)) || n.keyOff)continue; // scheduler has no rhythm OFF contract
        // Retain an already compatible native zone instead of replacing a valid kit.
        bool legacyCompatible=false;
        for(const auto& known:percussionAllCandidates_)
            if(known.font==legacyFont && known.bank==exactBank && known.pc==exactPc && known.key==n.key && known.family==family(n.identity))legacyCompatible=true;
        if(!legacyCompatible)lane.routes[n.key]=*c;
    }
    bool any=false;for(const auto& r:lane.routes)if(r.font)any=true;if(!any)return;
    const auto candidateStream=BASS_MIDI_StreamCreate(128,BASS_STREAM_DECODE|BASS_SAMPLE_FLOAT|BASS_MIDI_NOTEOFF1,sampleRate_);
    if(!candidateStream) {lane.routes={};return;}
    std::vector<BASS_MIDI_FONTEX2> maps;
    for(int key=0;key<128;++key) {
        const auto& c=lane.routes[key];if(!c.font)continue;
        BASS_MIDI_FONTEX2 map{};map.font=c.font;map.spreset=c.pc;map.sbank=c.nativeBank;map.dpreset=0;map.dbank=128;
        map.dbanklsb=0;map.minchan=static_cast<decltype(map.minchan)>(key);map.numchan=1;maps.push_back(map);
    }
    bool ok=BASS_MIDI_StreamSetFonts(candidateStream,maps.data(),DWORD(maps.size())|BASS_MIDI_FONT_EX2);
    for(int key=0;key<128 && ok;++key) {
        const auto& c=lane.routes[key];if(!c.font)continue;
        ok=BASS_MIDI_StreamEvent(candidateStream,key,MIDI_EVENT_DRUMS,1) &&
           BASS_MIDI_StreamEvent(candidateStream,key,MIDI_EVENT_BANK,0) &&
           BASS_MIDI_StreamEvent(candidateStream,key,MIDI_EVENT_BANK_LSB,0) &&
           BASS_MIDI_StreamEvent(candidateStream,key,MIDI_EVENT_PROGRAM,0);
        for(const DWORD event:{MIDI_EVENT_VOLUME,MIDI_EVENT_PAN,MIDI_EVENT_EXPRESSION,MIDI_EVENT_REVERB,MIDI_EVENT_CHORUS})
            ok=ok && BASS_MIDI_StreamEvent(candidateStream,key,event,BASS_MIDI_StreamGetEvent(stream_,channel,event));
        // GetPreset has no information until a real note has played. Do not
        // manufacture a warm-up NOTE_ON. Validate controls + the complete table
        // here, then verify the actual binding after the first explicit style ON.
        ok=ok && BASS_MIDI_StreamGetEvent(candidateStream,key,MIDI_EVENT_BANK)==0 &&
            BASS_MIDI_StreamGetEvent(candidateStream,key,MIDI_EVENT_DRUMS)==1 &&
            BASS_MIDI_StreamGetEvent(candidateStream,key,MIDI_EVENT_BANK_LSB)==0 &&
            BASS_MIDI_StreamGetEvent(candidateStream,key,MIDI_EVENT_PROGRAM)==0;
    }
    const DWORD nativeCount=BASS_MIDI_StreamGetFonts(candidateStream,nullptr,0);
    if(nativeCount!=maps.size())ok=false;
    std::vector<BASS_MIDI_FONTEX2> installed(nativeCount);
    if(ok && BASS_MIDI_StreamGetFonts(candidateStream,installed.data(),nativeCount|BASS_MIDI_FONT_EX2)!=nativeCount)ok=false;
    for(size_t i=0;i<maps.size() && ok;++i) {
        const auto& a=maps[i];const auto& b=installed[i];
        ok=a.font==b.font && a.spreset==b.spreset && a.sbank==b.sbank && a.dpreset==b.dpreset &&
           a.dbank==b.dbank && a.dbanklsb==b.dbanklsb && a.minchan==b.minchan && a.numchan==b.numchan;
    }
    float master=1.0f;
    ok=ok && BASS_ChannelGetAttribute(stream_,BASS_ATTRIB_MIDI_VOL,&master) &&
       BASS_ChannelSetAttribute(candidateStream,BASS_ATTRIB_MIDI_VOL,master) &&
       BASS_ChannelSetAttribute(candidateStream,BASS_ATTRIB_MIDI_SRC,float(interpolation_));
    // BASS_ATTRIB_BUFFER belongs to playback channels; auxiliary streams are DECODE.
    if(!ok) {LOGI("PERCUSSION prepare refused ch=%d reason=font_table_or_controller_readback error=%d legacyPreserved=1",channel,BASS_ErrorGetCode());BASS_StreamFree(candidateStream);lane.routes={};return;}
    lane.stream=candidateStream;
    LOGI("PERCUSSION prepared ch=%d requestedPC=%d compatibleKeys=%u auxStream=%u productionFontTableUnchanged=1",
         channel,requestedPc,unsigned(maps.size()),unsigned(lane.stream));
}

void BassMidiPlayer::mirrorPercussionController(int channel,DWORD event,DWORD param) {
    if(channel!=8 && channel!=9)return;
    if(event!=MIDI_EVENT_VOLUME && event!=MIDI_EVENT_PAN && event!=MIDI_EVENT_EXPRESSION && event!=MIDI_EVENT_REVERB && event!=MIDI_EVENT_CHORUS)return;
    auto& lane=percussionLanes_[channel-8];if(!lane.stream)return;
    for(int key=0;key<128;++key) if(lane.routes[key].font && !BASS_MIDI_StreamEvent(lane.stream,key,event,param)) {
        // Reject subsequent substituted ONs. Existing OFF bindings remain intact.
        lane.requestedPc=-1;
    }
}

bool BassMidiPlayer::percussionOn(int channel,int key,int velocity,const AudioPathOrigin& origin,bool& accepted) {
    using namespace percussion_fidelity;
    if(channel!=8 && channel!=9)return false;
    auto& lane=percussionLanes_[channel-8];
    auto& queue=lane.owners[key];queue.expire(percussionRenderedFrames_);
    if(!lane.stream && !queue.pending())return false;
    const bool sourceKnown=origin.styleBank==127*128 && origin.sourceNote==key && origin.sourceChannel==channel && lane.requestedPc==audioDiagnostics_[channel].requestedPc;
    const auto& route=lane.routes[key];
    bool mapped=sourceKnown && lane.stream && lane.generation==percussionGeneration_ &&
                      lane.requestedPc==audioDiagnostics_[channel].requestedPc && route.font;
    Owner owner{mapped,false,false,mapped?route.key:key,sourceKnown?lane.groups[key]:0};
    owner.stream=mapped?lane.stream:stream_;owner.font=mapped?route.font:(drumFont_?drumFont_:melodyFont_);
    owner.generation=lane.generation;owner.bank=mapped?route.nativeBank:channels_[channel].bankMsb;
    owner.pc=mapped?route.pc:channels_[channel].program;owner.lane=mapped?key:channel;
    owner.sourceChannel=origin.sourceChannel;
    owner.oneShot=origin.styleBank>=0 && origin.sourceChannel>=0;
    const auto lifetime=mapped?uint64_t(std::ceil((route.seconds+5)*sampleRate_)):lane.legacyLifetimeFrames[key];
    owner.endFrame=lifetime==UINT64_MAX?UINT64_MAX:percussionRenderedFrames_+lifetime;
    if(!queue.push(owner)) {++lane.overflowOns;accepted=false;++partPresence_[channel].attempts;++partPresence_[channel].sendFailed;return true;}
    // Only cross-stream relationships need an adapter. Native legacy pairs keep
    // their normal behavior. A routed pedal/triangle uses Yamaha alternate groups,
    // including directional 64 -> 96, never an assumed GM hat-key group.
    if(owner.group && owner.group<96) for(int peer=0;peer<128;++peer) {
        auto& held=lane.owners[peer];bool stopAux=false,stopLegacy=false;
        for(unsigned i=0;i<held.size;++i) {const auto& o=held.entries[(held.head+i)%held.entries.size()];
            if(o.accepted && !o.choked && chokes(owner.group,o.group) && (mapped || o.routed)) {
                stopAux=stopAux||o.routed;stopLegacy=stopLegacy||!o.routed;
            }
        }
        const bool auxOk=!stopAux || (lane.stream && BASS_MIDI_StreamEvent(lane.stream,peer,MIDI_EVENT_SOUNDOFF,0));
        const bool legacyOk=!stopLegacy || BASS_MIDI_StreamEvent(stream_,channel,MIDI_EVENT_NOTE,DWORD(peer|(255<<8)));
        for(unsigned i=0;i<held.size;++i) {auto& o=held.entries[(held.head+i)%held.entries.size()];
            if(o.accepted && !o.choked && chokes(owner.group,o.group) && (mapped || o.routed) && (o.routed?auxOk:legacyOk))o.choked=true;
        }
        if((stopAux && auxOk) || (stopLegacy && legacyOk))++lane.chokes;
        if(!auxOk || !legacyOk) {mapped=false;lane.requestedPc=-1;}
    }
    auto& stored=queue.entries[(queue.head+queue.size-1)%queue.entries.size()];
    if(!mapped && stored.routed) {stored.routed=false;stored.stream=stream_;stored.lane=channel;stored.key=key;stored.font=drumFont_?drumFont_:melodyFont_;stored.pc=channels_[channel].program;stored.bank=channels_[channel].bankMsb;}
    if(mapped) {
        accepted=BASS_MIDI_StreamEvent(lane.stream,key,MIDI_EVENT_NOTE,DWORD(route.key|(velocity<<8)));
        if(accepted && !lane.actualVerified[key]) {
            BASS_MIDI_FONT actual{};
            lane.actualVerified[key]=BASS_MIDI_StreamGetPreset(lane.stream,key,&actual) &&
                actual.font==route.font && actual.bank==route.nativeBank && actual.preset==route.pc;
            if(!lane.actualVerified[key]) {
                // No audio callback can run under this mutex. Stop the unverified
                // auxiliary note before rendering, then retain the legacy path.
                const bool stopped=BASS_MIDI_StreamEvent(lane.stream,key,MIDI_EVENT_SOUNDOFF,0);
                lane.requestedPc=-1;
                if(!stopped) {
                    BASS_StreamFree(lane.stream);lane.stream=0;
                    for(auto& held:lane.owners)for(unsigned i=0;i<held.size;++i)if(held.entries[(held.head+i)%held.entries.size()].routed)held.entries[(held.head+i)%held.entries.size()].choked=true;
                }
                {stored.routed=false;stored.choked=false;stored.stream=stream_;stored.font=drumFont_?drumFont_:melodyFont_;stored.lane=channel;stored.key=key;
                    stored.pc=channels_[channel].program;stored.bank=channels_[channel].bankMsb;
                    accepted=send(channel,MIDI_EVENT_NOTE,DWORD(key|(velocity<<8)));stored.accepted=accepted;++lane.legacyOns;
                    logAudioPath(channel,key,velocity,accepted,accepted?0:BASS_ErrorGetCode(),origin,"aux_binding_abstain_legacy",0);return true;}
            }
        }
        stored.accepted=accepted;if(accepted)++lane.mappedOns;else ++lane.failedOns;
        auto& presence=partPresence_[channel];++presence.attempts;
        if(accepted)++presence.sent;else ++presence.sendFailed;presence.lastKey=key;presence.lastVelocity=velocity;
        return true;
    }
    // Record legacy ownership too, so alternating native/substituted repeated
    // notes or a program transition cannot misroute an OFF.
    accepted=send(channel,MIDI_EVENT_NOTE,DWORD(key|(velocity<<8)));
    stored.accepted=accepted;++lane.legacyOns;
    logAudioPath(channel,key,velocity,accepted,accepted?0:BASS_ErrorGetCode(),origin,accepted?"bass_event_accepted":"bass_event_failed",0);
    return true;
}

bool BassMidiPlayer::percussionOff(int channel,int key,const AudioPathOrigin& origin) {
    if(channel!=8 && channel!=9)return false;
    auto& lane=percussionLanes_[channel-8];percussion_fidelity::Owner owner;
    if(!lane.owners[key].popSource(owner,origin.sourceChannel,origin.styleBank>=0 && origin.sourceChannel>=0))
        return lane.stream!=0 || lane.owners[key].pending(); // an orphan OFF cannot release a foreign owner
    if(owner.accepted && !owner.choked) {
        if(owner.routed && lane.stream==owner.stream && lane.generation==owner.generation)
            BASS_MIDI_StreamEvent(owner.stream,owner.lane,MIDI_EVENT_NOTE,DWORD(owner.key));
        else if(!owner.routed && owner.stream==stream_)return false; // unchanged legacy OFF/capture/ledger path
    }
    if(!owner.routed && owner.accepted && owner.choked) {
        const auto now=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
        audioDiagnostics_[channel].off(key,true,now); // observation only: choke already sent the stop
    }
    return true;
}

void BassMidiPlayer::renderPercussion(float* out,int frames) {
    // No heap allocation, file I/O, SF2 scan or scoring in either audio hot path.
    std::array<float,1024> scratch{};
    percussionRenderedFrames_+=uint64_t(frames);
    for(auto& lane:percussionLanes_)if(lane.stream) for(int offset=0;offset<frames;) {
        const int count=std::min(512,frames-offset);
        const DWORD got=BASS_ChannelGetData(lane.stream,scratch.data(),DWORD(count*2*sizeof(float))|BASS_DATA_FLOAT);
        if(got==DWORD(-1) || !got) {lane.requestedPc=-1;break;}
        const int samples=std::min(count*2,int(got/sizeof(float)));

// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
        headroom_.current.lanes[&lane-percussionLanes_.data()].consume(scratch.data(),samples);
#endif
// HEADROOM_OBSERVER_END
        for(int i=0;i<samples;++i)out[offset*2+i]+=scratch[i];
        offset+=count;
    }
}

void CALLBACK BassMidiPlayer::rolePcmTap(HDSP,DWORD,void* buffer,DWORD length,void* user) {
    // BASS calls this during the parent's synchronous decode, under mutex_.
    // Channel streams are always float. Never call BASS, allocate, log, or write buffer.
    auto& meter=*static_cast<RolePcmMeter*>(user);
// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    if(buffer)meter.blockPost.consume(static_cast<const float*>(buffer),length/sizeof(float));
#endif
// HEADROOM_OBSERVER_END

    if(buffer && meter.current>=0)
        meter.windows[meter.current].level.consume(static_cast<const float*>(buffer),length/sizeof(float));
}

void BassMidiPlayer::closeRolePcmWindows() {
    for(auto& m:rolePcmMeters_) if(m.current>=0) {
        auto& w=m.windows[m.current];w.closed=true;w.end=pcmSamples_;m.current=-1;
    }
}

void BassMidiPlayer::clearRolePcmMeters() {
#if YAMAHA_ROLE_PCM_METERS
    for(auto& m:rolePcmMeters_) {
        if(m.dsp) BASS_ChannelRemoveDSP(m.stream,m.dsp);

// MEASURED_BALANCE_BEGIN
        const auto index=&m-rolePcmMeters_.data();
        if(m.stream!=measuredBalance_[index].stream)
// MEASURED_BALANCE_END
        if(m.stream) BASS_StreamFree(m.stream); // child only; never the production MIDI stream
    }
#endif
    rolePcmMeters_={};
}

void BassMidiPlayer::updateRolePcmMeter(int channel) {
// MEASURED_BALANCE_BEGIN
    updateMeasuredBalance(channel); // fixed response trim, independent of diagnostic ON/OFF
// MEASURED_BALANCE_END

#if YAMAHA_ROLE_PCM_METERS
    if(channel<10 || channel>15 || !stream_)return;
    const auto& state=channels_[channel];
    if(!state.initialized || state.drum || state.melodySourceProgram<0)return;
    auto& meter=rolePcmMeters_[channel-10];
    const bool secondary=state.melodySource>0 && size_t(state.melodySource)<=secondaryMelodies_.size();
    const auto* font=secondary?&secondaryMelodies_[state.melodySource-1]:nullptr;
    const HSOUNDFONT handle=font?font->font:melodyFont_;
    const auto& path=font?font->path:melodyPath_;
    const auto& banks=font?font->banks:normalizedBanks_;
    if(!handle)return;
    int nativeBank=state.melodySourceBank;
    for(const auto& b:banks)if(b.rawBank==state.melodySourceBank){nativeBank=b.virtualBank;break;}
    if(meter.current>=0) {
        const auto& old=meter.windows[meter.current];
        if(old.font==handle && old.rawBank==state.melodySourceBank && old.pc==state.melodySourceProgram)return;
        auto& w=meter.windows[meter.current];w.end=pcmSamples_;w.closed=true;meter.current=-1;
    }
    if(meter.count>=int(meter.windows.size())){++meter.omittedWindows;return;}
    if(!meter.stream) {
        // Never request Rhythm or keyboard streams: GetChannel changes per-key drum FX semantics.

// MEASURED_BALANCE_BEGIN
        if(measuredBalance_[channel-10].stream)meter.stream=measuredBalance_[channel-10].stream;
        else
// MEASURED_BALANCE_END
        meter.stream=BASS_MIDI_StreamGetChannel(stream_,channel);

        if(!meter.stream){meter.error=BASS_ErrorGetCode();return;}
        meter.dsp=BASS_ChannelSetDSPEx(meter.stream,rolePcmTap,&meter,0,BASS_DSP_READONLY);
        if(!meter.dsp){meter.error=BASS_ErrorGetCode();
// MEASURED_BALANCE_BEGIN
            if(meter.stream!=measuredBalance_[channel-10].stream)
// MEASURED_BALANCE_END
            BASS_StreamFree(meter.stream);meter.stream=0;return;}
    }
    auto& w=meter.windows[meter.count];
    w.font=handle;w.path=path;w.rawBank=state.melodySourceBank;w.nativeBank=nativeBank;
    w.pc=state.melodySourceProgram;w.preset=state.melodySourceName;w.request=state.requestedVoiceName;
    w.start=pcmSamples_;w.generation=fontMappingGeneration_;
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    w.decodeCallsAtStart=pcmDecode_.calls;
#endif
// PCM_PATH_OBSERVER_END

    if(meter.count>0) {
        const auto& prior=meter.windows[meter.count-1];
        w.priorTailsPossible=prior.priorTailsPossible || prior.level.energy>0 || prior.input.sent || prior.input.other;
    }
    const auto fp=percussionFingerprints_.find(path);if(fp!=percussionFingerprints_.end())w.fingerprint=fp->second;
    meter.current=meter.count++;
#else
    (void)channel;
#endif
}

void BassMidiPlayer::observeRolePcmNote(int channel,int key,int velocity,bool sent,
                                       const AudioPathOrigin& origin,int cc7,int cc11) {
#if YAMAHA_ROLE_PCM_METERS
    if(channel<10 || channel>15)return;
    auto& m=rolePcmMeters_[channel-10];if(m.current<0)return;
    auto& w=m.windows[m.current];
    w.input.note(key,velocity,sent,origin.sourceChannel>=0 && origin.styleBank>=0,cc7,cc11);
// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
    w.decodeCallsAtLastNote=pcmDecode_.calls;
#endif
// PCM_PATH_OBSERVER_END

    if(sent && !w.readbackAttempted) {
        w.readbackAttempted=true;
        const bool ok=BASS_MIDI_StreamGetPreset(stream_,channel,&w.actual);
        w.verified=ok && w.actual.font==w.font && w.actual.bank==w.nativeBank && w.actual.preset==w.pc;
    }
#else
    (void)channel;(void)key;(void)velocity;(void)sent;(void)origin;(void)cc7;(void)cc11;
#endif
}

void BassMidiPlayer::observeRolePcmController(int channel,DWORD event,DWORD param) {
#if YAMAHA_ROLE_PCM_METERS
    if(channel<10 || channel>15 || (event!=MIDI_EVENT_VOLUME && event!=MIDI_EVENT_EXPRESSION))return;
    auto& m=rolePcmMeters_[channel-10];if(m.current<0)return;
    auto& v=m.windows[m.current].input;
    if(v.sent || v.other)v.control(event==MIDI_EVENT_VOLUME?7:11,int(param));
#else
    (void)channel;(void)event;(void)param;
#endif
}

std::string BassMidiPlayer::rolePcmReport(
    const std::array<RolePcmMeter,6>& rolePcmMeters_,
    const std::map<std::string,std::shared_ptr<const sf2_zones::Inventory>>& melodicZoneInventories_,
    uint64_t pcmSamples_) {
    std::ostringstream out;
    out << "ROLE_PCM_SCOPE enabled=" << YAMAHA_ROLE_PCM_METERS
        << " channels=10:15 excluded=Rhythm1/Rhythm2/RIGHT/LEFT dry_after_MIDI_controllers_and_master_before_shared_FX"
        << " no_gain_write no_extra_NOTE_ON no_Yamaha_reference windowCap=8_per_channel\n";
    static const char* roles[]={"Bass","Chord1","Chord2","Pad","Phrase1","Phrase2"};
    for(int ch=10;ch<16;++ch) {
        const auto& meter=rolePcmMeters_[ch-10];
        out << "ROLE_PCM_STATUS ch=" << ch << " destinationRole=" << roles[ch-10]
            << " tapReady=" << bool(meter.dsp) << " error=" << meter.error
            << " windows=" << meter.count << " omittedWindows=" << meter.omittedWindows << '\n';
        for(int i=0;i<meter.count;++i) {
            const auto& w=meter.windows[i];const auto& level=w.level;const auto& v=w.input;
            const uint64_t end=w.closed?w.end:pcmSamples_;
            const auto span=end>=w.start?end-w.start:0;
            out << "ROLE_PCM ch=" << ch << " destinationRole=" << roles[ch-10] << " window=" << i
                << " SF2='" << w.path << "' fingerprint=" << w.fingerprint
                << " selectedRawBank=" << w.rawBank << " nativeBank=" << w.nativeBank << " rawPC=" << w.pc
                << " preset='" << w.preset << "' requestedVoice='" << w.request << "' generationAtStart=" << w.generation
                << " bindingVerified=" << w.verified << " actualFont=" << w.actual.font << " actualBank=" << w.actual.bank
                << " actualPC=" << w.actual.preset << " readbackStage=" << (w.readbackAttempted?"FIRST_REAL_NOTE":"NO_REAL_NOTE")
                << " priorPresetTailsPossible=" << w.priorTailsPossible << " startFrame=" << w.start/2 << " endFrame=" << end/2
                << " renderedSpanSamples=" << span << " callbackSamples=" << level.samples
                << " activeBlockSamples=" << level.activeSamples << " callbackBlocks=" << level.blocks << " activeBlocks=" << level.activeBlocks << " dryRmsSpan=" << level.rms(span)
                << " dryRmsCallback=" << level.rms(level.samples) << " dryRmsActiveBlocks=" << level.rms(level.activeSamples)
                << " dryRmsSpanDbFS=" << role_pcm::db(level.rms(span)) << " peak=" << level.peak
                << " peakDbFS=" << role_pcm::db(level.peak) << " clippedSamples=" << level.clipped
                << " nonfiniteSamples=" << level.nonfinite << " styleOns=" << v.sent << " rejectedOns=" << v.rejected
                << " otherOns=" << v.other << " velocityP10=" << v.percentile(10) << " velocityP50=" << v.percentile(50)
                << " velocityP90=" << v.percentile(90) << " velocityMean=" << (v.sent?double(v.sum)/v.sent:0)
                << " controllerScope=live_from_first_accepted_note CC7Range=" << (v.sent||v.other?v.cc7Min:-1) << ':' << v.cc7Max
                << " CC11Range=" << (v.sent||v.other?v.cc11Min:-1) << ':' << v.cc11Max
                << " lastKey=" << v.key << " lastVelocity=" << v.velocity
                << " actualVoiceSampleId=UNAVAILABLE not_isolated_note_response_or_wet_role_level\n";

// PCM_PATH_OBSERVER_BEGIN
#if YAMAHA_ROLE_PCM_METERS
            out << "ROLE_PCM_PATH ch=" << ch << " window=" << i
                << " decodeCallsAtStart=" << w.decodeCallsAtStart
                << " decodeCallsAtLastNote=" << w.decodeCallsAtLastNote << "\n";
#endif
// PCM_PATH_OBSERVER_END
            // Immutable inventory only, at explicit export; no SF2 I/O/scan on NOTE_ON.
            const auto inv=melodicZoneInventories_.find(w.path);
            if(inv==melodicZoneInventories_.end() || v.key<0 || !w.verified)continue;
            const auto zones=inv->second->presets.find({w.rawBank,w.pc});
            if(zones==inv->second->presets.end())continue;
            unsigned eligible=0,printed=0;
            for(const auto& z:zones->second) if(v.key>=z.keyLow && v.key<=z.keyHigh && v.velocity>=z.velLow && v.velocity<=z.velHigh) {
                ++eligible;if(printed++>=12)continue;
                out << "ROLE_LAYER ch=" << ch << " window=" << i << " last_note_only=true sampleId=" << z.sampleId
                    << " instrument='" << z.instrument << "' sample='" << z.sample << "' keyRange=" << z.keyLow << ':' << z.keyHigh
                    << " velocityRange=" << z.velLow << ':' << z.velHigh << " sampleType=" << z.sampleType << " sampleLink=" << z.sampleLink
                    << " originalKey=" << z.rootKey << " rootOverride=" << (z.instrumentGenerators.has(58)?z.instrumentGenerators.amount[58]:-1)
                    << " fixedVelocity=" << (z.instrumentGenerators.has(47)?z.instrumentGenerators.amount[47]:-1)
                    << " presetAttenuationCb=" << (z.presetGenerators.has(48)?z.presetGenerators.amount[48]:0)
                    << " instrumentAttenuationCb=" << (z.instrumentGenerators.has(48)?z.instrumentGenerators.amount[48]:0)
                    << " effectiveAttenuationCb=" << sf2_zones::effective(z,48)
                    << " attackTimecents=" << sf2_zones::effective(z,34)
                    << " metadata_eligible_not_actual_voice_sample\n";
            }
            out << "ROLE_LAYER_COUNT ch=" << ch << " window=" << i << " eligible=" << eligible
                << " omitted=" << (eligible>12?eligible-12:0) << '\n';
        }
    }
    return out.str();
}


// MEASURED_BALANCE_BEGIN
void CALLBACK BassMidiPlayer::measuredBalanceTap(HDSP,DWORD,void* buffer,DWORD length,void* user) {

// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    auto& b=*static_cast<MeasuredBalance*>(user);
    if(buffer){b.blockPre.consume(static_cast<const float*>(buffer),length/sizeof(float));b.blockGainMin=std::min(b.blockGainMin,b.ramp.current);b.blockGainMax=std::max(b.blockGainMax,b.ramp.current);}
#endif
// HEADROOM_OBSERVER_END
    if(buffer)static_cast<MeasuredBalance*>(user)->ramp.process(static_cast<float*>(buffer),length/sizeof(float));
// HEADROOM_OBSERVER_BEGIN
#if YAMAHA_PCM_HEADROOM
    b.blockGainMin=std::min(b.blockGainMin,b.ramp.current);b.blockGainMax=std::max(b.blockGainMax,b.ramp.current);
#endif
// HEADROOM_OBSERVER_END

}
void BassMidiPlayer::clearMeasuredBalance() {
    for(auto& b:measuredBalance_) {
        if(b.dsp)BASS_ChannelRemoveDSP(b.stream,b.dsp);
        if(b.stream)BASS_StreamFree(b.stream); // child only, once, after the read-only meter is detached
    }
    measuredBalance_={};
}
void BassMidiPlayer::updateMeasuredBalance(int channel) {
    if(channel<10 || channel>15 || !stream_)return;
    auto& b=measuredBalance_[channel-10];const auto& state=channels_[channel];
    measured_balance::Profile profile;std::string fingerprint;
    if(state.initialized && !state.drum && state.melodySourceProgram>=0) {
        const bool secondary=state.melodySource>0 && size_t(state.melodySource)<=secondaryMelodies_.size();
        const auto* font=secondary?&secondaryMelodies_[state.melodySource-1]:nullptr;
        const auto handle=font?font->font:melodyFont_;const auto& path=font?font->path:melodyPath_;
        const auto& banks=font?font->banks:normalizedBanks_;
        const auto fp=percussionFingerprints_.find(path);
        if(fp!=percussionFingerprints_.end()) {
            fingerprint=fp->second;profile=measured_balance::lookup(fingerprint,state.melodySourceBank,state.melodySourceProgram);
        }
        if(profile.db!=0) {
            int nativeBank=state.melodySourceBank;
            for(const auto& bank:banks)if(bank.rawBank==nativeBank){nativeBank=bank.virtualBank;break;}
            BASS_MIDI_FONT actual{};
            if(!handle || !BASS_MIDI_StreamGetPreset(stream_,channel,&actual) || actual.font!=handle ||
                actual.bank!=nativeBank || actual.preset!=state.melodySourceProgram)profile={};
        }
    }
    b.db=profile.db;b.fingerprint=fingerprint;b.evidence=profile.evidence;
    if(profile.db==0){b.ramp.set(1);return;}
    if(!b.dsp) {
        const auto existing=rolePcmMeters_[channel-10].stream;
        b.stream=existing?existing:BASS_MIDI_StreamGetChannel(stream_,channel);
        if(!b.stream){b.error=BASS_ErrorGetCode();b.db=0;return;}
        // Higher priority than the read-only meter: exports measure corrected dry PCM.
        b.dsp=BASS_ChannelSetDSPEx(b.stream,measuredBalanceTap,&b,1,0);
        if(!b.dsp){b.error=BASS_ErrorGetCode();if(!existing)BASS_StreamFree(b.stream);b.stream=0;b.db=0;return;}
    }
    b.ramp.set(measured_balance::gain(profile.db));
}

// MEASURED_BALANCE_END

// Stage1/2 STOP-only observational snapshot. No NOTE hot-path hook or production-state writes.
std::string BassMidiPlayer::shadowDrumSnapshot() const {
    struct Live {int ch,inputBank,inputPc,bank=-1,pc=-1,effective;bool verified=false;std::string source;HSOUNDFONT handle=0;};
    struct Bank {std::string path;int raw,virtualBank;HSOUNDFONT handle;};
    std::vector<Live> lives;std::vector<Bank> banks;std::vector<std::pair<std::string,HSOUNDFONT>> fonts;
    uint64_t generation;
    {
        std::lock_guard<std::mutex> lock(mutex_);generation=fontMappingGeneration_;
        if(melodyFont_)fonts.emplace_back(melodyPath_,melodyFont_);
        if(drumFont_)fonts.emplace_back(drumPath_,drumFont_);
        for(const auto& m:normalizedBanks_)banks.push_back({melodyPath_,m.rawBank,m.virtualBank,melodyFont_});
        for(const auto& f:secondaryMelodies_) {
            fonts.emplace_back(f.path,f.font);
            for(const auto& m:f.banks)banks.push_back({f.path,m.rawBank,m.virtualBank,f.font});
        }
        for(int ch=8;ch<=9;++ch) {
            Live row{ch,audioDiagnostics_[ch].requestedBank,audioDiagnostics_[ch].requestedPc,-1,-1,channels_[ch].program,false,{}};
            BASS_MIDI_FONT live{};row.verified=stream_ && BASS_MIDI_StreamGetPreset(stream_,ch,&live);
            if(row.verified) {row.bank=live.bank;row.pc=live.preset;row.handle=live.font;
                for(const auto& f:fonts)if(f.second==live.font)row.source=f.first;
            }
            lives.push_back(std::move(row));
        }
    }
    // Encode file paths without delimiter/newline ambiguity; format outside synth mutex.
    auto hex=[](const std::string& value) {const char* digits="0123456789abcdef";std::string out;
        for(unsigned char c:value){out.push_back(digits[c>>4]);out.push_back(digits[c&15]);}return out.empty()?std::string("-"):out;};
    std::ostringstream out;out<<"GEN value="<<generation<<"\n";
    for(const auto& f:fonts)out<<"FONT source="<<hex(f.first)<<" handle="<<f.second<<" readiness=UNKNOWN\n";
    for(const auto& b:banks)out<<"BANK source="<<hex(b.path)<<" raw="<<b.raw<<" virtual="<<b.virtualBank<<" handle="<<b.handle<<"\n";
    for(const auto& r:lives)out<<"LIVE ch="<<r.ch<<" inputBank="<<r.inputBank<<" inputPC="<<r.inputPc
        <<" source="<<hex(r.source)<<" handle="<<r.handle<<" bank="<<r.bank<<" pc="<<r.pc<<" verified="<<r.verified<<" effectivePC="<<r.effective<<"\n";
    return out.str();
}

// R22_SELECTION_GETTER_BEGIN
std::string BassMidiPlayer::drumChannelSelection(int channel) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if(channel<0 || channel>=16)return "UNOBSERVED";
    const auto& o=drumSelections_[channel];
    std::ostringstream out;
    out<<o.generation<<'\t'<<o.pdta<<'\t'<<o.bank<<'\t'<<o.pc<<'\t'<<o.reason;
    return out.str(); // cache identity, not an independent BASS binding readback
}
// R22_SELECTION_GETTER_END


// R23 explicit diagnostic A/B; caller admits mode changes only after STOP jobs drain.
bool BassMidiPlayer::setDrumABMode(bool resolved) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(resolved==drumABResolved_)return true;
    const bool oldMode=drumABResolved_;const auto oldBindings=drumABBindings_;
    // Stop held percussion and release tails on both possible render routes.
    bool ok=true;
    if(stream_) for(int ch=8;ch<=9;++ch)ok=send(ch,MIDI_EVENT_SOUNDOFF,0)&&ok;
    for(auto& lane:percussionLanes_) {
        if(lane.stream) {
            for(int key=0;key<128;++key)ok=BASS_MIDI_StreamEvent(lane.stream,key,MIDI_EVENT_SOUNDOFF,0)&&ok;
            BASS_StreamFree(lane.stream);
        }
        lane={};
    }
    drumABBindings_={};drumABResolved_=resolved;++drumABGeneration_;
    for(int ch=8;ch<=9;++ch){channels_[ch].initialized=false;drumSelections_[ch]={};}
    // Remove the exact maps even when returning to LEGACY.
    if(stream_)ok=applyFonts()&&ok;
    if(!ok){
        drumABResolved_=oldMode;drumABBindings_=oldBindings;
        for(auto& b:drumABBindings_)b.verified=false;
        if(stream_)applyFonts();
    }
    return ok;
}
bool BassMidiPlayer::bindDrumAB(int ch,const std::string& pdta,int bank,int pc,const std::string& section) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(!drumABResolved_ || !stream_ || ch<8 || ch>9)return false;
    auto& b=drumABBindings_[ch-8];b={};b.section=section;
    // Reviewed automatic namespaces only. A program-only UI override is not a custom identity.
    const bool col=pdta=="ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";
    const bool tyr=pdta=="16bb60deafdf426cdc7e269b6c5f434cd5b7db6e14a83f86d17f54720176217a";
    if(pc<0 || pc>127 || !((col && (bank==127 || bank==128)) || (tyr && bank==128)))return false;
    auto choose=[&](HSOUNDFONT font,const std::string& path) {
        const auto fp=pdtaFingerprints_.find(path);
        if(b.font || !font || fp==pdtaFingerprints_.end() || !fp->second.valid || fp->second.sha256!=pdta)return;
        // Reserved drum banks retain their raw phdr identity in the loaded SF2.
        // Never consult the melodic normalization table: it excludes 127/128.
        const auto inventory=melodicZoneInventories_.find(path);
        if(inventory==melodicZoneInventories_.end() || !inventory->second->valid ||
           !inventory->second->presetNames.count({bank,pc}))return;
        const char* loadedName=BASS_MIDI_FontGetPreset(font,pc,bank);
        if(loadedName && inventory->second->presetNames.at({bank,pc})==loadedName)
            b={font,bank,bank,pc,pdta,section,false};
    };
    choose(melodyFont_,melodyPath_);
    for(const auto& s:secondaryMelodies_)choose(s.font,s.path);
    if(!b.font)return false;
    auto& state=channels_[ch];state.drum=true;state.initialized=true;state.bankMsb=128;state.bankLsb=0;state.program=pc;
    state.resolverReason="r23_exact_fingerprint_namespace_coverage";
    bool ok=applyFonts();
    ok=send(ch,MIDI_EVENT_DRUMS,1)&&ok;ok=send(ch,MIDI_EVENT_BANK,128)&&ok;
    ok=send(ch,MIDI_EVENT_BANK_LSB,0)&&ok;ok=send(ch,MIDI_EVENT_PROGRAM,pc)&&ok;
    BASS_MIDI_FONT live{};
    b.verified=ok && BASS_MIDI_StreamGetPreset(stream_,ch,&live) && live.font==b.font && live.bank==b.nativeBank && live.preset==pc;
    auto& o=drumSelections_[ch];o.generation=++drumSelectionGeneration_;o.bank=bank;o.pc=pc;o.pdta=pdta;o.reason=b.verified?state.resolverReason:"r23_binding_readback_failed";
    if(b.verified)BASS_MIDI_FontLoadEx(b.font,pc,b.nativeBank,-1,BASS_MIDI_FONTLOAD_NOWAIT);
    return b.verified;
}
// Manual drum kit for Rhythm 1/2 from ANY loaded SoundFont (custom or SX Prime Drum). The reviewed-namespace gate of
// bindDrumAB is intentionally not applied: the user chose this exact preset (font id + raw bank + program), and the
// preset must exist in the loaded font. Readback verifies it; an unverified bind never sounds (same rule as R2-3).
bool BassMidiPlayer::bindDrumManual(int ch, uint64_t fontId, int bank, int pc, const std::string& section) {
    std::lock_guard<std::mutex> lock(mutex_);
    if(!drumABResolved_ || !stream_ || ch<8 || ch>9)return false;
    auto& b=drumABBindings_[ch-8];b={};b.section=section;
    if(!fontId || pc<0 || pc>127 || (bank!=127 && bank!=128))return false;
    HSOUNDFONT font=0;std::string path;
    if(melodyFont_ && fontId==melodyFontId_){font=melodyFont_;path=melodyPath_;}
    for(const auto& s:secondaryMelodies_) if(s.fontId==fontId){font=s.font;path=s.path;}
    if(!font)return false;
    bool listed=false;
    for(const auto& p:browserPresets_) if(p.fontId==fontId && p.nativeBank==bank && p.program==pc)listed=true;
    if(!listed || !BASS_MIDI_FontGetPreset(font,pc,bank))return false;
    const auto fp=pdtaFingerprints_.find(path);
    b={font,bank,bank,pc,(fp!=pdtaFingerprints_.end() && fp->second.valid)?fp->second.sha256:std::string(),section,false};
    auto& state=channels_[ch];state.drum=true;state.initialized=true;state.bankMsb=128;state.bankLsb=0;state.program=pc;
    state.resolverReason="manual_drum_kit";
    bool ok=applyFonts();
    ok=send(ch,MIDI_EVENT_DRUMS,1)&&ok;ok=send(ch,MIDI_EVENT_BANK,128)&&ok;
    ok=send(ch,MIDI_EVENT_BANK_LSB,0)&&ok;ok=send(ch,MIDI_EVENT_PROGRAM,pc)&&ok;
    BASS_MIDI_FONT live{};
    b.verified=ok && BASS_MIDI_StreamGetPreset(stream_,ch,&live) && live.font==b.font && live.bank==b.nativeBank && live.preset==pc;
    auto& o=drumSelections_[ch];o.generation=++drumSelectionGeneration_;o.bank=bank;o.pc=pc;o.pdta=b.pdta;o.reason=b.verified?state.resolverReason:"manual_drum_binding_readback_failed";
    if(b.verified)BASS_MIDI_FontLoadEx(b.font,pc,b.nativeBank,-1,BASS_MIDI_FONTLOAD_NOWAIT);
    return b.verified;
}
std::string BassMidiPlayer::drumABReport() const {
    std::lock_guard<std::mutex> lock(mutex_);std::ostringstream out;
    out<<"=== DRUM_AB R2-3 ===\nmode="<<(drumABResolved_?"RESOLVED":"LEGACY")<<" generation="<<drumABGeneration_<<" frame="<<pcmSamples_/2<<"\n";
    for(int ch=8;ch<=9;++ch){const auto& b=drumABBindings_[ch-8];BASS_MIDI_FONT live{};
        const bool got=stream_ && BASS_MIDI_StreamGetPreset(stream_,ch,&live);
        bool verified=got && b.font && live.font==b.font && live.bank==b.nativeBank && live.preset==b.pc;
        out<<"DRUM_AB_BOUND ch="<<ch<<" section='"<<b.section<<"' pdta="<<b.pdta<<" rawBank="<<b.rawBank<<" expectedNativeBank="<<b.nativeBank
            <<" expectedPC="<<b.pc<<" actualFont="<<live.font<<" actualBank="<<live.bank<<" actualPC="<<live.preset<<" readback="<<got<<" bindingVerified="<<verified
            <<" NOTE_ON="<<pcmSessionNotes_.sent[ch]<<"\n";
    }return out.str();
}

// CPV_CHANNEL_BINDING_BEGIN
// Read-only snapshot of what one channel is bound to. No MIDI, mapping or
// render state is touched. Fields are tab-separated:
// channel status source sf2 pdta rawBank program presetName reason
std::string BassMidiPlayer::channelBinding(int channel) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (channel < 0 || channel >= 16) return "";
    auto base = [](const std::string& path) { return path.substr(path.find_last_of("/\\") + 1); };
    auto pdtaOf = [&](const std::string& path) {
        const auto fp = pdtaFingerprints_.find(path);
        return fp != pdtaFingerprints_.end() && fp->second.valid ? fp->second.sha256 : std::string();
    };
    auto clean = [](std::string text) { for (auto& ch : text) if (ch == '\t' || ch == '\n' || ch == '\r') ch = ' '; return text; };
    auto row = [&](const char* status, const char* source, const std::string& path, int bank, int pc,
                   const std::string& name, const std::string& reason) {
        std::ostringstream out;
        out << channel << '\t' << status << '\t' << source << '\t' << clean(path.empty() ? std::string() : base(path)) << '\t'
            << (path.empty() ? std::string() : pdtaOf(path)) << '\t' << bank << '\t' << pc << '\t' << clean(name) << '\t' << clean(reason);
        return out.str();
    };
    const ChannelState& state = channels_[channel];
    if (channel < 4 && keyboardPresets_[channel].fontId) {
        const auto* preset = keyboardPreset(keyboardPresets_[channel]);
        if (!preset) return row("UNRESOLVED", "KEYBOARD", "", -1, -1, "", "keyboard_preset_missing");
        return row(keyboardPresets_[channel].verified ? "BOUND" : "UNVERIFIED", "KEYBOARD", preset->file,
                   preset->bank, preset->program, preset->name, "keyboard_exact_selection");
    }
    if ((channel == 8 || channel == 9) && drumABResolved_) {
        const auto& b = drumABBindings_[channel - 8];
        if (!b.font) return row("UNRESOLVED", "DRUM_R23", "", -1, -1, "", "no_verified_drum_binding");
        std::string path;
        if (b.font == melodyFont_) path = melodyPath_;
        for (const auto& s : secondaryMelodies_) if (s.font == b.font) path = s.path;
        std::string name;
        const auto inventory = melodicZoneInventories_.find(path);
        if (inventory != melodicZoneInventories_.end() && inventory->second) {
            const auto found = inventory->second->presetNames.find({b.rawBank, b.pc});
            if (found != inventory->second->presetNames.end()) name = found->second;
        }
        return row(b.verified ? "BOUND" : "UNRESOLVED", "DRUM_R23", path, b.rawBank, b.pc, name,
                   b.verified ? state.resolverReason : "r23_binding_readback_failed");
    }
    if (channelPins_[channel].active) {
        const auto& pin = channelPins_[channel];
        return row(pin.verified ? "BOUND" : "UNVERIFIED", "PINNED", pin.path, pin.bank, pin.program, pin.name, "manual_override");
    }
    if (!state.initialized) return row("UNINITIALIZED", "NONE", "", -1, -1, "", "");
    if (state.drum) {
        const auto& o = drumSelections_[channel];
        std::string name = o.name;
        return row(o.bank >= 0 ? "BOUND" : "UNRESOLVED", "DRUM_LEGACY", o.path, o.bank, o.pc, name, o.reason);
    }
    if (state.melodySourceProgram < 0) return row("UNRESOLVED", "MELODY", "", -1, -1, "", state.resolverReason);
    std::string path;
    if (state.melodySource == 0) path = melodyPath_;
    else if (state.melodySource > 0 && static_cast<size_t>(state.melodySource) <= secondaryMelodies_.size())
        path = secondaryMelodies_[state.melodySource - 1].path;
    return row("BOUND", state.melodySource == 0 ? "PRIMARY" : "SECONDARY", path, state.melodySourceBank,
               state.melodySourceProgram, state.melodySourceName, state.resolverReason);
}
// CPV_CHANNEL_BINDING_END

// CPV_SOURCE_POLICY_BEGIN
// Colombo-primary policy: earlier SF2 sources outrank later ones before score.
void BassMidiPlayer::setSourceTiered(bool tiered) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sourceTiered_ == tiered) return;
    sourceTiered_ = tiered;
    if (!stream_) return;
    refreshMelodicChannels();
    if (!applyFonts()) invalidateMelodicChannels();
}
// CPV_SOURCE_POLICY_END

// CPV_CHANNEL_PIN_BEGIN
bool BassMidiPlayer::pinChannelPreset(int channel, uint64_t fontId, int bank, int program) {
    f12::SynthLock lock(mutex_,f12::Preset);
    if (channel < 4 || channel > 15 || channel == 8 || channel == 9) return false;
    auto& pin = channelPins_[channel];
    ChannelState& state = channels_[channel];
    if (!fontId) {
        const bool was = pin.active;
        pin = {};
        if (!was) return true;
        // Forget the pinned identity so the next style request resolves normally.
        state = ChannelState{};
        LOGI("CPV PIN cleared ch=%d", channel);
        return !stream_ || applyFonts();
    }
    if (!ensureEngine()) return false;
    int source = -1; std::string path; HSOUNDFONT font = 0;
    if (melodyFont_ && fontId == melodyFontId_) { source = 0; path = melodyPath_; font = melodyFont_; }
    for (size_t i = 0; i < secondaryMelodies_.size(); ++i)
        if (secondaryMelodies_[i].fontId == fontId) { source = static_cast<int>(i) + 1; path = secondaryMelodies_[i].path; font = secondaryMelodies_[i].font; }
    if (source < 0 || bank < 0 || bank == 127 || bank == 128 || program < 0 || program > 127) return false;
    const BrowserPreset* preset = nullptr;
    for (const auto& p : browserPresets_) if (p.fontId == fontId && p.bank == bank && p.program == program) preset = &p;
    if (!preset) return false;
    const ChannelState previous = state; const ChannelPin previousPin = pin;
    state = ChannelState{};
    state.initialized = true; state.drum = false; state.bankMsb = 0; state.bankLsb = 0; state.program = program;
    state.melodySource = source; state.melodySourceBank = bank; state.melodySourceProgram = program;
    state.melodySourceName = preset->name; state.requestedVoiceName = preset->name; state.resolverReason = "manual_override";
    pin = ChannelPin{fontId, bank, program, preset->name, path, true, false};
    if (!applyFonts()) {
        state = previous; pin = previousPin;
        if (!applyFonts()) invalidateMelodicChannels();
        return false;
    }
    bool ok = send(channel, MIDI_EVENT_DRUMS, 0);
    ok = send(channel, MIDI_EVENT_BANK, 0) && ok;
    ok = send(channel, MIDI_EVENT_BANK_LSB, 0) && ok;
    ok = send(channel, MIDI_EVENT_PROGRAM, static_cast<DWORD>(program)) && ok;
    BASS_MIDI_FONT live{};
    pin.verified = ok && BASS_MIDI_StreamGetPreset(stream_, channel, &live) && live.font == font && live.preset == program;
    LOGI("CPV PIN ch=%d sf2='%s' bank=%d prog=%d name='%s' verified=%d", channel, path.c_str(), bank, program,
         preset->name.c_str(), pin.verified ? 1 : 0);
    preloadCurrentPreset(channel);
    updateRolePcmMeter(channel);
    return pin.verified;
}

std::string BassMidiPlayer::fontPdta(uint64_t fontId) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::string path;
    if (fontId && fontId == melodyFontId_) path = melodyPath_;
    if (fontId && fontId == drumFontId_) path = drumPath_;
    for (const auto& s : secondaryMelodies_) if (fontId && s.fontId == fontId) path = s.path;
    const auto fp = pdtaFingerprints_.find(path);
    return fp != pdtaFingerprints_.end() && fp->second.valid ? fp->second.sha256 : std::string();
}
// CPV_CHANNEL_PIN_END

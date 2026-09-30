#pragma once
// Host test doubles record API requests. These are NOT real SDK/ABI definitions.
#include "bass.h"
#include <map>
#include <string>
#include <vector>
constexpr DWORD BASS_CONFIG_MIDI_COMPACT=20, BASS_CONFIG_MIDI_VOICES=21;
constexpr DWORD BASS_ATTRIB_MIDI_PPQN=30, BASS_ATTRIB_MIDI_SRC=31,
    BASS_ATTRIB_MIDI_VOICES=32, BASS_ATTRIB_MIDI_VOL=33;
constexpr DWORD BASS_MIDI_NOTEOFF1=64, BASS_MIDI_FONT_EX2=0x10000, BASS_MIDI_FONTLOAD_NOWAIT=128;
enum { MIDI_EVENT_NOTE=1, MIDI_EVENT_DRUMS, MIDI_EVENT_BANK, MIDI_EVENT_BANK_LSB,
    MIDI_EVENT_PROGRAM, MIDI_EVENT_NOTESOFF, MIDI_EVENT_VOLUME, MIDI_EVENT_PAN,
    MIDI_EVENT_EXPRESSION, MIDI_EVENT_REVERB, MIDI_EVENT_CHORUS, MIDI_EVENT_RELEASE };
struct BASS_MIDI_FONTEX2 {
    HSOUNDFONT font; int spreset, sbank, dpreset, dbank, dbanklsb, minchan, numchan;
};
struct BASS_MIDI_FONTINFO { DWORD presets=0; uint64_t samsize=0, samload=0; DWORD samtype=0; const char* name="mock"; };
namespace mock_bass {
inline std::map<HSOUNDFONT, std::string> fonts;
inline std::vector<BASS_MIDI_FONTEX2> mappings;
inline HSOUNDFONT nextFont=100, preloadedFont=0;
inline int preloadProgram=-1, preloadBank=-1, noteOns=0;
inline DWORD preloadFlags=0, streamFlags=0;
inline bool failMapping=false;
}
inline HSTREAM BASS_MIDI_StreamCreate(int, DWORD flags, int) { mock_bass::streamFlags=flags; return 1; }
inline bool BASS_MIDI_StreamEvent(HSTREAM, DWORD, DWORD event, DWORD value) {
    if (event==MIDI_EVENT_NOTE && value>>8) ++mock_bass::noteOns;
    return true;
}
inline HSOUNDFONT BASS_MIDI_FontInit(const char* path, DWORD) {
    if (std::string(path).find("fail")!=std::string::npos) return 0;
    const auto handle=mock_bass::nextFont++; mock_bass::fonts[handle]=path; return handle;
}
inline bool BASS_MIDI_FontFree(HSOUNDFONT h) { mock_bass::fonts.erase(h); return true; }
inline bool BASS_MIDI_FontSetVolume(HSOUNDFONT, float) { return true; }
inline bool BASS_MIDI_FontGetInfo(HSOUNDFONT, BASS_MIDI_FONTINFO*) { return true; }
inline bool BASS_MIDI_FontGetPresets(HSOUNDFONT, DWORD*) { return false; }
inline const char* BASS_MIDI_FontGetPreset(HSOUNDFONT, int, int) { return nullptr; }
inline bool BASS_MIDI_FontLoadEx(HSOUNDFONT h, int pc, int bank, int, DWORD flags) {
    mock_bass::preloadedFont=h; mock_bass::preloadProgram=pc; mock_bass::preloadBank=bank; mock_bass::preloadFlags=flags;
    return true;
}
inline bool BASS_MIDI_StreamSetFonts(HSTREAM, const BASS_MIDI_FONTEX2* maps, DWORD count) {
    if (mock_bass::failMapping || !(count & BASS_MIDI_FONT_EX2)) return false;
    mock_bass::mappings.assign(maps, maps+(count & ~BASS_MIDI_FONT_EX2)); return true;
}

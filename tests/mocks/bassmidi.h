#pragma once
// Host test doubles record API requests. These are NOT real SDK/ABI definitions.
#include "bass.h"
#include <map>
#include <string>
#include <vector>
#include <fstream>
#include <tuple>
constexpr DWORD BASS_CONFIG_MIDI_COMPACT=20, BASS_CONFIG_MIDI_VOICES=21;
constexpr DWORD BASS_ATTRIB_MIDI_PPQN=30, BASS_ATTRIB_MIDI_SRC=31,
    BASS_ATTRIB_MIDI_VOICES=32, BASS_ATTRIB_MIDI_VOL=33;
constexpr DWORD BASS_MIDI_NOTEOFF1=64, BASS_MIDI_FONT_EX2=0x10000, BASS_MIDI_FONTLOAD_NOWAIT=128;
enum { MIDI_EVENT_NOTE=1, MIDI_EVENT_DRUMS, MIDI_EVENT_BANK, MIDI_EVENT_BANK_LSB,
    MIDI_EVENT_PROGRAM, MIDI_EVENT_NOTESOFF, MIDI_EVENT_VOLUME, MIDI_EVENT_PAN,
    MIDI_EVENT_EXPRESSION, MIDI_EVENT_REVERB, MIDI_EVENT_CHORUS, MIDI_EVENT_RELEASE, MIDI_EVENT_SUSTAIN };
struct BASS_MIDI_FONTEX2 {
    HSOUNDFONT font; int spreset, sbank, dpreset, dbank, dbanklsb, minchan, numchan;
};
struct BASS_MIDI_FONT { HSOUNDFONT font=0; int preset=-1, bank=-1; };
struct BASS_MIDI_FONTINFO { DWORD presets=0; uint64_t samsize=0, samload=0; DWORD samtype=0; const char* name="mock"; };
namespace mock_bass {
inline std::map<HSOUNDFONT, std::string> fonts;
inline std::vector<BASS_MIDI_FONTEX2> mappings;
inline HSOUNDFONT nextFont=100, preloadedFont=0;
inline int preloadProgram=-1, preloadBank=-1, noteOns=0;
inline DWORD preloadFlags=0, streamFlags=0;
inline HSTREAM nextStream=1;
inline std::map<HSTREAM,std::vector<BASS_MIDI_FONTEX2>> otherMappings;
inline std::map<std::tuple<HSTREAM,int,DWORD>,DWORD> otherEvents;
inline int auditionNoteOns=0;
inline bool failMapping=false, failNote=false, mismatchPreset=false;
inline int forcedLivePc=-1;
inline bool changePresetOnNote=false, failProgram=false;
inline std::vector<std::tuple<HSTREAM,DWORD,DWORD,DWORD>> history;
inline std::map<std::pair<int,DWORD>,DWORD> events;
inline std::map<std::tuple<HSOUNDFONT,int,int>,std::string> presets;
}
inline HSTREAM BASS_MIDI_StreamCreate(int, DWORD flags, int) { mock_bass::streamFlags=flags; return mock_bass::nextStream++; }
inline bool BASS_MIDI_StreamEvent(HSTREAM stream, DWORD chan, DWORD event, DWORD value) {
    mock_bass::history.emplace_back(stream,chan,event,value);
    if (event==MIDI_EVENT_PROGRAM && mock_bass::failProgram) return false;
    if (event==MIDI_EVENT_NOTE && mock_bass::failNote) return false;
    if (stream==1 && event==MIDI_EVENT_NOTE && value>>8 && mock_bass::changePresetOnNote) mock_bass::forcedLivePc=0;
    if(stream!=1) {
        if(event==MIDI_EVENT_NOTE && value>>8) ++mock_bass::auditionNoteOns;
        mock_bass::otherEvents[{stream,chan,event}]=value; return true;
    }
    if (event==MIDI_EVENT_NOTE && value>>8) ++mock_bass::noteOns;
    mock_bass::events[{chan,event}]=value;
    return true;
}
inline HSOUNDFONT BASS_MIDI_FontInit(const char* path, DWORD) {
    if (std::string(path).find("fail")!=std::string::npos) return 0;
    const auto handle=mock_bass::nextFont++; mock_bass::fonts[handle]=path;
    std::ifstream file(path,std::ios::binary);
    std::vector<unsigned char> data((std::istreambuf_iterator<char>(file)),std::istreambuf_iterator<char>());
    if (data.size()>=108) {
        const int bank=data[54]|(data[55]<<8), pc=data[52]|(data[53]<<8);
        std::string name(reinterpret_cast<const char*>(data.data()+32),20);
        name.resize(name.find('\0')==std::string::npos?20:name.find('\0'));
        mock_bass::presets[{handle,bank,pc}]=name;
    }
    return handle;
}
inline bool BASS_MIDI_FontFree(HSOUNDFONT h) { mock_bass::fonts.erase(h); return true; }
inline bool BASS_MIDI_FontSetVolume(HSOUNDFONT, float) { return true; }
inline bool BASS_MIDI_FontGetInfo(HSOUNDFONT, BASS_MIDI_FONTINFO*) { return true; }
inline bool BASS_MIDI_FontGetPresets(HSOUNDFONT, DWORD*) { return false; }
inline const char* BASS_MIDI_FontGetPreset(HSOUNDFONT h, int pc, int bank) {
    const auto it=mock_bass::presets.find({h,bank,pc});
    return it==mock_bass::presets.end()?nullptr:it->second.c_str();
}
inline DWORD BASS_MIDI_StreamGetEvent(HSTREAM stream, DWORD ch, DWORD event) {
    if(stream!=1) {
        const auto it=mock_bass::otherEvents.find({stream,ch,event});
        return it!=mock_bass::otherEvents.end()?it->second:((event==MIDI_EVENT_VOLUME||event==MIDI_EVENT_EXPRESSION)?127:0);
    }
    const auto it=mock_bass::events.find({ch,event});
    return it!=mock_bass::events.end()?it->second:((event==MIDI_EVENT_VOLUME||event==MIDI_EVENT_EXPRESSION)?127:0);
}
inline bool BASS_MIDI_StreamGetPreset(HSTREAM stream, DWORD ch, BASS_MIDI_FONT* live) {
    const int pc=BASS_MIDI_StreamGetEvent(stream,ch,MIDI_EVENT_PROGRAM);
    const int msb=BASS_MIDI_StreamGetEvent(stream,ch,MIDI_EVENT_BANK);
    const int lsb=BASS_MIDI_StreamGetEvent(stream,ch,MIDI_EVENT_BANK_LSB);
    for (const auto& m:(stream==1?mock_bass::mappings:mock_bass::otherMappings[stream])) {
        if (int(ch)<m.minchan||int(ch)>=m.minchan+m.numchan||m.dbank!=msb||m.dbanklsb!=lsb||
            (m.dpreset!=-1&&m.dpreset!=pc)) continue;
        const int sourcePc=m.spreset==-1?pc:m.spreset;
        if (!BASS_MIDI_FontGetPreset(m.font,sourcePc,m.sbank)) continue;
        *live={m.font,sourcePc,m.sbank};
        if (mock_bass::mismatchPreset) live->preset=127;
        if (stream==1 && mock_bass::forcedLivePc>=0) live->preset=mock_bass::forcedLivePc;
        return true;
    }
    return false;
}
inline bool BASS_MIDI_FontLoadEx(HSOUNDFONT h, int pc, int bank, int, DWORD flags) {
    mock_bass::preloadedFont=h; mock_bass::preloadProgram=pc; mock_bass::preloadBank=bank; mock_bass::preloadFlags=flags;
    return true;
}
inline bool BASS_MIDI_StreamSetFonts(HSTREAM stream, const BASS_MIDI_FONTEX2* maps, DWORD count) {
    if (mock_bass::failMapping || !(count & BASS_MIDI_FONT_EX2)) return false;
    auto& target=stream==1?mock_bass::mappings:mock_bass::otherMappings[stream];
    target.assign(maps,maps+(count & ~BASS_MIDI_FONT_EX2)); return true;
}


inline float BASS_MIDI_FontGetVolume(HSOUNDFONT) { return 0.9f; }

#pragma once
#include <cstdint>
using DWORD = uint32_t;
using HSTREAM = DWORD;
using HSOUNDFONT = DWORD;
constexpr DWORD BASS_SAMPLE_FLOAT=1, BASS_STREAM_DECODE=2, BASS_DATA_FLOAT=4;
constexpr DWORD BASS_CONFIG_UPDATEPERIOD=10, BASS_ATTRIB_BUFFER=11;
#define LOWORD(x) ((x) & 0xffff)
#define HIWORD(x) (((x) >> 16) & 0xffff)
inline bool BASS_Init(int, int, DWORD, void*, void*) { return true; }
inline bool BASS_SetConfig(DWORD, DWORD) { return true; }
inline bool BASS_ChannelSetAttribute(HSTREAM, DWORD, float) { return true; }
inline bool BASS_StreamFree(HSTREAM) { return true; }
inline bool BASS_Free() { return true; }
inline int BASS_ErrorGetCode() { return 0; }
inline DWORD BASS_ChannelGetData(HSTREAM, void*, DWORD) { return 0; }

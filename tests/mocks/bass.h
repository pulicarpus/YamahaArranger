#pragma once
#include <cstdint>
#include <vector>
#include <algorithm>
using DWORD = uint32_t;
using HSTREAM = DWORD;
using HDSP = DWORD;
#ifndef CALLBACK
#define CALLBACK
#endif
using DSPPROC=void CALLBACK(HDSP,DWORD,void*,DWORD,void*);
constexpr DWORD BASS_DSP_READONLY=1;
using HSOUNDFONT = DWORD;
constexpr DWORD BASS_SAMPLE_FLOAT=1, BASS_STREAM_DECODE=2, BASS_DATA_FLOAT=4;
constexpr DWORD BASS_CONFIG_UPDATEPERIOD=10, BASS_ATTRIB_BUFFER=11;
#define LOWORD(x) ((x) & 0xffff)
#define HIWORD(x) (((x) >> 16) & 0xffff)
inline bool BASS_Init(int, int, DWORD, void*, void*) { return true; }
inline bool BASS_SetConfig(DWORD, DWORD) { return true; }
inline bool BASS_ChannelSetAttribute(HSTREAM, DWORD, float) { return true; }
namespace mock_bass { inline std::vector<HSTREAM> freedStreams; inline bool failDecode=false; }
inline bool BASS_StreamFree(HSTREAM stream) { mock_bass::freedStreams.push_back(stream); return true; }
inline bool BASS_Free() { return true; }
inline int BASS_ErrorGetCode() { return 0; }
inline DWORD BASS_ChannelGetData(HSTREAM stream, void* data, DWORD bytes) {
    if(mock_bass::failDecode) return static_cast<DWORD>(-1);
    if(stream==1) return 0;
    bytes &= ~BASS_DATA_FLOAT;
    std::fill_n(static_cast<float*>(data),bytes/sizeof(float),0.0f); return bytes;
}


inline bool BASS_ChannelGetAttribute(HSTREAM, DWORD, float* value) { *value=1.0f; return true; }

namespace mock_bass {
struct Dsp {HSTREAM stream;DSPPROC* callback;void* user;DWORD flags;};
inline std::vector<Dsp> dsps;
inline bool failDsp=false,failRoleChannel=false;
inline std::vector<std::pair<HSTREAM,DWORD>> roleChannelRequests;
}
inline HDSP BASS_ChannelSetDSPEx(HSTREAM h,DSPPROC* proc,void* user,int,DWORD flags) {
    if(mock_bass::failDsp)return 0;
    mock_bass::dsps.push_back({h,proc,user,flags});return mock_bass::dsps.size();
}
inline bool BASS_ChannelRemoveDSP(HSTREAM,HDSP id) {
    if(id && id<=mock_bass::dsps.size())mock_bass::dsps[id-1].callback=nullptr;
    return true;
}

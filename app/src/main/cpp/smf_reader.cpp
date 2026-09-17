#include "smf_reader.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "SmfReader"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
uint32_t readU32BE(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
uint16_t readU16BE(const uint8_t* p) {
    return (uint16_t(p[0]) << 8) | p[1];
}
}

uint32_t SmfReader::readVarLen(const uint8_t* data, size_t size, size_t& pos) {
    uint32_t value = 0;
    for (int i = 0; i < 4 && pos < size; ++i) {
        uint8_t b = data[pos++];
        value = (value << 7) | (b & 0x7F);
        if (!(b & 0x80)) break;
    }
    return value;
}

bool SmfReader::parse(const uint8_t* data, size_t size) {
    if (size < 14 || std::memcmp(data, "MThd", 4) != 0) {
        LOGE("Not an SMF (missing MThd header)");
        return false;
    }

    uint32_t headerLen = readU32BE(data + 4);
    format_ = readU16BE(data + 8);
    uint16_t numTracks = readU16BE(data + 10);
    ppq_ = readU16BE(data + 12);
    defaultTempoBpm_ = 120.0;
    if (ppq_ & 0x8000) {
        LOGE("SMPTE time division not supported, defaulting ppq=480");
        ppq_ = 480;
    }

    size_t pos = 8 + headerLen;
    tracks_.clear();
    tracks_.reserve(numTracks);

    bool foundTempo = false;

    for (int t = 0; t < numTracks && pos + 8 <= size; ++t) {
        if (std::memcmp(data + pos, "MTrk", 4) != 0) {
            LOGE("Expected MTrk at track %d, found something else", t);
            break;
        }
        uint32_t trackLen = readU32BE(data + pos + 4);
        size_t trackStart = pos + 8;
        size_t trackEnd = trackStart + trackLen;
        if (trackEnd > size) break;

        MidiTrack track;
        uint32_t absTick = 0;
        uint8_t runningStatus = 0;
        size_t p = trackStart;

        while (p < trackEnd) {
            uint32_t delta = readVarLen(data, trackEnd, p);
            absTick += delta;
            if (p >= trackEnd) break;

            uint8_t statusByte = data[p];
            if (statusByte < 0x80) {
                statusByte = runningStatus;
            } else {
                p++;
                runningStatus = statusByte;
            }

            MidiEvent ev{};
            ev.tick = absTick;
            ev.status = statusByte;
            if (statusByte < 0xF0) {
                ev.channel = statusByte & 0x0F;
            }

            if (statusByte == 0xFF) {
                if (p >= trackEnd) break;
                ev.metaType = data[p++];
                uint32_t len = readVarLen(data, trackEnd, p);
                if (p + len > trackEnd) break;
                if (ev.metaType == 0x03 && len > 0) {
                    track.name.assign(reinterpret_cast<const char*>(data + p), len);
                }
                if (ev.metaType == 0x51 && len == 3 && !foundTempo) {
                    const uint32_t usPerQuarter =
                        (uint32_t(data[p]) << 16) |
                        (uint32_t(data[p + 1]) << 8) |
                        uint32_t(data[p + 2]);
                    if (usPerQuarter > 0) {
                        const double bpm = 60000000.0 / static_cast<double>(usPerQuarter);
                        if (std::isfinite(bpm) && bpm >= 1.0 && bpm <= 999.0) {
                            defaultTempoBpm_ = bpm;
                            foundTempo = true;
                        }
                    }
                }
                ev.metaOrSysexData.assign(data + p, data + p + len);
                p += len;
            } else if (statusByte == 0xF0 || statusByte == 0xF7) {
                uint32_t len = readVarLen(data, trackEnd, p);
                if (p + len > trackEnd) break;
                ev.metaOrSysexData.assign(data + p, data + p + len);
                p += len;
            } else {
                uint8_t hi = statusByte & 0xF0;
                int dataBytes = (hi == 0xC0 || hi == 0xD0) ? 1 : 2;
                if (p >= trackEnd) break;
                ev.data1 = data[p++];
                if (dataBytes == 2) {
                    if (p >= trackEnd) break;
                    ev.data2 = data[p++];
                }
            }
            track.events.push_back(ev);
        }

        tracks_.push_back(std::move(track));
        pos = trackEnd;
    }

    return !tracks_.empty();
}

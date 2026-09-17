#pragma once
#include <cstdint>
#include <string>
#include <vector>

// Minimal Standard MIDI File (Format 0 & 1) reader. A Yamaha .sty file is
// (mostly) a Format 0/1 SMF with extra non-standard meta/sysex chunks
// (CASM, OTS, etc. — see style_parser.h) layered in, so this reader is the
// foundation both the plain-SMF song player and the .sty parser build on.
struct MidiEvent {
    uint32_t tick;        // absolute tick, resolved against `ppq`
    uint8_t status;       // 0x80 note-off, 0x90 note-on, 0xB0 CC, etc.
    // FIX: sebelumnya field ini tidak ada, jadi style_parser.cpp tidak
    // bisa mengelompokkan event per channel MIDI (cuma bisa per track) —
    // padahal identitas instrumen part (Bass/Chord/Pad/dst) di style
    // Yamaha ditentukan oleh CHANNEL, bukan track. 0-15 (0-based, sesuai
    // 4 bit rendah status byte) supaya konsisten dengan cara StyleSequencer
    // Anda sekarang mengecek drum (`ev.channel == 9`).
    uint8_t channel = 0;  // 0-15 untuk channel-voice events (status < 0xF0)
    uint8_t data1;
    uint8_t data2;
    std::vector<uint8_t> metaOrSysexData; // populated for 0xFF / 0xF0 events
    uint8_t metaType = 0; // valid when status == 0xFF
};

struct MidiTrack {
    std::string name;
    std::vector<MidiEvent> events; // sorted by tick
};

class SmfReader {
public:
    // Parses raw SMF bytes (already extracted from the .sty container, or
    // a plain .mid file). Returns false on malformed header/chunk data.
    bool parse(const uint8_t* data, size_t size);

    int ppq() const { return ppq_; }
    int format() const { return format_; }
    // First tempo meta-event (0xFF 0x51) in the SMF, expressed in BPM.
    // Yamaha styles conventionally store their default tempo at tick 0.
    double defaultTempoBpm() const { return defaultTempoBpm_; }
    const std::vector<MidiTrack>& tracks() const { return tracks_; }

private:
    int format_ = 0;
    int ppq_ = 480;
    double defaultTempoBpm_ = 120.0;
    std::vector<MidiTrack> tracks_;

    static uint32_t readVarLen(const uint8_t* data, size_t size, size_t& pos);
};

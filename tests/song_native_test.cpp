// MIDI SONG V1A native contract (host, mock BASS): the Song stream is separate from the keyboard/Style stream.
#include "bassmidi_player.h"
#include <iostream>
#include <stdexcept>
#include <vector>

static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const std::string d = argv[1];
    BassMidiPlayer p;
    const uint8_t on[] = {0x90, 60, 100};
    check(p.loadMelody(d + "/colombo.sf2"), "load melody");
    const auto mainEvents = mock_bass::events;                 // main-stream (stream 1) state after load
    const size_t hist0 = mock_bass::history.size();
    check(p.songEvents(on, 3), "song NOTE ON accepted");
    check(!mock_bass::rawEvents.empty(), "raw batch sent");
    const HSTREAM song = mock_bass::rawEvents.back().first;
    check(song != 1, "Song events never go to the keyboard/Style stream");
    check(mock_bass::rawEvents.back().second == std::vector<uint8_t>(on, on + 3), "bytes passed unchanged");
    auto& maps = mock_bass::otherMappings[song];
    check(maps.size() == 1 && maps[0].minchan == 0 && maps[0].numchan == 16 && maps[0].spreset == -1, "melody font mapped on all 16 Song channels");
    const uint8_t sysex[] = {0xF0, 0x7E, 0x7F, 0x09, 0x01, 0xF7};
    const size_t raw0 = mock_bass::rawEvents.size();
    check(!p.songEvents(sysex, sizeof sysex) && mock_bass::rawEvents.size() == raw0, "SysEx refused, nothing sent");
    const uint8_t sneaky[] = {0x90, 61, 90, 0xFF};
    check(!p.songEvents(sneaky, sizeof sneaky) && mock_bass::rawEvents.size() == raw0, "any system byte refuses the batch");
    p.songReset();
    int songResets = 0, mainTouched = 0;
    for (size_t i = hist0; i < mock_bass::history.size(); ++i) {
        const auto& h = mock_bass::history[i];
        if (std::get<0>(h) == song) ++songResets; else if (std::get<0>(h) == 1) ++mainTouched;
    }
    check(songResets == 32, "NOTESOFF + sustain 0 on the 16 Song channels");
    check(mainTouched == 0, "reset never touches the keyboard/Style stream");
    check(mock_bass::events == mainEvents, "main stream state unchanged by the Song");
    std::vector<float> buf(512 * 2, 0.0f);
    p.songSetActive(true); p.render(buf.data(), 512);
    p.songSetActive(false); p.render(buf.data(), 512);
    check(p.loadDrum(d + "/prime_drum.sf2"), "load drum font");
    check(p.songEvents(on, 3), "song event after font change");
    check(maps.size() == 2 && maps[0].font != maps[1].font, "drum font first, then melody, after the change");
    std::cout << "SONG NATIVE PASS song_stream=" << song << " reset_events=" << songResets << " main_stream_touched=" << mainTouched << "\n";
    return 0;
}

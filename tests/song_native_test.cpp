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
    // V1A.1: setup SysEx has its own entry point, Song stream only, whitelisted families only
    const uint8_t xgOn[] = {0xF0, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7};
    const uint8_t xgVib[] = {0xF0, 0x43, 0x10, 0x4C, 0x08, 0x00, 0x20, 0x00, 0xF7};
    const uint8_t gs[] = {0xF0, 0x41, 0x10, 0x42, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41, 0xF7};
    for (const auto& m : {std::vector<uint8_t>(sysex, sysex + 6), std::vector<uint8_t>(xgOn, xgOn + 9), std::vector<uint8_t>(xgVib, xgVib + 9), std::vector<uint8_t>(gs, gs + 11)}) {
        const size_t n0 = mock_bass::rawEvents.size();
        check(p.songSysEx(m.data(), m.size()), "setup SysEx accepted");
        check(mock_bass::rawEvents.size() == n0 + 1 && mock_bass::rawEvents.back().first == song && mock_bass::rawEvents.back().second == m, "setup SysEx to the Song stream only, unchanged");
    }
    const uint8_t styleCtl[] = {0xF0, 0x43, 0x7E, 0x00, 0x08, 0x7F, 0xF7};      // Yamaha style section control: not a synth setup
    const uint8_t privateMsg[] = {0xF0, 0x43, 0x73, 0x01, 0x51, 0x05, 0xF7};    // Yamaha instrument-private
    const uint8_t other[] = {0xF0, 0x00, 0x20, 0x33, 0x01, 0xF7};               // other manufacturer
    const uint8_t open[] = {0xF0, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00};    // no F7 (split packet)
    const uint8_t badData[] = {0xF0, 0x43, 0x10, 0x4C, 0x08, 0x90, 0x20, 0x00, 0xF7};
    const uint8_t chan[] = {0x90, 60, 100};
    const size_t r1 = mock_bass::rawEvents.size();
    check(!p.songSysEx(styleCtl, sizeof styleCtl) && !p.songSysEx(privateMsg, sizeof privateMsg) && !p.songSysEx(other, sizeof other) &&
          !p.songSysEx(open, sizeof open) && !p.songSysEx(badData, sizeof badData) && !p.songSysEx(chan, sizeof chan) &&
          mock_bass::rawEvents.size() == r1, "non-setup / malformed SysEx refused, nothing sent");
    check(!p.songEvents(xgOn, sizeof xgOn) && mock_bass::rawEvents.size() == r1, "the channel entry point still refuses SysEx");
    for (const auto& e : mock_bass::rawEvents) check(e.first == song, "nothing raw ever reaches the keyboard/Style stream");
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
    // V1A.2 SONG MIXER: a loaded SF2 preset pinned on one Song channel; the keyboard/Style stream and its mappings never change
    {
        struct P { std::string role, file, name; unsigned long long id; int bank, pc; };
        std::vector<P> list; const std::string raw = p.presetList();
        size_t start = 0;
        while (start < raw.size()) {
            const size_t end = raw.find('\x1e', start); const std::string rec = raw.substr(start, end - start); start = end == std::string::npos ? raw.size() : end + 1;
            std::vector<std::string> f; size_t a = 0; for (size_t b; (b = rec.find('\x1f', a)) != std::string::npos; a = b + 1) f.push_back(rec.substr(a, b - a)); f.push_back(rec.substr(a));
            if (f.size() == 6) list.push_back({f[0], f[2], f[5], std::stoull(f[1]), std::stoi(f[3]), std::stoi(f[4])});
        }
        const P* mel = nullptr; const P* kit = nullptr;
        for (const auto& x : list) { if (!mel && x.bank < 127 && x.pc != 0) mel = &x; if (!kit && (x.bank == 127 || x.bank == 128)) kit = &x; }
        check(mel && kit, "fixture fonts expose a melodic preset and a drum kit");
        const auto mainMaps = mock_bass::mappings; const auto mainEv = mock_bass::events;
        const int r = p.songSetChannelVoice(2, mel->id, mel->bank, mel->pc);
        check(r == 1, "melodic pin verified by readback on Song CH3");
        auto& sm = mock_bass::otherMappings[song];
        check(!sm.empty() && sm[0].minchan == 2 && sm[0].numchan == 1 && sm[0].spreset == mel->pc && sm[0].dpreset == 0 && sm[0].dbank == 0,
              "pin = first mapping, one channel, preset -> Bank 0 / Program 0");
        const int k = p.songSetChannelVoice(9, kit->id, kit->bank, kit->pc);
        check(k == 0 || k == 1, "drum kit pin mapped on Song CH10");
        bool kitMap = false; for (const auto& m : sm) kitMap |= m.minchan == 9 && m.numchan == 1 && m.dbank == 128 && m.spreset == kit->pc;
        check(kitMap, "kit pin -> drum bank 128 / Program 0 on that channel only");
        const size_t before = sm.size();
        check(p.songSetChannelVoice(4, mel->id, 99, 99) == -1 && sm.size() == before, "unknown preset refused, nothing changed");
        check(p.songSetChannelVoice(16, mel->id, mel->bank, mel->pc) == -1, "channel out of range refused");
        check(p.songChannelReport().find("SONGCH ch=3 sf2=") != std::string::npos && p.songChannelReport().find("pin=USER") != std::string::npos, "report shows the live pin");
        check(p.songSetChannelVoice(2, 0, 0, 0) == 2, "clear");
        bool still = false; for (const auto& m : sm) still |= m.minchan == 2 && m.numchan == 1;
        check(!still, "cleared pin removed from the Song map");
        check(mock_bass::mappings.size() == mainMaps.size() && mock_bass::events == mainEv, "keyboard/Style stream mappings and state unchanged by Song pins");
        std::cout << "SONG MIXER NATIVE PASS melodic=" << mel->file << ":" << mel->bank << ":" << mel->pc << " kit=" << kit->file << ":" << kit->bank << ":" << kit->pc << " kitBind=" << k << "\n";
    }
    std::cout << "SONG NATIVE PASS song_stream=" << song << " reset_events=" << songResets << " main_stream_touched=" << mainTouched << " setup_sysex=song_stream_only\n";
    return 0;
}

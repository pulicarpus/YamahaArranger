#include "bassmidi_player.h"
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <vector>

static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }

static std::string field(const std::string& line, const std::string& key) {
    const auto at = line.find(" " + key + "=");
    check(at != std::string::npos, "missing field " + key + " in: " + line);
    auto i = at + key.size() + 2;
    if (line[i] == '\'') { const auto e = line.find('\'', i + 1); return line.substr(i + 1, e - i - 1); }
    const auto e = line.find(' ', i);
    return line.substr(i, e == std::string::npos ? std::string::npos : e - i);
}
static std::string row(const BassMidiPlayer& p, int part) {
    std::stringstream in(p.rightVoiceDiagnostic()); std::string l; int i = 0;
    while (std::getline(in, l)) if (i++ == part) return l;
    throw std::runtime_error("row missing");
}
struct Preset { uint64_t id = 0; int bank = -1, pc = -1; std::string name; };
static Preset firstMelodic(BassMidiPlayer& p, const std::string& fileSubstr) {
    std::stringstream list(p.presetList()); std::string rec;
    while (std::getline(list, rec, '\x1e')) {
        std::vector<std::string> f; std::stringstream in(rec); std::string x;
        while (std::getline(in, x, '\x1f')) f.push_back(x);
        if (f.size() < 6) continue;
        const int bank = std::stoi(f[3]);
        if (f[2].find(fileSubstr) != std::string::npos && bank != 127 && bank != 128)
            return {std::stoull(f[1]), bank, std::stoi(f[4]), f[5]};
    }
    throw std::runtime_error("no melodic preset in " + fileSubstr);
}

// argv[1]: directory holding colombo.sf2 / tyros4.sf2 (with INFO/INAM) and bare.sf2 (no INFO)
int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const std::string d = argv[1];
    BassMidiPlayer p;
    p.setSourceTiered(true);
    check(p.loadMelody(d + "/colombo.sf2"), "load Colombo");
    check(p.loadMelodyFallback(d + "/tyros4.sf2"), "attach Tyros");
    const auto col = firstMelodic(p, "colombo"), tyr = firstMelodic(p, "tyros");

    // 1. Exact selections through the normal keyboard path, then backend readback.
    check(p.setKeyboardPreset(0, col.id, col.bank, col.pc), "RIGHT1 Colombo verified");
    check(p.setKeyboardPreset(1, tyr.id, tyr.bank, tyr.pc), "RIGHT2 Tyros verified");
    auto r1 = row(p, 0), r2 = row(p, 1), r3 = row(p, 2);
    std::cout << r1 << "\n" << r2 << "\n" << r3 << "\n";
    check(field(r1, "status") == "MATCH" && field(r1, "actualLabel") == "COLOMBO" && field(r1, "reqLabel") == "COLOMBO", "RIGHT1 MATCH Colombo");
    check(field(r2, "status") == "MATCH" && field(r2, "actualLabel") == "TYROS" && field(r2, "reqLabel") == "TYROS", "RIGHT2 MATCH Tyros");
    check(field(r1, "fileInam") == "Colombo Test Font" && field(r1, "bassFontName") == "Colombo Test Font", "INAM read independently");
    check(field(r3, "status") == "UNVERIFIED" && field(r3, "reason") == "no_exact_request_for_this_part", "no request is never MATCH");
    check(field(r1, "cc71") == "-1" && field(r1, "cc74") == "-1", "CC71/CC74 reported as not queryable in this build, not invented");

    // 2. Changing velocity never changes SF2/preset: nothing in the diag path writes to BASS.
    const auto history = mock_bass::history;
    (void)p.rightVoiceDiagnostic();
    check(mock_bass::history == history, "rightVoiceDiagnostic must not send any MIDI event");

    // 3. Controller readback comes from the stream, not from app state.
    p.setChannelMixer(0, 127, 64, 100, 40, 0);
    r1 = row(p, 0);
    check(field(r1, "cc7") == "127" && field(r1, "cc11") == "100" && field(r1, "cc91") == "40" && field(r1, "cc93") == "0", "controller readback");

    // 4. Velocity monitor counts only RIGHT parts; forwarded velocity is what BASS received.
    const float vels[] = {0.3f, 0.3f, 0.3f, 0.8f, 1.0f};
    for (float v : vels) p.noteOn(0, 60, v);
    p.noteOn(3, 60, 0.5f);   // LEFT
    p.noteOn(9, 38, 0.9f);   // drum channel
    p.noteOn(11, 60, 0.9f);  // style channel
    r1 = row(p, 0);
    check(field(r1, "fwdCount") == "5", "only RIGHT NOTE_ON counted");
    check(field(r1, "fwdMin") == "38" && field(r1, "fwdMed") == "38" && field(r1, "fwdMax") == "127" && field(r1, "fwdLast") == "127", "min/median/max");
    check(field(row(p, 1), "fwdCount") == "0", "RIGHT2 isolated from RIGHT1");
    p.resetRightVelocityStats();
    check(field(row(p, 0), "fwdCount") == "0" && field(row(p, 0), "fwdMax") == "-1", "reset");

    // 5. A live readback that disagrees is MISMATCH, never MATCH.
    mock_bass::mismatchPreset = true;
    check(field(row(p, 0), "status") == "MISMATCH", "wrong live preset -> MISMATCH");
    mock_bass::mismatchPreset = false;
    check(field(row(p, 0), "status") == "MATCH", "recovers when readback agrees again");

    // 6. A font whose identity cannot be proven independently is UNVERIFIED, not MATCH.
    BassMidiPlayer q;
    check(q.loadMelody(d + "/bare.sf2"), "load bare font");
    const auto bare = firstMelodic(q, "bare");
    check(q.setKeyboardPreset(0, bare.id, bare.bank, bare.pc), "bare selection verified");
    const auto b0 = row(q, 0);
    check(field(b0, "status") == "UNVERIFIED", "no INAM -> UNVERIFIED: " + b0);
    // 7. Onset probe: isolated decoder, verified preset only, never writes to the production stream.
    {
        auto production = [] { size_t n = 0; for (const auto& h : mock_bass::history) if (std::get<0>(h) == 1) ++n; return n; };
        const size_t before = production();
        const auto report = p.onsetReport(0, 60);
        check(report.find("ONSET part=RIGHT1") == 0 && report.find("sf2='colombo.sf2'") != std::string::npos, "onset header names the exact RIGHT preset: " + report);
        check(report.find("ONSET velocity=40") != std::string::npos && report.find("ONSET velocity=127") != std::string::npos, "four velocities reported");
        check(report.find("classification(v100)=NO_SIGNAL") != std::string::npos, "silent mock decoder -> NO_SIGNAL, never a made-up latency");
        check(production() == before, "onset probe must not send events to the production stream (stream 1)");
        check(p.onsetReport(2, 60).find("unavailable") != std::string::npos, "RIGHT3 without a verified selection is refused");
        check(p.onsetReport(3, 60).find("unavailable") != std::string::npos && p.onsetReport(0, 200).find("unavailable") != std::string::npos, "LEFT and bad keys are refused");
        mock_bass::mismatchPreset = true;
        check(p.onsetReport(0, 60).find("not verified") != std::string::npos, "live readback mismatch is refused");
        mock_bass::mismatchPreset = false;
        mock_bass::failDecode = true;
        check(p.onsetReport(0, 60).find("ONSET FAIL") != std::string::npos, "decoder failure is reported, not hidden");
        mock_bass::failDecode = false;
    }
    std::cout << "RIGHT VOICE DIAG NATIVE PASS\n";
}

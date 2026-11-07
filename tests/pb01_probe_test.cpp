// P-B0.1 TARGETED PROBES on the host: production BassMidiPlayer + mock BASS. HOST evidence only (no device timing).
// Proves: probes ON vs OFF send the same BASSMIDI events in the same order; applyFonts / StreamSetFonts are attributed to the
// operation that triggered them; mutex wait/hold are split by thread role; voice-apply and chord windows count font rebuilds and
// mutex acquisitions; disabled probes record nothing; render + notes + window edges + toggling from several threads finish
// (no deadlock) and run clean under ASan/UBSan/TSan; and the per-call overhead is reported.
#include "bassmidi_player.h"
#include "pb01_probe.h"
#include "pa_diag_mode.h"
#include "sf2_rich_fixture.h"
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <thread>
#include <tuple>
#include <vector>

JavaVM* g_jvm = nullptr; jclass g_debugLogClass = nullptr; jmethodID g_debugLogAddMethod = nullptr;
static int checks = 0;
static void check(bool ok, const char* what) { ++checks; if (!ok) { std::cerr << "FAIL: " << what << '\n'; std::exit(1); } }

static std::string fixture(const std::string& dir) {
    using namespace rich_fixture;
    const char* names[] = {"ConcertGrand", "Acoustic Bass", "Steel Guitar", "Strings", "Warm Pad", "Flute"};
    const int pcs[] = {0, 33, 24, 49, 89, 73};
    Bytes ph(38 * 7), pdta = {'p', 'd', 't', 'a'};
    for (int i = 0; i < 6; ++i) { std::memcpy(ph.data() + 38 * i, names[i], std::strlen(names[i])); word(ph, 38 * i + 20, pcs[i]); word(ph, 38 * i + 22, 8); }
    std::memcpy(ph.data() + 38 * 6, "EOP", 3); chunk(pdta, "phdr", ph);
    Bytes out = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 's', 'f', 'b', 'k'}; chunk(out, "LIST", pdta); dword(out, 4, out.size() - 8);
    const auto path = dir + "/PB01.sf2"; std::ofstream file(path, std::ios::binary); file.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}
using History = std::vector<std::tuple<HSTREAM, DWORD, DWORD, DWORD>>;
static const char* voices[] = {"Bass", "Piano", "A.Guitar", "Strings1", "Warm Pad", "Flute"};
static const int pcs[] = {33, 0, 24, 49, 89, 73};
static void setup(BassMidiPlayer& p, const std::string& font) {
    check(p.loadMelody(font), "font loads");
    for (int ch = 10; ch < 16; ++ch) { p.setChannelPreset(ch, 8 * 128, pcs[ch - 10], voices[ch - 10]); p.setChannelMixer(ch, 80, 64, 100, 40, 0); }
    p.setChannelPreset(8, 128, 27, "Pc"); p.setChannelPreset(9, 128, 0, "Dr");
}
/** One "section": a voice apply (presets + mixers + drum AB bind attempts) then a bar of notes. */
static void sectionVoiceApply(BassMidiPlayer& p, int variant) {
    for (int ch = 10; ch < 15; ++ch) p.setChannelPreset(ch, 8 * 128, pcs[(ch - 10 + variant) % 6], voices[(ch - 10 + variant) % 6]);
    for (int ch = 8; ch < 15; ++ch) p.setChannelMixer(ch, 70 + variant, 64, 100, 40, 0);
    p.bindDrumAB(8, "unreviewed", 127, 27, "MainD"); p.bindDrumAB(9, "unreviewed", 127, 0, "MainD");
}
static void bar(BassMidiPlayer& p, long long& id) {
    for (int step = 0; step < 16; ++step) {
        auto o = [&](int ch, int key) { return AudioPathOrigin{ch, key, ch >= 8 && ch <= 9 ? 127 * 128 : 114, step * 480LL, id++, false}; };
        p.noteOn(9, step % 4 == 0 ? 36 : 42, 0.8f, o(9, 36)); if (step % 8 == 4) p.noteOn(9, 40, 0.9f, o(9, 40));
        p.noteOn(11, 60 + step % 3 * 4, 0.5f, o(11, 60)); p.noteOff(11, 60 + step % 3 * 4, o(11, 60));
        if (step % 4 == 0) { p.noteOn(10, 36, 0.7f, o(10, 36)); } if (step % 4 == 3) p.noteOff(10, 36, o(10, 36));
    }
}
/** A chord retarget on the "main" thread: N replacements = N (OFF old, ON new) on held notes. */
static void retarget(BassMidiPlayer& p, int n, int shift) {
    for (int i = 0; i < n; ++i) { p.noteOff(12 + i % 3, 55 + i, {}); p.noteOn(12 + i % 3, 55 + i + shift, 0.6f, {}); }
}
static History run(const std::string& font, bool probes) {
    pb01::enabled = probes ? 1 : 0; pb01::reset();
    mock_bass::history.clear(); mock_bass::logs.clear();
    const HSTREAM main = mock_bass::nextStream;
    BassMidiPlayer p; setup(p, font); p.setDrumABMode(true);
    const size_t before = mock_bass::history.size();
    long long id = 1;
    std::thread sched([&] {
        pb01::role = pb01::RoleSched;
        for (int s = 0; s < 6; ++s) {
            pb01::windowEdge(pb01::voiceWin, pb01::RoleSched, true, 0, 0);
            const auto t0 = pb01::now(); sectionVoiceApply(p, s); pb01::windowEdge(pb01::voiceWin, pb01::RoleSched, false, pb01::now() - t0, 0);
            bar(p, id);
        }
    });
    sched.join();
    std::thread ui([&] {
        pb01::role = pb01::RoleMain;
        for (int c = 0; c < 5; ++c) {
            pb01::windowEdge(pb01::chordWin, pb01::RoleMain, true, 0, 0);
            const auto t0 = pb01::now(); retarget(p, 10, c + 1); pb01::windowEdge(pb01::chordWin, pb01::RoleMain, false, pb01::now() - t0, 10);
        }
    });
    ui.join();
    History h(mock_bass::history.begin() + long(before), mock_bass::history.end());
    for (auto& e : h) std::get<0>(e) = std::get<0>(e) == main ? 0 : 1;
    return h;
}

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const auto font = fixture(argv[1]);
    pa_mode::set(pa_mode::OPTIMIZED);  // the P-A baseline the probes will be used with
    const History off = run(font, false);
    check(pb01::cells[pb01::RoleSched][pb01::OpNoteOn].wait.count.load() == 0 && pb01::setFonts.count.load() == 0 && pb01::voiceWin.windows.load() == 0,
          "disabled probes record nothing");
    const History on = run(font, true);
    check(!on.empty() && on == off, "probes ON send exactly the same BASSMIDI events in the same order as OFF");

    using namespace pb01;
    const auto& c = cells;
    check(c[RoleSched][OpNoteOn].hold.count.load() > 0 && c[RoleSched][OpNoteOff].hold.count.load() > 0, "scheduler-role note calls counted");
    check(c[RoleSched][OpPreset].hold.count.load() == 6 * 5 && c[RoleSched][OpMixer].hold.count.load() == 6 * 7, "voice-apply preset/mixer acquisitions counted exactly");
    check(c[RoleSched][OpBindDrumAB].hold.count.load() == 6 * 2, "bindDrumAB lock (plain lock_guard) counted: 2 per voice apply");
    check(c[RoleMain][OpNoteOn].hold.count.load() == 50 && c[RoleMain][OpNoteOff].hold.count.load() == 50, "retarget calls on the main role: 2 acquisitions per replacement");
    check(c[RoleMain][OpPreset].hold.count.load() == 0 && c[RoleSched][OpRender].hold.count.load() == 0, "roles do not leak into each other");
    check(applyFonts[OpPreset].count.load() >= 30, "applyFonts attributed to setChannelPreset (one per melodic preset change)");
    check(setFonts.count.load() >= applyFonts[OpPreset].count.load() && fontEntriesMax.load() > 0, "StreamSetFonts timed with the table size");
    check(voiceWin.windows.load() == 6 && voiceWin.fontCallsMax.load() >= 5 && voiceWin.acquisitionsMax.load() == 5 + 7 + 2, "voice window: font rebuilds and 14 mutex acquisitions per apply");
    check(chordWin.windows.load() == 5 && chordWin.acquisitionsMax.load() == 20 && chordWin.replacementsSum.load() == 50, "chord window: 20 acquisitions for 10 replacements");
    const auto rep = report();
    check(rep.find("PB01_mutex_scheduler_bindDrumAB_hold") != std::string::npos && rep.find("PB01_applyFonts_by_setChannelPreset") != std::string::npos &&
          rep.find("PB01_WINDOW voiceApply windows=6") != std::string::npos && rep.find("PB01_chordRetarget_perReplacement") != std::string::npos, "report lines present");

    // Concurrency: audio render + scheduler notes + UI retarget + window edges + toggling the probe flag.
    {
        pb01::enabled = 1; pb01::reset(); mock_bass::history.clear(); mock_bass::logs.clear();
        BassMidiPlayer p; setup(p, font); p.setDrumABMode(true);
        std::atomic<bool> stop{false}; std::atomic<long> renders{0}, toggles{0};
        std::thread audio([&] { std::vector<float> b(512); while (!stop) { p.render(b.data(), 256); ++renders; } });
        std::thread flip([&] { while (!stop) { pb01::enabled = pb01::enabled.load() ^ 1u; ++toggles; std::this_thread::sleep_for(std::chrono::microseconds(300)); } });
        std::thread ui([&] { pb01::role = RoleMain; for (int i = 0; i < 200 && !stop; ++i) { windowEdge(chordWin, RoleMain, true, 0, 0); retarget(p, 6, i % 5 + 1); windowEdge(chordWin, RoleMain, false, 1000, 6); } });
        const auto t0 = std::chrono::steady_clock::now();
        { pb01::role = RoleSched; long long id = 1; for (int s = 0; s < 20; ++s) { windowEdge(voiceWin, RoleSched, true, 0, 0); sectionVoiceApply(p, s); windowEdge(voiceWin, RoleSched, false, 1000, 0); bar(p, id); } }
        ui.join(); stop = true; audio.join(); flip.join();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        check(renders > 0 && toggles > 0 && ms < 120000, "4 threads finished (no deadlock)");
        std::cout << "PB01_CONCURRENCY renders=" << renders.load() << " toggles=" << toggles.load() << " ms=" << ms << '\n';
    }
    // Overhead: ns per style NOTE ON/OFF pair with probes ON vs OFF (host mock synth; device cost differs, see report).
    {
        BassMidiPlayer p; setup(p, font);
        auto bench = [&](uint32_t en) {
            pb01::enabled = en; pb01::role = RoleSched;
            const auto t0 = std::chrono::steady_clock::now();
            for (int i = 0; i < 20000; ++i) { p.noteOn(11, 60 + i % 12, 0.5f, AudioPathOrigin{11, 60, 114, i, i, false}); p.noteOff(11, 60 + i % 12, {}); }
            return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count() / 20000.0;
        };
        bench(0); const double offNs = bench(0), onNs = bench(1);
        std::cout << "PB01_OVERHEAD pairOffNs=" << offNs << " pairOnNs=" << onNs << " deltaNs=" << (onNs - offNs) << '\n';
    }
    pa_mode::set(pa_mode::LEGACY); pb01::enabled = 1;
    std::cout << "PB01 PROBE PASS checks=" << checks << " events=" << on.size() << '\n';
    return 0;
}

// P-A EXPERIMENT (BASSMIDI mutex diagnostic A/B) on the host: production BassMidiPlayer + mock BASS.
// Proves: LEGACY and OPTIMIZED send the SAME BASSMIDI events in the SAME order (channel, event, value: notes, velocity, program,
// bank, CC) and keep the same native presence counters; OPTIMIZED makes no log call while a NOTE ON/OFF holds the synth mutex;
// render + notes + mode switches from three threads finish (no deadlock) and run clean under ASan/UBSan when built with them.
// HOST evidence only: says nothing about Redmi Pad timing.
#include "bassmidi_player.h"
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
    const auto path = dir + "/PA Mutex AB.sf2"; std::ofstream file(path, std::ios::binary); file.write(reinterpret_cast<const char*>(out.data()), out.size()); return path;
}

using History = std::vector<std::tuple<HSTREAM, DWORD, DWORD, DWORD>>;
struct Result { History notePhase; std::vector<std::string> presence; uint64_t logsUnderLock, skipped, calls; };

static void setup(BassMidiPlayer& p, const std::string& font) {
    check(p.loadMelody(font), "production font loader succeeds");
    const char* voices[] = {"Bass", "Piano", "A.Guitar", "Strings1", "Warm Pad", "Flute"};
    const int pc[] = {33, 0, 24, 49, 89, 73};
    for (int ch = 10; ch < 16; ++ch) { p.setChannelPreset(ch, 8 * 128, pc[ch - 10], voices[ch - 10]); p.setChannelMixer(ch, 80, 64, 100, 40, 0); }
    p.setChannelPreset(8, 128, 27, "Pc"); p.setChannelPreset(9, 128, 0, "Dr");
    p.setChannelMixer(8, 40, 64, 127, 40, 0); p.setChannelMixer(9, 60, 64, 127, 40, 0);
}

/** A FunkPopRock-like burst pattern: drums incl. snare (sampled diagnostics), Bass/Chord/Pad/Phrase, OFFs, a mixer change and a re-preset. */
static void play(BassMidiPlayer& p) {
    long long id = 1;
    for (int bar = 0; bar < 40; ++bar) {
        for (int step = 0; step < 16; ++step) {
            const long long tick = bar * 7680LL + step * 480;
            auto o = [&](int ch, int key) { return AudioPathOrigin{ch, key, ch >= 8 && ch <= 9 ? 127 * 128 : 0 * 128 + 114, tick, id++, false}; };
            p.noteOn(9, step % 4 == 0 ? 36 : 42, 0.8f, o(9, step % 4 == 0 ? 36 : 42));
            if (step % 8 == 4) p.noteOn(9, 40, 0.9f, o(9, 40));                     // snare: separate diagnostic sample budget
            if (step % 4 == 2) p.noteOn(8, step % 8 == 2 ? 26 : 17, 0.6f, o(8, 26));  // Rhythm 1 (Pc)
            if (step % 4 == 0) { p.noteOn(10, 36 + (bar % 3), 0.7f, o(10, 36)); }
            p.noteOn(11, 60 + step % 3 * 4, 0.5f, o(11, 60));
            if (step % 2 == 0) { p.noteOn(12, 55, 0.6f, o(12, 55)); p.noteOn(12, 64, 0.6f, o(12, 64)); }
            if (step == 0) p.noteOn(13, 67, 0.5f, o(13, 67));
            p.noteOn(14, 59 + step % 2 * 5, 0.5f, o(14, 59));
            p.noteOff(11, 60 + step % 3 * 4, o(11, 60));
            p.noteOff(14, 59 + step % 2 * 5, o(14, 59));
            if (step % 2 == 0) { p.noteOff(12, 55, o(12, 55)); p.noteOff(12, 64, o(12, 64)); }
            if (step % 4 == 3) p.noteOff(10, 36 + (bar % 3), o(10, 36));
            if (step == 15) p.noteOff(13, 67, o(13, 67));
        }
        if (bar == 20) { p.setChannelMixer(12, 70, 60, 100, 30, 0); p.setChannelPreset(12, 8 * 128, 0, "Piano"); }  // Main/Fill style voice apply
    }
    p.allNotesOff();  // STOP / panic path
}

static Result run(int mode, const std::string& font) {
    pa_mode::set(mode);
    mock_bass::history.clear(); mock_bass::logs.clear();
    const HSTREAM mainStream = mock_bass::nextStream;  // the player's first StreamCreate is its main stream
    BassMidiPlayer p; setup(p, font);
    const size_t before = mock_bass::history.size();
    pa_mode::resetCounters();
    // Simulated "now": the diagnostic sampling windows use steady_clock ms; the event stream must not depend on them.
    play(p);
    Result r;
    r.notePhase.assign(mock_bass::history.begin() + static_cast<long>(before), mock_bass::history.end());
    // Each player opens a new mock stream handle; compare the stream ROLE (0 = this player's main stream, else auxiliary).
    for (auto& e : r.notePhase) std::get<0>(e) = std::get<0>(e) == mainStream ? 0 : 1;
    r.logsUnderLock = pa_mode::logsUnderNoteLock.load(); r.skipped = pa_mode::skippedDiagnostics.load(); r.calls = pa_mode::noteCalls.load();
    const auto report = p.noteZoneReport();
    size_t pos = 0;
    while ((pos = report.find("NATIVE ch=", pos)) != std::string::npos) { const auto end = report.find('\n', pos); auto line = report.substr(pos, end - pos); line = line.substr(0, line.find(" requestedBank")); r.presence.push_back(line); pos = end; }  // counters only (font handles differ per player)
    return r;
}

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory provided");
    const auto font = fixture(argv[1]);
    check(pa_mode::mode.load() == pa_mode::LEGACY, "default mode is LEGACY");

    const Result legacy = run(pa_mode::LEGACY, font);
    const Result optimized = run(pa_mode::OPTIMIZED, font);
    const Result legacy2 = run(pa_mode::LEGACY, font);

    check(!legacy.notePhase.empty(), "events were sent");
    auto diff=[](const History& a,const History& b,const char* tag){ size_t n=std::min(a.size(),b.size()),i=0; while(i<n&&a[i]==b[i])++i;
        if(i<n||a.size()!=b.size()){ std::cerr<<tag<<" sizes "<<a.size()<<"/"<<b.size()<<" firstDiff="<<i;
            for(size_t k=i;k<std::min(n,i+4);++k) std::cerr<<" | A("<<std::get<0>(a[k])<<","<<std::get<1>(a[k])<<","<<std::get<2>(a[k])<<","<<std::get<3>(a[k])<<") B("<<std::get<0>(b[k])<<","<<std::get<1>(b[k])<<","<<std::get<2>(b[k])<<","<<std::get<3>(b[k])<<")";
            std::cerr<<'\n'; } };
    diff(legacy.notePhase,legacy2.notePhase,"LEGACY-vs-LEGACY"); diff(legacy.notePhase,optimized.notePhase,"LEGACY-vs-OPTIMIZED");
    check(legacy.notePhase == optimized.notePhase, "OPTIMIZED sends exactly the LEGACY BASSMIDI events (channel/event/value, same order)");
    check(legacy.notePhase == legacy2.notePhase, "LEGACY is repeatable (control)");
    size_t notes = 0, offs = 0, other = 0;
    for (const auto& e : legacy.notePhase) {
        if (std::get<2>(e) == MIDI_EVENT_NOTE) { if (std::get<3>(e) >> 8) ++notes; else ++offs; } else ++other;
    }
    check(notes > 1000 && offs > 500 && other > 0, "pattern covers NOTE ON, NOTE OFF and controller/program/panic events");
    for (size_t i=0;i<std::max(legacy.presence.size(),optimized.presence.size());++i) if (i>=legacy.presence.size()||i>=optimized.presence.size()||legacy.presence[i]!=optimized.presence[i]) std::cerr<<"L: "<<(i<legacy.presence.size()?legacy.presence[i]:"-")<<"\nO: "<<(i<optimized.presence.size()?optimized.presence[i]:"-")<<"\n";
    check(legacy.presence == optimized.presence && !legacy.presence.empty(), "native presence counters (attempts/sent per channel) identical");
    check(legacy.calls == optimized.calls && legacy.calls > 0, "same number of NOTE ON/OFF critical sections");
    check(legacy.logsUnderLock > 0, "LEGACY logs while the synth mutex is held (the contention under test exists)");
    check(optimized.logsUnderLock == 0, "OPTIMIZED: zero uiLog/JNI DebugLog calls while a NOTE ON/OFF holds the synth mutex");
    check(optimized.skipped > 0 && legacy.skipped == 0, "OPTIMIZED skips per-note diagnostics; LEGACY skips none");

    // Concurrency: audio thread renders, scheduler thread plays notes, a third thread flips the mode. Must finish (no deadlock).
    {
        pa_mode::set(pa_mode::LEGACY); mock_bass::history.clear(); mock_bass::logs.clear();
        BassMidiPlayer p; setup(p, font);
        std::atomic<bool> stop{false}; std::atomic<long> renders{0}, flips{0};
        std::thread audio([&] { std::vector<float> buf(256 * 2); while (!stop.load()) { p.render(buf.data(), 256); ++renders; } });
        std::thread toggler([&] { int m = 0; while (!stop.load()) { m ^= 1; pa_mode::set(m); ++flips; std::this_thread::sleep_for(std::chrono::microseconds(200)); } });
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < 3; ++i) play(p);
        stop = true; audio.join(); toggler.join();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        check(renders.load() > 0 && flips.load() > 0, "render and mode switches ran concurrently with notes");
        check(ms < 60000, "three threads finished (no deadlock)");
        std::cout << "PA_CONCURRENCY renders=" << renders.load() << " flips=" << flips.load() << " ms=" << ms << '\n';
    }
    pa_mode::set(pa_mode::LEGACY);
    std::cout << "PA MUTEX AB PASS checks=" << checks << " events=" << legacy.notePhase.size() << " noteOn=" << notes << " noteOff=" << offs
              << " other=" << other << " criticalSections=" << legacy.calls << " legacyLogsUnderLock=" << legacy.logsUnderLock
              << " optimizedLogsUnderLock=" << optimized.logsUnderLock << " optimizedSkipped=" << optimized.skipped << '\n';
    return 0;
}

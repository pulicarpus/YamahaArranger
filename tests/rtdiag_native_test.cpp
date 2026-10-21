// REALTIME DIAGNOSTIC (native, HOST, MOCK BASS). Measures the PRODUCTION BassMidiPlayer note/render code:
//  A) app-side C++ cost of one NOTE_ON/NOTE_OFF (everything except BASSMIDI itself), keyboard vs melodic style vs drum style;
//  B) contention on the single synth mutex shared by the audio callback and the note callers (render hold is a MODEL parameter).
// It does not measure BASSMIDI, Oboe, ARM, logcat/JNI or USB. Output: RTDIAG_NATIVE lines.
#include "bassmidi_player.h"
#include <atomic>
#include <chrono>
#include <iostream>
#include <sstream>
#include <thread>
#include <algorithm>
#include <stdexcept>

using Clock = std::chrono::steady_clock;
static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }
static uint64_t ns() { return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count(); }
static double pct(std::vector<uint64_t> v, double p) { if (v.empty()) return 0; std::sort(v.begin(), v.end()); return v[size_t((v.size() - 1) * p)] / 1000.0; }
static double mx(const std::vector<uint64_t>& v) { return v.empty() ? 0 : *std::max_element(v.begin(), v.end()) / 1000.0; }

int main(int argc, char** argv) {
    check(argc == 2, "fixture directory");
    const std::string d = argv[1];
    const std::string col = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506";
    BassMidiPlayer p;
    p.setSourceTiered(true);
    check(p.loadMelody(d + "/colombo.sf2") && p.loadMelodyFallback(d + "/tyros4.sf2"), "load fonts");
    check(p.setDrumABMode(true) && p.bindDrumAB(8, col, 127, 0, "Main A"), "drum kit bound");
    p.setChannelPreset(10, 0, 33); p.setChannelPreset(11, 0, 0);

    // ---- A) per-call overhead --------------------------------------------------------------------------------------------
    const int N = 20000;
    std::vector<uint64_t> raw; raw.reserve(N);
    for (int i = 0; i < N; ++i) { const auto a = ns(); BASS_MIDI_StreamEvent(1, 10, MIDI_EVENT_NOTE, 60 | (90 << 8)); raw.push_back(ns() - a); if ((i & 1023) == 0) mock_bass::history.clear(); }
    mock_bass::history.clear();
    std::cout << "RTDIAG_NATIVE scenario=raw_mock_StreamEvent p50us=" << pct(raw, 0.5) << " p95us=" << pct(raw, 0.95) << "\n";
    struct Case { const char* name; int channel; bool style; } cases[] = {
        {"keyboard_ch0_noteOn", 0, false}, {"style_melodic_ch10_noteOn", 10, true}, {"style_drum_ch8_noteOn", 8, true}, {"style_chord_ch11_noteOn", 11, true}};
    for (const auto& c : cases) {
        std::vector<uint64_t> on, off; on.reserve(N); off.reserve(N);
        for (int i = 0; i < N; ++i) {
            const int key = c.channel == 8 ? (i % 3 == 0 ? 36 : i % 3 == 1 ? 38 : 42) : 48 + (i % 24);
            AudioPathOrigin origin; if (c.style) { origin.sourceChannel = c.channel; origin.sourceNote = key; origin.styleBank = 113; origin.tick = i; origin.id = i; }
            auto a = ns(); p.noteOn(c.channel, key, 0.7f, origin); on.push_back(ns() - a);
            a = ns(); p.noteOff(c.channel, key, origin); off.push_back(ns() - a);
            if ((i & 1023) == 0) mock_bass::history.clear();
        }
        mock_bass::history.clear();
        std::cout << "RTDIAG_NATIVE scenario=" << c.name << " calls=" << N << " noteOn_p50us=" << pct(on, 0.5) << " noteOn_p95us=" << pct(on, 0.95) << " noteOn_maxus=" << mx(on)
                  << " noteOff_p50us=" << pct(off, 0.5) << " noteOff_p95us=" << pct(off, 0.95) << "\n";
    }

    // ---- B) mutex contention model: render holds the synth mutex for `hold` us per callback (MODEL), chords hammer noteOn ------
    for (unsigned hold : {0u, 1000u, 3000u, 5000u}) {
        mock_bass::getDataSpinUs = hold;
        std::atomic<bool> stop{false}; std::vector<uint64_t> renderLate, renderDur, noteDur, burst;
        std::thread audio([&] {
            std::vector<float> out(256 * 2); const uint64_t period = 5333333; uint64_t next = ns() + 2000000;
            while (!stop) { while (ns() < next) std::this_thread::yield(); const uint64_t start = ns(); renderLate.push_back(start > next ? start - next : 0);
                p.render(out.data(), 256); renderDur.push_back(ns() - start); next += period; }
        });
        const uint64_t until = ns() + 1500000000ull; int round = 0;
        while (ns() < until) {
            const uint64_t b0 = ns();
            for (int layer = 0; layer < 3; ++layer) for (int k : {60, 64, 67, 72}) { AudioPathOrigin o; const auto a = ns(); p.noteOn(layer, k, 0.7f, o); noteDur.push_back(ns() - a); }
            burst.push_back(ns() - b0);
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            for (int layer = 0; layer < 3; ++layer) for (int k : {60, 64, 67, 72}) p.noteOff(layer, k);
            std::this_thread::sleep_for(std::chrono::milliseconds(30)); ++round; if ((round & 7) == 0) mock_bass::history.clear();
        }
        stop = true; audio.join(); mock_bass::history.clear();
        std::cout << "RTDIAG_NATIVE scenario=mutex_contention_model renderHoldUs=" << hold << " chordBursts=" << burst.size()
                  << " noteOn_p50us=" << pct(noteDur, 0.5) << " noteOn_p95us=" << pct(noteDur, 0.95) << " noteOn_maxus=" << mx(noteDur)
                  << " burst12_p50us=" << pct(burst, 0.5) << " burst12_p95us=" << pct(burst, 0.95) << " burst12_maxus=" << mx(burst)
                  << " renderStartLate_p95us=" << pct(renderLate, 0.95) << " renderStartLate_maxus=" << mx(renderLate)
                  << " renderDur_p95us=" << pct(renderDur, 0.95) << " renderDur_maxus=" << mx(renderDur) << " budgetUs=5333\n";
    }
    mock_bass::getDataSpinUs = 0;
    std::cout << "RTDIAG_NATIVE PASS\n";
}

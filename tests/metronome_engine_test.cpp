// Host test of the metronome click generator (pure C++): sample accuracy, meters, tempo change, Follow scheduling, cancellation, off = untouched.
#include "metronome_engine.h"
#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <vector>
using namespace metronome;
static void check(bool v, const std::string& m) { if (!v) throw std::runtime_error(m); }

// Renders `seconds` in blocks of `block` frames; returns onset frames (first |sample| > 1e-4 after silence) and the peak.
struct Run { std::vector<long> onsets; std::vector<float> peaks; float peak = 0; };
static Run renderStandalone(Engine& e, double seconds, int block, int sr, std::vector<std::pair<long, int>> tempoChanges = {}) {
    Run r; long frame = 0; const long total = long(seconds * sr); std::vector<float> out(size_t(block) * 2); long lastActive = -100000; uint64_t ns = 1000000000ull; size_t next = 0;
    while (frame < total) {
        if (next < tempoChanges.size() && frame >= tempoChanges[next].first) { e.configure(true, Standalone, true, tempoChanges[next].second, 4, 4, 1000); ++next; }
        std::fill(out.begin(), out.end(), 0.0f); e.render(out.data(), block, sr, ns); ns += uint64_t(double(block) / sr * 1e9);
        for (int i = 0; i < block; ++i) {
            if (frame + i >= total) break;
            const float s = std::abs(out[2 * i]); r.peak = std::max(r.peak, s);
            if (s > 1e-4f) { if (frame + i - lastActive > 2000) r.onsets.push_back(frame + i); lastActive = frame + i; }   // a click is 1440 frames long
        }
        frame += block;
    }
    return r;
}
int main() {
    const int sr = 48000;
    // 1) sample-accurate pulses at 120 BPM 4/4 for several block sizes (Oboe bursts differ per device)
    for (int block : {256, 240, 137, 512, 1024}) {
        Engine e; e.configure(true, Standalone, true, 12000, 4, 4, 1000);
        const auto r = renderStandalone(e, 10.0, block, sr);
        check(r.onsets.size() == 20, "20 clicks in 10 s at 120 BPM, block " + std::to_string(block) + " got " + std::to_string(r.onsets.size()));
        for (size_t k = 0; k < r.onsets.size(); ++k) check(std::labs(r.onsets[k] - long(k) * 24000) <= 2, "click " + std::to_string(k) + " on grid, block " + std::to_string(block));
        check(r.peak <= 0.36f && r.peak > 0.2f, "bounded click level");
        std::cout << "METRONOME standalone block=" << block << " clicks=" << r.onsets.size() << " maxErrFrames="
                  << [&] { long m = 0; for (size_t k = 0; k < r.onsets.size(); ++k) m = std::max(m, std::labs(r.onsets[k] - long(k) * 24000)); return m; }() << "\n";
    }
    // 2) tempo change takes effect at once (rescaled), no drift afterwards: 120 BPM then 90 BPM at 2 s
    { Engine e; e.configure(true, Standalone, true, 12000, 4, 4, 1000);
      const auto r = renderStandalone(e, 8.0, 256, sr, {{2 * sr, 9000}});
      long prev = -1; int slow = 0; for (auto o : r.onsets) { if (prev >= 0 && o > 2 * sr + 256 * 2) { const long d = o - prev; check(std::labs(d - 32000) <= 2, "90 BPM interval 32000 frames got " + std::to_string(d)); ++slow; } prev = o; }
      check(slow >= 8, "enough 90 BPM pulses"); std::cout << "METRONOME tempoChange 120->90 pulsesChecked=" << slow << "\n"; }
    // 3) levels: 4/4 Strong,Weak,Weak,Weak ; 6/8 Strong,Weak,Weak,Medium,Weak,Weak ; 3/4 ; 2/4
    check(levelFor(0, 4, 4) == Strong && levelFor(1, 4, 4) == Weak && levelFor(4, 4, 4) == Strong, "4/4");
    { const Level want[6] = {Strong, Weak, Weak, Medium, Weak, Weak}; for (int i = 0; i < 12; ++i) check(levelFor(i, 6, 8) == want[i % 6], "6/8 grouping"); }
    check(levelFor(3, 3, 4) == Strong && levelFor(2, 3, 4) == Weak && levelFor(1, 2, 4) == Weak, "3/4, 2/4");
    // 4) 6/8 pulses are eighth notes: at 120 quarter-BPM a pulse is 12000 frames, a bar (6 pulses) 72000
    { Engine e; e.configure(true, Standalone, true, 12000, 6, 8, 1000);
      const auto r = renderStandalone(e, 3.0, 256, sr); check(r.onsets.size() == 12, "12 eighth pulses in 3 s"); for (size_t k = 0; k < r.onsets.size(); ++k) check(std::labs(r.onsets[k] - long(k) * 12000) <= 2, "6/8 pulse grid");
      std::cout << "METRONOME 6/8 pulses=" << r.onsets.size() << " pulseFrames=12000 barFrames=72000\n"; }
    // 5) disabled = output untouched, bit for bit; render() reports nothing mixed
    { Engine e; std::vector<float> a(512, 0.25f), b = a; const bool mixed = e.render(a.data(), 256, sr, 5); check(!mixed && a == b, "disabled leaves the buffer untouched"); }
    // 6) STOP (running=false) silences new pulses; enabled stays
    { Engine e; e.configure(true, Standalone, true, 12000, 4, 4, 1000); renderStandalone(e, 0.3, 256, sr); e.configure(true, Standalone, false, 12000, 4, 4, 1000);
      std::vector<float> out(512); float tail = 0; uint64_t ns = 1; for (int b = 0; b < 400; ++b) { std::fill(out.begin(), out.end(), 0.f); e.render(out.data(), 256, sr, ns); ns += 5333333; if (b > 20) for (float v : out) tail = std::max(tail, std::abs(v)); }
      check(tail == 0.0f, "no click after STOP"); }
    // 7) FOLLOW: a click fires in the first callback whose start >= target; lateness < one block; stale and cancelled clicks never sound
    { Engine e; e.configure(true, Follow, false, 12000, 4, 4, 1000);
      const uint64_t block = 5333333; const uint32_t gen = e.generation(); std::vector<float> out(512);
      const uint64_t t0 = 100 * block;
      for (int k = 0; k < 8; ++k) check(e.push(t0 + uint64_t(k) * 500000000ull + 1234567ull, k % 4 == 0 ? Strong : Weak, gen), "push");
      uint64_t ns = t0 - 5 * block; int fired = 0; uint64_t lastBeat = 0;
      for (int b = 0; b < 800; ++b, ns += block) { std::fill(out.begin(), out.end(), 0.f); e.render(out.data(), 256, sr, ns);
          if (e.beatSeq() != lastBeat) { ++fired; lastBeat = e.beatSeq(); } }
      check(fired == 8, "all 8 follow clicks fired once, got " + std::to_string(fired));
      const std::string rep = e.report(); check(rep.find("droppedStale=0") != std::string::npos, "none stale");
      std::cout << rep; }
    { Engine e; e.configure(true, Follow, false, 12000, 4, 4, 1000); std::vector<float> out(512);
      e.push(1000, Strong, e.generation()); e.render(out.data(), 256, sr, 1000 + 150000000ull);       // 150 ms late: dropped, not played
      check(e.beatSeq() == 0 && e.report().find("droppedStale=1") != std::string::npos, "stale click dropped");
      e.push(2000000000ull, Strong, e.generation()); e.bumpGeneration();                              // cancelled by a generation bump (tempo/section change)
      e.render(out.data(), 256, sr, 2000000000ull + 1000); check(e.beatSeq() == 0, "cancelled click never fires");
      for (int i = 0; i < Engine::kRing + 20; ++i) e.push(5000000000ull + i, Weak, e.generation());
      check(e.report().find("droppedRingFull=") != std::string::npos && e.report().find("droppedRingFull=0") == std::string::npos, "ring-full counted"); }
    // 7b) duplicates from a re-plan are dropped, not played twice
    { Engine e; e.configure(true, Follow, false, 12000, 4, 4, 1000); std::vector<float> out(512);
      e.push(1000, Strong, e.generation()); e.push(1000, Strong, e.generation()); e.push(2000, Weak, e.generation());
      e.render(out.data(), 256, sr, 5000); check(e.beatSeq() == 2 && e.report().find("duplicatesDropped=1") != std::string::npos, "duplicate click dropped"); }
    // 8) enabling/disabling in Follow mode drains old clicks
    { Engine e; e.configure(true, Follow, false, 12000, 4, 4, 1000); std::vector<float> out(512); e.push(10, Strong, e.generation());
      e.configure(false, Follow, false, 12000, 4, 4, 1000); e.render(out.data(), 256, sr, 20); e.configure(true, Follow, false, 12000, 4, 4, 1000); e.render(out.data(), 256, sr, 30);
      check(e.beatSeq() == 0, "clicks queued before disable do not fire after re-enable"); }
    std::cout << "METRONOME PASS\n";
}

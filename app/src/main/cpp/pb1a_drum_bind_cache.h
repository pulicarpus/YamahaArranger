#pragma once
// P-B1a EXPERIMENT (A/B, not a production fix): skip a redundant Drum AB rebind.
//   OFF (default) = unchanged: every section voice apply calls bindDrumAB for ch8/ch9, which resets the binding, rebuilds the whole
//                   font table (applyFonts + BASS_MIDI_StreamSetFonts), re-sends DRUMS/BANK/BANK_LSB/PROGRAM and preloads the preset.
//   ON            = when the request is IDENTICAL to the current binding (same fingerprint, raw bank, program) AND that binding is
//                   still verified AND BASSMIDI's live readback on the channel still shows exactly that font/bank/program, nothing is
//                   rebuilt or re-sent: the binding is kept (only its section label is updated) and bindDrumAB returns true.
//                   Any other request (different kit, unresolved/empty fingerprint, lost readback, mode change, font unload) takes the
//                   unchanged original path.
// The flag is a relaxed atomic read once per bindDrumAB call (under the synth mutex the call already holds).
#include <atomic>
#include <cstdint>
#include <sstream>
#include <string>

namespace pb1a {
inline std::atomic<int> mode{0};
inline std::atomic<uint64_t> calls{0}, skipped{0}, rebound{0}, switches{0};
// True after a Drum AB binding was reset (or changed) and the published font table was not rebuilt since: the table may still carry
// the old exact mapping, so no rebind may be skipped until the next successful applyFonts. Starts true (unknown = rebuild).
inline std::atomic<bool> tableStale{true};
inline bool on() { return mode.load(std::memory_order_relaxed) == 1; }
inline void set(bool enabled) { const int v = enabled ? 1 : 0; if (mode.exchange(v) != v) switches.fetch_add(1); }
inline void resetCounters() { calls = 0; skipped = 0; rebound = 0; }
inline std::string report() {
    std::ostringstream out;
    out << "PB1A drumBindCache=" << (on() ? "ON" : "OFF") << " bindDrumABCalls=" << calls.load() << " skippedIdentical=" << skipped.load()
        << " rebound=" << rebound.load() << " switchesSinceStart=" << switches.load()
        << " (P-B1a experiment; counters since RESET LATENCY; OFF = legacy rebind every voice apply)\n";
    return out.str();
}
}  // namespace pb1a

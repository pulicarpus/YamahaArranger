#pragma once
// P-A EXPERIMENT (diagnostic A/B, not a production fix): where the per-note audio-path diagnostics run.
//   LEGACY    (default) = unchanged: NOTE ON/OFF diagnostics (logAudioPath, drum-zone signature, NOTE ZONE audit, sampled
//             AUDIO PATH/LIVE/DRUM PATH/OFF/SUMMARY lines through uiLog = logcat + JNI DebugLog.add) run while the synth mutex is held.
//   OPTIMIZED = the same BASSMIDI events in the same order; only the expensive diagnostic work is skipped inside the critical
//             section (cheap counters that feed the Inspector stay). Nothing is deferred to another thread, so no BASSMIDI handle or
//             font pointer is touched after the mutex is released.
// The flag is read once per note with a relaxed load; it gates diagnostics only, never an event. Counters are relaxed atomics.
#include <atomic>
#include <cstdint>
#include <sstream>
#include <string>

namespace pa_mode {
enum : int { LEGACY = 0, OPTIMIZED = 1 };
inline std::atomic<int> mode{LEGACY};
inline std::atomic<uint64_t> logsUnderNoteLock{0};   // uiLog calls made while a style/keyboard NOTE ON/OFF holds the synth mutex
inline std::atomic<uint64_t> skippedDiagnostics{0};  // OPTIMIZED: per-note diagnostic blocks not executed
inline std::atomic<uint64_t> noteCalls{0};           // NOTE ON/OFF entries counted under the lock (both modes)
inline std::atomic<uint64_t> switches{0};
inline thread_local int noteLockDepth = 0;           // >0 while this thread is inside noteOn/noteOff with the mutex held

inline bool optimized() { return mode.load(std::memory_order_relaxed) == OPTIMIZED; }
inline const char* name(int m) { return m == OPTIMIZED ? "OPTIMIZED" : "LEGACY"; }
inline void set(int m) { const int v = m == OPTIMIZED ? OPTIMIZED : LEGACY; if (mode.exchange(v) != v) switches.fetch_add(1); }
inline void resetCounters() { logsUnderNoteLock = 0; skippedDiagnostics = 0; noteCalls = 0; }

/** Constructed AFTER the synth lock is taken, destroyed before it is released (declare it after the SynthLock). */
struct NoteLockScope {
    NoteLockScope() { ++noteLockDepth; noteCalls.fetch_add(1, std::memory_order_relaxed); }
    ~NoteLockScope() { --noteLockDepth; }
    NoteLockScope(const NoteLockScope&) = delete;
    NoteLockScope& operator=(const NoteLockScope&) = delete;
};
inline void onLog() { if (noteLockDepth > 0) logsUnderNoteLock.fetch_add(1, std::memory_order_relaxed); }
inline void skipped() { skippedDiagnostics.fetch_add(1, std::memory_order_relaxed); }

inline std::string report() {
    std::ostringstream out;
    out << "PA_MODE mode=" << name(mode.load()) << " noteCallsUnderLock=" << noteCalls.load()
        << " logsUnderNoteLock=" << logsUnderNoteLock.load() << " skippedDiagnostics=" << skippedDiagnostics.load()
        << " switchesSinceStart=" << switches.load()
        << " (P-A experiment; counters since RESET LATENCY; OPTIMIZED expects logsUnderNoteLock=0)\n";
    return out.str();
}
}  // namespace pa_mode

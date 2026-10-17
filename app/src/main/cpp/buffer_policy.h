#pragma once
// Output buffer A/B policy (pure, host-testable). Never below one device burst, never above the cap.
#include <algorithm>

namespace buffer_policy {

enum Mode { BASELINE_2X = 0, ONE_AND_HALF = 1, ONE_BURST = 2, ADAPTIVE = 3 };
inline constexpr int kModeCount = 4;

inline int clampMode(int mode) { return mode < 0 || mode >= kModeCount ? BASELINE_2X : mode; }

// Frames to request for a mode. BASELINE_2X is the production value (burst * 2) and must stay so.
// ADAPTIVE starts at one burst; the caller raises it with nextAdaptive().
inline int framesFor(int mode, int burst, int capacity) {
    burst = std::max(1, burst);
    int frames = burst * 2;
    switch (clampMode(mode)) {
        case ONE_AND_HALF: frames = burst + burst / 2; break;
        case ONE_BURST:
        case ADAPTIVE: frames = burst; break;
        default: break;
    }
    if (capacity > 0) frames = std::min(frames, capacity);
    return std::max(burst, frames);
}

// One underrun burst -> add one burst, capped at 3 bursts (and capacity). No underrun -> unchanged.
inline int nextAdaptive(int current, int burst, long xrunDelta, int capacity) {
    burst = std::max(1, burst);
    if (xrunDelta <= 0) return std::max(burst, current);
    int next = current + burst;
    next = std::min(next, burst * 3);
    if (capacity > 0) next = std::min(next, capacity);
    return std::max(burst, next);
}

}  // namespace buffer_policy

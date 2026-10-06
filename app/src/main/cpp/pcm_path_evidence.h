#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <sstream>

// Diagnostic only. No output, synth, controller or scheduling operations.
// 32-bit atomics stay lock-free on both supported Android ABIs. Counts wrap.
namespace pcm_path {
inline uint32_t nowMs() {
    return uint32_t(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
class Output {
    static_assert(std::atomic<uint32_t>::is_always_lock_free,"No callback locks");
    std::atomic<uint32_t> starts_{0},started_{0},stops_{0},callbacks_{0},frames_{0};
    std::atomic<uint32_t> synth_{0},fallback_{0},returned_{0},lastCallbackMs_{0};
    std::atomic<uint32_t> callbacksAtStart_{0},callbacksAtStop_{0};
    std::atomic<int> lastResult_{0};
    static uint32_t get(const std::atomic<uint32_t>& a) { return a.load(std::memory_order_relaxed); }
public:
    void startAttempt() { ++starts_;callbacksAtStart_.store(get(callbacks_),std::memory_order_relaxed); }
    void startResult(int result) { lastResult_.store(result,std::memory_order_relaxed);if(!result)++started_; }
    void stop() { ++stops_;callbacksAtStop_.store(get(callbacks_),std::memory_order_relaxed); }
    void callback(int frames) {
        callbacks_.fetch_add(1,std::memory_order_relaxed);
        frames_.fetch_add(uint32_t(frames),std::memory_order_relaxed);
        lastCallbackMs_.store(nowMs(),std::memory_order_relaxed);
    }
    void synth() { synth_.fetch_add(1,std::memory_order_relaxed); }
    void fallback() { fallback_.fetch_add(1,std::memory_order_relaxed); }
    void returned() { returned_.fetch_add(1,std::memory_order_relaxed); }
    std::string report() const {
        const auto callbacks=get(callbacks_),last=get(lastCallbackMs_);
        std::ostringstream out;
        out << "AUDIO_OUTPUT starts=" << get(starts_) << " started=" << get(started_)
            << " stops=" << get(stops_) << " lastStartResult=" << lastResult_.load(std::memory_order_relaxed)
            << " callbacks=" << callbacks << " frames=" << get(frames_)
            << " synthCallbacks=" << get(synth_) << " fallbackCallbacks=" << get(fallback_)
            << " returned=" << get(returned_) << " callbacksAtLastStart=" << get(callbacksAtStart_)
            << " callbacksAtLastStop=" << get(callbacksAtStop_)
            << " callbackAgeMs=" << (callbacks?int64_t(uint32_t(nowMs()-last)):-1)
            << " scope=relaxed_process_lifetime_mod32 no_output_restart_or_volume_write\n";
        return out.str();
    }
};
// Protected by the existing BassMidiPlayer mutex, never its own lock.
struct Decode {
    uint64_t calls=0,success=0,failed=0,noStream=0,shortReads=0,bytes=0;
    int lastError=0;
    std::string report() const {
        std::ostringstream out;
        out << "PCM_DECODE calls=" << calls << " success=" << success << " failed=" << failed
            << " noStream=" << noStream << " shortReads=" << shortReads << " bytes=" << bytes
            << " lastError=" << lastError << " scope=parent_decode_attempts_not_NOTE_ON_acceptance\n";
        return out.str();
    }
};
}

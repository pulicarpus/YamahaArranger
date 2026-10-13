package com.yourapp.yamahaarranger.audio

/** Console focus is gain-only: it never switches parts off or discards note ownership. */
data class MixerConsoleState(val keyboardMuted: Set<Int> = emptySet(), val solo: Set<Int> = emptySet()) {
    fun toggleMute(channel: Int): MixerConsoleState {
        require(channel in 0..3)
        return copy(keyboardMuted = keyboardMuted.toggle(channel))
    }
    fun toggleSolo(channel: Int): MixerConsoleState {
        require(channel in 0..15)
        return copy(solo = solo.toggle(channel))
    }
    fun muted(channel: Int, manualStyleMute: Boolean = false): Boolean =
        (if (channel in 0..3) channel in keyboardMuted else manualStyleMute) ||
            (solo.isNotEmpty() && channel !in solo)
    fun keyboardGain(channel: Int, storedVolume: Int): Int =
        if (muted(channel)) 0 else storedVolume.coerceIn(0, 127)
    fun retainChannels(channels: Set<Int>) = copy(solo = solo.intersect(channels))
    private fun Set<Int>.toggle(channel: Int) = if (channel in this) this - channel else this + channel
}

data class KeyboardMixerDetail(val pan: Int = 64, val reverb: Int = 40, val chorus: Int = 0)

/** Y is measured downward; the full touchable track maps directly to the MIDI range. */
fun mixerFaderValue(y: Float, height: Float): Int {
    if (height <= 0f || !height.isFinite() || !y.isFinite()) return 64
    return kotlin.math.round((1f - (y / height).coerceIn(0f, 1f)) * 127f).toInt()
}

package com.yourapp.yamahaarranger.audio

/** Stored knob and effective CC72 are distinct; bypass never changes the stored knob. */
data class ReleaseControlState(val knobValue: Int = 64, val enabled: Boolean = true) {
    val effectiveValue: Int get() = if (enabled) knobValue.coerceIn(0, 127) else 64
    fun step(delta: Int) = copy(knobValue = (knobValue + delta).coerceIn(0, 127))
    fun toggle() = copy(enabled = !enabled)
}

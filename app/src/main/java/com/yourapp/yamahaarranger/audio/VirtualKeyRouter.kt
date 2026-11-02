package com.yourapp.yamahaarranger.audio

/**
 * On-screen keyboard -> lower keyboard area (V1C). Uses the SAME split / ACMP / LEFT policy as a USB key, by handing lower-area keys
 * to the USB path ([lowerArea] = `ArrangerBrain.onKeyboardNoteOn/Off`), which already decides CHORD and/or LEFT:
 *  - key <= split and (ACMP ON or LEFT ON)  -> lower area (chord detector and/or LEFT voice, exactly like the PSR-E343);
 *  - otherwise (upper area, or ACMP OFF + LEFT OFF = whole keyboard RIGHT) -> the existing on-screen RIGHT path [right], unchanged.
 * The route is decided at NOTE ON and remembered per key, so a NOTE OFF always reaches the path that started the note,
 * even if the split, ACMP or LEFT change while the key is held. Pure Kotlin; no new routing rule.
 */
class VirtualKeyRouter(
    private val split: () -> Int, private val acmp: () -> Boolean, private val left: () -> Boolean,
    private val lowerOn: (pitch: Int, velocity01: Float) -> Unit, private val lowerOff: (pitch: Int) -> Unit,
    private val right: VirtualKeyboardSink,
) {
    private val lower = BooleanArray(128)

    fun noteOn(pitch: Int, velocity127: Int) {
        if (pitch !in 0..127) return
        if (pitch <= split() && (acmp() || left())) {
            if (lower[pitch]) lowerOff(pitch)                 // a repeated press without release: end the previous one first
            lower[pitch] = true; lowerOn(pitch, velocity127.coerceIn(1, 127) / 127f)
        } else right.noteOn(pitch, velocity127)
    }

    fun noteOff(pitch: Int) {
        if (pitch !in 0..127) return
        if (lower[pitch]) { lower[pitch] = false; lowerOff(pitch) } else right.noteOff(pitch)
    }

    /** Panel closed / focus lost: release the lower-area keys this keyboard holds, then the RIGHT path as before. */
    fun releaseAll() {
        for (p in 0 until 128) if (lower[p]) { lower[p] = false; lowerOff(p) }
        right.releaseAll()
    }

    fun heldLower(): List<Int> = (0 until 128).filter { lower[it] }
}

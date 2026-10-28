package com.yourapp.yamahaarranger.audio

/**
 * On-screen keyboard logic (pure Kotlin, no Android types, no allocation on the touch path).
 *
 * Register: 61 keys C2..C7 in scientific pitch notation (C4 = MIDI 60), so index 0 = MIDI 36.
 * OCTAVE shifts the played register by whole octaves (clamped so every key stays inside MIDI 0..127);
 * it never transposes style, ACMP or the USB keyboard.
 */
object VirtualKeyboardLayout {
    const val KEY_COUNT = 61
    const val BASE_PITCH = 36
    const val MIN_OCTAVE_SHIFT = -3   // 36 - 36 = 0
    const val MAX_OCTAVE_SHIFT = 2    // 96 + 24 = 120 <= 127
    const val WHITE_KEY_COUNT = 36
    private const val BLACK_WIDTH = 0.6f
    private const val BLACK_HEIGHT = 0.62f

    private val BLACK_PC = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    // White-key ordinal for every white pitch class (C D E F G A B).
    private val WHITE_ORDINAL = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)

    fun isBlack(index: Int): Boolean = BLACK_PC[index % 12]

    /** Ordinal (0..35) among white keys; for a black key, the white key just to its left. */
    fun whiteOrdinal(index: Int): Int {
        val octave = index / 12
        val pc = index % 12
        return octave * 7 + if (WHITE_ORDINAL[pc] >= 0) WHITE_ORDINAL[pc] else WHITE_ORDINAL[pc - 1]
    }

    fun pitchOf(index: Int, octaveShift: Int): Int = BASE_PITCH + index + 12 * octaveShift

    fun clampOctave(shift: Int): Int = shift.coerceIn(MIN_OCTAVE_SHIFT, MAX_OCTAVE_SHIFT)

    /** Scientific name of a MIDI pitch, e.g. 60 -> "C4". */
    fun noteName(pitch: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[((pitch % 12) + 12) % 12] + (Math.floorDiv(pitch, 12) - 1)
    }

    /** Label for C keys only (blank otherwise), e.g. "C3". */
    fun cLabel(index: Int, octaveShift: Int): String =
        if (index % 12 == 0) noteName(pitchOf(index, octaveShift)) else ""

    fun totalWidth(whiteWidth: Float): Float = WHITE_KEY_COUNT * whiteWidth

    /**
     * Key index under (x, y) in keyboard coordinates, or -1. Black keys win in the upper part of the keyboard.
     * x already includes the horizontal scroll offset.
     */
    fun hitTest(x: Float, y: Float, height: Float, whiteWidth: Float): Int {
        if (x < 0f || y < 0f || y > height || whiteWidth <= 0f || x >= totalWidth(whiteWidth)) return -1
        val white = (x / whiteWidth).toInt().coerceIn(0, WHITE_KEY_COUNT - 1)
        if (y <= height * BLACK_HEIGHT) {
            // A black key straddles the boundary on the right edge of white `white` and on its left edge.
            val inKey = x - white * whiteWidth
            val half = whiteWidth * BLACK_WIDTH / 2f
            if (inKey >= whiteWidth - half) blackAfterWhite(white)?.let { return it }
            if (inKey <= half && white > 0) blackAfterWhite(white - 1)?.let { return it }
        }
        return whiteIndex(white)
    }

    fun whiteIndex(ordinal: Int): Int {
        val octave = ordinal / 7
        val steps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return octave * 12 + steps[ordinal % 7]
    }

    private fun blackAfterWhite(ordinal: Int): Int? {
        val index = whiteIndex(ordinal) + 1
        return if (index < KEY_COUNT && isBlack(index)) index else null
    }

    /** Left edge and width of key `index` in keyboard coordinates (for drawing). */
    fun keyLeft(index: Int, whiteWidth: Float): Float =
        if (isBlack(index)) (whiteOrdinal(index) + 1) * whiteWidth - whiteWidth * BLACK_WIDTH / 2f
        else whiteOrdinal(index) * whiteWidth

    fun keyWidth(index: Int, whiteWidth: Float): Float = if (isBlack(index)) whiteWidth * BLACK_WIDTH else whiteWidth
    fun blackHeight(height: Float): Float = height * BLACK_HEIGHT
}

/** Where the controller delivers note events. Implemented by ArrangerBrain's virtual RIGHT path. */
interface VirtualKeyboardSink {
    fun noteOn(pitch: Int, velocity127: Int)
    fun noteOff(pitch: Int)
    fun setSustain(enabled: Boolean)
    fun releaseAll()
}

/**
 * Pointer bookkeeping. Each finger owns exactly one pitch (recorded at NOTE_ON, so octave changes while
 * held cannot strand a note). A pitch sounds while at least one finger holds it; NOTE_OFF is sent exactly
 * once, when the last holder lets go. Sliding = release old key + press new key.
 */
class VirtualKeyboardController(private val sink: VirtualKeyboardSink) {
    private val pointerPitch = IntArray(MAX_POINTERS) { -1 }
    private val pointerIndex = IntArray(MAX_POINTERS) { -1 }
    private val pitchHolders = IntArray(128)

    var octaveShift: Int = 0
        private set
    var velocity: Int = DEFAULT_VELOCITY
        private set
    var sustain: Boolean = false
        private set

    /** Bit i set while key index i (0..60) is held by at least one finger. */
    var pressedMask: Long = 0L
        private set

    fun setOctave(shift: Int) { octaveShift = VirtualKeyboardLayout.clampOctave(shift) }
    fun setVelocity(value: Int) { velocity = value.coerceIn(1, 127) }

    fun setSustain(enabled: Boolean) {
        if (sustain == enabled) return
        sustain = enabled
        sink.setSustain(enabled)
    }

    /** `keyIndex` -1 = finger is not on any key. */
    fun pointerDown(pointerId: Int, keyIndex: Int) {
        val slot = slot(pointerId)
        if (pointerPitch[slot] >= 0) { pointerMove(pointerId, keyIndex); return }
        if (keyIndex < 0 || keyIndex >= VirtualKeyboardLayout.KEY_COUNT) return
        press(slot, keyIndex)
    }

    fun pointerMove(pointerId: Int, keyIndex: Int) {
        val slot = slot(pointerId)
        val valid = keyIndex in 0 until VirtualKeyboardLayout.KEY_COUNT
        if (pointerPitch[slot] < 0) { if (valid) press(slot, keyIndex); return }
        if (valid && pointerIndex[slot] == keyIndex) return
        release(slot)
        if (valid) press(slot, keyIndex)
    }

    fun pointerUp(pointerId: Int) { release(slot(pointerId)) }

    /** ACTION_CANCEL and any other aborted gesture. */
    fun pointerCancel(pointerId: Int) { release(slot(pointerId)) }

    fun cancelAll() { for (slot in 0 until MAX_POINTERS) release(slot) }

    /** Panel closed / app lost focus: release every finger, then everything the sink still owns (incl. sustain). */
    fun closePanel() {
        cancelAll()
        sustain = false
        sink.releaseAll()
    }

    fun activePointerCount(): Int { var n = 0; for (p in pointerPitch) if (p >= 0) n++; return n }

    private fun slot(pointerId: Int) = ((pointerId % MAX_POINTERS) + MAX_POINTERS) % MAX_POINTERS

    private fun press(slot: Int, keyIndex: Int) {
        val pitch = VirtualKeyboardLayout.pitchOf(keyIndex, octaveShift)
        if (pitch !in 0..127) return
        pointerPitch[slot] = pitch
        pointerIndex[slot] = keyIndex
        pressedMask = pressedMask or (1L shl keyIndex)
        if (++pitchHolders[pitch] == 1) sink.noteOn(pitch, velocity)
    }

    private fun release(slot: Int) {
        val pitch = pointerPitch[slot]
        if (pitch < 0) return
        val keyIndex = pointerIndex[slot]
        pointerPitch[slot] = -1
        pointerIndex[slot] = -1
        if (--pitchHolders[pitch] == 0) sink.noteOff(pitch)
        var stillHeld = false
        for (i in 0 until MAX_POINTERS) if (pointerIndex[i] == keyIndex) stillHeld = true
        if (!stillHeld) pressedMask = pressedMask and (1L shl keyIndex).inv()
    }

    companion object {
        const val MAX_POINTERS = 32
        const val DEFAULT_VELOCITY = 100
    }
}

/**
 * Virtual keyboard's own RIGHT 1/2/3 ledger, driven by ArrangerBrain under its keyboard lock.
 * Never touches the USB ownership map, chord detector, style or MIDI OUT: it only calls the injected engine
 * functions. `externallyHeld(channel, pitch)` lets a virtual NOTE_OFF yield to a USB note on the same key.
 */
class VirtualRightVoices(
    private val engineOn: (channel: Int, pitch: Int, velocity01: Float) -> Unit,
    private val engineOff: (channel: Int, pitch: Int) -> Unit,
    private val rightEnabled: (channel: Int) -> Boolean,
    private val externallyHeld: (channel: Int, pitch: Int) -> Boolean
) {
    private val down = IntArray(128)       // bit c: channel c sounding for a pressed virtual key
    private val sustained = IntArray(128)  // bit c: key released but held by virtual sustain
    private var sustainOn = false

    /** True while the virtual keyboard still owns (channel, pitch); USB releases must then yield. */
    fun owns(channel: Int, pitch: Int): Boolean =
        pitch in 0..127 && channel in 0..2 && ((down[pitch] or sustained[pitch]) and (1 shl channel)) != 0

    fun noteOn(pitch: Int, velocity127: Int) {
        if (pitch !in 0..127) return
        // Re-press (or press while sustained): stop what this keyboard left on that key first.
        val previous = down[pitch] or sustained[pitch]
        down[pitch] = 0; sustained[pitch] = 0
        for (c in 0..2) if ((previous and (1 shl c)) != 0) release(c, pitch)
        var mask = 0
        val velocity = velocity127.coerceIn(1, 127)
        RightInputProbe.routed(RightInputSource.OTHER, pitch, velocity); RightInputProbe.forwarded(RightInputSource.OTHER, velocity)
        for (c in 0..2) if (rightEnabled(c)) { engineOn(c, pitch, velocity / 127f); mask = mask or (1 shl c) }
        down[pitch] = mask
    }

    fun noteOff(pitch: Int) {
        if (pitch !in 0..127) return
        val mask = down[pitch]
        if (mask == 0) return // never a second NOTE_OFF
        down[pitch] = 0
        if (sustainOn) { sustained[pitch] = sustained[pitch] or mask; return }
        for (c in 0..2) if ((mask and (1 shl c)) != 0) release(c, pitch)
    }

    fun setSustain(enabled: Boolean) {
        if (sustainOn == enabled) return
        sustainOn = enabled
        if (!enabled) flushSustained()
    }

    fun releaseAll() {
        sustainOn = false
        for (pitch in 0..127) {
            val mask = down[pitch] or sustained[pitch]
            down[pitch] = 0; sustained[pitch] = 0
            for (c in 0..2) if ((mask and (1 shl c)) != 0) release(c, pitch)
        }
    }

    /** RIGHT part switched OFF: the engine already stopped the whole part, just forget ownership. */
    fun forgetPart(channel: Int) {
        if (channel !in 0..2) return
        val bit = (1 shl channel).inv()
        for (pitch in 0..127) { down[pitch] = down[pitch] and bit; sustained[pitch] = sustained[pitch] and bit }
    }

    private fun flushSustained() {
        for (pitch in 0..127) {
            val mask = sustained[pitch]
            if (mask == 0) continue
            sustained[pitch] = 0
            for (c in 0..2) if ((mask and (1 shl c)) != 0) release(c, pitch)
        }
    }

    private fun release(channel: Int, pitch: Int) {
        if (!externallyHeld(channel, pitch)) engineOff(channel, pitch)
    }
}

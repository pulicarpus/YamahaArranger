package com.yourapp.yamahaarranger.ui

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Pure math of the DATA CONTROL rotary encoder (no Compose, no engine): unit-tested on the JVM and used by RotaryDataKnob.
 * Angles are "dial" degrees: 0 = 12 o'clock, growing CLOCKWISE (screen coordinates: y grows downwards).
 */
object RotaryKnobMath {
    /** Dial angle of point (x, y) around (cx, cy), in [0, 360). */
    fun angle(x: Float, y: Float, cx: Float, cy: Float): Float {
        val a = Math.toDegrees(atan2((x - cx).toDouble(), (cy - y).toDouble())).toFloat()
        return if (a < 0f) a + 360f else a
    }

    /** Signed shortest turn from [from] to [to], in (-180, 180]: crossing 0/360 gives a small step, never a jump of ~360. */
    fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Value indicator on a 270 degree arc: min at -135 (7:30), max at +135 (4:30), 0 = 12 o'clock. */
    const val ARC_START = -135f
    const val ARC_SWEEP = 270f
    fun indicatorAngle(value: Int, min: Int, max: Int): Float =
        if (max <= min) ARC_START else ARC_START + ARC_SWEEP * ((value.coerceIn(min, max) - min).toFloat() / (max - min))
}

/** One parameter the knob can edit. [degreesPerStep]: finger rotation needed for one step (one detent). */
data class DataParamSpec(val name: String, val min: Int, val max: Int, val degreesPerStep: Float) {
    fun clamp(v: Int) = v.coerceIn(min, max)
    /** New value after [steps] detents from [current]; clamped to min/max. */
    fun apply(current: Int, steps: Int) = clamp(current + steps)
    /** The value to send, or null when nothing changes (at a limit, or 0 steps): the caller then sends nothing. */
    fun target(current: Int, steps: Int): Int? = apply(current, steps).takeIf { it != current }

    companion object {
        // Same limits as the existing view-model paths: tempo 20..280 (ArrangerBrain.setTempo), transpose -12..12, release knob 0..127.
        val TEMPO = DataParamSpec("TEMPO", 20, 280, 6f)
        val TRANSPOSE = DataParamSpec("TRANSPOSE", -12, 12, 22f)
        val RELEASE = DataParamSpec("RELEASE", 0, 127, 4f)
        val ALL = listOf(TEMPO, TRANSPOSE, RELEASE)
        fun of(name: String) = ALL.firstOrNull { it.name == name } ?: TEMPO
    }
}

/**
 * Relative rotary encoder: touch-down only anchors (a tap never changes the value), every move adds the shortest signed
 * angle (wraps through 0/360 without a jump), whole detents are emitted and the remainder is kept for precision.
 * Touches too close to the centre are ignored (the angle is unstable there) and the anchor is re-taken when the finger leaves it.
 */
class RotaryAccumulator(private val deadZoneFraction: Float = 0.22f) {
    private var last: Float? = null
    private var acc = 0f
    /** Total signed rotation since touch-down (for the visual "grip" mark). */
    var turned = 0f; private set

    fun down(x: Float, y: Float, cx: Float, cy: Float, radius: Float) {
        acc = 0f; turned = 0f
        last = if (inside(x, y, cx, cy, radius)) null else RotaryKnobMath.angle(x, y, cx, cy)
    }

    /** Returns whole detents for this move (positive = clockwise). */
    fun move(x: Float, y: Float, cx: Float, cy: Float, radius: Float, degreesPerStep: Float): Int {
        if (inside(x, y, cx, cy, radius)) { last = null; return 0 }
        val a = RotaryKnobMath.angle(x, y, cx, cy)
        val prev = last
        last = a
        if (prev == null) return 0                     // first sample after down / after leaving the centre: anchor only
        val d = RotaryKnobMath.shortestDelta(prev, a)
        acc += d; turned += d
        // EPS: many tiny float samples (e.g. 40 x 0.5 deg) add up to 19.99998, which must still count as the 5th detent of 4 deg
        val steps = if (acc >= 0f) floor((acc + EPS) / degreesPerStep).toInt() else -floor((-acc + EPS) / degreesPerStep).toInt()
        acc -= steps * degreesPerStep
        return steps
    }

    fun up() { last = null; acc = 0f }

    private companion object { const val EPS = 1e-3f }

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, radius: Float) = hypot(x - cx, y - cy) < radius * deadZoneFraction
}

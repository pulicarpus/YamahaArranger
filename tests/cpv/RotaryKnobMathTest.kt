package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.ui.DataParamSpec
import com.yourapp.yamahaarranger.ui.RotaryAccumulator
import com.yourapp.yamahaarranger.ui.RotaryKnobMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** DATA CONTROL rotary encoder math (pure): direction, 0/360 wrap, tap, dead zone, detents with remainder, limits, no-op detection. */
class RotaryKnobMathTest {
    private val cx = 100f; private val cy = 100f; private val r = 100f
    private fun px(deg: Float, rad: Float = 70f) = cx + rad * sin(Math.toRadians(deg.toDouble())).toFloat()
    private fun py(deg: Float, rad: Float = 70f) = cy - rad * cos(Math.toRadians(deg.toDouble())).toFloat()
    /** Finger path from [from] to [to] dial degrees in 1-degree samples; returns the sum of emitted detents. */
    private fun turn(acc: RotaryAccumulator, from: Float, to: Float, dps: Float): Int {
        acc.down(px(from), py(from), cx, cy, r)
        var sum = 0; val n = kotlin.math.abs(to - from).toInt(); val dir = if (to >= from) 1f else -1f
        for (i in 1..n) { val a = from + dir * i; sum += acc.move(px(a), py(a), cx, cy, r, dps) }
        acc.up(); return sum
    }

    @Test fun anglesAreDialDegreesClockwiseFromTwelve() {
        assertEquals(0f, RotaryKnobMath.angle(cx, cy - 50, cx, cy), 1e-3f)
        assertEquals(90f, RotaryKnobMath.angle(cx + 50, cy, cx, cy), 1e-3f)
        assertEquals(180f, RotaryKnobMath.angle(cx, cy + 50, cx, cy), 1e-3f)
        assertEquals(270f, RotaryKnobMath.angle(cx - 50, cy, cx, cy), 1e-3f)
    }
    @Test fun shortestDeltaCrossesZeroWithoutAJump() {
        assertEquals(20f, RotaryKnobMath.shortestDelta(350f, 10f), 1e-3f)
        assertEquals(-20f, RotaryKnobMath.shortestDelta(10f, 350f), 1e-3f)
        assertEquals(5f, RotaryKnobMath.shortestDelta(358f, 3f), 1e-3f)
        assertEquals(-90f, RotaryKnobMath.shortestDelta(45f, 315f), 1e-3f)
    }
    @Test fun clockwiseUpCounterClockwiseDown() {
        assertEquals(15, turn(RotaryAccumulator(), 30f, 120f, 6f))
        assertEquals(-10, turn(RotaryAccumulator(), 200f, 140f, 6f))
    }
    @Test fun turningThroughTwelveOClockIsContinuous() {
        assertEquals("330 -> 390 (= 30): +60 deg = +10 detents", 10, turn(RotaryAccumulator(), 330f, 390f, 6f))
        assertEquals("30 -> -30: -60 deg = -10 detents", -10, turn(RotaryAccumulator(), 30f, -30f, 6f))
        assertEquals("two full turns", 120, turn(RotaryAccumulator(), 0f, 720f, 6f))
    }
    @Test fun aTapNeverChangesTheValue() {
        val acc = RotaryAccumulator()
        acc.down(px(77f), py(77f), cx, cy, r)
        assertEquals(0, acc.move(px(77f), py(77f), cx, cy, r, 6f))
        assertEquals("a tiny wobble below one detent", 0, acc.move(px(80f), py(80f), cx, cy, r, 6f) + acc.move(px(75f), py(75f), cx, cy, r, 6f))
        acc.up()
    }
    @Test fun remainderIsKeptForPrecisionAndSlowTurns() {
        val acc = RotaryAccumulator(); acc.down(px(0f), py(0f), cx, cy, r)
        var sum = 0; for (i in 1..40) sum += acc.move(px(i * 0.5f), py(i * 0.5f), cx, cy, r, 4f)   // 20 deg in 0.5 deg samples
        assertEquals("20 deg / 4 deg per step = 5 even with tiny samples", 5, sum)
    }
    @Test fun centreDeadZoneReAnchorsWithoutAJump() {
        val acc = RotaryAccumulator(); acc.down(px(0f), py(0f), cx, cy, r)
        assertEquals(0, acc.move(cx + 1f, cy + 1f, cx, cy, r, 6f))                      // through the middle
        assertEquals("re-anchored at 180, no jump of 180 deg", 0, acc.move(px(180f), py(180f), cx, cy, r, 6f))
        assertEquals(1, acc.move(px(186f), py(186f), cx, cy, r, 6f))
    }
    @Test fun limitsAndNoOpDetection() {
        val t = DataParamSpec.TEMPO
        assertEquals(280, t.apply(277, 10)); assertNull("at max: nothing to send", t.target(280, 3)); assertNull(t.target(20, -1)); assertNull(t.target(120, 0))
        assertEquals(121, t.target(120, 1))
        val x = DataParamSpec.TRANSPOSE
        assertEquals(12, x.target(11, 5)); assertNull(x.target(12, 1)); assertEquals(-12, x.target(-10, -9)); assertNull(x.target(-12, -1))
        val rel = DataParamSpec.RELEASE
        assertEquals(127, rel.target(120, 50)); assertNull(rel.target(0, -3)); assertEquals(63, rel.target(64, -1))
        assertEquals(DataParamSpec.RELEASE, DataParamSpec.of("RELEASE")); assertEquals("unknown -> TEMPO", DataParamSpec.TEMPO, DataParamSpec.of("X"))
    }
    @Test fun indicatorUsesA270DegreeArc() {
        assertEquals(-135f, RotaryKnobMath.indicatorAngle(20, 20, 280), 1e-3f)
        assertEquals(135f, RotaryKnobMath.indicatorAngle(280, 20, 280), 1e-3f)
        assertEquals(0f, RotaryKnobMath.indicatorAngle(0, -12, 12), 1e-3f)
        assertEquals("clamped", 135f, RotaryKnobMath.indicatorAngle(999, 0, 127), 1e-3f)
    }
}

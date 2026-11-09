package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.audio.Pb02Core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-B0.2: exact, synthetic timelines (no clocks, no threads). Proves the per-event split sums to the lateness, typed time is never
 * counted twice in the thread-time accounting, an inherited oversleep is attributed to WAKE (not Kotlin), ring eviction becomes
 * UNKNOWN (never guessed), nested calls stay disjoint, and section changes are reported as BOUNDARY totals.
 */
class Pb02ClassifierTest {
    private val ms = 1_000_000L
    private fun us(x: Double) = (x * ms).toLong()

    @Test fun idleBacklogAndInheritedOversleep() {
        val c = Pb02Core()
        c.wake(0, 0)                                   // first wake: anchors the timeline, not classified
        c.jni(us(1.0), us(2.0))                        // event 1 work: 1 ms JNI
        c.wake(us(13.0), us(10.0))                     // event 2 due 10, nothing observed after 2 -> IDLE, 3 ms wake overrun
        assertEquals(1, c.idleCase.get()); assertEquals(us(3.0), c.partSum[0].get())
        c.jni(us(13.5), us(16.0)); c.midiOut(us(16.0), us(16.5))
        c.wake(us(17.0), us(11.0))                     // event 3 due 11 (busy at due) -> BACKLOG over [11,17] = 6 ms
        // [11,13] = inherited oversleep of event 2 (WAKE), [13.5,16] JNI, [16,16.5] MIDI OUT, rest 1 ms Kotlin
        assertEquals(1, c.backlogCase.get())
        c.jni(us(17.2), us(18.0))
        c.wake(us(18.4), us(11.0))                     // event 4 same tick: [11,18.4] = 7.4 ms
        assertEquals(0, c.mismatches.get())
        // per-event parts (events 2,3,4): wake 3 + 2 + 2, jni 0 + 2.5 + 3.3, midiOut 0 + 0.5 + 0.5, kotlin 0 + 1.0 + 1.6
        assertEquals(us(7.0), c.partSum[0].get()); assertEquals(us(5.8), c.partSum[1].get())
        assertEquals(us(1.0), c.partSum[2].get()); assertEquals(us(2.6), c.partSum[4].get()); assertEquals(0L, c.partSum[5].get())
        assertEquals(c.latenessSum.get(), (0 until 6).sumOf { c.partSum[it].get() })
        // thread-time accounting: every ns between wakes once. Oversleep counted ONCE (3 ms) although two later events inherit it.
        val t = c.timeline.map { it.get() }
        assertEquals(us(18.4), c.span.get()); assertEquals(c.span.get(), t.sum())
        assertEquals(us(8.0), t[0]); assertEquals(us(3.0), t[1]); assertEquals(us(1.0 + 2.5 + 0.8), t[2]); assertEquals(us(0.5), t[3])
        assertEquals(us(1.0 + 0.5 + 0.5 + 0.2 + 0.4), t[5])
    }

    @Test fun ringEvictionIsUnknownNotGuessed() {
        val c = Pb02Core(ringSize = 4)
        c.wake(0, 0)
        var t = 0.0
        repeat(10) { c.jni(us(t + 0.1), us(t + 1.0)); t += 1.0 }   // ten 0.9 ms calls; the ring keeps only the last four
        c.wake(us(10.0), us(0.5))                                  // lateness 9.5 ms reaching back past the ring
        assertTrue(c.partSum[5].get() > 0)                          // older part = UNKNOWN
        assertEquals(c.latenessSum.get(), (0 until 6).sumOf { c.partSum[it].get() })
        assertEquals(us(4 * 0.9), c.partSum[1].get())               // only the retained four are attributed to JNI
        assertEquals(0, c.mismatches.get())
    }

    @Test fun nestedCallsStayDisjoint() {
        val c = Pb02Core()
        c.wake(0, 0)
        c.jni(us(1.0), us(2.0))                                     // inner call recorded first (fallback path nests calls)
        c.jni(us(0.5), us(3.0))                                     // outer: only [2,3] is new
        c.wake(us(4.0), us(0.2))
        assertEquals(us(2.0), c.partSum[1].get())                   // 1 + 1, never 1 + 2.5
        assertEquals(c.span.get(), c.timeline.sumOf { it.get() }); assertEquals(0, c.mismatches.get())
    }

    /** Cost per scheduler event with the probe ON: 2 JNI stamps + 1 MIDI OUT stamp + one wake classification (backlog, ~40 intervals back). */
    @Test fun overheadMicrobench() {
        val c = Pb02Core()
        var t = 1_000_000_000L; c.wake(t, t)
        val n = 200_000
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val d = t - (i % 20) * 200_000L                       // clusters: up to 20 events behind
            c.jni(t + 10_000, t + 60_000); c.midiOut(t + 60_000, t + 70_000); c.jni(t + 80_000, t + 120_000)
            t += 150_000; c.wake(t, d)
        }
        val perEvent = (System.nanoTime() - t0).toDouble() / n
        println("PB02OVERHEAD nsPerEvent=%.0f (host JVM, classification + 3 stamps recorded)".format(perEvent))
        assertEquals(0, c.mismatches.get()); assertTrue(perEvent < 50_000)
    }

    @Test fun sectionChangeIsBoundaryTotalOnly() {
        val c = Pb02Core()
        c.wake(0, 0)
        c.jni(us(1.0), us(2.0)); c.voiceBegin(us(2.0)); c.jni(us(3.0), us(4.0)) /* inside voice: not separate */; c.voiceEnd(us(12.0))
        c.wake(us(20.0), us(15.0))                                  // unhooked waits may be inside: BOUNDARY, not split
        c.wake(us(21.0), us(15.0))                                  // same tick, reaches back into the change: BOUNDARY
        c.jni(us(21.5), us(22.0)); c.wake(us(30.0), us(29.0))     // later, after the change: normal (idle)
        assertEquals(2, c.boundaryCase.get()); assertEquals(1, c.idleCase.get()); assertEquals(us(1.0), c.latenessSum.get())
        assertEquals(us(10.0), c.timeline[4].get())                 // voice 2..12 once (its JNI is not counted again)
        assertEquals(us(1.0 + 0.5), c.timeline[2].get())
        assertEquals(c.span.get(), c.timeline.sumOf { it.get() }); assertEquals(0, c.mismatches.get())
        println("PB02CLASSIFIER " + c.report().lines().first())
    }
}

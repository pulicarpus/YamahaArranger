package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.LatencyProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CpvLatencyProbeTest {
    @Before fun clean() { LatencyProbe.enabled = true; LatencyProbe.reset() }
    @After fun restore() { LatencyProbe.enabled = true }

    @Test fun statBucketsPercentilesAndMax() {
        val s = LatencyProbe.Stat("t")
        assertEquals(0, s.percentileUpperUs(0.5))
        repeat(90) { s.record(100_000L) }       // 100 us -> bucket <250
        repeat(9) { s.record(3_000_000L) }      // 3 ms   -> bucket <4000
        s.record(200_000_000L)                  // 200 ms -> open bucket
        assertEquals(100, s.count())
        assertEquals(250, s.percentileUpperUs(0.50))
        assertEquals(4000, s.percentileUpperUs(0.99))
        assertEquals(200_000, s.percentileUpperUs(1.0))
        assertEquals(200_000, s.maxUs())
        s.record(-5L)                           // clock anomalies are ignored
        assertEquals(100, s.count())
        assertTrue(s.line().startsWith("LATENCY_KOTLIN stat=t count=100 "))
    }

    @Test fun midiIngressUsesUsbTimestampOnlyWhenPlausible() {
        val now = System.nanoTime()
        LatencyProbe.onSendEnter(now - 3_000_000L)   // event stamped 3 ms ago
        LatencyProbe.noteOnParsed()
        assertEquals(1, LatencyProbe.midiIngressLag.count())
        assertTrue(LatencyProbe.midiIngressLag.maxUs() in 3000..200_000)
        LatencyProbe.onSendEnter(0L)                 // 0 = "no timestamp"
        LatencyProbe.noteOnParsed()
        LatencyProbe.onSendEnter(System.nanoTime() + 5_000_000_000L) // future stamp
        LatencyProbe.noteOnParsed()
        LatencyProbe.onSendEnter(System.nanoTime() - 5_000_000_000L) // stale stamp
        LatencyProbe.noteOnParsed()
        assertEquals(1, LatencyProbe.midiIngressLag.count())
        LatencyProbe.onSendExit()
        assertTrue(LatencyProbe.midiOnSendTotal.count() >= 1)
    }

    @Test fun rightNoteHandoffAndLockWaitAreRecorded() {
        LatencyProbe.onSendEnter(0L); LatencyProbe.noteOnParsed()
        LatencyProbe.keyboardLockAcquired(System.nanoTime() - 1_000_000L)
        LatencyProbe.rightHandedOff()
        LatencyProbe.jniKeyboardNote(System.nanoTime() - 500_000L)
        assertEquals(1, LatencyProbe.keyboardLockWait.count())
        assertEquals(1, LatencyProbe.rightIngressToHandoff.count())
        assertEquals(1, LatencyProbe.jniKeyboardNote.count())
        assertTrue(LatencyProbe.keyboardLockWait.maxUs() >= 1000)
    }

    @Test fun sectionTransitionSeparatesQuantizeReleaseApplyAndFirstNote() {
        val base = System.nanoTime()
        LatencyProbe.sectionRequested()
        LatencyProbe.sectionBoundary()
        LatencyProbe.voiceApplyStart()
        LatencyProbe.voiceApplyEnd()
        assertEquals(1, LatencyProbe.sectionRequestToBoundary.count())
        assertEquals(1, LatencyProbe.sectionBoundaryToApplyStart.count())
        assertEquals(1, LatencyProbe.sectionApply.count())
        assertEquals(0, LatencyProbe.sectionBoundaryToFirstNote.count())   // waits for the first NOTE_ON
        LatencyProbe.styleEventDispatched(System.nanoTime(), base, false)  // a non-note does not count
        assertEquals(0, LatencyProbe.sectionBoundaryToFirstNote.count())
        LatencyProbe.styleEventDispatched(System.nanoTime(), base, true)
        LatencyProbe.styleEventDispatched(System.nanoTime(), base, true)   // only the first note per transition
        assertEquals(1, LatencyProbe.sectionApplyEndToFirstNote.count())
        assertEquals(1, LatencyProbe.sectionBoundaryToFirstNote.count())
        assertEquals(1, LatencyProbe.firstNoteLateness.count())
        assertEquals(3, LatencyProbe.schedulerDispatchLateness.count())
        // A plain start with no preceding boundary must not produce a first-note sample.
        LatencyProbe.voiceApplyStart(); LatencyProbe.voiceApplyEnd()
        LatencyProbe.styleEventDispatched(System.nanoTime(), base, true)
        assertEquals(1, LatencyProbe.sectionBoundaryToFirstNote.count())
    }

    @Test fun chordTimingStartsAtTheLastKeyboardNote() {
        LatencyProbe.onSendEnter(0L); LatencyProbe.noteOnParsed(); LatencyProbe.chordNoteParsed()
        val start = LatencyProbe.chordApplyStart()
        LatencyProbe.chordApplyEnd(start)
        assertEquals(1, LatencyProbe.chordMidiToApplyStart.count())
        assertEquals(1, LatencyProbe.chordRetarget.count())
        assertEquals(1, LatencyProbe.chordMidiToRetargetEnd.count())
        LatencyProbe.chordApplyEnd(0L)                                       // disabled/unknown start is ignored
        assertEquals(1, LatencyProbe.chordRetarget.count())
    }

    @Test fun disabledProbeRecordsNothingAndReportMarksDispatchAsNotAudible() {
        LatencyProbe.enabled = false
        LatencyProbe.sectionRequested(); LatencyProbe.sectionBoundary(); LatencyProbe.voiceApplyStart(); LatencyProbe.voiceApplyEnd()
        LatencyProbe.keyboardLockAcquired(System.nanoTime()); LatencyProbe.jniKeyboardNote(System.nanoTime())
        assertEquals(0, LatencyProbe.sectionApply.count()); assertEquals(0, LatencyProbe.keyboardLockWait.count())
        LatencyProbe.enabled = true
        val text = LatencyProbe.report()
        assertTrue(text.contains("dispatch_is_not_audible_time=1"))
        assertTrue(text.lines().count { it.startsWith("LATENCY_KOTLIN stat=") } == 16)
        LatencyProbe.reset()
        assertEquals(0, LatencyProbe.sectionApply.count())
    }
}

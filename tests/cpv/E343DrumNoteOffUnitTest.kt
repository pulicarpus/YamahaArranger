package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.arranger.E343DrumNoteOffExperiment
import com.yourapp.yamahaarranger.arranger.E343DrumNoteOffMode as Mode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pure pairing logic of the E343 drum NOTE OFF experiment against a fake router (counts per note + epoch, like MidiMixerOutput.noteProbe). */
class E343DrumNoteOffUnitTest {
    private val E343DrumNoteOff = E343DrumNoteOffExperiment()   // own instance: independent of the app singleton and of other tests
    private val wire: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
    private val count = IntArray(128); private var epoch = 0L; private var active = true
    private fun on(n: Int) { if (active) { count[n]++; wire += "ON$n" } }
    private fun off(n: Int) { if (active) { count[n] = (count[n] - 1).coerceAtLeast(0); wire += "OFF$n" } }
    private fun play(styleNote: Int, sent: Int = styleNote, ch: Int = 9) = E343DrumNoteOff.noteOn(ch, styleNote, sent, { on(sent) }, { off(it) })

    @Before fun setUp() {
        E343DrumNoteOff.setMode(Mode.LEGACY); E343DrumNoteOff.resetStats(); E343DrumNoteOff.setGate(120)
        E343DrumNoteOff.styleName = "EnglishWaltz2.S519.prs"
        E343DrumNoteOff.probe = { _, n -> (epoch shl 32) or (if (active) count[n].toLong() else 0L) }
    }
    @After fun tearDown() { E343DrumNoteOff.setMode(Mode.LEGACY); E343DrumNoteOff.probe = null; E343DrumNoteOff.styleName = "" }

    @Test fun legacyIsUntouched() {
        play(26); assertFalse(E343DrumNoteOff.authoredOff(9, 26)); assertEquals(0, E343DrumNoteOff.releaseAll("t"))
        assertEquals(listOf("ON26"), wire); assertEquals(0, E343DrumNoteOff.openCount())
    }
    @Test fun scopeIsOnlyEnglishWaltz2Channel10Note26WithARouter() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        assertTrue(E343DrumNoteOff.inScope(9, 26))
        assertFalse(E343DrumNoteOff.inScope(8, 26)); assertFalse(E343DrumNoteOff.inScope(9, 25)); assertFalse(E343DrumNoteOff.inScope(9, 27))
        E343DrumNoteOff.styleName = "ROSITA.STY"; assertFalse(E343DrumNoteOff.inScope(9, 26))
        E343DrumNoteOff.styleName = "EnglishWaltz2.S519.prs"; E343DrumNoteOff.probe = null; assertFalse(E343DrumNoteOff.inScope(9, 26))
    }
    @Test fun authoredOffUsesTheSentNumberAfterTheDrumMap() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        play(26, sent = 85); assertTrue(E343DrumNoteOff.authoredOff(9, 26))
        assertEquals(listOf("ON85", "OFF85"), wire); assertEquals(0, E343DrumNoteOff.openCount())
        assertFalse("a second NOTE OFF is never sent", E343DrumNoteOff.authoredOff(9, 26))
    }
    @Test fun otherNotesAndRhythm1NeverGetANoteOff() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        play(25); play(27); play(26, ch = 8)
        assertFalse(E343DrumNoteOff.authoredOff(9, 25)); assertFalse(E343DrumNoteOff.authoredOff(8, 26))
        assertEquals(listOf("ON25", "ON27", "ON26"), wire)
    }
    @Test fun retriggerClosesThePreviousInstanceFirst() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        play(26); play(26); assertTrue(E343DrumNoteOff.authoredOff(9, 26)); assertFalse(E343DrumNoteOff.authoredOff(9, 26))
        assertEquals(listOf("ON26", "OFF26", "ON26", "OFF26"), wire)
    }
    @Test fun releaseAllAndModeChangeCloseOnlyOurNote() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        play(26); assertEquals(1, E343DrumNoteOff.releaseAll("STOP")); assertEquals(listOf("ON26", "OFF26"), wire)
        play(26); E343DrumNoteOff.setMode(Mode.LEGACY); assertEquals(listOf("ON26", "OFF26", "ON26", "OFF26"), wire)
        assertTrue("no All Notes Off / other notes", wire.all { it.endsWith("26") })
    }
    @Test fun disconnectOrDisableBetweenOnAndOffSendsNothing() {
        E343DrumNoteOff.setMode(Mode.AUTHORED)
        play(26); epoch++   // router released it on disable/disconnect
        assertFalse(E343DrumNoteOff.authoredOff(9, 26)); assertEquals(listOf("ON26"), wire)
        active = false; play(26); assertEquals("not written -> not tracked", 0, E343DrumNoteOff.openCount()); active = true
        assertTrue(E343DrumNoteOff.report().contains("dropped=2"))
    }
    @Test fun shortGateFiresOnceAndAStaleTimerNeverClosesANewerNote() {
        E343DrumNoteOff.setGate(40); E343DrumNoteOff.setMode(Mode.SHORT_GATE)
        play(26); Thread.sleep(150)
        assertEquals(listOf("ON26", "OFF26"), wire.toList())
        play(26); Thread.sleep(10); play(26)                                    // retrigger before the gate: first is closed by the retrigger
        Thread.sleep(150)
        assertEquals(listOf("ON26", "OFF26", "ON26", "OFF26", "ON26", "OFF26"), wire.toList())
        assertEquals(0, E343DrumNoteOff.openCount())
        play(26); assertTrue("authored OFF before the gate wins", E343DrumNoteOff.authoredOff(9, 26)); Thread.sleep(100)
        assertEquals("the gate timer of a closed note sends nothing", 8, wire.size)
    }
    @Test fun gateSettingsAreBounded() {
        E343DrumNoteOff.setGate(5); assertEquals(10, E343DrumNoteOff.gateMs); E343DrumNoteOff.setGate(5000); assertEquals(1000, E343DrumNoteOff.gateMs)
        E343DrumNoteOff.setGate(300); E343DrumNoteOff.nextGate(); assertEquals(40, E343DrumNoteOff.gateMs)
    }
}

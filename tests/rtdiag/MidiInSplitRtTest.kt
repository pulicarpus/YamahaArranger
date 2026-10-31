package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Test

/** USB MIDI IN -> parser -> Brain/engine/visualizer on the production chain: the ACMP/LEFT routing table in both areas, split moves, the channel filter. HOST only; no PSR-E343. */
class MidiInSplitRtTest {
    private val LEFT_KEY = 48            // below the default split 54 (F#3)
    private val RIGHT_KEY = 72
    private fun rig(): RtRig { DebugLog.clear(); return RtRig(true, 120) }
    private fun RtRig.ons(ch: Int) = engineEvents.toList().filter { it.kind == 'O' && it.ch == ch }.map { it.note }
    private fun RtRig.onsAfter(m: Int, ch: Int) = engineEvents.toList().drop(m).filter { it.kind == 'O' && it.ch == ch }.map { it.note }
    private fun RtRig.offs(ch: Int) = engineEvents.toList().filter { it.kind == 'F' && it.ch == ch }.map { it.note }
    private fun RtRig.mode(acmp: Boolean, left: Boolean) { brain.setAcmpEnabled(acmp); brain.setLeftVoiceEnabled(left); settle(20) }

    @Test fun routingTableHoldsInBothAreasAndEveryKeyIsVisualized() {
        for ((acmp, left) in listOf(false to false, false to true, true to false, true to true)) {
            val r = rig()
            try {
                r.mode(acmp, left)
                r.noteOn(LEFT_KEY, 90); r.noteOn(RIGHT_KEY, 90); r.settle(30)
                val tag = "ACMP=${if (acmp) "ON" else "OFF"} LEFT=${if (left) "ON" else "OFF"}"
                assertTrue("$tag: right area is always RIGHT", RIGHT_KEY in r.ons(0))
                if (!acmp && !left) assertTrue("$tag: whole keyboard is RIGHT, including the left area", LEFT_KEY in r.ons(0) && r.ons(3).isEmpty())
                if (!acmp && left) assertTrue("$tag: left area plays LEFT, not RIGHT", LEFT_KEY in r.ons(3) && LEFT_KEY !in r.ons(0))
                if (acmp && !left) assertTrue("$tag: left area controls the chord only, no RIGHT/LEFT voice", LEFT_KEY !in r.ons(0) && r.ons(3).isEmpty())
                if (acmp && left) assertTrue("$tag: left area plays LEFT and feeds the chord", LEFT_KEY in r.ons(3) && LEFT_KEY !in r.ons(0))
                assertEquals("$tag: both zones are lit in the visualizer whatever the sound does", listOf(LEFT_KEY, RIGHT_KEY), r.midi.liveKeys.downNotes())
                r.noteOff(LEFT_KEY); r.noteOff(RIGHT_KEY); r.settle(30)
                assertEquals("$tag: both released", 0, r.midi.liveKeys.downCount())
                assertEquals("$tag: engine notes released (no stuck note)", 0, r.oracle.stuckOnChannels(0..3))
                println("RTDIAG SPLITTABLE $tag leftKey->${when { !acmp && !left -> "RIGHT"; !acmp -> "LEFT"; !left -> "CHORD only"; else -> "CHORD+LEFT" }} rightKey->RIGHT")
            } finally { r.close() }
        }
    }

    @Test fun aChordInTheLeftAreaIsRecognisedWithAcmpOnEvenWithLeftOff() {
        val r = rig()
        try {
            r.mode(true, false)
            listOf(36, 40, 43).forEach { r.noteOn(it, 90) }; r.settle(80)
            assertTrue("chord recognised: ${r.brain.state.value.currentChordLabel}", r.brain.state.value.currentChordLabel.isNotEmpty())
            assertEquals(listOf(36, 40, 43), r.midi.liveKeys.downNotes()); assertTrue("no voice sounds for chord keys", r.ons(0).isEmpty() && r.ons(3).isEmpty())
            listOf(36, 40, 43).forEach { r.noteOff(it) }
        } finally { r.close() }
    }

    @Test fun theSplitMovesOneSemitoneAtATimeAndTheKeyAtTheSplitIsLeft() {
        val r = rig()
        try {
            r.mode(false, true)
            for (split in 55..60) {
                r.brain.setSplitNote(split)
                val m = r.engineEvents.size
                r.noteOn(split, 80); r.noteOn(split + 1, 80); r.settle(15)
                assertTrue("split $split: the key AT the split is LEFT", split in r.onsAfter(m, 3) && split !in r.onsAfter(m, 0))
                assertTrue("split $split: the next key is RIGHT", (split + 1) in r.onsAfter(m, 0))
                r.noteOff(split); r.noteOff(split + 1); r.settle(15)
            }
            assertEquals(60, r.brain.splitNoteValue()); assertEquals(36, r.brain.setSplitNote(0)); assertEquals(95, r.brain.setSplitNote(500))
        } finally { r.close() }
    }

    @Test fun movingTheSplitWhileKeysAreHeldNeverStrandsANote() {
        val r = rig()
        try {
            r.mode(false, true)
            r.noteOn(56, 90); r.noteOn(50, 90); r.settle(20)               // 56 = RIGHT, 50 = LEFT under split 54
            r.brain.setSplitNote(60); r.settle(10)                          // 56 would now be LEFT; 50 stays LEFT
            r.noteOff(56); r.noteOff(50); r.settle(40)
            assertTrue("the RIGHT note finished on RIGHT", 56 in r.offs(0)); assertTrue("the LEFT note finished on LEFT", 50 in r.offs(3))
            assertEquals(0, r.oracle.stuckOnChannels(0..3)); assertEquals(0, r.midi.liveKeys.downCount())
            val m = r.engineEvents.size
            r.noteOn(56, 90); r.settle(15); assertTrue("the new split applies from the next NOTE_ON", 56 in r.onsAfter(m, 3) && 56 !in r.onsAfter(m, 0)); r.noteOff(56)
        } finally { r.close() }
    }

    @Test fun movingTheSplitDoesNotTouchTheChordContextOrTheRunningStyle() {
        val r = rig()
        try {
            r.mode(true, false)
            listOf(36, 40, 43).forEach { r.noteOn(it, 90) }; r.settle(60)
            val chord = r.brain.state.value.currentChordLabel; assertTrue(chord.isNotEmpty())
            r.brain.startStop(); r.settle(250)
            val before = r.engineEvents.count { it.kind == 'O' && it.ch in 8..14 }; val panics = r.engineEvents.count { it.kind == 'A' }
            for (s in listOf(55, 56, 57, 50, 54)) { r.brain.setSplitNote(s); r.settle(30) }
            r.settle(300)
            assertEquals("chord context kept", chord, r.brain.state.value.currentChordLabel)
            assertEquals("no all-notes-off from a split move", panics, r.engineEvents.count { it.kind == 'A' })
            assertTrue("Style kept playing", r.engineEvents.count { it.kind == 'O' && it.ch in 8..14 } > before)
            listOf(36, 40, 43).forEach { r.noteOff(it) }; r.brain.startStop(); r.settle(150)
        } finally { r.close() }
    }

    @Test fun anIgnoredChannelIsCountedAndShownButNeverPlayed_andCanBeSwitchedOn() {
        val r = rig()
        try {
            r.mode(false, false)
            r.noteOn(45, 90, ch = 2); r.settle(20)                                     // E343 sends a zone on channel 3 only
            assertTrue("not played", r.ons(0).isEmpty() && r.ons(3).isEmpty()); assertEquals("not a played key", 0, r.midi.liveKeys.downCount())
            assertEquals("shown as received-but-ignored", listOf(45), r.midi.liveKeys.ignoredNotes())
            val report = r.midi.inputStats.summary(54); assertTrue(report, report.contains("ch3") && report.contains("IGNORED by the channel filter") && report.contains("only LEFT-area keys"))
            r.noteOff(45, ch = 2); assertTrue(r.midi.liveKeys.ignoredNotes().isEmpty())
            r.noteOn(60, 90, ch = 0); r.noteOff(60, ch = 5)                              // a NOTE_OFF on another channel must not release a played key
            assertEquals(listOf(60), r.midi.liveKeys.downNotes()); r.noteOff(60, ch = 0)
            r.midi.extraInputChannels = 1 shl 2                                           // user switches channel 3 on
            r.noteOn(45, 90, ch = 2); r.settle(20)
            assertTrue("now played as RIGHT", 45 in r.ons(0)); assertEquals(listOf(45), r.midi.liveKeys.downNotes()); assertTrue(r.midi.liveKeys.ignoredNotes().isEmpty())
            r.feed(0x92, 45, 0); r.settle(20); assertEquals("velocity 0 releases it", 0, r.midi.liveKeys.downCount()); assertEquals(0, r.oracle.stuckOnChannels(0..3))
        } finally { r.close() }
    }

    @Test fun styleOutputChannelsOfTheKeyboardAreNeverPlayedOrShownAndCannotBeEnabled() {
        val r = rig()
        try {
            r.midi.extraInputChannels = 0xFFFF                                            // even a bad value must not open channels 9..16
            r.noteOn(60, 90, ch = 9); r.noteOn(62, 90, ch = 12); r.settle(20)
            assertTrue(r.ons(0).isEmpty()); assertEquals(0, r.midi.liveKeys.downCount()); assertTrue("and no fake highlight", r.midi.liveKeys.ignoredNotes().isEmpty())
            assertEquals("counted for the diagnostic", 1, r.midi.inputStats.noteOnCount(9))
            r.midi.extraInputChannels = 0; r.midi.close()
        } finally { r.close() }
    }
}

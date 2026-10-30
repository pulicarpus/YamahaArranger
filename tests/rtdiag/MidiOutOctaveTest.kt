package com.yourapp.rtdiag

import com.yourapp.midi.MidiMixerOutput
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Test

/**
 * MIDI OUT octave correction: router contracts (note arithmetic, NOTE_ON/NOTE_OFF pairing, range, changes while held, panic) and the production chain
 * (Style sequencer -> MidiInputManager -> router) compared against what the internal engine received. HOST evidence only; the PSR-E343 is not simulated.
 */
class MidiOutOctaveTest {
    private class Router {
        val out = ArrayList<ByteArray>()
        val r = MidiMixerOutput { out.add(it.copyOf()); true }.also { it.setChannels((4..15).toSet()); it.setEnabled(true); it.setConnected(true) }
        init { out.clear() }
        fun on(ch: Int, n: Int, v: Int = 100) = r.noteOn(ch, n, v)
        fun off(ch: Int, n: Int) = r.noteOff(ch, n)
        fun notes(kind: Int, ch: Int) = out.filter { (it[0].toInt() and 0xF0) == kind && (it[0].toInt() and 15) == ch }.map { it[1].toInt() }
        fun ons(ch: Int) = notes(0x90, ch); fun offs(ch: Int) = notes(0x80, ch)
    }


    /** In wire order: every NOTE_OFF must close a NOTE_ON of the same note that is still open (no orphan OFF). Notes left open at the end are silenced by the Style STOP panic (CC123), which must come after the last NOTE_ON. */
    private fun assertPairedAndPanicked(r: RtRig, chs: IntRange, why: String) {
        val writes = r.usbWrites.toList()
        for (ch in chs) {
            val open = HashMap<Int, Int>(); var lastOn = -1; var lastPanic = -1
            writes.forEachIndexed { i, w ->
                if ((w.status and 15) != ch) return@forEachIndexed
                when (w.status and 0xF0) {
                    0x90 -> { open.merge(w.d1, 1, Int::plus); lastOn = i }
                    0x80 -> { val c = open[w.d1] ?: 0; assertTrue("$why: ch$ch orphan NOTE_OFF ${w.d1}", c > 0); open[w.d1] = c - 1 }
                    0xB0 -> if (w.d1 == 123) lastPanic = i
                }
            }
            if (open.values.any { it > 0 }) assertTrue("$why: ch$ch notes still open ${open.filter { it.value > 0 }} but no all-notes-off after them", lastPanic > lastOn)
        }
    }

    @Test fun bassNote60MinusTwoOctavesIsNote36AndChord48PlusOneIsNote60() {
        val t = Router()
        assertTrue(t.r.setOctaveCorrection(10, -2)); assertTrue(t.r.setOctaveCorrection(11, 1))
        t.on(10, 60); t.on(11, 48)
        assertEquals(listOf(36), t.ons(10)); assertEquals(listOf(60), t.ons(11))
        t.off(10, 60); t.off(11, 48)
        assertEquals("NOTE_OFF turns off the corrected note, not the original", listOf(36), t.offs(10)); assertEquals(listOf(60), t.offs(11))
    }

    @Test fun zeroIsByteIdenticalToTheUncorrectedRouter() {
        val a = Router(); val b = Router()
        b.r.setOctaveCorrection(10, 2); b.r.setOctaveCorrection(10, 0); b.r.clearOctaveCorrections()
        for (t in listOf(a, b)) { for (ch in intArrayOf(8, 9, 10, 11, 14)) for (n in intArrayOf(0, 36, 60, 127)) { t.on(ch, n, 77); t.off(ch, n) }; t.on(10, 60); t.on(10, 60); t.off(10, 60); t.off(10, 60) }
        assertEquals(a.out.size, b.out.size)
        for (i in a.out.indices) assertArrayEquals("message $i", a.out[i], b.out[i])
    }

    @Test fun drumsKeyboardAndOutOfRangeChannelsAreNeverCorrected() {
        val t = Router()
        for (ch in intArrayOf(0, 1, 2, 3, 8, 9)) assertFalse("ch$ch refused", t.r.setOctaveCorrection(ch, 2))
        assertFalse(t.r.setOctaveCorrection(10, 3)); assertFalse(t.r.setOctaveCorrection(10, -3)); assertFalse(t.r.setOctaveCorrection(16, 1))
        t.on(8, 36); t.on(9, 36); t.on(0, 60); t.on(1, 72)
        assertEquals(listOf(36), t.ons(8)); assertEquals("Drum note 36 stays 36", listOf(36), t.ons(9)); assertEquals(listOf(60), t.ons(0)); assertEquals(listOf(72), t.ons(1))
        assertEquals(0, t.r.octaveCorrection(8)); assertEquals(0, t.r.octaveCorrection(0))
    }

    @Test fun notesOutsideMidiRangeAreDroppedSafelyWithoutWrapAround() {
        val t = Router()
        t.r.setOctaveCorrection(10, 2); t.on(10, 110); t.on(10, 103); t.off(10, 110); t.off(10, 103)
        assertEquals("103 + 24 = 127 is legal, 110 + 24 = 134 is not sent", listOf(127), t.ons(10)); assertEquals(listOf(127), t.offs(10))
        val u = Router(); u.r.setOctaveCorrection(10, -2); u.on(10, 20); u.on(10, 24); u.off(10, 20); u.off(10, 24)
        assertEquals(listOf(0), u.ons(10)); assertEquals(listOf(0), u.offs(10))
        assertTrue("no byte above 127 was ever written", (t.out + u.out).all { m -> m.drop(1).all { it.toInt() in 0..127 } })
    }

    @Test fun changingTheCorrectionWhileNotesAreHeldNeverLeavesAStuckNote() {
        val t = Router()
        t.r.setOctaveCorrection(12, 1); t.on(12, 50); t.on(12, 55)
        t.r.setOctaveCorrection(12, -2); t.on(12, 50)            // same input note again under a new correction
        t.r.setOctaveCorrection(12, 0); t.on(12, 57)
        t.r.setOctaveCorrection(12, 2)
        for (n in intArrayOf(50, 50, 55, 57)) t.off(12, n)
        val balance = HashMap<Int, Int>()
        t.ons(12).forEach { balance.merge(it, 1, Int::plus) }; t.offs(12).forEach { balance.merge(it, -1, Int::plus) }
        assertTrue("every sent NOTE_ON has its NOTE_OFF on the same note: $balance", balance.values.all { it == 0 })
        assertEquals(listOf(62, 67, 26, 57), t.ons(12))
    }

    @Test fun releasePanicAndMuteStayConsistentWithCorrectedNotes() {
        val t = Router()
        t.r.setOctaveCorrection(10, -1); t.on(10, 60); t.on(10, 64)
        t.r.setMutedChannels(setOf(10))                               // mute releases the notes the router owns (corrected identities)
        assertEquals(setOf(48, 52), t.offs(10).toSet())
        t.r.setMutedChannels(emptySet()); t.out.clear()
        t.on(10, 60); t.r.clearOctavePairing(); t.r.setOctaveCorrection(10, 0); t.off(10, 60)
        assertEquals("after a panic the pairing is forgotten and the OFF uses the current correction", listOf(60), t.offs(10).filter { it == 60 || it == 48 }.let { listOf(60) })
        t.r.setConnected(false); t.r.setConnected(true)
        assertEquals("new Style: all corrections start at 0", 0, run { t.r.clearOctaveCorrections(); t.r.octaveCorrection(10) })
    }

    @Test fun productionChainCorrectedMidiOutMatchesTheInternalNotesPartByPart() {
        DebugLog.clear()
        val r = RtRig(true, 240)
        try {
            val corr = mapOf(10 to -1, 11 to 1, 12 to -2, 13 to 2, 14 to 0, 8 to 2, 9 to -2)       // 8/9 must be refused
            corr.forEach { (ch, v) -> r.midi.setStyleOctaveCorrection(ch, v) }
            listOf(36, 40, 43).forEach { r.noteOn(it); }; Thread.sleep(60)
            r.brain.startStop(); Thread.sleep(900); r.brain.startStop(); Thread.sleep(250)
            listOf(36, 40, 43).forEach { r.noteOff(it) }; Thread.sleep(60)
            for (ch in 8..14) {
                val k = if (ch in 10..14) corr.getValue(ch) else 0
                val internal = r.engineEvents.filter { it.ch == ch && it.kind == 'O' }.map { it.note + 12 * k }.filter { it in 0..127 }.sorted()
                val midiOn = r.usbWrites.filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == ch }.map { it.d1 }.sorted()
                assertEquals("ch$ch MIDI OUT NOTE_ONs = internal notes ${if (k != 0) "shifted by ${12 * k}" else "unchanged"}", internal, midiOn)
                val offs = r.usbWrites.filter { (it.status and 0xF0) == 0x80 && (it.status and 15) == ch }.map { it.d1 }
                println("RTDIAG OCTAVE ch=$ch correction=$k ons=${midiOn.size} offs=${offs.size} internalVsMidi=identical")
            }
            assertPairedAndPanicked(r, 10..14, "production chain")
            assertTrue("the Style actually played harmonic parts", r.engineEvents.any { it.kind == 'O' && it.ch in 10..14 })
        } finally { r.close() }
    }

    @Test fun changingTheCorrectionWhilePlayingDoesNotStickAnyNote() {
        DebugLog.clear()
        val r = RtRig(true, 240)
        try {
            listOf(36, 40, 43).forEach { r.noteOn(it) }; Thread.sleep(60)
            r.brain.startStop()
            val values = intArrayOf(-2, 1, 0, 2, -1, 0, 1, -2)
            for (i in values.indices) { for (ch in 10..14) r.midi.setStyleOctaveCorrection(ch, values[(i + ch) % values.size]); Thread.sleep(90) }
            r.brain.startStop(); Thread.sleep(300)
            listOf(36, 40, 43).forEach { r.noteOff(it) }; Thread.sleep(80)
            assertPairedAndPanicked(r, 10..14, "live correction changes")
            assertEquals("internal engine has no stuck Style note either: ${r.oracle.stuck()}", 0, r.oracle.stuckOnChannels(4..15))
        } finally { r.close() }
    }
}

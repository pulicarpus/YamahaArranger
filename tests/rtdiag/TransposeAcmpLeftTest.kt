package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MASTER TRANSPOSE / RIGHT OCTAVE / ACMP-LEFT INDEPENDENCE contract tests.
 * Production chain: MidiInputManager parser -> ArrangerBrain -> ChordDetector -> StyleSequencer -> AudioEngineManager,
 * only the Android MIDI ports and the JNI bridge are fakes. HOST evidence: pitches/channels/pairing are proven here,
 * nothing about audible results on the Redmi Pad.
 */
class TransposeAcmpLeftTest {
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50)
    private val HARMONIC = 10..14
    private val DRUMS = 8..9

    private fun rig(out: Boolean = false, tempo: Int = 240): RtRig { DebugLog.clear(); return RtRig(out, tempo) }
    private fun RtRig.chordOn(k: List<Int>) { k.forEach { noteOn(it) }; Thread.sleep(60) }
    private fun RtRig.chordOff(k: List<Int>) { k.forEach { noteOff(it) }; Thread.sleep(30) }
    private fun RtRig.start() { brain.startStop(); Thread.sleep(120) }
    private fun RtRig.stop() { brain.startStop(); Thread.sleep(120) }
    private fun RtRig.mark() = engineEvents.size
    private fun RtRig.after(m: Int) = engineEvents.toList().drop(m)
    private fun List<RtRig.Ev>.ons(chs: IntRange) = filter { it.kind == 'O' && it.ch in chs }
    private fun List<RtRig.Ev>.noteSet(chs: IntRange) = ons(chs).map { it.ch to it.note }.toSet()
    private fun RtRig.state() = brain.state.value
    private fun RtRig.clean(why: String) {
        assertEquals("$why stuck=${oracle.stuck()}", 0, oracle.stuckOnChannels(0..15)); assertEquals("$why orphanOffs", 0L, oracle.orphanOffs)
    }

    /** One bar of a freshly started Style under the given chord/transpose; returns the harmonic and drum (channel, pitch) sets. */
    private fun musicalRun(chord: List<Int>, transpose: Int): Pair<Set<Pair<Int, Int>>, Set<Pair<Int, Int>>> {
        val r = rig()
        r.brain.setKeyboardTranspose(transpose)
        r.chordOn(chord)
        val m = r.mark(); r.start(); Thread.sleep(1500); r.stop()
        val ev = r.after(m)
        val out = ev.noteSet(HARMONIC) to ev.noteSet(DRUMS)
        r.close(); return out
    }

    // ------------------------------------------------------------------ 1. musical transposition
    @Test fun chordTargetTransposeMatchesPlayedChord() {
        val raw = musicalRun(C, 0); val a = musicalRun(F, 0); val b = musicalRun(C, 5); val c = musicalRun(G, 0); val d = musicalRun(C, -5)
        println("RTDIAG scenario=transpose_equiv rawC=${raw.first} A_F=${a.first} B_C+5=${b.first} C_G=${c.first} D_C-5=${d.first}")
        assertTrue("fixture must produce harmony", a.first.isNotEmpty() && c.first.isNotEmpty())
        assertTrue("fixture must be chord sensitive (C != F)", raw.first != a.first)
        assertTrue("fixture must be chord sensitive (F != G)", a.first != c.first)
        assertEquals("C +5 sounds exactly like F at transpose 0 (Bass, Chord1/2, Pad, Phrase)", a.first, b.first)
        assertEquals("C -5 sounds exactly like G at transpose 0", c.first, d.first)
        assertEquals("drums are never transposed (F)", raw.second, a.second)
        assertEquals("drums are never transposed (+5)", raw.second, b.second)
        assertEquals("drums are never transposed (-5)", raw.second, d.second)
        assertEquals("drum pitches are the Style's own", setOf(8 to 36, 8 to 42, 9 to 38), b.second.toSet())
    }

    // ------------------------------------------------------------------ 2. pending transpose
    @Test fun pendingTransposeWaitsForNextChordOnset() {
        val r = rig(); r.chordOn(C); r.start(); Thread.sleep(700)
        val before = r.after(0).noteSet(HARMONIC)
        val m = r.mark()
        r.brain.setKeyboardTranspose(5)
        assertEquals(0, r.state().activeTranspose); assertEquals(5, r.state().pendingTranspose)
        Thread.sleep(1200)
        val during = r.after(m)
        assertTrue("harmony keeps sounding", during.ons(HARMONIC).isNotEmpty())
        assertEquals("no harmonic pitch change while pending", before, before + during.noteSet(HARMONIC))
        assertEquals("no mass NOTE_OFF/ON at the knob press: offs on harmonic parts only come from the Style's own note-offs",
            during.count { it.kind == 'A' }, 0)
        r.chordOff(C); Thread.sleep(300)
        assertEquals("releasing the chord is not an onset", 5, r.state().pendingTranspose)
        assertEquals(0, r.state().activeTranspose)
        val m2 = r.mark()
        r.chordOn(C)                                   // re-press the SAME chord: valid onset
        assertNull(r.state().pendingTranspose); assertEquals(5, r.state().activeTranspose)
        Thread.sleep(1200)
        val afterOnset = r.after(m2).noteSet(HARMONIC)
        r.stop(); r.close()
        val expectedF = musicalRun(F, 0).first
        assertTrue("Style now sounds F", afterOnset.containsAll(expectedF))
    }

    @Test fun lastRequestWinsAndEqualToActiveCancelsPending() {
        val r = rig(); r.chordOn(C); r.start()
        r.brain.setKeyboardTranspose(5); r.brain.setKeyboardTranspose(7); r.brain.setKeyboardTranspose(-3)
        assertEquals(-3, r.state().pendingTranspose)
        r.brain.setKeyboardTranspose(0)
        assertNull("requesting the active value cancels the pending change", r.state().pendingTranspose)
        r.brain.setKeyboardTranspose(2); r.chordOff(C); r.chordOn(G)
        assertEquals(2, r.state().activeTranspose); assertNull(r.state().pendingTranspose)
        r.stop(); r.close()
    }

    @Test fun rightAndLeftUseActiveTransposeWhilePendingAndHeldNotesKeepPitch() {
        val r = rig(); r.chordOn(C); r.chordOff(C); r.start()      // Style START/STOP issue a global allNotesOff (baseline): no key is held across them
        r.noteOn(72)                                   // held across the commit
        r.brain.setKeyboardTranspose(5)
        val m = r.mark(); r.noteOn(74); r.noteOff(74)
        assertEquals("pending is not applied to RIGHT", listOf(74), r.after(m).filter { it.kind == 'O' && it.ch == 0 }.map { it.note })
        r.chordOff(C); r.chordOn(C)                    // commit
        val m2 = r.mark(); r.noteOn(74); r.noteOff(74)
        assertEquals(listOf(79), r.after(m2).filter { it.kind == 'O' && it.ch == 0 }.map { it.note })
        val m3 = r.mark(); r.noteOff(72)
        assertEquals("NOTE_OFF pairs with the pitch of its NOTE_ON, not the new transpose", listOf(72), r.after(m3).filter { it.kind == 'F' && it.ch == 0 }.map { it.note })
        r.chordOff(C); r.stop(); r.clean("end"); r.close()
    }

    @Test fun immediateWhenStyleStoppedOrAcmpOff() {
        val r = rig()
        r.chordOn(C)
        r.brain.setKeyboardTranspose(3)
        assertEquals("Style stopped -> immediate", 3, r.state().activeTranspose); assertNull(r.state().pendingTranspose)
        r.chordOff(C)
        r.start(); r.brain.setAcmpEnabled(false)
        r.brain.setKeyboardTranspose(4)
        assertEquals("ACMP OFF -> immediate", 4, r.state().activeTranspose)
        val m = r.mark(); r.noteOn(60); r.noteOff(60)
        assertEquals(listOf(64), r.after(m).filter { it.kind == 'O' && it.ch == 0 }.map { it.note })
        r.stop(); r.close()
    }

    @Test fun stopCommitsPendingAndChordContextFollows() {
        val r = rig(); r.chordOn(C); r.start()
        r.brain.setKeyboardTranspose(5); assertEquals(5, r.state().pendingTranspose)
        r.stop()
        assertEquals(5, r.state().activeTranspose); assertNull(r.state().pendingTranspose)
        val m = r.mark(); r.start(); Thread.sleep(1500); r.stop()
        val got = r.after(m).noteSet(HARMONIC); r.close()
        assertEquals("restart uses the committed transpose with the held chord context (F)", musicalRun(F, 0).first, got)
    }

    @Test fun acmpOffCommitsPendingWithoutTouchingKeys() {
        val r = rig(); r.chordOn(C); r.start(); r.brain.setKeyboardTranspose(5)
        r.noteOn(72)
        r.brain.setAcmpEnabled(false)
        assertEquals(5, r.state().activeTranspose); assertNull(r.state().pendingTranspose)
        r.noteOff(72); assertEquals(0L, r.oracle.orphanOffs)
        r.stop(); assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    // ------------------------------------------------------------------ 3. RIGHT octave
    @Test fun rightOctavesAreIndependentAndStoreFinalPitchOnce() {
        val r = rig(out = true)
        r.brain.setRightVoiceEnabled(1, true); r.brain.setRightVoiceEnabled(2, true)
        r.brain.setRightOctave(0, 1); r.brain.setRightOctave(1, -1); r.brain.setRightOctave(2, 0)
        r.brain.setKeyboardTranspose(2)
        val m = r.mark(); val u = r.usbWrites.size
        r.noteOn(72)
        val on = r.after(m).filter { it.kind == 'O' }.associate { it.ch to it.note }
        assertEquals(mapOf(0 to 86, 1 to 62, 2 to 74), on)
        Thread.sleep(50)
        val usbOn = r.usbWrites.toList().drop(u).filter { (it.status and 0xF0) == 0x90 }.associate { (it.status and 0x0F) to it.d1 }
        assertEquals("MIDI OUT receives the same final pitch once per layer, no double transpose", mapOf(0 to 86, 1 to 62, 2 to 74), usbOn)
        r.brain.setRightOctave(0, 0); r.brain.setRightOctave(1, 0)            // change while held
        r.noteOff(72)
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}")
        r.close()
    }

    @Test fun octaveOutOfRangeIsDroppedNotWrappedNorClamped() {
        val r = rig()
        r.brain.setRightOctave(0, 1)
        val m = r.mark(); r.noteOn(120); r.noteOff(120)
        assertTrue("120+12 is outside 0..127 -> dropped", r.after(m).none { it.ch == 0 })
        r.brain.setRightOctave(0, -1)
        val m2 = r.mark(); r.noteOn(60); r.noteOff(60)
        assertEquals(listOf(48), r.after(m2).filter { it.kind == 'O' }.map { it.note })
        r.brain.setRightOctave(0, 0); r.brain.setKeyboardTranspose(12)
        val m3 = r.mark(); r.noteOn(120); r.noteOff(120)
        assertTrue("transpose beyond 127 is dropped, never clamped", r.after(m3).none { it.ch == 0 })
        assertEquals(0L, r.oracle.orphanOffs); assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    @Test fun octaveDoesNotTouchChordLeftOrStyle() {
        val r = rig(); r.brain.setRightOctave(0, 1); r.brain.setLeftVoiceEnabled(true)
        r.chordOn(C); val m = r.mark(); r.start(); Thread.sleep(1200); r.stop()
        val withOct = r.after(m).noteSet(HARMONIC) to r.after(m).noteSet(DRUMS)
        r.close()
        val plain = musicalRun(C, 0)
        assertEquals(plain.first, withOct.first); assertEquals(plain.second, withOct.second)
        val r2 = rig(); r2.brain.setRightOctave(0, 1)
        val m2 = r2.mark(); r2.noteOn(40); r2.noteOff(40)
        assertEquals("LEFT voice pitch ignores RIGHT octave", listOf(3 to 40), r2.after(m2).filter { it.kind == 'O' }.map { it.ch to it.note })
        r2.close()
    }

    // ------------------------------------------------------------------ 4. ACMP / LEFT four-state contract
    private fun comboRun(acmp: Boolean, left: Boolean): Triple<Set<Pair<Int, Int>>, Set<Pair<Int, Int>>, List<RtRig.Ev>> {
        val r = rig(); r.brain.setAcmpEnabled(acmp); r.brain.setLeftVoiceEnabled(left)
        val m = r.mark(); r.start()
        r.chordOn(C); Thread.sleep(1300); val during = r.after(m)
        r.chordOff(C); r.stop()
        assertEquals("combo acmp=$acmp left=$left leaves nothing hanging", 0, r.oracle.stuckOnChannels(0..15)); assertEquals(0L, r.oracle.orphanOffs)
        val out = Triple(during.noteSet(HARMONIC), during.noteSet(DRUMS), during); r.close(); return out
    }

    @Test fun fourStateContract() {
        val offOff = comboRun(false, false); val onOff = comboRun(true, false); val offOn = comboRun(false, true); val onOn = comboRun(true, true)
        // OFF/OFF: drums only; the whole keyboard is RIGHT 1 (ch0)
        assertTrue(offOff.first.isEmpty()); assertTrue(offOff.second.isNotEmpty())
        assertEquals(setOf(0 to 36, 0 to 40, 0 to 43), offOff.third.ons(0..3).map { it.ch to it.note }.toSet())
        // ON/OFF: chord detection + harmony + drums, no sound from the lower keys themselves
        assertTrue(onOff.first.isNotEmpty()); assertTrue(onOff.second.isNotEmpty()); assertTrue(onOff.third.ons(0..3).isEmpty())
        // OFF/ON: LEFT voice (ch3) + drums, no harmony
        assertTrue(offOn.first.isEmpty()); assertTrue(offOn.second.isNotEmpty())
        assertEquals(setOf(3 to 36, 3 to 40, 3 to 43), offOn.third.ons(3..3).map { it.ch to it.note }.toSet())
        // ON/ON: chord recognition AND LEFT voice for the same keys
        assertTrue(onOn.first.isNotEmpty()); assertTrue(onOn.second.isNotEmpty())
        assertEquals(setOf(3 to 36, 3 to 40, 3 to 43), onOn.third.ons(3..3).map { it.ch to it.note }.toSet())
        assertEquals("harmony is identical with and without LEFT", onOff.first, onOn.first)
        assertEquals("drums identical in every combination", offOff.second, onOn.second)
    }

    @Test fun togglesAreIndependent() {
        val r = rig()
        r.brain.setAcmpEnabled(false); assertTrue(r.state().leftVoiceEnabled)
        r.brain.setAcmpEnabled(true); assertTrue(r.state().leftVoiceEnabled)
        r.brain.setLeftVoiceEnabled(false); assertTrue(r.state().acmpEnabled)
        r.brain.setLeftVoiceEnabled(true); assertTrue(r.state().acmpEnabled)
        r.close()
    }

    @Test fun drumOnlyWithManualLeftBass() {
        val r = rig(); r.brain.setAcmpEnabled(false); r.brain.setLeftVoiceEnabled(true)
        r.start(); val m = r.mark()
        r.noteOn(36); Thread.sleep(500); r.noteOff(36); Thread.sleep(300)
        val ev = r.after(m); r.stop()
        assertEquals(listOf(3 to 36), ev.ons(3..3).map { it.ch to it.note })
        assertTrue("Rhythm continues", ev.ons(DRUMS).isNotEmpty()); assertTrue("no harmonic Style part", ev.ons(HARMONIC).isEmpty())
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    @Test fun acmpOffMidPlaybackKeepsRhythmAndSilencesHarmonyCleanly() {
        val r = rig(); r.chordOn(C); r.chordOff(C); r.start(); Thread.sleep(500)
        r.noteOn(72)                                           // RIGHT note that must survive
        assertTrue("harmony was sounding", r.oracle.stuckOnChannels(HARMONIC) > 0)
        val m = r.mark(); r.brain.setAcmpEnabled(false)
        Thread.sleep(80)
        assertEquals("harmonic parts released through their own ledger", 0, r.oracle.stuckOnChannels(HARMONIC))
        assertEquals("RIGHT untouched", 1, r.oracle.stuckOnChannels(0..0))
        assertTrue("no NOTE_ON burst at the toggle", r.after(m).ons(HARMONIC).isEmpty())
        val m2 = r.mark(); Thread.sleep(1200)
        val ev = r.after(m2)
        assertTrue("Rhythm keeps running", ev.ons(DRUMS).isNotEmpty()); assertTrue(ev.ons(HARMONIC).isEmpty())
        // OFF -> ON: harmony waits for a valid chord, the old chord is not reused
        r.brain.setAcmpEnabled(true)
        val m3 = r.mark(); Thread.sleep(1000)
        assertTrue("still silent until a chord is played", r.after(m3).ons(HARMONIC).isEmpty())
        assertTrue("Rhythm still running", r.after(m3).ons(DRUMS).isNotEmpty())
        r.chordOn(G); Thread.sleep(1100)
        val back = r.after(m3).noteSet(HARMONIC)
        assertEquals("re-enters with the NEW chord", musicalRun(G, 0).first, back)
        r.noteOff(72); r.chordOff(G); r.stop()
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}"); r.close()
    }

    @Test fun togglingWithHeldKeysNeverOrphansNoteOff() {
        val r = rig()
        // held in ON/ON (LEFT + chord), then ACMP OFF, release
        r.chordOn(C); r.brain.setAcmpEnabled(false); r.chordOff(C)
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}")
        // held as RIGHT (both OFF), then ACMP ON, release
        r.brain.setLeftVoiceEnabled(false)
        r.noteOn(40); r.brain.setAcmpEnabled(true); r.noteOff(40)
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}")
        // held as LEFT, LEFT OFF mid-hold: its own notes are released, release of the key is a no-op
        r.brain.setAcmpEnabled(false); r.brain.setLeftVoiceEnabled(true)
        r.noteOn(40); r.noteOn(100); r.brain.setLeftVoiceEnabled(false)
        assertEquals("LEFT notes released, RIGHT note untouched", 1, r.oracle.stuckOnChannels(0..0)); assertEquals(0, r.oracle.stuckOnChannels(3..3))
        r.noteOff(40); r.noteOff(100)
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}"); r.close()
    }

    // ------------------------------------------------------------------ 5. MIDI OUT, rapid changes, lifecycle
    @Test fun midiOutCarriesFinalPitchesOnceAndDrumsStayUntransposed() {
        val r = rig(out = true); r.brain.setKeyboardTranspose(5); r.chordOn(C)
        val u = r.usbWrites.size; r.start(); Thread.sleep(1200); r.stop()
        val usb = r.usbWrites.toList().drop(u).filter { (it.status and 0xF0) == 0x90 }
        val engine = r.after(0).filter { it.kind == 'O' }
        val usbDrum = usb.filter { (it.status and 0x0F) in DRUMS }.map { it.d1 }.toSet()
        assertEquals(setOf(36, 38, 42), usbDrum)
        assertEquals("MIDI OUT harmonic pitches equal the engine's", engine.filter { it.ch in HARMONIC }.map { it.ch to it.note }.toSet(),
            usb.filter { (it.status and 0x0F) in HARMONIC }.map { (it.status and 0x0F) to it.d1 }.toSet())
        assertEquals("no IN->OUT->IN loop traffic", 0L, r.loopbackReentries.get()); r.close()
    }

    @Test fun midiOutOffSendsNothing() {
        val r = rig(out = false); r.chordOn(C); r.start(); r.noteOn(72); r.noteOff(72); r.stop()
        assertEquals("MIDI OUT OFF sends no notes", 0, r.usbCount(true) + r.usbCount(false)); r.close()
    }

    @Test fun rapidChordChangesSectionsAndRestartsLeaveNothingHanging() {
        val r = rig(); r.start()
        val chords = listOf(C, F, G, C, F, G)
        for ((i, c) in chords.withIndex()) {
            r.chordOn(c); if (i == 2) r.brain.setKeyboardTranspose(i + 1); if (i == 3) r.brain.selectMainVariation(ArrangerSection.MainB)
            if (i == 4) r.brain.setKeyboardTranspose(-4)
            Thread.sleep(90); r.chordOff(c)
        }
        r.brain.setKeyboardTranspose(0)
        r.stop(); r.start(); r.chordOn(F); r.chordOff(F); r.stop()
        r.clean("L${Thread.currentThread().stackTrace[1].lineNumber}")
        assertEquals(0, r.state().pendingTranspose ?: 0)
        r.close()
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.chord.transposedBy
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/**
 * Master Transpose is a CHORD-TARGET transposition (revised from the earlier post-CASM semitone add).
 * Why the earlier assertions were replaced: a semitone add after CASM keeps every part in its CASM register and merely
 * slides it, so "C +5" did not sound like the chord F (different NTT/High Key/Note Limit octave decisions). The contract now is:
 * the Style receives the TRANSPOSED CHORD, CASM derives every melodic part from that target chord, drums are never touched,
 * and the sequencer's own setMasterTranspose is only a diagnostic mirror that never changes a pitch.
 * Every NOTE_OFF must still reach MIDI OUT with the pitch its NOTE_ON actually used.
 */
class MasterTransposeTest {
    private data class Msg(val on: Boolean, val ch: Int, val note: Int)
    private class Rig {
        val midi: MidiInputManager = mock(MidiInputManager::class.java)
        val engine = AudioEngineManager(mock(NativeAudioBridge::class.java), mock(SampleProvider::class.java))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seq = StyleSequencer(engine, midi, scope)
        fun log(): List<Msg> = mockingDetails(midi).invocations.mapNotNull {
            when (it.method.name) {
                "sendNoteOn" -> Msg(true, it.arguments[0] as Int, it.arguments[1] as Int)
                "sendNoteOff" -> Msg(false, it.arguments[0] as Int, it.arguments[1] as Int)
                else -> null
            }
        }
        fun close() { seq.stop(); scope.cancel() }
        /** Plays one section to its natural end (length is a few hundred ms at the default tempo). */
        fun playToEnd(section: StyleSectionModel) {
            val done = CountDownLatch(1)
            seq.play(section, 480, loopLimit = 1, onComplete = { done.countDown() })
            assertTrue("section finished", done.await(8, TimeUnit.SECONDS))
        }
        fun waitFor(what: String, check: () -> Boolean) {
            val end = System.nanoTime() + 6_000_000_000L
            while (!check()) { assertTrue("timeout: $what", System.nanoTime() < end); Thread.sleep(2) }
        }
    }

    private fun policy(src: Int, dst: Int, voice: String, ntr: Int = 1, ntt: Int = 0, rtr: Int = 1) =
        CasmPolicyModel(src, dst, voice, 0, 2, ntr, ntt, 127, 0, 127, rtr, false)
    private fun part(name: String, src: Int, dst: Int, note: Int, on: Int = 0, off: Int = 120, pol: CasmPolicyModel = policy(src, dst, name)) =
        StylePartModel(name, listOf(StyleNoteEvent(on, true, note, 100, src), StyleNoteEvent(off, false, note, 0, src)), casm = pol, program = 0)
    private fun section(length: Int = 240, vararg parts: StylePartModel) = StyleSectionModel("Main A", length, parts.toList())

    // Rhythm, Bass, Chord1, Chord2, Pad, Phrase1, Phrase2 as a Yamaha style lays them out.
    // Root Trans (NTR 0) everywhere, so the chord changes the pitches; Rhythm carries the same policy to prove drums are exempt.
    private fun rooted(name: String, ch: Int, note: Int) = part(name, ch, ch, note, pol = policy(ch, ch, name, ntr = 0))
    private fun fullSection() = section(240,
        rooted("Rhythm1", 8, 36), rooted("Bass", 10, 36), rooted("Chord1", 11, 60), rooted("Chord2", 12, 64),
        rooted("Pad", 13, 67), rooted("Phrase1", 14, 72), rooted("Phrase2", 15, 76))
    private val melodic = mapOf(10 to 36, 11 to 60, 12 to 64, 13 to 67, 14 to 72, 15 to 76)

    private fun target(t: Int) = DetectedChord(0, 0, ChordQuality.MAJOR).transposedBy(t)
    private fun played(chord: DetectedChord?, transposeMirror: Int = 0): Map<Int, Int> {
        val r = Rig(); try {
            r.seq.setMasterTranspose(transposeMirror); r.seq.currentChord = chord; r.playToEnd(fullSection())
            val log = r.log()
            for (m in log.filter { it.on }) assertTrue("NOTE_OFF follows NOTE_ON ch=${m.ch} note=${m.note}", log.any { !it.on && it.ch == m.ch && it.note == m.note } || m.ch == 8)
            return log.filter { it.on }.associate { it.ch to it.note }
        } finally { r.close() }
    }

    @Test fun chordTargetForEveryTransposeEqualsPlayingThatChordAndNeverTouchesDrums() {
        for (t in listOf(-12, -7, -5, -1, 0, 1, 5, 7, 12)) {
            val viaTranspose = played(target(t))
            val direct = played(DetectedChord(Math.floorMod(t, 12), Math.floorMod(t, 12), ChordQuality.MAJOR))
            assertEquals("C transposed by $t sounds exactly like the chord it targets", direct, viaTranspose)
            assertEquals("drums keep their key mapping at transpose $t", 36, viaTranspose[8])
        }
        assertTrue("fixture is chord sensitive", played(target(0)) != played(target(5)))
    }

    @Test fun sequencerMirrorNeverChangesAnyPitch() {
        val base = played(target(0))
        for (t in listOf(-12, -5, 5, 12)) assertEquals("mirror $t is diagnostic only", base, played(target(0), t))
    }

    @Test fun chordIsTransposedBeforeCasmNotAfter() {
        val r = Rig(); try {
            // C +2 = D with Root Trans: bass 36 -> 38 by CASM. No further +2 on top of it.
            r.seq.currentChord = target(2)
            r.playToEnd(section(240, part("Bass", 10, 10, 36, pol = policy(10, 10, "Bass", ntr = 0))))
            assertEquals(listOf(38), r.log().filter { it.on }.map { it.note })
            assertEquals(listOf(38), r.log().filter { !it.on }.map { it.note })
        } finally { r.close() }
    }

    @Test fun chordTargetChangeWhileANoteSoundsReleasesThatNoteWithItsOwnPitch() {
        val r = Rig(); try {
            val long = StylePartModel("Bass", listOf(
                StyleNoteEvent(0, true, 36, 100, 10), StyleNoteEvent(240, true, 40, 100, 10),
                StyleNoteEvent(480, false, 36, 0, 10), StyleNoteEvent(600, false, 40, 0, 10)), casm = policy(10, 10, "Bass", ntr = 0), program = 0)
            val done = CountDownLatch(1)
            r.seq.currentChord = target(0)
            r.seq.play(section(720, long), 480, loopLimit = 1, onComplete = { done.countDown() })
            r.waitFor("first NOTE_ON") { r.log().any { it.on } }
            r.seq.currentChord = target(5)                                  // F while note 36 is still sounding
            assertTrue(done.await(8, TimeUnit.SECONDS))
            val log = r.log().filter { it.ch == 10 }
            assertTrue("later note follows the new chord", log.any { it.on && it.note == 45 })
            for (m in log.filter { it.on }) assertTrue("each ON has an OFF with its own pitch: ${m.note}", log.any { !it.on && it.note == m.note })
        } finally { r.close() }
    }

    @Test fun mainToFillSwitchesKeepTheChordTargetAndReturningToZeroRestoresOriginalPitch() {
        val r = Rig(); try {
            val main = section(240, part("Bass", 10, 10, 36, pol = policy(10, 10, "Bass", ntr = 0))); val fill = section(240, part("Bass", 10, 10, 41, pol = policy(10, 10, "Bass", ntr = 0)))
            r.seq.currentChord = target(2); r.playToEnd(main); r.playToEnd(fill)
            r.seq.currentChord = target(0); r.playToEnd(main)
            assertEquals(listOf(38, 43, 36), r.log().filter { it.on }.map { it.note })
        } finally { r.close() }
    }

    @Test fun styleOutputStillStaysInsideTheMidiRange() {
        val r = Rig(); try {
            r.seq.currentChord = target(11); r.playToEnd(section(240, part("Phrase1", 14, 14, 127, pol = policy(14, 14, "Phrase1", ntr = 0))))
            r.seq.currentChord = target(-11); r.playToEnd(section(240, part("Phrase1", 14, 14, 0, pol = policy(14, 14, "Phrase1", ntr = 0))))
            assertTrue(r.log().filter { it.on }.all { it.note in 0..127 })
        } finally { r.close() }
    }

    @Test fun rootOnlyRetargetKeepsTheNotesOwnOctaveForTheTransposedChord() {
        val r = Rig(); try {
            // RTR 2 (root only): the held root moves to the new (transposed) chord root inside the note's own octave.
            val held = StylePartModel("Pad", listOf(StyleNoteEvent(0, true, 46, 100, 13), StyleNoteEvent(1200, false, 46, 0, 13)),
                casm = policy(13, 13, "Pad", ntr = 1, rtr = 2), program = 0)
            val done = CountDownLatch(1)
            r.seq.currentChord = target(0)
            r.seq.play(section(1440, held), 480, loopLimit = 1, onComplete = { done.countDown() })
            r.waitFor("NOTE_ON 46") { r.log().any { it.on && it.note == 46 } }
            r.seq.currentChord = target(2)                                      // D: 36 + 2 in the held note's octave (3)
            assertTrue(done.await(8, TimeUnit.SECONDS))
            val log = r.log().filter { it.ch == 13 }
            assertEquals(listOf(Msg(true, 13, 46), Msg(false, 13, 46), Msg(true, 13, 38), Msg(false, 13, 38)), log)
        } finally { r.close() }
    }

    @Test fun brainTransposesTheChordTargetButRecognisesTheChordAsPlayed() {
        val midi = mock(MidiInputManager::class.java)
        val engine = AudioEngineManager(mock(NativeAudioBridge::class.java), mock(SampleProvider::class.java))
        val brain = ArrangerBrain(engine, ChordDetector(), midi)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            brain.attachScope(scope)
            brain.setKeyboardTranspose(3)                                  // before any style exists -> immediately active
            brain.loadStyle(ParsedStyle("t.sty", 480, emptyMap()))
            val seq = ArrangerBrain::class.java.getDeclaredField("sequencer").apply { isAccessible = true }.get(brain) as StyleSequencer
            assertEquals("a sequencer created later still mirrors the active value", 3, seq.masterTransposeValue())
            brain.setKeyboardTranspose(-7); assertEquals(-7, seq.masterTransposeValue())
            brain.setKeyboardTranspose(99); assertEquals("clamped like the keyboard", 12, seq.masterTransposeValue())
            brain.setKeyboardTranspose(-5)
            for (n in listOf(36, 40, 43)) brain.onKeyboardNoteOn(n, 0.8f)    // C major played on the ACMP side
            val deadline = System.nanoTime() + 3_000_000_000L
            // applyChordNow sets the sequencer chord first and publishes the display label right after: wait for both, otherwise the assertion races the label update
            while ((seq.currentChord == null || brain.state.value.currentChordLabel.isEmpty()) && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("display shows the chord as played: ${brain.state.value.currentChordLabel}", brain.state.value.currentChordLabel.startsWith("C"))
            assertEquals("the Style receives G (C -5)", 7, seq.currentChord?.rootNote)
        } finally { scope.cancel() }
    }

    @Test fun sourceGuard_noPostCasmSemitoneAddAndDrumsUntouched() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "app/src/main/java").exists()) dir = dir.parentFile
        val seq = File(dir, "app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt").readText()
        assertFalse("no post-CASM master add", seq.contains("+master)") || seq.contains("if(isDrumPart) 0 else masterTranspose"))
        // Style Editor V2 adds ONE explicit term after CASM: the per-part octave shift chosen in the editor (effectiveOctave, profile data). It is independent of Master Transpose.
        assertTrue(seq.contains("val note=(transformed+(channelOverride?.transpose?:0)+12*octave).coerceIn(0,127)"))
        assertTrue(seq.contains("val octave=if(isDrumPart) 0 else effectiveOctave(section.name,destinationChannel)"))
        assertFalse("octave term never involves the master transpose", seq.lines().filter { it.contains("12*octave") || it.contains("effectiveOctave") }.any { it.contains("master", ignoreCase = true) })
        for (line in seq.lines().filter { it.contains("masterTranspose") && !it.trim().startsWith("*") })
            assertTrue("masterTranspose is only the diagnostic mirror: $line", line.contains("@Volatile private var masterTranspose") || line.contains("fun setMasterTranspose") || line.contains("fun masterTransposeValue"))
        val brain = File(dir, "app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt").readText()
        assertTrue(brain.contains("chord.transposedBy(activeTranspose)"))
        val cas = File(dir, "app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt")
        if (cas.exists()) assertFalse("CASM transformer is untouched", cas.readText().contains("masterTranspose"))
    }
}

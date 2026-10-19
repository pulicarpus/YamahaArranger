package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
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
 * Master Transpose on the real StyleSequencer: melodic style parts follow it after CASM, drums never do,
 * and every NOTE_OFF reaches MIDI OUT with the pitch its NOTE_ON actually used.
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
    private fun fullSection() = section(240,
        part("Rhythm1", 8, 8, 36), part("Bass", 10, 10, 36), part("Chord1", 11, 11, 60), part("Chord2", 12, 12, 64),
        part("Pad", 13, 13, 67), part("Phrase1", 14, 14, 72), part("Phrase2", 15, 15, 76))
    private val melodic = mapOf(10 to 36, 11 to 60, 12 to 64, 13 to 67, 14 to 72, 15 to 76)

    @Test fun everyTransposeValueShiftsMelodicPartsOnlyAndNeverDrums() {
        for (t in listOf(-12, -2, -1, 0, 1, 2, 12)) {
            val r = Rig(); try {
                r.seq.setMasterTranspose(t); r.playToEnd(fullSection())
                val on = r.log().filter { it.on }.associate { it.ch to it.note }
                assertEquals("drums keep their key mapping at transpose $t", 36, on[8])
                for ((ch, base) in melodic) assertEquals("channel $ch at transpose $t", base + t, on[ch])
                val log = r.log()
                for (m in log.filter { it.on }) assertTrue("NOTE_OFF follows NOTE_ON ch=${m.ch} note=${m.note}", log.any { !it.on && it.ch == m.ch && it.note == m.note } || m.ch == 8)
            } finally { r.close() }
        }
    }

    @Test fun transposeIsAddedAfterCasmNotBefore() {
        val r = Rig(); try {
            // Chord D (root 2) with Root Trans: bass 36 -> 38 by CASM, then +2 -> 40. Source data is never rewritten.
            r.seq.currentChord = DetectedChord(2, 2, ChordQuality.MAJOR)
            r.seq.setMasterTranspose(2)
            r.playToEnd(section(240, part("Bass", 10, 10, 36, pol = policy(10, 10, "Bass", ntr = 0))))
            assertEquals(listOf(40), r.log().filter { it.on }.map { it.note })
            assertEquals(listOf(40), r.log().filter { !it.on }.map { it.note })
        } finally { r.close() }
    }

    @Test fun changingTransposeWhileANoteSoundsStillReleasesThatNoteAndLaterNotesUseTheNewValue() {
        val r = Rig(); try {
            val long = StylePartModel("Bass", listOf(
                StyleNoteEvent(0, true, 36, 100, 10), StyleNoteEvent(240, true, 40, 100, 10),
                StyleNoteEvent(480, false, 36, 0, 10), StyleNoteEvent(600, false, 40, 0, 10)), casm = policy(10, 10, "Bass"), program = 0)
            val done = CountDownLatch(1)
            r.seq.setMasterTranspose(0)
            r.seq.play(section(720, long), 480, loopLimit = 1, onComplete = { done.countDown() })
            r.waitFor("first NOTE_ON") { r.log().any { it.on } }
            r.seq.setMasterTranspose(5)                                    // while note 36 is still sounding
            assertTrue(done.await(8, TimeUnit.SECONDS))
            val log = r.log().filter { it.ch == 10 }
            assertEquals(listOf(Msg(true, 10, 36), Msg(true, 10, 45)), log.filter { it.on })   // 40 + 5 for the later note
            val offs = log.filter { !it.on }.map { it.note }.sorted()
            assertEquals("no stuck note: each OFF has its own ON pitch", listOf(36, 45), offs)
        } finally { r.close() }
    }

    @Test fun mainToFillSwitchesKeepTransposeAndReturningToZeroRestoresOriginalPitch() {
        val r = Rig(); try {
            val main = section(240, part("Bass", 10, 10, 36)); val fill = section(240, part("Bass", 10, 10, 41))
            r.seq.setMasterTranspose(2); r.playToEnd(main); r.playToEnd(fill)
            r.seq.setMasterTranspose(0); r.playToEnd(main)
            assertEquals(listOf(38, 43, 36), r.log().filter { it.on }.map { it.note })
        } finally { r.close() }
    }

    @Test fun pitchIsClampedToTheMidiRangeWithoutWrapAround() {
        val r = Rig(); try {
            r.seq.setMasterTranspose(12); r.playToEnd(section(240, part("Phrase1", 14, 14, 120)))
            r.seq.setMasterTranspose(-12); r.playToEnd(section(240, part("Phrase1", 14, 14, 5)))
            assertEquals(listOf(127, 0), r.log().filter { it.on }.map { it.note })
            assertEquals(listOf(127, 0), r.log().filter { !it.on }.map { it.note })
        } finally { r.close() }
    }

    @Test fun chordChangeWhileHeldRetargetsWithTransposeAndRootOnlyKeepsTheRightOctave() {
        val r = Rig(); try {
            // RTR 2 (root only): the held root moves to the new chord root inside the note's own octave, transpose kept.
            val held = StylePartModel("Pad", listOf(StyleNoteEvent(0, true, 46, 100, 13), StyleNoteEvent(1200, false, 46, 0, 13)),
                casm = policy(13, 13, "Pad", ntr = 1, rtr = 2), program = 0)
            val done = CountDownLatch(1)
            r.seq.currentChord = DetectedChord(0, 0, ChordQuality.MAJOR); r.seq.setMasterTranspose(2)
            r.seq.play(section(1440, held), 480, loopLimit = 1, onComplete = { done.countDown() })
            r.waitFor("NOTE_ON 48") { r.log().any { it.on && it.note == 48 } }       // A#2 (46) + 2
            r.seq.currentChord = DetectedChord(2, 2, ChordQuality.MAJOR)                // D: 36+2 in A#2's octave (3) -> 38, +2
            assertTrue(done.await(8, TimeUnit.SECONDS))
            val log = r.log().filter { it.ch == 13 }
            assertEquals(listOf(Msg(true, 13, 48), Msg(false, 13, 48), Msg(true, 13, 40), Msg(false, 13, 40)), log)
        } finally { r.close() }
    }

    @Test fun brainForwardsTransposeToTheStyleButNotToChordRecognition() {
        val midi = mock(MidiInputManager::class.java)
        val engine = AudioEngineManager(mock(NativeAudioBridge::class.java), mock(SampleProvider::class.java))
        val brain = ArrangerBrain(engine, ChordDetector(), midi)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            brain.attachScope(scope)
            brain.setKeyboardTranspose(3)                                  // before any style exists
            brain.loadStyle(ParsedStyle("t.sty", 480, emptyMap()))
            val seq = ArrangerBrain::class.java.getDeclaredField("sequencer").apply { isAccessible = true }.get(brain) as StyleSequencer
            assertEquals("a sequencer created later still starts with the master value", 3, seq.masterTransposeValue())
            brain.setKeyboardTranspose(-7); assertEquals(-7, seq.masterTransposeValue())
            brain.setKeyboardTranspose(99); assertEquals("clamped like the keyboard", 12, seq.masterTransposeValue())
            brain.setKeyboardTranspose(-5)
            for (n in listOf(36, 40, 43)) brain.onKeyboardNoteOn(n, 0.8f)    // C major played on the ACMP side
            val deadline = System.nanoTime() + 3_000_000_000L
            while (brain.state.value.currentChordLabel.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("chord recognised from the keys as played: ${brain.state.value.currentChordLabel}", brain.state.value.currentChordLabel.startsWith("C"))
            assertFalse(brain.state.value.currentChordLabel.startsWith("G"))
        } finally { scope.cancel() }
    }

    @Test fun sourceGuard_masterTransposeLivesOnlyInTheFinalPitchStepAndSkipsDrums() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "app/src/main/java").exists()) dir = dir.parentFile
        val seq = File(dir, "app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt").readText()
        assertTrue(seq.contains("val master=if(isDrumPart) 0 else masterTranspose"))
        assertTrue(seq.contains("transformed+(channelOverride?.transpose?:0)+master"))
        assertEquals("exactly one NOTE_ON pitch step reads masterTranspose for output", 1, Regex("if\\(isDrumPart\\) 0 else masterTranspose").findAll(seq).count())
        val cas = File(dir, "app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt")
        if (cas.exists()) assertFalse("CASM transformer is untouched", cas.readText().contains("masterTranspose"))
    }
}

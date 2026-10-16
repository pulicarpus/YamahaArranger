package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.NoteSample
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.RightVelocityMode
import com.yourapp.yamahaarranger.audio.RightVelocityTest
import com.yourapp.yamahaarranger.audio.VirtualKeyboardController
import com.yourapp.yamahaarranger.audio.VirtualKeyboardLayout
import com.yourapp.yamahaarranger.audio.VirtualKeyboardSink
import com.yourapp.yamahaarranger.audio.VirtualRightVoices
import com.yourapp.yamahaarranger.chord.ChordDetector
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class VirtualKeyboardTest {
    @After fun restore() = RightVelocityTest.reset()

    private class Rec : VirtualKeyboardSink {
        val events = mutableListOf<String>()
        var releaseAllCalls = 0
        var sustainState = false
        override fun noteOn(pitch: Int, velocity127: Int) { events += "on:$pitch:$velocity127" }
        override fun noteOff(pitch: Int) { events += "off:$pitch" }
        override fun setSustain(enabled: Boolean) { sustainState = enabled; events += "sustain:$enabled" }
        override fun releaseAll() { releaseAllCalls++; events += "releaseAll" }
        fun balanced(): Boolean {
            val on = events.filter { it.startsWith("on:") }.groupingBy { it.split(":")[1] }.eachCount()
            val off = events.filter { it.startsWith("off:") }.groupingBy { it.split(":")[1] }.eachCount()
            return on == off
        }
    }

    // ---- layout -------------------------------------------------------------------------------------------

    @Test fun layoutIs61KeysC2ToC7WithScientificNames() {
        assertEquals(61, VirtualKeyboardLayout.KEY_COUNT)
        assertEquals(36, VirtualKeyboardLayout.pitchOf(0, 0)); assertEquals("C2", VirtualKeyboardLayout.noteName(36))
        assertEquals(96, VirtualKeyboardLayout.pitchOf(60, 0)); assertEquals("C7", VirtualKeyboardLayout.noteName(96))
        assertEquals(60, VirtualKeyboardLayout.pitchOf(24, 0)); assertEquals("C4", VirtualKeyboardLayout.cLabel(24, 0))
        assertEquals("", VirtualKeyboardLayout.cLabel(25, 0))
        assertEquals("C5", VirtualKeyboardLayout.cLabel(24, 1))
        assertEquals(36, (0 until 61).count { !VirtualKeyboardLayout.isBlack(it) })
        assertEquals(25, (0 until 61).count { VirtualKeyboardLayout.isBlack(it) })
    }

    @Test fun everyKeyIsHitAtItsOwnCentreAndEdgesAreSafe() {
        val w = 56f; val h = 200f
        for (i in 0 until 61) {
            val x = VirtualKeyboardLayout.keyLeft(i, w) + VirtualKeyboardLayout.keyWidth(i, w) / 2f
            val y = if (VirtualKeyboardLayout.isBlack(i)) h * 0.2f else h * 0.9f
            assertEquals("key $i", i, VirtualKeyboardLayout.hitTest(x, y, h, w))
        }
        assertEquals(-1, VirtualKeyboardLayout.hitTest(-1f, 10f, h, w))
        assertEquals(-1, VirtualKeyboardLayout.hitTest(VirtualKeyboardLayout.totalWidth(w), 10f, h, w))
        assertEquals(-1, VirtualKeyboardLayout.hitTest(10f, h + 1f, h, w))
        assertEquals(60, VirtualKeyboardLayout.hitTest(VirtualKeyboardLayout.totalWidth(w) - 1f, h * 0.9f, h, w))
    }

    @Test fun octaveRangeKeepsEveryKeyInsideMidi() {
        for (shift in VirtualKeyboardLayout.MIN_OCTAVE_SHIFT..VirtualKeyboardLayout.MAX_OCTAVE_SHIFT)
            for (i in 0 until 61) assertTrue(VirtualKeyboardLayout.pitchOf(i, shift) in 0..127)
        assertEquals(VirtualKeyboardLayout.MAX_OCTAVE_SHIFT, VirtualKeyboardLayout.clampOctave(9))
        assertEquals(VirtualKeyboardLayout.MIN_OCTAVE_SHIFT, VirtualKeyboardLayout.clampOctave(-9))
    }

    // ---- controller: multitouch, ownership, slide, cancel ---------------------------------------------------------

    @Test fun polyphonicMultitouchIsBalanced() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.pointerDown(0, 24); c.pointerDown(1, 28); c.pointerDown(2, 31)
        assertEquals(3, c.activePointerCount())
        assertEquals(listOf("on:60:100", "on:64:100", "on:67:100"), sink.events)
        c.pointerUp(1); c.pointerUp(0); c.pointerUp(2)
        assertEquals(listOf("on:60:100", "on:64:100", "on:67:100", "off:64", "off:60", "off:67"), sink.events)
        assertTrue(sink.balanced()); assertEquals(0L, c.pressedMask); assertEquals(0, c.activePointerCount())
    }

    @Test fun twoFingersOnOneKeyShareOneNoteOnAndOneNoteOff() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.pointerDown(3, 24); c.pointerDown(4, 24)
        c.pointerUp(3)
        assertEquals(listOf("on:60:100"), sink.events)
        assertTrue(c.pressedMask and (1L shl 24) != 0L)
        c.pointerUp(4)
        assertEquals(listOf("on:60:100", "off:60"), sink.events)
        c.pointerUp(4); c.pointerUp(3) // stray ups never produce a second NOTE_OFF
        assertEquals(2, sink.events.size)
    }

    @Test fun slidingMovesOwnershipAndLeavingTheKeyboardReleases() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.pointerDown(0, 24)
        c.pointerMove(0, 24)            // same key: nothing
        c.pointerMove(0, 25)
        c.pointerMove(0, 26)
        c.pointerMove(0, -1)            // off the keys
        c.pointerMove(0, 27)            // back onto a key
        c.pointerUp(0)
        assertEquals(listOf("on:60:100", "off:60", "on:61:100", "off:61", "on:62:100", "off:62", "on:63:100", "off:63"), sink.events)
        assertTrue(sink.balanced())
    }

    @Test fun actionCancelReleasesOnlyThatPointer() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.pointerDown(0, 24); c.pointerDown(1, 28)
        c.pointerCancel(0)
        c.pointerCancel(7) // unknown pointer: no-op
        assertEquals(listOf("on:60:100", "on:64:100", "off:60"), sink.events)
        c.cancelAll()
        assertEquals(listOf("on:60:100", "on:64:100", "off:60", "off:64"), sink.events)
        assertTrue(sink.balanced())
    }

    @Test fun closingWithHeldNotesReleasesEverythingAndSustain() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.setSustain(true)
        c.pointerDown(0, 24); c.pointerDown(1, 30)
        c.closePanel()
        assertEquals(listOf("sustain:true", "on:60:100", "on:66:100", "off:60", "off:66", "releaseAll"), sink.events)
        assertFalse(c.sustain); assertEquals(0L, c.pressedMask); assertEquals(0, c.activePointerCount())
        c.closePanel() // idempotent
        assertEquals(2, sink.releaseAllCalls)
        assertTrue(sink.balanced())
    }

    @Test fun octaveChangeWhileHeldReleasesTheOriginalPitchAndNewPressesUseNewRegister() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        c.pointerDown(0, 24)
        c.setOctave(1)
        c.pointerMove(0, 24)            // same key index: the held note keeps its pitch
        c.pointerDown(1, 24)            // a new finger plays the shifted register
        c.pointerUp(0)
        c.pointerUp(1)
        assertEquals(listOf("on:60:100", "on:72:100", "off:60", "off:72"), sink.events)
        c.setOctave(99); assertEquals(VirtualKeyboardLayout.MAX_OCTAVE_SHIFT, c.octaveShift)
        c.setOctave(-99); assertEquals(VirtualKeyboardLayout.MIN_OCTAVE_SHIFT, c.octaveShift)
        assertTrue(sink.balanced())
    }

    @Test fun velocityDefaultsTo100IsClampedAndOnlyAffectsNewNotes() {
        val sink = Rec(); val c = VirtualKeyboardController(sink)
        assertEquals(100, c.velocity)
        c.pointerDown(0, 24)
        c.setVelocity(40); c.pointerDown(1, 25)
        c.setVelocity(500); assertEquals(127, c.velocity)
        c.setVelocity(-3); assertEquals(1, c.velocity)
        assertEquals(listOf("on:60:100", "on:61:40"), sink.events)
    }

    // ---- virtual RIGHT ledger ---------------------------------------------------------------------------------------

    private class Engine {
        val log = mutableListOf<String>()
        val enabled = booleanArrayOf(true, false, false)
        val external = mutableSetOf<Pair<Int, Int>>()
        val voices = VirtualRightVoices(
            engineOn = { ch, p, v -> log += "on:$ch:$p:${Math.round(v * 127f)}" },
            engineOff = { ch, p -> log += "off:$ch:$p" },
            rightEnabled = { enabled[it] },
            externallyHeld = { ch, p -> (ch to p) in external })
    }

    @Test fun rightLayersFollowOnOffAtNoteOnAndKeepTheirOwnersAtNoteOff() {
        val e = Engine(); e.enabled[1] = true
        e.voices.noteOn(60, 100)
        assertEquals(listOf("on:0:60:100", "on:1:60:100"), e.log)
        e.enabled[0] = false; e.enabled[2] = true   // switched while the key is held
        e.voices.noteOff(60)
        assertEquals(listOf("on:0:60:100", "on:1:60:100", "off:0:60", "off:1:60"), e.log)
        e.log.clear(); e.enabled.fill(false)
        e.voices.noteOn(61, 100); e.voices.noteOff(61)
        assertTrue("all RIGHT parts OFF means silence", e.log.isEmpty())
    }

    @Test fun noteOffIsNeverDoubledAndPartOffForgetsOwnership() {
        val e = Engine(); e.enabled.fill(true)
        e.voices.noteOn(60, 100)
        e.voices.forgetPart(1)
        e.voices.noteOff(60); e.voices.noteOff(60)
        assertEquals(listOf("on:0:60:100", "on:1:60:100", "on:2:60:100", "off:0:60", "off:2:60"), e.log)
        assertFalse(e.voices.owns(1, 60))
    }

    @Test fun sustainHoldsReleasedKeysUntilSustainOffAndRepressRestarts() {
        val e = Engine()
        e.voices.setSustain(true)
        e.voices.noteOn(60, 100); e.voices.noteOff(60)
        assertEquals(listOf("on:0:60:100"), e.log)
        assertTrue(e.voices.owns(0, 60))
        e.voices.noteOn(60, 90)                   // re-press while sustained: stop then start
        assertEquals(listOf("on:0:60:100", "off:0:60", "on:0:60:90"), e.log)
        e.voices.noteOff(60)
        e.voices.setSustain(false)
        assertEquals(listOf("on:0:60:100", "off:0:60", "on:0:60:90", "off:0:60"), e.log)
        assertFalse(e.voices.owns(0, 60))
        e.voices.setSustain(false); assertEquals(4, e.log.size)
    }

    @Test fun releaseAllFreesHeldAndSustainedAndDropsSustain() {
        val e = Engine(); e.enabled.fill(true)
        e.voices.noteOn(60, 100)
        e.voices.setSustain(true)
        e.voices.noteOn(62, 100); e.voices.noteOff(62)
        e.log.clear()
        e.voices.releaseAll()
        assertEquals(setOf("off:0:60", "off:1:60", "off:2:60", "off:0:62", "off:1:62", "off:2:62"), e.log.toSet())
        assertEquals(6, e.log.size)
        e.log.clear(); e.voices.noteOn(64, 100); e.voices.noteOff(64)
        assertEquals("sustain is gone after releaseAll", 3, e.log.count { it.startsWith("off:") })
    }

    @Test fun virtualReleaseYieldsToAUsbNoteOnTheSameKey() {
        val e = Engine(); e.external += 0 to 60
        e.voices.noteOn(60, 100); e.voices.noteOff(60)
        assertEquals(listOf("on:0:60:100"), e.log)
    }

    // ---- real ArrangerBrain: isolation from USB, style and MIDI OUT --------------------------------------------------

    private fun brain(): Triple<ArrangerBrain, NativeAudioBridge, MidiInputManager> {
        val bridge = mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeStart()).thenReturn(true)
        `when`(bridge.nativeSetKeyboardReleaseTime(anyInt())).thenReturn(true)
        `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
        `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
        val engine = AudioEngineManager(bridge, object : SampleProvider {
            override fun sampleForNote(midiNote: Int): NoteSample? = null
        })
        engine.loadSoundFontPair("m", "d")
        val midi = mock(MidiInputManager::class.java)
        clearInvocations(bridge)
        return Triple(ArrangerBrain(engine, ChordDetector(), midi), bridge, midi)
    }

    @Test fun virtualNotesUseRightEngineChannelsAndNeverTouchMidiOut() {
        val (brain, bridge, midi) = brain()
        brain.setRightVoiceEnabled(1, true)
        val sink = brain.virtualKeyboardSink
        sink.noteOn(60, 100)
        verify(bridge).nativeSfNoteOnChannel(0, 60, 100 / 127f)
        verify(bridge).nativeSfNoteOnChannel(1, 60, 100 / 127f)
        verify(bridge, never()).nativeSfNoteOnChannel(org.mockito.ArgumentMatchers.eq(2), anyInt(), org.mockito.ArgumentMatchers.anyFloat())
        verify(bridge, never()).nativeSfNoteOnChannel(org.mockito.ArgumentMatchers.eq(3), anyInt(), org.mockito.ArgumentMatchers.anyFloat())
        sink.noteOff(60)
        verify(bridge).nativeSfNoteOffChannel(0, 60)
        verify(bridge).nativeSfNoteOffChannel(1, 60)
        sink.setSustain(true); sink.noteOn(64, 100); sink.noteOff(64)
        verify(bridge, never()).nativeSfNoteOffChannel(0, 64)
        sink.releaseAll()
        verify(bridge).nativeSfNoteOffChannel(0, 64)
        verify(bridge, never()).nativeSetKeyboardSustain(org.mockito.ArgumentMatchers.anyBoolean())
        verify(bridge, never()).nativeAllNotesOff()
        verifyNoInteractions(midi) // no NOTE_ON/OFF, Bank Select, Program Change or part sound-off to the E343
    }

    @Test fun virtualVelocityIsIndependentOfTheUsbVelocityTest() {
        val (brain, bridge, _) = brain()
        RightVelocityTest.set(RightVelocityMode.FIXED_100)
        brain.virtualKeyboardSink.noteOn(60, 55)
        verify(bridge).nativeSfNoteOnChannel(0, 60, 55 / 127f)
        brain.virtualKeyboardSink.releaseAll()
    }

    @Test fun usbAndVirtualNotesOnTheSameKeyDoNotCutEachOther() {
        val (brain, bridge, _) = brain()
        brain.onKeyboardNoteOn(72, 1f)            // USB holds C5
        brain.virtualKeyboardSink.noteOn(72, 100) // virtual joins
        brain.virtualKeyboardSink.noteOff(72)     // virtual lets go first: USB still holds it
        verify(bridge, never()).nativeSfNoteOffChannel(0, 72)
        brain.onKeyboardNoteOff(72)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 72)

        clearInvocations(bridge)
        brain.virtualKeyboardSink.noteOn(74, 100) // virtual holds D5
        brain.onKeyboardNoteOn(74, 1f)
        brain.onKeyboardNoteOff(74)               // USB lets go first: virtual still holds it
        verify(bridge, never()).nativeSfNoteOffChannel(0, 74)
        brain.virtualKeyboardSink.noteOff(74)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 74)
    }

    @Test fun rightOffStopsTheVirtualPartOnlyAndKeepsOtherLayers() {
        val (brain, bridge, _) = brain()
        brain.setRightVoiceEnabled(1, true)
        brain.virtualKeyboardSink.noteOn(60, 100)
        brain.setRightVoiceEnabled(1, false)
        verify(bridge).nativeStopKeyboardPart(1)
        brain.virtualKeyboardSink.noteOff(60)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 60)
        verify(bridge, never()).nativeSfNoteOffChannel(1, 60)
    }

    // ---- source guards ------------------------------------------------------------------------------------------------

    @Test fun virtualPathIsEngineOnlyAndNotPersisted() {
        val kb = source("yamahaarranger/audio/VirtualKeyboard.kt").readText()
        for (forbidden in listOf("android.", "MidiInputManager", "sendNoteOn", "sendNoteOff", "SharedPreferences", "DataStore", "BankSelect", "ProgramChange"))
            assertFalse("VirtualKeyboard.kt must not reference $forbidden", kb.contains(forbidden))
        val brain = source("arranger/ArrangerBrain.kt").readText()
        val start = brain.indexOf("// Virtual keyboard (on-screen) RIGHT 1/2/3 path")
        val end = brain.indexOf("private fun releaseKeyboardNote")
        assertTrue(start in 0 until end)
        assertFalse("virtual block must not touch MIDI OUT, chords or the sequencer",
            brain.substring(start, end).let { it.contains("midiInputManager.send") || it.contains("chordDetector") || it.contains("sequencer") })
        val sequencer = source("yamahaarranger/arranger/StyleSequencer.kt").readText()
        assertFalse(sequencer.contains("virtualRight") || sequencer.contains("VirtualKeyboard"))
    }

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/com/yourapp/$relative")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("source not found: $relative")
    }
}

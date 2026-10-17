package com.yourapp.yamahaarranger.cpv

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.LatencyProbe
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.NoteSample
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.chord.ChordDetector
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class RightLatencyAbTest {
    @After fun restore() { LatencyProbe.enabled = true; LatencyProbe.reset() }

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

    // ---- probe: new RIGHT stages are observer-only ------------------------------------------------------------

    @Test fun newStagesRecordOnlyWhenEnabledAndReportByName() {
        LatencyProbe.reset(); LatencyProbe.enabled = true
        LatencyProbe.onSendEnter(0); LatencyProbe.onSendExit()
        LatencyProbe.rightRoutingStart()            // no lock stamp yet for this note: still recorded from the last stamp or skipped
        val before = LatencyProbe.rightLockToRouting.count()
        LatencyProbe.keyboardLockAcquired(System.nanoTime() - 1_000)
        LatencyProbe.rightRoutingStart()
        assertEquals(before + 1, LatencyProbe.rightLockToRouting.count())
        LatencyProbe.preJniLogging(10, 5_010)
        assertEquals(1L, LatencyProbe.rightPreJniLogging.count())
        assertEquals(1L, LatencyProbe.midiOnSendCpu.count())
        val report = LatencyProbe.report()
        for (name in listOf("midiOnSendThreadCpuTime", "rightKeyboardLockToStartRightNote", "rightEngineNoteOnEntryToJniCall"))
            assertTrue("report names $name", report.contains(name))
        LatencyProbe.reset(); LatencyProbe.enabled = false
        LatencyProbe.onSendEnter(0); LatencyProbe.onSendExit(); LatencyProbe.preJniLogging(0, 10); LatencyProbe.keyboardLockAcquired(0); LatencyProbe.rightRoutingStart()
        assertEquals(0L, LatencyProbe.midiOnSendCpu.count() + LatencyProbe.rightPreJniLogging.count() + LatencyProbe.rightLockToRouting.count())
    }

    @Test fun probeNeverChangesWhatReachesTheEngine() {
        val (brain, bridge, midi) = brain()
        for (enabled in listOf(true, false)) {
            LatencyProbe.enabled = enabled
            brain.setRightVoiceEnabled(1, true)
            brain.onKeyboardNoteOn(72, 100 / 127f)
            brain.onKeyboardNoteOff(72)
            verify(bridge, atLeastOnce()).nativeSfNoteOnChannel(0, 72, 100 / 127f)
            verify(bridge, atLeastOnce()).nativeSfNoteOnChannel(1, 72, 100 / 127f)
            clearInvocations(bridge, midi)
        }
    }

    @Test fun styleAndChordCodeCarryNoLatencyHooks() {
        val seq = source("yamahaarranger/arranger/StyleSequencer.kt").readText()
        assertFalse("style engine untouched by latency work", seq.contains("LatencyProbe"))
        val brain = source("arranger/ArrangerBrain.kt").readText()
        assertFalse(brain.contains("chordNoteParsed") || brain.contains("chordApplyStart") || brain.contains("chordApplyEnd"))
    }

    @Test fun bufferModeIsNeverPersistedAndDefaultsToBaseline() {
        val vm = source("ui/MainViewModel.kt").readText()
        assertTrue(vm.contains("private val _bufferMode = MutableStateFlow(0)"))
        val ui = source("ui/LatencyAbOverlay.kt").readText()
        for (forbidden in listOf("SharedPreferences", "DataStore", "dataStore"))
            assertFalse("A/B mode must not persist: $forbidden", ui.contains(forbidden) || vm.substringAfter("_bufferMode").substringBefore("fun runOnsetTest").contains(forbidden))
        val engine = File(root(), "app/src/main/cpp/audio_engine.cpp").readText()
        assertTrue("production buffer = 2 bursts unless an A/B mode is set", File(root(), "app/src/main/cpp/buffer_policy.h").readText().contains("int frames = burst * 2;"))
        assertTrue(engine.contains("buffer_policy::framesFor(bufferMode_.load()"))
        assertFalse("audio callback must not call the buffer policy", engine.substringAfter("AudioEngine::onAudioReady").substringBefore("void AudioEngine::sfNoteOnChannel").contains("buffer_policy"))
    }

    // ---- RIGHT play regression: notes, sustain, release, layers, chords, rapid input ----------------------------------

    @Test fun rapidRepeatedNotesAndChordsLeaveNothingStuck() {
        val (brain, bridge, _) = brain()
        brain.setRightVoiceEnabled(1, true); brain.setRightVoiceEnabled(2, true)
        val ons = ArgumentCaptor.forClass(Int::class.java)
        repeat(300) { n ->
            val note = 60 + n % 24
            brain.onKeyboardNoteOn(note, 0.8f); brain.onKeyboardNoteOff(note)
        }
        for (note in listOf(72, 76, 79, 84)) brain.onKeyboardNoteOn(note, 0.7f)   // chord
        for (note in listOf(72, 76, 79, 84)) brain.onKeyboardNoteOff(note)
        for (ch in 0..2) {
            val onCount = org.mockito.Mockito.mockingDetails(bridge).invocations.count { it.method.name == "nativeSfNoteOnChannel" && it.arguments[0] == ch }
            val offCount = org.mockito.Mockito.mockingDetails(bridge).invocations.count { it.method.name == "nativeSfNoteOffChannel" && it.arguments[0] == ch }
            assertEquals("RIGHT${ch + 1} NOTE_ON/OFF balanced", onCount, offCount)
            assertEquals(304, onCount)
        }
        assertTrue(ons.allValues.isEmpty())
    }

    @Test fun sustainHoldsThenFlushesEveryKey() {
        val (brain, bridge, _) = brain()
        brain.setKeyboardSustain(true)
        for (note in 70..75) { brain.onKeyboardNoteOn(note, 0.6f); brain.onKeyboardNoteOff(note) }
        verify(bridge, never()).nativeSfNoteOffChannel(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())
        brain.setKeyboardSustain(false)
        for (note in 70..75) verify(bridge).nativeSfNoteOffChannel(0, note)
    }

    @Test fun releaseKnobIsNotTouchedByNotePlay() {
        val (brain, bridge, _) = brain()
        brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOff(72)
        verify(bridge, never()).nativeSetKeyboardReleaseTime(anyInt())
    }

    @Test fun rightLayersOnOffAndPartOffStopOnlyThatLayer() {
        val (brain, bridge, _) = brain()
        brain.setRightVoiceEnabled(1, true)
        brain.onKeyboardNoteOn(72, 1f)
        brain.setRightVoiceEnabled(1, false)
        verify(bridge).nativeStopKeyboardPart(1)
        verify(bridge, never()).nativeStopKeyboardPart(0)
        brain.onKeyboardNoteOff(72)
        verify(bridge).nativeSfNoteOffChannel(0, 72)
    }

    @Test fun virtualKeyboardStillWorksNextToUsbInput() {
        val (brain, bridge, midi) = brain()
        brain.onKeyboardNoteOn(72, 1f)
        brain.virtualKeyboardSink.noteOn(74, 100)
        brain.virtualKeyboardSink.noteOff(74)
        brain.onKeyboardNoteOff(72)
        verify(bridge).nativeSfNoteOffChannel(0, 74)
        verify(bridge).nativeSfNoteOffChannel(0, 72)
        verify(midi, never()).sendNoteOn(1, 74, 100)
    }

    // ---- USB parsing: split packets, running status, realtime bytes, reconnect-safe parser state ----------------------

    private fun parse(manager: MidiInputManager, vararg packets: ByteArray) {
        val method = MidiInputManager::class.java.getDeclaredMethod("parseMessages", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        method.isAccessible = true
        for (p in packets) method.invoke(manager, p, 0, p.size)
    }

    @Test fun usbParserHandlesSplitPacketsRunningStatusAndRealtimeBytes() {
        val manager = MidiInputManager(mock(android.content.Context::class.java))
        val events = mutableListOf<String>()
        manager.onNoteOn = { n, v -> events += "on:$n:$v" }
        manager.onNoteOff = { n -> events += "off:$n" }
        val ch = manager.chordInputChannel
        val on = (0x90 or ch).toByte(); val off = (0x80 or ch).toByte()
        parse(manager,
            byteArrayOf(on, 60), byteArrayOf(100),                                    // NOTE_ON split across two packets
            byteArrayOf(0xF8.toByte(), 62, 90),                                       // running status + clock in between
            byteArrayOf(on, 64, 0),                                                   // velocity 0 = NOTE_OFF
            byteArrayOf(off, 60, 64, 62, 0))                                          // NOTE_OFF + running-status NOTE_OFF
        assertEquals(listOf("on:60:100", "on:62:90", "off:64", "off:60", "off:62"), events)
    }

    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { if (File(dir, "app/src/main/java").exists()) return dir; dir = dir.parentFile }
        throw AssertionError("repo root not found")
    }
    private fun source(relative: String) = File(root(), "app/src/main/java/com/yourapp/$relative")
}

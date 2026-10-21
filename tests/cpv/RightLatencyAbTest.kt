package com.yourapp.yamahaarranger.cpv

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.LatencyProbe
import com.yourapp.yamahaarranger.audio.NoteLogTuning
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
    @After fun restore() { LatencyProbe.enabled = true; LatencyProbe.reset(); NoteLogTuning.lean = false; NoteLogTuning.resetCounter() }

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

    @Test fun styleAndChordCodeCarryOnlyTheWhitelistedObserverHooks() {
        // Policy of the realtime audit (docs/REALTIME_AUDIT_METRONOME_REPORT.md): the Style/ACMP code may call the observer-only
        // LatencyProbe entry points below and nothing else; the CASM transformer and the chord detector stay hook-free.
        val allowed = setOf("enabled", "schedulerWake", "styleEventDispatched", "retargetDecision", "sectionBoundary", "voiceApplyStart", "voiceApplyEnd",
            "sectionRequested", "chordNoteParsed", "chordKeyDown", "chordRecognized", "chordApplyStart", "chordApplyEnd", "rightRoutingStart", "rightHandedOff", "keyboardLockAcquired")
        for (file in listOf("yamahaarranger/arranger/StyleSequencer.kt", "arranger/ArrangerBrain.kt")) {
            val used = Regex("LatencyProbe\\.(\\w+)").findAll(source(file).readText()).map { it.groupValues[1] }.toSet()
            assertTrue("$file uses only whitelisted LatencyProbe calls, got $used", allowed.containsAll(used))
        }
        for (file in listOf("yamahaarranger/arranger/CasmNoteTransformer.kt", "chord/ChordDetector.kt"))
            assertFalse("$file stays hook-free", source(file).readText().contains("LatencyProbe"))
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

    // ---- LEAN NOTE LOG (A/B): only log lines are skipped ---------------------------------------------------------

    private fun playPhrase(brain: ArrangerBrain) {
        brain.setRightVoiceEnabled(1, true)
        brain.onKeyboardNoteOn(72, 100 / 127f); brain.onKeyboardNoteOn(76, 90 / 127f); brain.onKeyboardNoteOn(79, 80 / 127f)
        brain.onKeyboardNoteOff(76); brain.onKeyboardNoteOff(72); brain.onKeyboardNoteOff(79)
    }

    private fun engineCalls(bridge: NativeAudioBridge) = org.mockito.Mockito.mockingDetails(bridge).invocations
        .filter { it.method.name.startsWith("nativeSfNote") }.map { it.method.name + it.arguments.joinToString(",", "(", ")") }

    @Test fun leanLogIsOffByDefaultAndChangesNoEngineOrMidiCall() {
        assertFalse("every launch starts normal", NoteLogTuning.lean)
        val (brainA, bridgeA, midiA) = brain()
        NoteLogTuning.lean = false; NoteLogTuning.resetCounter()
        playPhrase(brainA)
        assertEquals("normal mode suppresses nothing", 0L, NoteLogTuning.suppressed)
        val normalEngine = engineCalls(bridgeA)
        val normalMidi = org.mockito.Mockito.mockingDetails(midiA).invocations.map { it.method.name + it.arguments.joinToString(",", "(", ")") }

        val (brainB, bridgeB, midiB) = brain()
        NoteLogTuning.lean = true; NoteLogTuning.resetCounter()
        playPhrase(brainB)
        assertTrue("lean mode does skip the per-note log lines", NoteLogTuning.suppressed >= 12)
        assertEquals("identical engine calls, order and velocities", normalEngine, engineCalls(bridgeB))
        val leanMidi = org.mockito.Mockito.mockingDetails(midiB).invocations.map { it.method.name + it.arguments.joinToString(",", "(", ")") }
        assertEquals("MIDI OUT traffic is untouched", normalMidi, leanMidi)
    }

    @Test fun leanLogKeepsUsbParsingAndSustainIdentical() {
        for (lean in listOf(false, true)) {
            NoteLogTuning.lean = lean
            val manager = MidiInputManager(mock(android.content.Context::class.java))
            val events = mutableListOf<String>()
            manager.onNoteOn = { n, v -> events += "on:$n:$v" }; manager.onNoteOff = { n -> events += "off:$n" }
            val ch = manager.chordInputChannel
            parse(manager, byteArrayOf((0x90 or ch).toByte(), 60, 100, 64, 90), byteArrayOf((0x80 or ch).toByte(), 60, 0))
            assertEquals(listOf("on:60:100", "on:64:90", "off:60"), events)
        }
        val (brain, bridge, _) = brain()
        NoteLogTuning.lean = true
        brain.setKeyboardSustain(true)
        for (note in 70..72) { brain.onKeyboardNoteOn(note, 0.6f); brain.onKeyboardNoteOff(note) }
        verify(bridge, never()).nativeSfNoteOffChannel(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())
        brain.setKeyboardSustain(false)
        for (note in 70..72) verify(bridge).nativeSfNoteOffChannel(0, note)
    }

    @Test fun leanSwitchIsProcessLocalAndNeverPersisted() {
        val probe = source("yamahaarranger/audio/PcmSessionControls.kt").readText()
        val tuning = probe.substringAfter("object NoteLogTuning")
        assertTrue(tuning.contains("@Volatile var lean = false"))
        for (forbidden in listOf("SharedPreferences", "DataStore", "File("))
            assertFalse(tuning.contains(forbidden) || source("ui/LatencyAbOverlay.kt").readText().contains(forbidden))
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

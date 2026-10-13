package com.yourapp.audio

import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.midi.MidiInputManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class VoiceReleaseContractTest {
    @Test fun rightOffReleasesOwnedKeyBeforePhysicalKeyUp() {
        val (engine, bridge) = engine()
        engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.onKeyboardNoteOn(72, 1f)
        brain.setRightVoiceEnabled(0, false)
        verify(bridge).nativeSfNoteOffChannel(0, 72)
        brain.onKeyboardNoteOff(72)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 72)
        verify(bridge, never()).nativeAllNotesOff()
    }

    @Test fun everyRightOffOnlyStopsItsOwnersAndPreservesLeftAndOtherLayers() {
        for (target in 0..2) {
            val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
            val midi = mock(MidiInputManager::class.java)
            val brain = ArrangerBrain(engine, ChordDetector(), midi)
            brain.setRightVoiceEnabled(1, true); brain.setRightVoiceEnabled(2, true)
            brain.setAcmpEnabled(false); brain.setKeyboardReleaseTime(127)
            brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOn(40, 1f)
            clearInvocations(bridge, midi)
            brain.setRightVoiceEnabled(target, false)
            verify(bridge).nativeSfNoteOffChannel(target, 72)
            verify(bridge).nativeStopKeyboardPart(target)
            verify(midi).sendRightPartSoundOff(target)
            for (other in (0..3).filter { it != target }) {
                verify(bridge, never()).nativeSfNoteOffChannel(eq(other), anyInt())
                verify(bridge, never()).nativeStopKeyboardPart(other)
            }
            brain.onKeyboardNoteOff(72)
            for (channel in 0..2) verify(bridge, times(1)).nativeSfNoteOffChannel(channel, 72)
            verify(bridge, never()).nativeSfNoteOffChannel(3, 40)
            verify(bridge, never()).nativeAllNotesOff()
            verify(bridge, never()).nativeSetKeyboardReleaseTime(anyInt())
            verify(bridge, never()).nativeSetChannelPreset(anyInt(), anyInt(), anyInt())
        }
    }

    @Test fun rightOffStopsAlreadyReleasedTailWithoutSendingOtherNoteOffs() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setRightVoiceEnabled(1, true); brain.setKeyboardReleaseTime(127)
        brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOff(72)
        clearInvocations(bridge)
        brain.setRightVoiceEnabled(1, false)
        verify(bridge).nativeStopKeyboardPart(1)
        verify(bridge, never()).nativeSfNoteOffChannel(anyInt(), anyInt())
        verify(bridge, never()).nativeAllNotesOff()
        verify(bridge, never()).nativeSetKeyboardReleaseTime(anyInt())
    }

    @Test fun noteOffUsesOriginalOwnersAndPitchAfterNewLayerAndSplitChanges() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setKeyboardTranspose(5); brain.onKeyboardNoteOn(72, 1f)
        brain.setRightVoiceEnabled(1, true); brain.setKeyboardTranspose(-5); brain.setSplitPoint(80)
        brain.onKeyboardNoteOff(72)
        verify(bridge).nativeSfNoteOffChannel(0, 77)
        verify(bridge, never()).nativeSfNoteOffChannel(1, 77)
        verify(bridge, never()).nativeSfNoteOffChannel(anyInt(), eq(67))
    }

    @Test fun rightOffOnBeforeKeyUpDoesNotCreateNewOwnership() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.onKeyboardNoteOn(72, 1f); brain.setRightVoiceEnabled(0, false)
        brain.setRightVoiceEnabled(0, true); brain.onKeyboardNoteOff(72)
        verify(bridge, times(1)).nativeSfNoteOnChannel(0, 72, 1f)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 72)
        brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOff(72)
        verify(bridge, times(2)).nativeSfNoteOffChannel(0, 72)
    }

    @Test fun rightOffBypassesSustainOnlyForSelectedPart() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setRightVoiceEnabled(1, true); brain.setRightVoiceEnabled(2, true)
        brain.setKeyboardSustain(true); brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOff(72)
        brain.setRightVoiceEnabled(0, false)
        verify(bridge).nativeSfNoteOffChannel(0, 72)
        verify(bridge, never()).nativeSfNoteOffChannel(1, 72)
        brain.setKeyboardSustain(false)
        for (channel in 0..2) verify(bridge, times(1)).nativeSfNoteOffChannel(channel, 72)
    }

    @Test fun lowerKeyRoutedToRightRetainsOwnersAcrossModeChanges() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setLeftVoiceEnabled(false); brain.setRightVoiceEnabled(1, true)
        brain.onKeyboardNoteOn(40, 1f); brain.setRightVoiceEnabled(0, false)
        brain.setLeftVoiceEnabled(true); brain.onKeyboardNoteOff(40)
        verify(bridge, times(1)).nativeSfNoteOffChannel(0, 40)
        verify(bridge, times(1)).nativeSfNoteOffChannel(1, 40)
        verify(bridge, never()).nativeSfNoteOffChannel(3, 40)
    }

    @Test fun partOffCannotOvertakeInFlightNativeNoteOn() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        val entered = java.util.concurrent.CountDownLatch(1)
        val allowOn = java.util.concurrent.CountDownLatch(1)
        val offRequested = java.util.concurrent.CountDownLatch(1)
        doAnswer { entered.countDown(); assertTrue(allowOn.await(5, java.util.concurrent.TimeUnit.SECONDS)); null }
            .`when`(bridge).nativeSfNoteOnChannel(0, 72, 1f)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val on = pool.submit { brain.onKeyboardNoteOn(72, 1f) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val off = pool.submit { offRequested.countDown(); brain.setRightVoiceEnabled(0, false) }
            assertTrue(offRequested.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(off.isDone)
            verify(bridge, never()).nativeStopKeyboardPart(0)
            allowOn.countDown(); on.get(5, java.util.concurrent.TimeUnit.SECONDS); off.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val ordered = inOrder(bridge)
            ordered.verify(bridge).nativeSfNoteOnChannel(0, 72, 1f)
            ordered.verify(bridge).nativeSfNoteOffChannel(0, 72)
            ordered.verify(bridge).nativeStopKeyboardPart(0)
            brain.onKeyboardNoteOn(74, 1f); brain.onKeyboardNoteOff(72)
            verify(bridge, never()).nativeSfNoteOnChannel(0, 74, 1f)
            verify(bridge, times(1)).nativeSfNoteOffChannel(0, 72)
        } finally { allowOn.countDown(); pool.shutdownNow() }
    }

    @Test fun externalPartStopIsScopedAndNeverSendsCc64OrCc72() {
        val midi = MidiInputManager(mock(android.content.Context::class.java))
        val port = mock(android.media.midi.MidiInputPort::class.java)
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible = true }.set(midi, port)
        midi.sendRightPartSoundOff(0); verifyNoInteractions(port)
        midi.midiOutEnabled = true
        for (channel in listOf(-1, 3, 8, 15, 16)) midi.sendRightPartSoundOff(channel)
        verifyNoInteractions(port)
        midi.sendRightPartSoundOff(1)
        verify(port).send(byteArrayOf(0xB1.toByte(), 120, 0), 0, 3)
        verifyNoMoreInteractions(port)
    }

    @Test fun releaseBypassRetainsKnobAndRestoresItAtBothRangeEnds() {
        for (value in listOf(0, 37, 127)) {
            val on = ReleaseControlState(value)
            val off = on.toggle()
            assertEquals(value, off.knobValue); assertEquals(64, off.effectiveValue)
            assertEquals(value, off.toggle().effectiveValue)
        }
        assertEquals(127, ReleaseControlState(127).step(1).knobValue)
        assertEquals(0, ReleaseControlState(0).step(-1).knobValue)
        val edited = ReleaseControlState(37, false).step(1)
        assertEquals(38, edited.knobValue); assertEquals(64, edited.effectiveValue)
    }

    @Test fun releaseBypassAndRestartDoNotStopHeldKeysOrLoseRightOwnership() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setRightVoiceEnabled(1, true); brain.setRightVoiceEnabled(2, true)
        brain.onKeyboardNoteOn(72, 1f); clearInvocations(bridge)
        var control = ReleaseControlState(127)
        brain.setKeyboardReleaseTime(control.effectiveValue)
        control = control.toggle(); brain.setKeyboardReleaseTime(control.effectiveValue)
        engine.start()
        assertEquals(127, control.knobValue)
        verify(bridge, atLeastOnce()).nativeSetKeyboardReleaseTime(64)
        verify(bridge, never()).nativeAllNotesOff()
        verify(bridge, never()).nativeStopKeyboardPart(anyInt())
        verify(bridge, never()).nativeSfNoteOffChannel(anyInt(), anyInt())
        control = control.toggle(); brain.setKeyboardReleaseTime(control.effectiveValue)
        brain.onKeyboardNoteOff(72)
        for (channel in 0..2) verify(bridge).nativeSfNoteOffChannel(channel, 72)
        verify(bridge, times(2)).nativeSetKeyboardReleaseTime(127)
    }

    @Test fun existingMixerRetainsChannelVolumeThroughMuteAndUsesStyleBusOnly() {
        val (engine, bridge) = engine(); engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.attachScope(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        brain.setStyleChannelMixer(10, 64, 96, 100, 50, 30)
        verify(bridge).nativeSetChannelMixer(10,64,96,100,50,30)
        brain.setStyleChannelMute(10,true)
        brain.setStyleChannelMixer(10,80,96,100,50,30)
        verify(bridge, times(2)).nativeSetChannelMixer(10,0,96,100,50,30)
        brain.setStyleChannelMute(10,false)
        verify(bridge).nativeSetChannelMixer(10,80,96,100,50,30)
        clearInvocations(bridge)
        brain.setStyleBusTrim(64)
        verify(bridge).nativeSetChannelExpression(10,50)
        for (channel in 0..3) verify(bridge, never()).nativeSetChannelExpression(eq(channel), anyInt())
        engine.setChannelExpression(0,33); engine.setChannelExpression(1,44)
        engine.setChannelExpression(2,55); engine.setChannelExpression(3,0)
        verify(bridge).nativeSetChannelExpression(0,33); verify(bridge).nativeSetChannelExpression(1,44)
        verify(bridge).nativeSetChannelExpression(2,55); verify(bridge).nativeSetChannelExpression(3,0)
        verify(bridge, never()).nativeAllNotesOff()
        verify(bridge, never()).nativeStopKeyboardPart(anyInt())
    }

    @Test fun mixerInventoryUsesRealNoteOnsAndEveryMatchingCasmDestination() {
        fun policy(source: Int, dest: Int) = com.yourapp.yamahaarranger.style.CasmPolicyModel(source,dest,"Strings",0,0,0,0,127,0,127,0,false)
        fun event(channel: Int, velocity: Int, on: Boolean = true) = com.yourapp.yamahaarranger.style.StyleNoteEvent(0,on,60,velocity,channel)
        val part = com.yourapp.yamahaarranger.style.StylePartModel("part",listOf(event(14,100)),
            casmPolicies=listOf(policy(14,11),policy(14,12),policy(13,15)))
        assertEquals(setOf(11,12),StyleMixerChannels.destinations(part))
        assertTrue(StyleMixerChannels.destinations(part.copy(events=listOf(event(14,0)))).isEmpty())
        assertTrue(StyleMixerChannels.destinations(part.copy(events=listOf(event(14,64,false)))).isEmpty())
        assertEquals(setOf(9),StyleMixerChannels.destinations(part.copy(casmPolicies=emptyList(),events=listOf(event(9,100)))))
        assertTrue(StyleMixerChannels.destinations(part.copy(casmPolicies=listOf(policy(14,0),policy(14,3),policy(14,16)))).isEmpty())
    }

    @Test fun categoryNavigationPreservesEveryTargetPartIncludingRight2AndRight3() {
        val (engine, bridge) = engine()
        `when`(bridge.nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())).thenReturn(true)
        val preset = AudioEngineManager.SfPreset("MELODY", 1025, 24, "Variation", 8, "a.sf2")
        for (part in KeyboardPart.entries) {
            var browser = KeyboardVoiceBrowserState().selectPart(part)
            for (category in listOf("STRINGS", "PIANO", "USER")) {
                browser = browser.selectCategory(category)
                assertEquals(part, browser.part)
            }
            assertTrue(engine.selectKeyboardPreset(browser.part.channel, preset))
            verify(bridge).nativeSetKeyboardPreset(part.channel, 8, 1025, 24)
            assertEquals(part, browser.selectCategory(null).part)
        }
    }

    @Test fun supersededVoiceRequestDoesNotReachNative() {
        val (engine, bridge) = engine()
        val preset = AudioEngineManager.SfPreset("MELODY", 0, 24, "Guitar", 7, "a.sf2")
        assertFalse(engine.selectKeyboardPreset(KeyboardPart.RIGHT2.channel, preset) { false })
        verify(bridge, never()).nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())
    }

    @Test fun offlineProbeRequiresStoppedStyleAndRightPartThenResumesSameRelease() {
        val (engine, bridge) = engine()
        engine.setKeyboardReleaseTime(127); engine.start()
        assertTrue(engine.diagnosticKeyboardRelease(KeyboardPart.RIGHT2) { false }.wav.isEmpty())
        assertTrue(engine.diagnosticKeyboardRelease(KeyboardPart.LEFT) { true }.wav.isEmpty())
        verify(bridge, never()).nativeDiagnosticKeyboardReleaseWav(anyInt(), anyInt(), anyInt())
        verify(bridge, never()).nativeStop()
        `when`(bridge.nativeDiagnosticKeyboardReleaseWav(1,60,100)).thenReturn(byteArrayOf(1,2))
        `when`(bridge.nativeKeyboardReleaseProbeReport()).thenReturn("measured actual source")
        val result = engine.diagnosticKeyboardRelease(KeyboardPart.RIGHT2) { true }
        assertArrayEquals(byteArrayOf(1,2), result.wav)
        assertEquals("measured actual source", result.report)
        verify(bridge).nativeStop()
        verify(bridge, times(3)).nativeSetKeyboardReleaseTime(127)
        verify(bridge, never()).nativeSetChannelPreset(anyInt(), anyInt(), anyInt())
    }

    private fun engine(): Pair<AudioEngineManager, NativeAudioBridge> {
        val bridge = mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeStart()).thenReturn(true)
        `when`(bridge.nativeSetKeyboardReleaseTime(anyInt())).thenReturn(true)
        `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
        `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
        return AudioEngineManager(bridge, object : SampleProvider {
            override fun sampleForNote(midiNote: Int): NoteSample? = null
        }) to bridge
    }

    @Test fun duplicateAddressesRemainDistinctAcrossSoundFonts() {
        val raw = "MELODY\u001f1\u001fa.sf2\u001f1025\u001f24\u001fSame name\u001e" +
            "MELODY_SECONDARY_1\u001f2\u001fb.sf2\u001f1025\u001f24\u001fSame name\u001e" +
            "DRUM\u001f3\u001fdrums.sf2\u001f128\u001f0\u001fKit\u001e"
        val presets = SoundFontPresetCatalog.parse(raw)
        assertEquals(3, presets.size)
        assertEquals(3, presets.map { it.identity }.toSet().size)
        assertEquals(1025, presets[1].bank)
        assertTrue(presets[2].isDrum)
        assertEquals("b.sf2", presets[1].soundFontName)
    }

    @Test fun malformedAndLegacyRecordsNeverBecomeSelectableFallbacks() {
        assertTrue(SoundFontPresetCatalog.parse("MELODY|0|0|Piano;0:0:Piano").isEmpty())
        assertTrue(SoundFontPresetCatalog.parse("MELODY\u001f0\u001ff.sf2\u001f0\u001f0\u001fPiano").isEmpty())
        assertTrue(SoundFontPresetCatalog.parse("MELODY\u001f1\u001ff.sf2\u001f-1\u001f0\u001fPiano").isEmpty())
    }

    @Test fun searchAndSf2FilterRetainNonGmAndDrumPresets() {
        val presets = listOf(
            AudioEngineManager.SfPreset("MELODY", 65535, 127, "Wide Guitar", 1, "a.sf2"),
            AudioEngineManager.SfPreset("DRUM", 127, 1, "Wide Kit", 2, "b.sf2"))
        assertEquals(presets, SoundFontPresetCatalog.filter(presets, "  wIDE "))
        assertEquals(listOf(presets[1]), SoundFontPresetCatalog.filter(presets, "kit", 2))
        assertTrue(SoundFontPresetCatalog.filter(presets, "guitar", 2).isEmpty())
    }

    @Test fun keyboardSelectionCarriesExactIdAndRawBankToNativeForAllFourParts() {
        val (engine, bridge) = engine()
        val preset = AudioEngineManager.SfPreset("MELODY_SECONDARY_2", 65535, 127, "Variation", 42, "b.sf2")
        `when`(bridge.nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())).thenReturn(true)
        for (channel in 0..3) {
            assertTrue(engine.selectKeyboardPreset(channel, preset))
            verify(bridge).nativeSetKeyboardPreset(channel, 42, 65535, 127)
        }
        assertFalse(engine.selectKeyboardPreset(8, preset))
        verify(bridge, never()).nativeSetChannelPreset(anyInt(), anyInt(), anyInt())
        verify(bridge, never()).nativeSetChannelPresetWithName(anyInt(), anyInt(), anyInt(), anyString())
    }

    @Test fun failedSelectionIsReportedWithoutResolverFallback() {
        val (engine, bridge) = engine()
        assertFalse(engine.selectKeyboardPreset(0, AudioEngineManager.SfPreset("MELODY", 0, 0, "Piano", 7, "a.sf2")))
        assertFalse(engine.selectKeyboardPreset(0, AudioEngineManager.SfPreset("MELODY", 0, 0, "Piano")))
        verify(bridge, never()).nativeSetChannelPreset(anyInt(), anyInt(), anyInt())
    }

    @Test fun catalogRefreshDoesNotPauseOutputAndUnloadClearsIt() {
        val (engine, bridge) = engine()
        engine.start()
        `when`(bridge.nativeGetSoundFontPresets()).thenReturn("DRUM\u001f5\u001fd.sf2\u001f128\u001f0\u001fKit\u001e")
        assertEquals(5L, engine.loadedSoundFontPresets().single().soundFontId)
        verify(bridge, never()).nativeStop()
        engine.unloadSoundFont()
        `when`(bridge.nativeGetSoundFontPresets()).thenReturn("")
        assertTrue(engine.loadedSoundFontPresets().isEmpty())
    }

    @Test fun releaseRangeAndNormalCenterReachNativeAndNeverSustain() {
        val (engine, bridge) = engine()
        val brain = ArrangerBrain(engine, mock(ChordDetector::class.java), mock(MidiInputManager::class.java))
        for (value in listOf(-10, 64, 200)) brain.setKeyboardReleaseTime(value)
        verify(bridge).nativeSetKeyboardReleaseTime(0)
        verify(bridge).nativeSetKeyboardReleaseTime(64)
        verify(bridge).nativeSetKeyboardReleaseTime(127)
        verify(bridge, never()).nativeSetKeyboardSustain(anyBoolean())
    }

    @Test fun releaseIsReplayedAfterStartAndSf2PauseResume() {
        val (engine, bridge) = engine()
        assertTrue(engine.setKeyboardReleaseTime(127))
        engine.start()
        engine.loadSoundFontPair("melody.sf2", "drums.sf2")
        verify(bridge, times(3)).nativeSetKeyboardReleaseTime(127)
        `when`(bridge.nativeSetKeyboardReleaseTime(0)).thenReturn(false)
        assertFalse(engine.setKeyboardReleaseTime(0))
    }

    @Test fun releaseCc72ReachesExternalKeyboardOnlyWhenMidiOutIsEnabled() {
        val midi = MidiInputManager(mock(android.content.Context::class.java))
        val port = mock(android.media.midi.MidiInputPort::class.java)
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible = true }.set(midi, port)
        midi.sendReleaseTime(1, 127)
        verifyNoInteractions(port)
        midi.midiOutEnabled = true
        for ((channel, value) in listOf(0 to 0, 1 to 127, 2 to 200)) midi.sendReleaseTime(channel, value)
        verify(port).send(byteArrayOf(0xB0.toByte(), 72, 0), 0, 3)
        verify(port).send(byteArrayOf(0xB1.toByte(), 72, 127), 0, 3)
        verify(port).send(byteArrayOf(0xB2.toByte(), 72, 127), 0, 3)
    }

    @Test fun sustainDefersRightNoteOffWhileReleaseControllerStillReachesNative() {
        val (engine, bridge) = engine()
        engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setKeyboardSustain(true)
        brain.setKeyboardReleaseTime(0)
        brain.onKeyboardNoteOn(72, 1f); brain.onKeyboardNoteOff(72)
        verify(bridge).nativeSetKeyboardReleaseTime(0)
        verify(bridge, never()).nativeSfNoteOffChannel(0, 72)
        brain.setKeyboardSustain(false)
        verify(bridge).nativeSfNoteOffChannel(0, 72)
    }

    @Test fun rightLayersAndLeftStillUseTheirOwnNoteOnAndNoteOffChannels() {
        val (engine, bridge) = engine()
        engine.loadSoundFontPair("m", "d")
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.setRightVoiceEnabled(1, true); brain.setRightVoiceEnabled(2, true)
        brain.setAcmpEnabled(false)
        brain.onKeyboardNoteOn(64, 1f); brain.onKeyboardNoteOff(64)
        brain.onKeyboardNoteOn(40, 1f); brain.onKeyboardNoteOff(40)
        for (channel in 0..2) {
            verify(bridge).nativeSfNoteOnChannel(channel, 64, 1f)
            verify(bridge).nativeSfNoteOffChannel(channel, 64)
        }
        verify(bridge).nativeSfNoteOnChannel(3, 40, 1f)
        verify(bridge).nativeSfNoteOffChannel(3, 40)
        verify(bridge, never()).nativeSfNoteOnChannel(eq(8), anyInt(), anyFloat())
        verify(bridge, never()).nativeSfNoteOnChannel(eq(9), anyInt(), anyFloat())
    }
}

package com.yourapp.audio

import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.midi.MidiInputManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class VoiceReleaseContractTest {
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

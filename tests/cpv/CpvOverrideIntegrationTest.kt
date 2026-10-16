package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleVoicePin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`

class CpvOverrideIntegrationTest {
    private val col = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506"
    private val presets = listOf(
        "MELODY\u001f1\u001fColomboGMGS2_BM.sf2\u001f0\u001f33\u001fFinger Bass",
        "MELODY\u001f1\u001fColomboGMGS2_BM.sf2\u001f127\u001f16\u001fXG Rock",
        "MELODY\u001f2\u001fyamaha tyros 4.sf2\u001f0\u001f48\u001fTyros Strings"
    ).joinToString("\u001e", postfix = "\u001e")

    private fun section(name: String): StyleSectionModel {
        fun part(src: Int, dst: Int, voice: String, msb: Int, pc: Int) = StylePartModel(voice, listOf(
            StyleNoteEvent(0, false, 0, msb, src, 0xB0 or src), StyleNoteEvent(0, false, 32, 0, src, 0xB0 or src),
            StyleNoteEvent(0, false, pc, 0, src, 0xC0 or src), StyleNoteEvent(12, true, 40, 100, src)),
            casm = CasmPolicyModel(src, dst, voice, 0, 0, 0, 0, 127, 0, 127, 0, false), program = pc, bankMsb = msb)
        return StyleSectionModel(name, 48, listOf(part(8, 8, "Rhythm1", 127, 0), part(10, 10, "Bass", 0, 33)))
    }

    private fun names(bridge: NativeAudioBridge, name: String) = mockingDetails(bridge).invocations.filter { it.method.name == name }

    @Test fun pinsSurviveSectionChangesNeverReachMidiOutAndDoNotLeak() {
        val bridge = mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeStart()).thenReturn(true)
        `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
        `when`(bridge.nativeLoadMelodyFallbackSoundFont(anyString())).thenReturn(true)
        `when`(bridge.nativeSetDrumABMode(anyBoolean())).thenReturn(true)
        `when`(bridge.nativeBindDrumAB(anyInt(), anyString(), anyInt(), anyInt(), anyString())).thenReturn(true)
        `when`(bridge.nativePinChannelPreset(anyInt(), anyLong(), anyInt(), anyInt())).thenReturn(true)
        `when`(bridge.nativeGetSoundFontPresets()).thenReturn(presets)
        `when`(bridge.nativeFontPdta(anyLong())).thenReturn(col)
        val engine = AudioEngineManager(bridge, mock(SampleProvider::class.java))
        assertTrue(engine.loadColomboPrimaryStack("ColomboGMGS2_BM.sf2", "yamaha tyros 4.sf2"))
        assertTrue(engine.drumABResolved)
        assertTrue(names(bridge, "nativeSetSourceTiered").single().arguments[0] == true)
        assertTrue(names(bridge, "nativeLoadDrumSoundFont").isEmpty())   // no Prime / dedicated drum SF2

        val midi = mock(MidiInputManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seq = StyleSequencer(engine, midi, scope)
        val apply = StyleSequencer::class.java.getDeclaredMethod("applyVoicesFromCasm", StyleSectionModel::class.java)
            .apply { isAccessible = true }

        val bassPin = StyleVoicePin("yamaha tyros 4.sf2", 0, 48, "Tyros Strings", false)
        val kitPin = StyleVoicePin("ColomboGMGS2_BM.sf2", 127, 16, "XG Rock", true)
        val midiBefore = mockingDetails(midi).invocations.size
        assertTrue(seq.setVoicePin(10, bassPin))
        assertTrue(seq.setVoicePin(8, kitPin))
        assertEquals(midiBefore, mockingDetails(midi).invocations.size) // pins send nothing to E343
        val pin = names(bridge, "nativePinChannelPreset").single()
        assertEquals(listOf<Any>(10, 2L, 0, 48), pin.arguments.toList())
        val kit = names(bridge, "nativeBindDrumAB").single()
        assertEquals(listOf<Any>(8, col, 127, 16, "OVERRIDE"), kit.arguments.toList())

        // Main A -> Fill AA -> Main B: style setup never replaces the pinned voices.
        com.yourapp.yamahaarranger.audio.LatencyProbe.reset()
        for (name in listOf("Main A", "Fill AA", "Main B")) apply.invoke(seq, section(name))
        assertEquals(3, com.yourapp.yamahaarranger.audio.LatencyProbe.sectionApply.count()) // every section apply is timed
        assertTrue(names(bridge, "nativeSetChannelPresetWithName").none { it.arguments[0] == 10 })
        assertTrue(names(bridge, "nativeSetChannelPreset").none { it.arguments[0] == 10 })
        assertEquals(1, names(bridge, "nativeBindDrumAB").size)
        // MIDI OUT keeps the style's own request, not the internal override.
        val external = mockingDetails(midi).invocations.filter { it.method.name == "sendProgramChange" }
        assertTrue(external.filter { it.arguments[0] == 10 }.all { it.arguments[1] == 33 && it.arguments[2] == 0 })
        assertTrue(external.filter { it.arguments[0] == 8 }.all { it.arguments[1] == 0 && it.arguments[2] == 127 })

        // RESET: the style's own voice comes back at once.
        assertTrue(seq.setVoicePin(10, null))
        assertTrue(names(bridge, "nativePinChannelPreset").any { it.arguments.toList() == listOf<Any>(10, 0L, 0, 0) })
        assertTrue(names(bridge, "nativeSetChannelPresetWithName").any { it.arguments[0] == 10 && it.arguments[2] == 33 })
        assertNull(seq.voicePin(10))

        // A new style starts clean: no pin from the previous style survives.
        seq.clearVoicePins()
        assertNull(seq.voicePin(8))
        apply.invoke(seq, section("Main A"))
        assertTrue(names(bridge, "nativeBindDrumAB").size > 1) // style kit request is bound again
        scope.cancel()
    }

    @Test fun missingPresetIsNotSubstituted() {
        val bridge = mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeGetSoundFontPresets()).thenReturn(presets)
        val engine = AudioEngineManager(bridge, mock(SampleProvider::class.java))
        val absent = StyleVoicePin("PRIME MELODI.sf2", 0, 5, "Prime EP", false)
        assertTrue(!engine.pinStyleChannel(11, absent))
        assertTrue(names(bridge, "nativePinChannelPreset").isEmpty())
        // A melodic preset cannot be pinned to a rhythm channel or vice versa.
        assertTrue(!engine.pinStyleChannel(8, StyleVoicePin("yamaha tyros 4.sf2", 0, 48, "Tyros Strings", false)))
        assertTrue(!engine.pinStyleChannel(2, StyleVoicePin("yamaha tyros 4.sf2", 0, 48, "Tyros Strings", false)))
    }
}

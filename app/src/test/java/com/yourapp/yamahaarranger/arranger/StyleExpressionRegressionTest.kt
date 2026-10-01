package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/** Calls the real production controller method with an observable audio boundary. */
class StyleExpressionRegressionTest {
    private class Fixture(initialVolume: Int = 100) {
        val audio = mock(AudioEngineManager::class.java)
        var volume = initialVolume
        var expression = 127
        var mixerWrites = 0
        var expressionWrites = 0
        val sequencer = StyleSequencer(audio, mock(MidiInputManager::class.java), CoroutineScope(SupervisorJob()))
        init {
            doAnswer { call ->
                volume = call.getArgument(1)
                expression = call.getArgument(3)
                mixerWrites++
                null
            }.`when`(audio).setChannelMixer(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())
            doAnswer { call ->
                expression = call.getArgument(1)
                expressionWrites++
                null
            }.`when`(audio).setChannelExpression(anyInt(), anyInt())
            // Reproduce the source/native mismatch observed in Build756.
            val field = StyleSequencer::class.java.getDeclaredField("mixerStates").apply { isAccessible = true }
            val state = (field.get(sequencer) as Array<*>)[13]!!
            state.javaClass.getDeclaredField("volume").apply { isAccessible = true }.setInt(state, 62)
        }
        fun cc(controller: Int, value: Int, channel: Int = 13) {
            val method = StyleSequencer::class.java.getDeclaredMethod("applyStyleController", Int::class.javaPrimitiveType, StyleNoteEvent::class.java)
            method.isAccessible = true
            method.invoke(sequencer, channel, StyleNoteEvent(564, false, controller, value, 13, 0xBD))
        }
        @Suppress("UNCHECKED_CAST")
        fun override(value: StyleChannelOverride) {
            val field = StyleSequencer::class.java.getDeclaredField("channelOverrides").apply { isAccessible = true }
            (field.get(sequencer) as MutableMap<Int, StyleChannelOverride>)[13] = value
        }
    }
    @Test fun expressionDoesNotResendRawVolumeOverEffectiveStringsVolume() {
        val f = Fixture()
        f.cc(11, 126); f.cc(11, 125)
        assertEquals(100, f.volume)
        assertEquals(125, f.expression)
        assertEquals(0, f.mixerWrites)
        assertEquals(2, f.expressionWrites)
    }
    @Test fun ExplicitStyleVolumeEventStillChangesVolume() {
        val f = Fixture()
        f.cc(11, 126); f.cc(7, 62); f.cc(11, 125)
        assertEquals(62, f.volume)
        assertEquals(125, f.expression)
        assertEquals(1, f.mixerWrites)
    }
    @Test fun expressionPreservesMuteAndHonorsExpressionOverride() {
        val f = Fixture(0)
        f.override(StyleChannelOverride(muted = true, expression = 90))
        f.cc(11, 126)
        assertEquals(0, f.volume)
        assertEquals(90, f.expression)
        assertEquals(0, f.mixerWrites)
    }
    @Test fun expressionPreservesIntentionallyInstalledUserVolume() {
        val f = Fixture(55)
        f.override(StyleChannelOverride(volume = 55, expression = 85))
        f.cc(11, 126)
        assertEquals(55, f.volume)
        assertEquals(85, f.expression)
    }
    @Test fun invalidOrUnrelatedControllersDoNotWriteMixer() {
        val f = Fixture()
        f.cc(64, 127); f.cc(11, 126, 1)
        assertEquals(0, f.mixerWrites)
        assertEquals(0, f.expressionWrites)
    }
}

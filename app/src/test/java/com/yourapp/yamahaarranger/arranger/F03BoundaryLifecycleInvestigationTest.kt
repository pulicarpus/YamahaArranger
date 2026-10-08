package com.yourapp.yamahaarranger.arranger

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlinx.coroutines.*
import com.yourapp.yamahaarranger.style.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real external MIDI serializer and real ArrangerBrain ACMP control;
 * Android port/audio boundaries mocked, no hardware/PCM success claim. */
class F03BoundaryLifecycleInvestigationTest {
    @Test fun I_actualMidiOutSerializerUsesDestinationPitchAndFlagsGateOnlyNoteSends() {
        val context=mock(Context::class.java)
        val midi=MidiInputManager(context)
        val port=mock(MidiInputPort::class.java)
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible=true }.set(midi,port)
        val packets=mutableListOf<List<Int>>()
        doAnswer { call ->
            val bytes=call.arguments[0] as ByteArray
            val offset=call.arguments[1] as Int;val count=call.arguments[2] as Int
            packets.add(bytes.slice(offset until offset+count).map { it.toInt() and 255 });null
        }.`when`(port).send((any(ByteArray::class.java) ?: byteArrayOf()),anyInt(),anyInt())
        midi.sendNoteOn(13,65,90);midi.sendNoteOff(13,65)
        assertTrue(packets.isEmpty())
        midi.midiOutEnabled=true
        midi.sendNoteOn(13,65,90);midi.sendNoteOff(13,65)
        assertEquals(listOf(listOf(0x9d,65,90),listOf(0x8d,65,0)),packets)
        midi.midiOutEnabled=false;midi.allNotesOff()
        assertEquals(18,packets.size)
        assertEquals((0..15).map { listOf(0xb0 or it,123,0) },packets.drop(2))
        // No source/event/generation token exists in these actual MIDI bytes.
        println("S5_EXTERNAL_MIDI packets=$packets productionSerializer=true physicalDevice=NOT_MEASURED")
    }

    @Test fun I_missingPortDropsOutputAndSendExceptionDoesNotPropagateIntoSequencer() {
        val midi=MidiInputManager(mock(Context::class.java));midi.midiOutEnabled=true
        midi.sendNoteOn(11,60,100);midi.sendNoteOff(11,60)
        val port=mock(MidiInputPort::class.java)
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible=true }.set(midi,port)
        doThrow(java.io.IOException("synthetic unavailable port")).`when`(port).send((any(ByteArray::class.java) ?: byteArrayOf()),anyInt(),anyInt())
        midi.sendNoteOn(11,60,100);midi.sendNoteOff(11,60)
        verify(port,times(2)).send((any(ByteArray::class.java) ?: byteArrayOf()),anyInt(),anyInt())
    }

    @Test fun J_actualAcmpToggleResetsDetectorButDoesNotClearSequencerChordOrOwner() {
        val audio=mock(AudioEngineManager::class.java);val midi=mock(MidiInputManager::class.java)
        val brain=ArrangerBrain(audio,ChordDetector(),midi)
        val scope=CoroutineScope(SupervisorJob())
        try {
            brain.attachScope(scope)
            val seq=ArrangerBrain::class.java.getDeclaredField("sequencer").apply { isAccessible=true }.get(brain) as StyleSequencer
            val chord=DetectedChord(0,0,ChordQuality.MAJOR);seq.currentChord=chord
            val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val done=CountDownLatch(1)
            doAnswer { entered.countDown();assertTrue(proceed.await(20,TimeUnit.SECONDS));null }.`when`(audio)
                .noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
            val policy=CasmPolicyModel(4,11,"Piano",0,0,0,0,11,0,127,1,false,-1)
            seq.playSeamless(StyleSectionModel("SYNTHETIC_ACMP_RUNNING",3,listOf(StylePartModel("source4",listOf(StyleNoteEvent(0,true,60,100,4),StyleNoteEvent(2,false,60,0,4)),policy,listOf(policy)))),1_000_000_000,1) { done.countDown() }
            assertTrue(entered.await(20,TimeUnit.SECONDS))
            val registry=StyleSequencer::class.java.getDeclaredField("activeTransposedNotes").apply { isAccessible=true }
            val owner=(registry.get(seq) as Map<*,*>)["4:60"]!!
            clearInvocations(audio,midi)
            brain.setAcmpEnabled(false)
            assertSame(chord,seq.currentChord);assertFalse(brain.state.value.acmpEnabled)
            assertSame(owner,(registry.get(seq) as Map<*,*>)["4:60"])
            verifyNoInteractions(audio,midi)
            brain.setAcmpEnabled(true)
            assertSame(chord,seq.currentChord);assertTrue(brain.state.value.acmpEnabled)
            assertSame(owner,(registry.get(seq) as Map<*,*>)["4:60"])
            verifyNoInteractions(audio,midi)
            proceed.countDown();assertTrue(done.await(20,TimeUnit.SECONDS))
            assertTrue((registry.get(seq) as Map<*,*>).isEmpty())
            println("S5_ACMP actualArrangerBrain=true runningSyntheticStyle=true OFF_resets_detector=true sequencerChordAndOwnerRetained=true noToggleStyleReleaseDispatch=true")
        } finally { scope.cancel() }
    }
}

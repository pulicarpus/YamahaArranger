package com.yourapp.yamahaarranger.arranger

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.*
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual sequencer/transformer and MIDI serializer, mocked Android/audio endpoints.
 * Dispatch/packet controls are not native PCM or Yamaha device certification. */
class F04RoutingRegressionTest {
    private fun policy(src:Int,dst:Int,mask:Long=0) = CasmPolicyModel(src,dst,"Piano",0,0,0,0,11,0,127,1,false,mask)
    private fun section(src:Int,policies:List<CasmPolicyModel>,name:String="MainD") =
        StyleSectionModel(name,4,listOf(StylePartModel("source$src",listOf(
            StyleNoteEvent(0,true,60,96,src),StyleNoteEvent(2,false,60,0,src)),policies.firstOrNull(),policies)))
    private class Run:AutoCloseable {
        val audio=mock(AudioEngineManager::class.java)
        val midi=MidiInputManager(mock(Context::class.java))
        val port=mock(MidiInputPort::class.java)
        val packets=mutableListOf<List<Int>>()
        private val scope=CoroutineScope(SupervisorJob())
        val seq=StyleSequencer(audio,midi,scope)
        init {
            MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible=true }.set(midi,port)
            midi.midiOutEnabled=true
            doAnswer {
                val bytes=it.arguments[0] as ByteArray;val start=it.arguments[1] as Int;val count=it.arguments[2] as Int
                synchronized(packets) { packets.add(bytes.slice(start until start+count).map { b->b.toInt() and 255 }) };null
            }.`when`(port).send(any(ByteArray::class.java) ?: byteArrayOf(),anyInt(),anyInt())
        }
        fun play(s:StyleSectionModel,chord:DetectedChord?=DetectedChord(0,0,ChordQuality.MAJOR)) {
            seq.currentChord=chord
            val done=CountDownLatch(1)
            seq.playSeamless(s,1_000_000_000,1) { done.countDown() }
            assertTrue("actual scheduler completion",done.await(20,TimeUnit.SECONDS))
        }
        fun on()=mockingDetails(audio).invocations.filter { it.method.name=="noteOnStyleChannel" }
        fun off()=mockingDetails(audio).invocations.filter { it.method.name in listOf("noteOffStyleChannel","noteOffChannel") }
        fun owners() = StyleSequencer::class.java.getDeclaredField("activeTransposedNotes").apply { isAccessible=true }.get(seq) as Map<*,*>
        fun wireOn()=packets.filter { it[0] and 0xf0 == 0x90 }
        override fun close() { seq.stop();scope.cancel() }
    }
    @Test fun rejectedPureMelodicDeclarationsOnBothHistoricalRhythmSourcesDrop() {
        for(src in listOf(8,9)) for(dst in 10..15) Run().use { r ->
            r.play(section(src,listOf(policy(src,dst))))
            assertTrue(r.on().isEmpty());assertTrue(r.wireOn().isEmpty());assertTrue(r.owners().isEmpty())
        }
    }
    @Test fun emptyDeclarationsKeepLegacySourceEightAndNine() {
        for(src in listOf(8,9)) Run().use { r ->
            r.play(section(src,emptyList()))
            assertEquals(src,r.on().single().arguments[0]);assertEquals(listOf(0x90+src,60,96),r.wireOn().single())
        }
    }
    @Test fun mixedUnknownAndMismatchedDeclarationsKeepHistoricalFallback() {
        for(ps in listOf(listOf(policy(8,11),policy(8,9)),listOf(policy(8,11),policy(8,16)),
                         listOf(policy(8,11),policy(9,12)),listOf(policy(8,4)))) Run().use { r ->
            r.play(section(8,ps));assertEquals(8,r.on().single().arguments[0]);assertEquals(1,r.wireOn().size)
        }
    }
    @Test fun noChordBehaviorIsUnchangedEvenForPureMelodicDeclarations() {
        for(src in listOf(8,9)) Run().use { r ->
            r.play(section(src,listOf(policy(src,11))),null)
            assertEquals(1,r.on().size);assertEquals(1,r.wireOn().size)
        }
    }
    @Test fun applicableRhythmRoutingAndExistingOneShotOffContractRemain() {
        for((src,dst) in listOf(15 to 8,14 to 9,8 to 8,9 to 9)) Run().use { r ->
            r.play(section(src,listOf(policy(src,dst,-1))))
            assertEquals(dst,r.on().single().arguments[0]);assertEquals(listOf(0x90+dst,60,96),r.wireOn().single())
            // F05 remains baseline: scheduled rhythm OFF is not dispatched. Do not invent a fix.
            assertTrue(r.off().isEmpty());assertTrue(r.packets.none { it[0] and 0xf0 == 0x80 })
            assertTrue(r.owners().isEmpty())
        }
    }
    @Test fun validMelodicOnOffAndWirePacketsRemainAcrossSectionNames() {
        // Synthetic section controls, not additional original style captures or device timing proof.
        for(name in listOf("MainA","MainD","FillBB","FillAA","IntroA","EndingA")) Run().use { r ->
            r.play(section(8,listOf(policy(8,11,-1)),name))
            assertEquals(11,r.on().single().arguments[0]);assertEquals(11,r.off().single().arguments[0]);assertTrue(r.owners().isEmpty())
            assertEquals(listOf(listOf(0x9b,60,96),listOf(0x8b,60,0)),r.packets.filter { it[0] and 0xf0 in listOf(0x90,0x80) })
        }
    }
    @Test fun chordMaskAndSourceNoteRangeRemainAuthoritative() {
        Run().use { r ->
            val p=policy(8,11,-1).copy(sourceNoteLow=61,sourceNoteHigh=70)
            r.play(section(8,listOf(p)));assertTrue(r.on().isEmpty());assertTrue(r.owners().isEmpty())
        }
        Run().use { r ->
            r.play(section(4,listOf(policy(4,11))));assertTrue(r.on().isEmpty())
        }
    }
}

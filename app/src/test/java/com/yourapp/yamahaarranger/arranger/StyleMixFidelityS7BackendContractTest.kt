package com.yourapp.yamahaarranger.arranger

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Six isolated contract/packet tests + production native API probe.
 * Models describe alternatives; they are never Yamaha or native voice evidence.
 * No alternative is installed in StyleSequencer or BassMidiPlayer.
 */
class StyleMixFidelityS7BackendContractTest {
    private data class Instance(val id:String,val source:Int,val generation:Int=0,val admitted:Boolean=true)
    private fun fifo(live:List<Instance>)=live.first().id
    private fun lifo(live:List<Instance>)=live.last().id
    private enum class Contract { FIFO, LIFO, COALESCED_COUNT, RETRIGGER }
    // Executable test-only model, not a production ownership implementation.
    private fun model(contract:Contract):List<String> {
        val live=mutableListOf<String>();val wire=mutableListOf<String>()
        val events=listOf(0 to "A",1 to "B",2 to null,3 to null)
        for((tick,on) in events) {
            if(on!=null) {
                if(contract==Contract.RETRIGGER && live.isNotEmpty()) {
                    wire.add("OFF_${live.removeAt(0)}@$tick")
                }
                if(contract!=Contract.COALESCED_COUNT || live.isEmpty())wire.add("ON_$on@$tick")
                live.add(on)
            } else if(live.isNotEmpty()) {
                val released=live.removeAt(if(contract==Contract.LIFO)live.lastIndex else 0)
                if(contract==Contract.COALESCED_COUNT) {
                    if(live.isEmpty())wire.add("OFF_SHARED@$tick")
                } else wire.add("OFF_$released@$tick")
            }
        }
        return wire
    }

    @Test fun fifoAndLifoRequireDifferentExplicitAuthoredOffContracts() {
        val live=listOf(Instance("A",4),Instance("B",4))
        assertEquals("A",fifo(live));assertEquals("B",lifo(live))
        // Same MIDI source/key and same NOTE_OFF bytes cannot select between
        // these two explicit fixture contracts. Neither is a Yamaha oracle.
        assertNotEquals(fifo(live),lifo(live))
        assertEquals(listOf("ON_A@0","ON_B@1","OFF_A@2","OFF_B@3"),model(Contract.FIFO))
        assertEquals(listOf("ON_A@0","ON_B@1","OFF_B@2","OFF_A@3"),model(Contract.LIFO))
        println("S7_MODEL SAME_SOURCE A@0 B@1 OFF@2 FIFO=A LIFO=B authoredPairing=UNKNOWN")
    }

    @Test fun perSourceFifoDoesNotImplyGlobalDestinationFifo() {
        val live=listOf(Instance("A",4),Instance("B",5))
        val intended=live.first { it.source==5 }.id
        val hypotheticalGlobalFifo=fifo(live)
        assertEquals("B",intended);assertEquals("A",hypotheticalGlobalFifo)
        assertNotEquals(intended,hypotheticalGlobalFifo)
        println("S7_MODEL SOURCE5_OFF intended=B hypotheticalDestinationFIFO=A backendBehaviorMustBeMeasured=true")
    }

    @Test fun coalescedReferenceCountingAndRetriggerCannotPreserveBothIntervals() {
        // Explicit FIFO fixture: A=[0,2), B=[1,3), differing velocities.
        val instancePreserving=model(Contract.FIFO)
        val coalescedReferenceCount=model(Contract.COALESCED_COUNT)
        val retrigger=model(Contract.RETRIGGER)
        assertEquals(listOf("ON_A@0","OFF_SHARED@3"),coalescedReferenceCount)
        assertEquals(listOf("ON_A@0","OFF_A@1","ON_B@1","OFF_B@2"),retrigger)
        assertEquals(2,instancePreserving.count { it.startsWith("ON") })
        assertEquals(1,coalescedReferenceCount.count { it.startsWith("ON") })
        assertFalse(coalescedReferenceCount.contains("OFF_A@2"))
        assertTrue(retrigger.contains("OFF_A@1"));assertFalse(retrigger.contains("OFF_B@3"))
        println("S7_MODEL COALESCED losesSecondAttack=true extendsFirst=true RETRIGGER destroysOverlap=true soundEquivalence=NOT_CLAIMED")
    }

    @Test fun rejectedOnMustNotConsumeAnAdmittedInstanceThroughItsOff() {
        val submitted=listOf(Instance("REJECTED_A",4,admitted=false),Instance("ADMITTED_B",4))
        val naiveAdmittedOnly=submitted.filter { it.admitted }
        assertEquals("ADMITTED_B",fifo(naiveAdmittedOnly))
        val explicitlyAttributedOff=submitted.first()
        assertFalse(explicitlyAttributedOff.admitted)
        assertNotEquals(explicitlyAttributedOff.id,fifo(naiveAdmittedOnly))
        println("S7_MODEL REJECTED_A_OFF explicitContract=NO_WIRE_OFF naiveAdmittedFIFO=OFF_B nativeAdmissionAcknowledgmentRequired=true")
    }

    @Test fun staleTaskGenerationAndIntentionalCarryAreDifferentContracts() {
        val carried=Instance("CARRY_A",4,0);val new=Instance("NEW_B",4,1)
        val live=listOf(carried,new)
        val explicitCarryRelease=live.first { it.id=="CARRY_A" }
        assertEquals(carried,explicitCarryRelease)
        val staleTaskGeneration=0;val activeSessionGeneration=1
        assertNotEquals(staleTaskGeneration,activeSessionGeneration)
        assertEquals(2,live.size)
        println("S7_MODEL staleSessionTask=REJECT_BEFORE_DISPATCH intentionalCarry=EXPLICIT_INSTANCE_RELEASE blanketCrossSectionOffDrop=UNSAFE")
    }

    @Test fun actualMidiPacketsEraseSourceIdentityAndOffAttribution() {
        val midi=MidiInputManager(mock(Context::class.java));val port=mock(MidiInputPort::class.java)
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible=true }.set(midi,port)
        midi.midiOutEnabled=true
        val packets=mutableListOf<List<Int>>()
        doAnswer {
            val bytes=it.arguments[0] as ByteArray;val offset=it.arguments[1] as Int;val count=it.arguments[2] as Int
            packets.add(bytes.slice(offset until offset+count).map { x->x.toInt() and 255 });null
        }.`when`(port).send(any(ByteArray::class.java) ?: byteArrayOf(),anyInt(),anyInt())
        // Source4 A, source5 B labels are observer-only; actual API has no source.
        midi.sendNoteOn(11,60,96);midi.sendNoteOn(11,60,32)
        midi.sendNoteOff(11,60);midi.sendNoteOff(11,60)
        assertEquals(listOf(listOf(0x9b,60,96),listOf(0x9b,60,32),listOf(0x8b,60,0),listOf(0x8b,60,0)),packets)
        assertEquals(packets[2],packets[3])
        println("S7_EXTERNAL actualSerializer=true packets=$packets sourceOffAttribution=ERASED PSRE343Behavior=UNKNOWN")
    }

    @Test fun nativeProductionApiRecorderAndAvailableRealSdkProbe() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it,"tools/test_s7_backend_native.py").isFile }
        val output=File(root,"build/s7-native").apply { mkdirs() }
        val log=File(output,"process.log")
        val process=ProcessBuilder("python3",File(root,"tools/test_s7_backend_native.py").path,"--output",output.path)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val finished=process.waitFor(180,TimeUnit.SECONDS)
        if(!finished)process.destroyForcibly()
        assertTrue("native diagnostic timeout",finished)
        val text=log.readText();println(text)
        assertEquals(text,0,process.exitValue())
        val proof=File(output,"proof.json").readText()
        println("S7_NATIVE_PROOF $proof")
        assertTrue(proof.contains("\"api_recorder\": \"PASS\""))
        assertFalse(proof.contains("\"FAIL\""))
        // Real mode BLOCKED is reported separately, never counted as PCM PASS.
        assertTrue(proof.contains("\"real_sdk\": \"PASS\"") || proof.contains("\"real_sdk\": \"BLOCKED\""))
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.audio.ProductionDrumOwners
import com.yourapp.audio.ProductionDrumPlan
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Real scheduler + production admission seam. Native lane semantics are tested
 * independently in production_drum_adapter_test against the actual C++ player. */
class ProductionDrumSequencerTest {
    private class Fixture(val enabled:Boolean=true) {
        val audio=mock(AudioEngineManager::class.java)
        val midi=mock(MidiInputManager::class.java)
        val sequencer=StyleSequencer(audio,midi,CoroutineScope(SupervisorJob()))
        val owners=ProductionDrumOwners(64)
        var sequence=0L
        val on=mutableListOf<Pair<Long,List<Any?>>>()
        val off=mutableListOf<Long>()
        var onObserved:((Long)->Unit)?=null
        var boundaryObserved:(()->Unit)?=null
        init {
            val entries=(8..9).flatMap {ch->listOf(31,32).map {key->ProductionDrumPlan.address(ch,ch,127*128,73,key,110)}}.sorted()
            val table=ProductionDrumPlan.Table(entries.toLongArray(),IntArray(entries.size){1})
            doReturn(ProductionDrumPlan(listOf("Intro","Main","Fill","Ending").associateWith {table},4,4,"fixture")).`when`(audio).productionDrumPlan
            doAnswer {call->
                val a=call.arguments;val slot=owners.reserve(a[1] as Int,a[5] as Int,a[9] as Int)
                val table=a[0] as? ProductionDrumPlan.Table
                val admit=enabled && a[8]==false && a[5]==a[6] &&
                    (table?.route(a[1] as Int,a[2] as Int,a[3] as Int,a[4] as Int,a[5] as Int,a[7] as Int)?:0)>0
                val token=if(admit)++sequence else 0
                owners.commit(slot,token);if(admit) {on+=token to a.toList();onObserved?.invoke(token)};admit
            }.`when`(audio).tryProductionDrumOn(any(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyBoolean(),anyInt())
            doAnswer {call->
                val token=owners.pop(call.getArgument(0),call.getArgument(1),call.getArgument(2))
                if(token>0)off+=token;token>0
            }.`when`(audio).endProductionDrumNote(anyInt(),anyInt(),anyInt())
            doAnswer {owners.clear {off+=it};boundaryObserved?.invoke();null}.`when`(audio).endProductionDrumSection()
        }
        fun section(name:String,events:List<StyleNoteEvent>,channel:Int=9):StyleSectionModel {
            val policy=CasmPolicyModel(channel,channel,"DrumKit",0,0,0,0,127,0,127,0,false)
            return StyleSectionModel(name,100,listOf(StylePartModel("Rhythm",events,casm=policy,program=73,bankMsb=127)))
        }
        fun play(section:StyleSectionModel,loops:Int=1) {
            val done=CountDownLatch(1)
            sequencer.playSeamless(section,1920,loopLimit=loops,onComplete={done.countDown()})
            assertTrue("scheduler completes",done.await(5,TimeUnit.SECONDS))
        }
    }
    private fun on(t:Int,key:Int=31,v:Int=110,ch:Int=9)=StyleNoteEvent(t,true,key,v,ch)
    private fun off(t:Int,key:Int=31,ch:Int=9)=StyleNoteEvent(t,false,key,0,ch)
    @Test fun realSchedulerCapturesRepeatedSimultaneousNotesAndRoutesEachOffToItsOwner() {
        val f=Fixture();f.play(f.section("Main",listOf(on(0),on(1),on(1,32),off(2),off(3,32),off(4))))
        assertEquals(listOf(1L,3L,2L),f.off);assertEquals(0,f.owners.size)
        assertEquals(3,f.on.size)
        verify(f.audio,never()).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
        verify(f.audio,never()).noteOffStyleChannel(anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyLong(),anyInt())
    }
    @Test fun legacyMarkerBeforeMappedRepeatedNoteCannotReleaseTheMappedOwner() {
        val f=Fixture();f.play(f.section("Fill",listOf(on(0,v=69),on(1),off(2),off(3))))
        assertEquals(listOf(1L),f.off);assertEquals(1,f.on.size)
        verify(f.audio,times(1)).noteOnStyleChannel(anyInt(),eq(31),eq(69/127f),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
    }
    @Test fun rawIdentityAndVelocityAreDeliveredAfterActualSchedulerRoutingForBothRhythms() {
        for(ch in 8..9) {
            val f=Fixture();f.play(f.section("Main",listOf(on(0,ch=ch),off(1,ch=ch)),ch))
            val a=f.on.single().second
            assertEquals(ch,a[1]);assertEquals(ch,a[2]);assertEquals(127*128,a[3]);assertEquals(73,a[4]);assertEquals(31,a[5]);assertEquals(31,a[6]);assertEquals(110,a[7])
            assertEquals(false,a[8])
        }
    }
    @Test fun transposeOrExplicitProgramOverrideMakesActualRoutingIneligible() {
        for(override in listOf(StyleChannelOverride(transpose=2),StyleChannelOverride(program=24),StyleChannelOverride(bank=128))) {
            val f=Fixture();f.sequencer.setChannelOverride(9,override)
            f.play(f.section("Main",listOf(on(0),off(1))))
            assertTrue(f.on.isEmpty())
            verify(f.audio,times(1)).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
        }
    }
    @Test fun introMainFillMainEndingAndRapidSectionsFlushCapturedNotesWithoutGlobalNoteOff() {
        val f=Fixture()
        for(section in listOf("Intro","Main","Fill","Main","Fill","Main","Ending")) {
            f.play(f.section(section,listOf(on(0)))) // missing source OFF: boundary must release only captured note
            assertEquals(0,f.owners.size)
        }
        assertEquals((1L..7L).toList(),f.off)
        verify(f.audio,never()).allNotesOff()
    }
    @Test fun actualSeamlessMainFillMainAndRapidQueueReplacementKeepCapturedOwners() {
        val f=Fixture();val started=CountDownLatch(1);val finished=CountDownLatch(1)
        f.onObserved={if(it==1L)started.countDown()}
        f.boundaryObserved={if(f.off.size>=3)finished.countDown()}
        val main=f.section("Main",listOf(on(0),off(1800))).copy(lengthTicks=2000)
        f.sequencer.playSeamless(main,1920)
        assertTrue(started.await(5,TimeUnit.SECONDS))
        Thread.sleep(2) // queue at a positive master tick on the real scheduler
        val transition=listOf(f.section("Fill",listOf(on(0))) to 1,f.section("Main",listOf(on(0))) to 1)
        f.sequencer.queueSeamlessTransition(transition,1920,4,4)
        f.sequencer.queueSeamlessTransition(transition,1920,4,4) // replace a still-pending rapid request
        assertTrue(finished.await(5,TimeUnit.SECONDS))
        assertEquals(listOf(1L,2L,3L),f.off);assertEquals(0,f.owners.size)
        verify(f.audio,never()).allNotesOff()
    }
    @Test fun repeatedLoopBoundariesReleaseOnlyCapturedExperimentalNotes() {
        val f=Fixture();f.play(f.section("Main",listOf(on(0),on(1))),loops=3)
        assertEquals((1L..6L).toList(),f.off);assertEquals(0,f.owners.size)
        verify(f.audio,never()).allNotesOff()
    }
    @Test fun flagOffUsesLegacyNotePathAndLeavesExternalMidiBehaviorUnchanged() {
        val f=Fixture(false);f.play(f.section("Main",listOf(on(0),off(1))))
        assertTrue(f.on.isEmpty());assertTrue(f.off.isEmpty())
        verify(f.audio,times(1)).noteOnStyleChannel(eq(9),eq(31),eq(110/127f),eq(9),eq(31),eq(127*128),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
        verify(f.midi,times(1)).sendNoteOn(9,31,110)
        verify(f.midi,never()).sendNoteOff(anyInt(),anyInt()) // preserves legacy one-shot drum MIDI behavior
    }
}

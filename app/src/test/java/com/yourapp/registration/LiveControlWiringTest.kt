package com.yourapp.yamahaarranger.registration
import com.yourapp.yamahaarranger.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class LiveControlWiringTest {
    suspend fun until(check:()->Boolean)=withTimeout(3000){while(!check())delay(1)}
    @Test fun installingLibraryAndChangingBankSendNoMidiOrSf2()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try{val slot=r.target();r.reset();r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",listOf(slot)+List(7){null}),RegistrationBank("b","B",List(8){null})))
            r.vm.onLiveBankChange(1);assertEquals("b",r.vm.liveRegistration.value.bank!!.id);assertTrue(r.bytes.isEmpty());verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
            assertFalse(r.vm.onLiveRegRecall(0));assertFalse(r.vm.onLiveRegRecall(8));assertFalse(r.vm.onLiveRegRecall(-1));assertNull(r.vm.liveRegistration.value.selectedSlot)
        }finally{r.close()}
    }
    @Test fun U2_liveRecallUsesCoordinatorAndReportsOutputOffWithoutQueue()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try{val slot=r.target();r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",listOf(slot)+List(7){null})));r.midi.midiOutEnabled=false;r.reset()
            assertTrue(r.vm.onLiveRegRecall(0));until{!r.vm.liveRegistration.value.recalling};val s=r.vm.liveRegistration.value
            assertEquals(OutputMode.EXTERNAL,s.selectedMode);assertTrue(s.message.contains("SKIPPED_MIDI_OUT_OFF"));assertTrue(r.bytes.isEmpty());r.midi.midiOutEnabled=true;assertFalse(r.bytes.any{it[0] and 240==192})
        }finally{r.close()}
    }
    @Test fun rapidLive123LatestStatusAndPresetAgree()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try{val slot=r.target();val slots=(1..3).map{i->slot.copy(name="REG$i",parts=slot.parts.mapIndexed{ch,p->if(ch==0)p.copy(external=ExternalVoice(0,112,i,"E$i"))else p})}+List(5){null}
            r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",slots)));r.reset();(0..2).forEach{assertTrue(r.vm.onLiveRegRecall(it))};until{!r.vm.liveRegistration.value.recalling}
            assertEquals(2,r.vm.liveRegistration.value.selectedSlot);assertEquals(listOf(0xc0,3),r.bytes.last{it[0] and 240==192});verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        }finally{r.close()}
    }
    @Test fun liveMissingSf2ReportsFailureAndPreservesCurrentVoice()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try{val slot=r.target(OutputMode.INTERNAL).let{s->s.copy(parts=s.parts.mapIndexed{i,p->if(i==0)p.copy(sf2=p.sf2!!.copy(program=127))else p})}
            r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",listOf(slot)+List(7){null})));r.reset();r.vm.onLiveRegRecall(0);until{!r.vm.liveRegistration.value.recalling}
            assertTrue(r.vm.liveRegistration.value.missingVoice);assertTrue(r.vm.liveRegistration.value.message.contains("SF2_MISSING"));assertEquals(0,r.vm._rightVoices.value[0].program);verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        }finally{r.close()}
    }
    @Test fun liveFillUsesExplicitLastMainEvenBeforeStateCollectorRuns()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try {
            r.reset();r.vm.onLiveMainSelected("C");r.vm.onLiveFill()
            assertEquals("Fill C",r.vm.observedSectionLabel)
            assertTrue(r.bytes.isEmpty());verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        } finally { r.close() }
    }
    @Test fun bothNativeFailureReportsLayerAndStillAppliesExplicitExternal()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try {
            `when`(r.bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenReturn(false)
            val slot=r.target(OutputMode.BOTH);r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",listOf(slot)+List(7){null})));r.reset()
            r.vm.onLiveRegRecall(0);until{!r.vm.liveRegistration.value.recalling}
            assertTrue(r.vm.liveRegistration.value.message.contains("SF2 RIGHT1 FAILED"));assertTrue(r.vm.liveRegistration.value.warning)
            assertEquals(listOf(listOf(0xb0,0,0),listOf(0xb0,32,112),listOf(0xc0,5)),r.bytes.filter{it[0] and 240==192 || it.size==3 && it[0] and 240==176 && it[1] in listOf(0,32)})
            assertEquals(0,r.vm._rightVoices.value[0].program)
        } finally { r.close() }
    }
    @Test fun oldRecallCompletionCannotOverwriteNewLiveSelection()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();try{val slot=r.target();r.vm.installLiveRegistrationBanks(listOf(RegistrationBank("a","A",listOf(slot,slot.copy(name="Next"))+List(6){null})))
            r.vm.onLiveRegRecall(0);r.vm.onLiveRegRecall(1);until{!r.vm.liveRegistration.value.recalling};assertEquals(1,r.vm.liveRegistration.value.selectedSlot);assertEquals("COMPLETED",r.vm.liveRegistration.value.message)
        }finally{r.close()}
    }
}

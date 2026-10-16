package com.yourapp.yamahaarranger.registration

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import com.yourapp.midi.MidiMixerOutput
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.StyleSequencer
import com.yourapp.yamahaarranger.style.*
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.ui.RecallViewModelHarness
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RecallAdapterTest {
    class Rig {
        val bytes=Collections.synchronizedList(mutableListOf<List<Int>>())
        val midi=MidiInputManager(mock(Context::class.java))
        val bridge=mock(NativeAudioBridge::class.java)
        val engine:AudioEngineManager
        val brain:ArrangerBrain
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val vm:RecallViewModelHarness
        val preset=AudioEngineManager.SfPreset("MELODY",65535,24,"Strings",1,"a.sf2")
        val router:MidiMixerOutput
        init {
            val port=object:MidiInputPort(){override fun send(data:ByteArray,offset:Int,count:Int){bytes+=data.slice(offset until offset+count).map{it.toInt() and 255}}}
            MidiInputManager::class.java.getDeclaredField("inputPort").apply{isAccessible=true}.set(midi,port)
            router=MidiInputManager::class.java.getDeclaredField("mixerOutput").apply{isAccessible=true}.get(midi) as MidiMixerOutput
            router.setConnected(true);midi.midiOutEnabled=true
            `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
            `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
            `when`(bridge.nativeSetKeyboardReleaseTime(anyInt())).thenReturn(true)
            `when`(bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenReturn(true)
            `when`(bridge.nativeGetSoundFontPresets()).thenReturn("MELODY\u001f1\u001fa.sf2\u001f65535\u001f24\u001fStrings\u001e")
            engine=AudioEngineManager(bridge,object:SampleProvider{override fun sampleForNote(midiNote:Int):NoteSample?=null})
            engine.loadSoundFontPair("m","d");engine.onMixerControllers=midi::setMixerControllers
            brain=ArrangerBrain(engine,ChordDetector(),midi);vm=RecallViewModelHarness(engine,brain,midi,scope)
        }
        suspend fun target(mode:OutputMode=OutputMode.EXTERNAL,external:ExternalVoice?=ExternalVoice(0,112,5,"E343")):RegistrationSlot {
            val snap=vm.testSnapshot()
            return RegistrationSlot("REG",mode,snap.parts.mapIndexed{i,p->if(i==0)p.copy(sf2=Sf2VoiceRef("a.sf2",65535,24,"Strings"),external=external)else p},snap.release,null,null,0)
        }
        fun reset(){bytes.clear();clearInvocations(bridge)}
        fun close(){vm.registrationCoordinator.cancel();scope.cancel()}
    }
    fun isBank(e:List<Int>)=e[0] and 240==192 || (e.size==3 && e[0] and 240==176 && e[1] in listOf(0,32))
    suspend fun until(check:()->Boolean)=withTimeout(2000){while(!check())delay(1)}
    @Test fun M1_connectReconnectEnableNeverAutomaticallySendsVoice()=runBlocking<Unit> {
        val r=Rig();try{r.vm.selectExternalVoice(KeyboardPart.RIGHT1,ExternalVoice(0,112,5,"E343"));r.reset()
            r.router.setConnected(false);r.router.setConnected(true);r.midi.midiOutEnabled=false;r.midi.midiOutEnabled=true
            assertFalse(r.bytes.any(::isBank));verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        }finally{r.close()}
    }
    @Test fun M2_internalNeverSendsSf2BankProgramToE343()=runBlocking<Unit> {
        val r=Rig();try{r.reset();r.vm.registrationCoordinator.recall("I",r.target(OutputMode.INTERNAL)).await();assertFalse(r.bytes.any(::isBank));verify(r.bridge,times(1)).nativeSetKeyboardPreset(0,1L,65535,24)}finally{r.close()}
    }
    @Test fun M3_externalExactBankPcThenCc11AndNoSf2EvenWithStyleNotes()=runBlocking<Unit> {
        val r=Rig();try{r.midi.setMixerStyleChannels(setOf(9));r.midi.sendNoteOn(9,36,100);r.engine.setChannelExpression(0,93);r.reset()
            val start=System.nanoTime();val report=r.vm.registrationCoordinator.recall("E",r.target()).await();val elapsed=System.nanoTime()-start
            assertEquals(RecallCompletion.COMPLETED,report.completion)
            assertEquals(listOf(listOf(0xb0,0,0),listOf(0xb0,32,112),listOf(0xc0,5)),r.bytes.take(3));assertTrue(r.bytes.drop(3).contains(listOf(0xb0,11,93)))
            assertTrue(r.bytes.all{it[0] and 15==0});verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt());verify(r.bridge,never()).nativeGetSoundFontPresets()
            r.midi.sendNoteOff(9,36);assertEquals(listOf(0x89,36,0),r.bytes.last())
            println("REG_RECORDER EXTERNAL bytes=${r.bytes.take(4)} hostElapsedNs=$elapsed deviceLatency=UNVERIFIED sf2Calls=0")
        }finally{r.close()}
    }
    @Test fun externalRecallWhileRealStyleSequencerIsPlayingKeepsItsNotesAndClock()=runBlocking<Unit> {
        val r=Rig();val seq=StyleSequencer(r.engine,r.midi,r.scope);val completed=CompletableDeferred<Unit>()
        // Event handshake, not a timing delay: the scheduled style OFF cannot
        // finish before recall, even if the observer resumes after its deadline.
        val recallApplied=CountDownLatch(1)
        doAnswer {
            assertTrue("external recall must complete before scheduled style OFF",recallApplied.await(3,TimeUnit.SECONDS))
            null
        }.`when`(r.bridge).nativeSfNoteOffChannel(10,36)
        try {
            val policy=CasmPolicyModel(10,10,"Bass",0,0,0,0,127,0,127,0,false)
            val section=StyleSectionModel("Main A",480,listOf(StylePartModel("Bass",listOf(
                StyleNoteEvent(0,true,36,100,10,0x9a),StyleNoteEvent(120,false,36,0,10,0x8a)),casm=policy,program=0)))
            r.midi.setMixerStyleChannels(setOf(10));seq.play(section,480,loopLimit=1,onComplete={completed.complete(Unit)})
            until { synchronized(r.bytes) { r.bytes.any{it[0]==0x9a} } }
            assertFalse("style must still be playing during recall",completed.isCompleted)
            r.reset();val report=r.vm.registrationCoordinator.recall("STYLE-EXT",r.target()).await()
            recallApplied.countDown()
            assertEquals(RecallCompletion.COMPLETED,report.completion);withTimeout(3000){completed.await()}
            val bytes=synchronized(r.bytes){r.bytes.toList()}
            println("REG_STYLE_CAPTURE bytes=$bytes")
            assertTrue(bytes.any{it==listOf(0x8a,36,0)})
            assertEquals(listOf(listOf(0xb0,0,0),listOf(0xb0,32,112),listOf(0xc0,5)),bytes.filter(::isBank))
            verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
            verify(r.bridge,never()).nativeGetSoundFontPresets()
            println("REG_STYLE_RECORDER actualSequencer=true naturalCompletion=true externalRecall=true SF2=0 deviceTiming=UNVERIFIED")
        } finally { recallApplied.countDown();seq.stop();r.close() }
    }
    @Test fun M4_bothUsesDistinctSf2AndExplicitExternalIdentities()=runBlocking<Unit> {
        val r=Rig();try{r.reset();r.vm.registrationCoordinator.recall("B",r.target(OutputMode.BOTH)).await();verify(r.bridge).nativeSetKeyboardPreset(0,1L,65535,24);assertEquals(listOf(listOf(0xb0,0,0),listOf(0xb0,32,112),listOf(0xc0,5)),r.bytes.filter(::isBank))}finally{r.close()}
    }
    @Test fun M5_heldKeyRecallExternalThenKeyUpPreservesOwnership()=runBlocking<Unit> {
        val r=Rig();try{r.brain.onKeyboardNoteOn(72,1f);r.reset();r.vm.registrationCoordinator.recall("E",r.target()).await();r.brain.onKeyboardNoteOff(72);assertTrue(r.bytes.contains(listOf(0x80,72,0)));verify(r.bridge).nativeSfNoteOffChannel(0,72);verify(r.bridge,never()).nativeAllNotesOff()}finally{r.close()}
    }
    @Test fun M6_recallRight2OffStopsOnlyItsOwnedNotes()=runBlocking<Unit> {
        val r=Rig();try{r.vm.toggleRightVoice(1);r.brain.onKeyboardNoteOn(72,1f);val s=r.target(external=null);r.reset()
            r.vm.registrationCoordinator.recall("OFF",s.copy(parts=s.parts.mapIndexed{i,p->if(i==1)p.copy(enabled=false)else p})).await()
            assertEquals(listOf(listOf(0x81,72,0),listOf(0xb1,120,0)),r.bytes.toList());r.brain.onKeyboardNoteOff(72);assertTrue(r.bytes.contains(listOf(0x80,72,0)));verify(r.bridge,never()).nativeAllNotesOff()
        }finally{r.close()}
    }
    @Test fun M7_transposeRecallKeyUpStillUsesOriginalPitch()=runBlocking<Unit> {
        val r=Rig();try{r.brain.onKeyboardNoteOn(72,1f);r.reset();r.vm.registrationCoordinator.recall("TR",r.target(external=null).copy(transpose=5),FreezeFlags(transpose=false)).await();r.brain.onKeyboardNoteOff(72);assertEquals(listOf(0x80,72,0),r.bytes.last())}finally{r.close()}
    }
    @Test fun M8_releaseEffectiveChangeOnlyOnceAndOffPreservesKnob()=runBlocking<Unit> {
        val r=Rig();try{r.reset();val s=r.target(external=null).copy(release=ReleaseSetup(127,false));r.vm.registrationCoordinator.recall("OFF",s).await();delay(30);assertTrue(r.bytes.isEmpty());assertEquals(127,r.vm._releaseControl.value.knobValue)
            r.vm.registrationCoordinator.recall("ON",s.copy(release=ReleaseSetup(127,true))).await();until{r.bytes.size==3};assertEquals((0..2).map{listOf(0xb0 or it,72,127)},r.bytes.toList());r.reset()
            r.vm.registrationCoordinator.recall("SAME",s.copy(release=ReleaseSetup(127,true))).await();delay(30);assertTrue(r.bytes.isEmpty())
        }finally{r.close()}
    }
    @Test fun M9_missingPresetAndMidiOffPreserveVoicesWithoutFutureQueue()=runBlocking<Unit> {
        val r=Rig();try{r.midi.midiOutEnabled=false;r.reset();val s=r.target(OutputMode.BOTH).copy(parts=r.target().parts.mapIndexed{i,p->if(i==0)p.copy(sf2=p.sf2!!.copy(program=26))else p})
            assertEquals(RecallCompletion.PARTIAL,r.vm.registrationCoordinator.recall("MISSING",s).await().completion);assertTrue(r.bytes.isEmpty());verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt());r.midi.midiOutEnabled=true;assertFalse(r.bytes.any(::isBank))
        }finally{r.close()}
    }
    @Test fun M10_rapid123ExternalCompletesAndFinalPcIsLastRequest()=runBlocking<Unit> {
        val r=Rig();try{val s=r.target();r.reset();val requests=(1..10).map{i->r.vm.registrationCoordinator.recall("REG$i",s.copy(parts=s.parts.mapIndexed{ch,p->if(ch==0)p.copy(external=ExternalVoice(0,112,i,"PC$i"))else p}))};withTimeout(3000){requests.forEach{it.await()}}
            assertEquals(listOf(0xc0,10),r.bytes.last{it[0] and 240==192});assertTrue(r.bytes.filter(::isBank).size<=30);assertTrue(r.bytes.all{it[0] and 15 in 0..3});verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        }finally{r.close()}
    }
    @Test fun X1_supersededAsyncVoiceAlwaysCompletesAndNeverMirrors()=runBlocking<Unit> {
        val r=Rig();val started=CountDownLatch(1);val finish=CountDownLatch(1);try{
            `when`(r.bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenAnswer{started.countDown();finish.await(2,TimeUnit.SECONDS);true}
            var current=true;val a=async(Dispatchers.Default){r.vm.applyKeyboardVoice(KeyboardPart.RIGHT1,r.preset,isCurrent={current})}
            assertTrue(started.await(2,TimeUnit.SECONDS));current=false;finish.countDown();assertEquals(VoiceResult.SUPERSEDED,withTimeout(2000){a.await()});r.vm.awaitRegistrationVoicesIdle();assertFalse(r.bytes.any(::isBank))
            assertEquals(24,r.vm._rightVoices.value[0].program)
        }finally{finish.countDown();r.close()}
    }
    @Test fun X5_timeoutReportsButLateNativeMustDrainBeforeNextRecall()=runBlocking<Unit> {
        val r=Rig();val started=CountDownLatch(1);val finish=CountDownLatch(1);try{
            `when`(r.bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenAnswer{started.countDown();finish.await(3,TimeUnit.SECONDS);true}
            val a=async(Dispatchers.Default){r.vm.applyKeyboardVoice(KeyboardPart.RIGHT1,r.preset,timeoutMs=1500)}
            assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(VoiceResult.TIMEOUT,withTimeout(2500){a.await()})
            val s=r.target(external=null);val next=r.vm.registrationCoordinator.recall("next",s);delay(30);assertFalse(next.isCompleted);assertTrue(r.vm.registrationCoordinator.busy.value)
            finish.countDown();withTimeout(2000){next.await()};assertFalse(r.bytes.any(::isBank));verify(r.bridge,times(1)).nativeSetKeyboardPreset(0,1L,65535,24)
        }finally{finish.countDown();r.close()}
    }
    @Test fun X2_realAdapterLateNativeSettlesBeforeRecallBackToOriginal()=runBlocking<Unit> {
        val r=Rig();val started=CountDownLatch(1);val finish=CountDownLatch(1)
        try {
            val v0=Sf2VoiceRef("a.sf2",65535,0,"Piano")
            `when`(r.bridge.nativeGetSoundFontPresets()).thenReturn("MELODY\u001f1\u001fa.sf2\u001f65535\u001f24\u001fStrings\u001eMELODY\u001f1\u001fa.sf2\u001f65535\u001f0\u001fPiano\u001e")
            r.vm._rightVoices.value=r.vm._rightVoices.value.mapIndexed{i,p->if(i==0)p.copy(program=0,bank=65535,name="Piano",soundFontId=1,soundFontName="a.sf2")else p}
            val original=r.vm.testSnapshot()
            `when`(r.bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenAnswer { call ->
                if(call.getArgument<Int>(3)==24) { started.countDown();finish.await(2,TimeUnit.SECONDS) };true
            }
            val a=r.vm.registrationCoordinator.recall("A",r.target(OutputMode.INTERNAL))
            assertTrue(started.await(2,TimeUnit.SECONDS))
            val b=r.vm.registrationCoordinator.recall("B",r.target(OutputMode.INTERNAL).copy(parts=original.parts))
            finish.countDown();assertEquals(RecallCompletion.SUPERSEDED,a.await().completion);assertEquals(RecallCompletion.COMPLETED,b.await().completion)
            assertEquals(v0,r.vm.testSnapshot().parts[0].sf2)
            val order=inOrder(r.bridge);order.verify(r.bridge).nativeSetKeyboardPreset(0,1L,65535,24);order.verify(r.bridge).nativeSetKeyboardPreset(0,1L,65535,0)
            assertFalse(r.bytes.any(::isBank))
        } finally { finish.countDown();r.close() }
    }
    @Test fun X4_manualRequestWinsOverDelayedRecallPublication()=runBlocking<Unit> {
        val r=Rig();val started=CountDownLatch(1);val finish=CountDownLatch(1);try{
            `when`(r.bridge.nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())).thenAnswer{started.countDown();finish.await(2,TimeUnit.SECONDS);true}
            val a=async(Dispatchers.Default){r.vm.applyKeyboardVoice(KeyboardPart.RIGHT1,r.preset)}
            assertTrue(started.await(2,TimeUnit.SECONDS));r.vm.voiceRequestIds.incrementAndGet(0);r.vm._rightVoices.value=r.vm._rightVoices.value.mapIndexed{i,p->if(i==0)p.copy(program=50,name="Manual")else p};finish.countDown()
            assertEquals(VoiceResult.SUPERSEDED,a.await());assertEquals(50,r.vm._rightVoices.value[0].program)
        }finally{finish.countDown();r.close()}
    }
    @Test fun R11_adapterReadsActualBrainLeftStateNotUiSlot()=runBlocking<Unit> {val r=Rig();try{r.brain.setLeftVoiceEnabled(false);assertTrue(r.vm._rightVoices.value[3].enabled);assertFalse(r.vm.testSnapshot().parts[3].enabled)}finally{r.close()}}
    @Test fun explicitMirrorRequestIsRejectedAndFrozenRecallSendsNothing()=runBlocking<Unit> {val r=Rig();try{r.reset();assertEquals(VoiceResult.FAILED,r.vm.applyKeyboardVoice(KeyboardPart.RIGHT1,r.preset,mirrorToMidiOut=true));r.vm.registrationCoordinator.recall("FREEZE",r.target(OutputMode.BOTH),FreezeFlags(true,true,true,true,true,true)).await();assertTrue(r.bytes.isEmpty());verify(r.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())}finally{r.close()}}
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production controller method and real section activation. */
class StyleMixFidelityRegressionTest {
    private class Fixture : AutoCloseable {
        val audio=mock(AudioEngineManager::class.java)
        val scope=CoroutineScope(SupervisorJob())
        val seq=StyleSequencer(audio,mock(MidiInputManager::class.java),scope)
        fun cc(ch:Int,cc:Int,value:Int) {
            val method=StyleSequencer::class.java.getDeclaredMethod("applyStyleController",Int::class.javaPrimitiveType,StyleNoteEvent::class.java)
            method.isAccessible=true;method.invoke(seq,ch,StyleNoteEvent(1,false,cc,value,ch,0xB0 or ch))
        }
        fun calls(name:String)=mockingDetails(audio).invocations.filter { it.method.name==name }.map { it.arguments.toList() }
        fun mix()=calls("setChannelMixer").last()
        override fun close(){scope.cancel()}
    }
    @Test fun neutralTrimPreservesEverySourceValueAndIntentionalZero() {
        Fixture().use { f -> for(ch in 8..15) for(v in 0..127) {
            f.cc(ch,7,v);assertEquals(v,f.mix()[1]);f.cc(ch,11,v)
            assertEquals(v,f.calls("setChannelExpression").last()[1])
        } }
    }
    @Test fun volumeTrimDoesNotResetExpressionPanOrFxAndSurvivesStyleCCs() {
        Fixture().use { f ->
            f.cc(12,7,80);f.cc(12,11,72);f.cc(12,10,19);f.cc(12,91,26);f.cc(12,93,15)
            f.seq.setChannelVolume(12,64)
            assertEquals(listOf(12,40,19,72,26,15),f.mix())
            assertTrue(f.calls("setChannelVolume").isEmpty())
            f.cc(12,7,40);assertEquals(20,f.mix()[1]);f.cc(12,10,93);assertEquals(20,f.mix()[1]);assertEquals(72,f.mix()[3])
        }
    }
    @Test fun muteAndUnmuteRestoreLatestStyleEnvelopeInsteadOf127() {
        Fixture().use { f ->
            f.cc(13,7,62);f.cc(13,11,43);f.cc(13,10,27);f.seq.setChannelMute(13,true)
            assertEquals(listOf(13,0,27,43,40,0),f.mix())
            f.cc(13,7,48);f.cc(13,11,20);f.seq.setChannelMute(13,false)
            assertEquals(listOf(13,48,27,20,40,0),f.mix())
        }
    }
    @Test fun programOnlyOverrideCannotFreezeMixOrExpression() {
        Fixture().use { f ->
            f.seq.setChannelProgramOverride(12,24,1024);f.cc(12,7,54);f.cc(12,11,32);f.cc(12,10,18)
            assertEquals(listOf(12,54,18,32,40,0),f.mix())
        }
    }
    @Test fun expressionTrimPreservesSourceDynamicsAndZero() {
        Fixture().use { f ->
            f.cc(12,7,78);f.seq.setChannelMixer(12,127,64,64,40,0)
            for(v in listOf(0,16,64,126,127)) {f.cc(12,11,v);assertEquals((v*64+63)/127,f.calls("setChannelExpression").last()[1])}
            assertEquals(78,f.mix()[1])
        }
    }
    @Test fun styleBusFaderComposesWithEnvelopeAndDoesNotTouchKeyboardParts() {
        Fixture().use { f ->
            f.cc(8,11,62);f.cc(12,11,80);f.seq.setStyleBusTrim(64)
            assertEquals((8..15).toList(),f.calls("setChannelExpression").takeLast(8).map {it[0]})
            assertEquals(40,f.calls("setChannelExpression").first {it[0]==12 && it[1]==40}[1])
            f.cc(12,11,40);assertEquals(20,f.calls("setChannelExpression").last()[1])
            assertTrue(f.calls("setChannelExpression").all {it[0] as Int >= 8})
        }
    }
    @Test fun sectionSetupDoesNotRaiseStringsTo100OrReplaceSourceControllers() {
        Fixture().use { f ->
            val policy=CasmPolicyModel(13,13,"Strings1",0,0,0,0,11,0,127,1,false)
            val events=listOf(StyleNoteEvent(0,false,7,62,13,0xBD),StyleNoteEvent(0,false,11,36,13,0xBD),StyleNoteEvent(0,false,10,24,13,0xBD))
            val part=StylePartModel("Pad",events,policy,program=49,bankMsb=0,bankLsb=8)
            val done=CountDownLatch(1);f.seq.playSeamless(StyleSectionModel("MainA",2,listOf(part)),1_000_000_000,1){done.countDown()}
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(listOf(13,62,24,36,40,0),f.mix())
            assertEquals(listOf(13,49,8,"Strings1"),f.calls("setChannelProgram").single())
        }
    }
    @Test fun explicitPanOverrideDoesNotResetTrimOnLaterStylePan() {
        Fixture().use { f ->
            f.cc(12,7,90);f.cc(12,10,20);f.seq.setChannelMixer(12,64,30,127,40,0)
            f.cc(12,10,90);assertEquals(30,f.mix()[2]);assertEquals(45,f.mix()[1])
        }
    }
    @Test fun expressionOnlyPanelEditKeepsDynamicStylePanAndEffects() {
        Fixture().use { f ->
            f.cc(12,7,80);f.cc(12,11,72);f.cc(12,10,19);f.cc(12,91,26);f.cc(12,93,15)
            f.seq.setChannelMixer(12,127,null,64,null,null)
            assertEquals(listOf(12,80,19,36,26,15),f.mix())
            f.cc(12,10,93);f.cc(12,91,45);assertEquals(93,f.mix()[2]);assertEquals(45,f.mix()[4])
        }
    }

    @Test fun equalHeadersAcrossMainFillRestoreMixAfterAutomationChangedLiveState() {
        Fixture().use { f ->
            val policy=CasmPolicyModel(12,12,"A.Guitar",0,0,0,0,11,0,127,1,false)
            val setup=listOf(StyleNoteEvent(0,false,7,79,12,0xBC),StyleNoteEvent(0,false,11,110,12,0xBC))
            val section=StyleSectionModel("MainA",2,listOf(StylePartModel("Chord2",setup,policy,program=1,bankMsb=8,bankLsb=16)))
            fun activate(name:String) {val done=CountDownLatch(1);f.seq.playSeamless(section.copy(name=name),1_000_000_000,1){done.countDown()};assertTrue(done.await(10,TimeUnit.SECONDS))}
            activate("MainA");val count=f.calls("setChannelMixer").size
            f.cc(12,11,20);assertEquals(20,f.calls("setChannelExpression").last()[1])
            activate("FillAA");assertTrue(f.calls("setChannelMixer").size>count);assertEquals(110,f.mix()[3])
            f.cc(12,7,12);activate("MainB");assertEquals(79,f.mix()[1])
            assertEquals(1,f.calls("setChannelProgram").last()[1])
        }
    }

}

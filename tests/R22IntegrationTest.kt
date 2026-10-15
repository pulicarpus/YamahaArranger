package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.style.*
import com.yourapp.midi.MidiInputManager
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import org.mockito.Mockito.mockingDetails
import kotlinx.coroutines.*
import java.io.File

class R22IntegrationTest {
    private val pdta="338385762217a9ecd9ecb8707fa71202a115da4eaad81441c27d714acbf61283"
    private fun section():StyleSectionModel {
        val policy=CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false)
        val events=listOf(StyleNoteEvent(0,false,0,127,9,0xB9),StyleNoteEvent(0,false,32,0,9,0xB9),StyleNoteEvent(0,false,0,0,9,0xC9),
            StyleNoteEvent(12,false,8,0,9,0xC9),StyleNoteEvent(14,true,36,90,9),StyleNoteEvent(18,false,36,0,9))
        return StyleSectionModel("MainA",48,listOf(StylePartModel("Rhythm2",events,casm=policy,program=0,bankMsb=127,bankLsb=0)))
    }
    private fun prepare(section:StyleSectionModel){
        val style=ParsedStyle("Synthetic-SInt-equivalent.STY",480,mapOf("MainA" to section))
        DrumKitShadowRuntime.prepare(style,byteArrayOf(1,2,3),File("app/src/main/assets/r22/kits.tsv.gz").inputStream())
    }
    private fun runShadow(on:Boolean):Pair<List<String>,String> = runBlocking {
        // Equal fixture initial state: preserve raw trace IDs/sample flags instead of normalizing output.
        val diagnostic=StyleAudioPathDiagnostic::class.java
        (diagnostic.getDeclaredField("ids").apply { isAccessible=true }.get(null) as java.util.concurrent.atomic.AtomicLong).set(0)
        for(name in listOf("windows","normal","snares")) {
            val value=diagnostic.getDeclaredField(name).apply { isAccessible=true }.get(null)
            if(value is LongArray)value.fill(0) else (value as IntArray).fill(0)
        }
        val bridge=mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeStart()).thenReturn(true);`when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
        `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true);`when`(bridge.nativeSetKeyboardReleaseTime(anyInt())).thenReturn(true)
        val provider=mock(SampleProvider::class.java);val engine=AudioEngineManager(bridge,provider);engine.loadSoundFontPair("m","d")
        val midi=mock(MidiInputManager::class.java);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val sequencer=StyleSequencer(engine,midi,scope)
        var pc=0;var gen=0
        doAnswer { a->pc=a.getArgument<Int>(2);gen++;null }.`when`(bridge).nativeSetChannelPresetWithName(anyInt(),anyInt(),anyInt(),anyString())
        doAnswer { a->pc=a.getArgument<Int>(2);gen++;null }.`when`(bridge).nativeSetChannelPreset(anyInt(),anyInt(),anyInt())
        doAnswer { "$gen\t$pdta\t128\t$pc\tlegacy_same_pc_first_matching_cache_entry" }.`when`(bridge).nativeDrumChannelSelection(anyInt())
        val section=section();prepare(section);DrumKitShadowRuntime.setEnabled(on)
        clearInvocations(bridge,midi)
        // Deterministic event-dispatch fixture: initial master anchor=0 makes all
        // fixture ticks already due. Invoke the unmodified setup and playOnce
        // bodies directly; this test certifies command identity, not wall-clock timing.
        val apply=StyleSequencer::class.java.getDeclaredMethod("applyVoicesFromCasm",StyleSectionModel::class.java).apply { isAccessible=true }
        apply.invoke(sequencer,section)
        val playOnce=StyleSequencer::class.java.declaredMethods.single { it.name=="playOnce" }.apply { isAccessible=true }
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            playOnce.invoke(sequencer,section,480,0L,0L,continuation)
        }
        sequencer.stop()
        if(on)try { withTimeout(3000){while(!DrumKitShadowRuntime.report().contains("submitted=2 completed=2 dropped=0"))delay(5)} } catch(e:TimeoutCancellationException) { throw AssertionError("shadow completion timeout "+DrumKitShadowRuntime.report(),e) }
        val report=DrumKitShadowRuntime.report()
        val trace=(mockingDetails(bridge).invocations.filter { it.method.name!="nativeDrumChannelSelection" }.map { "N:${it.method.name}:${it.arguments.toList()}" }+
            mockingDetails(midi).invocations.map { "M:${it.method.name}:${it.arguments.toList()}" })
        scope.cancel();DrumKitShadowRuntime.setEnabled(false)
        trace to report
    }
    @Test fun shadowOnOffPreservesAllNativeAndExternalMidiCommandsAndObservesDynamicRawBank() {
        val off=runShadow(false);val on=runShadow(true)
        assertEquals(off.first,on.first)
        assertTrue(on.second.contains("src=SINT_OR_TICK0_SETUP req=127:0:0 ns=YAMAHA_XG"))
        assertTrue(on.second.contains("src=DYNAMIC_PC req=127:0:8 ns=YAMAHA_XG"))
        assertTrue(on.second.contains("PRIME_DRUM:128:8:'Kendang Alex'"))
        assertTrue(on.second.contains("COLOMBO:127:8:'XG Room'"))
        assertEquals(2,Regex("DRUM_KIT_SHADOW drumSelectionGeneration=").findAll(on.second).count())
        assertFalse(on.second.contains("thread=Oboe"));assertTrue(on.second.contains("thread=R22-shadow-metadata"))
    }
}

package com.yourapp.yamahaarranger.registration

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.file.Files
import com.yourapp.yamahaarranger.audio.KeyboardPart

class LivePhase4WiringTest {
    @Test fun inputViewportKeepsLandscapeFormAvailableWithKeyboard() {
        assertTrue(com.yourapp.yamahaarranger.ui.liveInputViewportSupported(1280f,400f,true))
        assertTrue(com.yourapp.yamahaarranger.ui.liveInputViewportSupported(960f,600f,false))
        assertFalse(com.yourapp.yamahaarranger.ui.liveInputViewportSupported(1280f,400f,false))
        assertFalse(com.yourapp.yamahaarranger.ui.liveInputViewportSupported(800f,400f,true))
    }
    private suspend fun until(check:()->Boolean)=withTimeout(5000) { while(!check()) delay(5) }
    @Test fun saveRestartRecallUsesActualLeftAndExplicitExternalOnly()=runBlocking<Unit> {
        val directory=Files.createTempDirectory("reg-live-restart")
        val a=RecallAdapterTest.Rig()
        try {
            a.vm.initializeLiveLibrary(directory);until { a.vm.liveLibrary.value.loaded }
            a.vm.onLiveCreateBank("Live");until { a.vm.liveLibrary.value.bank!=null }
            a.vm.selectExternalVoice(KeyboardPart.RIGHT1,ExternalVoice(0,112,5,"User E343"))
            a.brain.setLeftVoiceEnabled(false);a.vm._rightVoices.value=a.vm._rightVoices.value.map { if(it.layer==3) it.copy(enabled=true) else it }
            a.vm._right1Volume.value=83;a.reset()
            a.vm.onLiveSaveArm(600);a.vm.onLiveSlotTap(0,"Saved live")
            until { a.vm.liveLibrary.value.bank?.slots?.get(0)!=null }
            val slot=a.vm.liveLibrary.value.bank!!.slots[0]!!
            assertEquals(83,slot.parts[0].volume);assertFalse(slot.parts[3].enabled);assertEquals(5,slot.parts[0].external!!.program)
            assertTrue(a.bytes.isEmpty());verify(a.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
        } finally { a.close() }
        val b=RecallAdapterTest.Rig()
        try {
            b.reset();b.vm.initializeLiveLibrary(directory);until { b.vm.liveLibrary.value.loaded && b.vm.liveRegistration.value.bank!=null }
            assertTrue(b.bytes.isEmpty());assertNull(b.vm.currentExternal.value[0])
            b.vm.onLiveSlotTap(0,"");until { b.vm.currentExternal.value[0]!=null && !b.vm.liveRegistration.value.recalling }
            assertEquals(listOf(0xc0,5),b.bytes.first { it[0] and 240==192 });verify(b.bridge,never()).nativeSetKeyboardPreset(anyInt(),anyLong(),anyInt(),anyInt())
            b.bytes.clear();b.router.setConnected(false);b.router.setConnected(true);b.midi.midiOutEnabled=false;b.midi.midiOutEnabled=true
            assertFalse(b.bytes.any { it[0] and 240==192 || (it.size==3 && it[0] and 240==176 && it[1] in listOf(0,32)) })
        } finally { b.close() }
    }
    @Test fun saveRejectsInFlightEngineOperationWithoutStaleSnapshot()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig();val release=CompletableDeferred<Unit>()
        try {
            r.vm.initializeLiveLibrary(Files.createTempDirectory("reg-engine-busy"));until { r.vm.liveLibrary.value.loaded }
            r.vm.onLiveCreateBank("Bank");until { r.vm.liveLibrary.value.bank!=null }
            val entered=CompletableDeferred<Unit>()
            val operation=r.scope.launch { r.engine.readiness.operations.withLock { entered.complete(Unit);release.await() } }
            entered.await();r.reset();r.vm.onLiveSaveArm(600);r.vm.onLiveSlotTap(0,"Unsafe")
            until { r.vm.liveLibrary.value.message.contains("WAIT: ENGINE OPERATION") }
            assertNull(r.vm.liveLibrary.value.bank!!.slots[0]);assertTrue(r.bytes.isEmpty())
            release.complete(Unit);operation.join()
            r.vm.onLiveSaveArm(600);r.vm.onLiveSlotTap(0,"Safe")
            until { r.vm.liveLibrary.value.bank!!.slots[0]!=null }
            assertEquals("Safe",r.vm.liveLibrary.value.bank!!.slots[0]!!.name)
        } finally { release.complete(Unit);r.close() }
    }
    @Test fun addingExternalVoiceDoesNotSendButSelectingDoes()=runBlocking<Unit> {
        val r=RecallAdapterTest.Rig()
        try {
            r.vm.initializeLiveLibrary(Files.createTempDirectory("reg-input"));until { r.vm.liveLibrary.value.loaded }
            r.vm.onLiveCreateBank("Bank");until { r.vm.liveLibrary.value.bank!=null };r.reset()
            val v=ExternalVoice(0,112,5,"Explicit")
            r.vm.onLiveAddExternal(v);until { r.vm.liveLibrary.value.manifest.externalVoices.size==1 }
            assertTrue(r.bytes.isEmpty());r.vm.onLiveExternalSelected(2,v)
            assertEquals(listOf(listOf(0xb2,0,0),listOf(0xb2,32,112),listOf(0xc2,5)),r.bytes.take(3))
        } finally { r.close() }
    }
}

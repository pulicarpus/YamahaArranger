package com.yourapp.audio

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import com.yourapp.midi.MidiMixerOutput
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.StyleSequencer
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class MidiMixerOutputTest {
    private class Recorder {
        val events = mutableListOf<List<Int>>()
        val router = MidiMixerOutput { data -> events += data.map { it.toInt() and 255 }; true }
        init { router.setConnected(true); router.setEnabled(true); events.clear() }
        fun cc(ch: Int, cc: Int) = events.lastOrNull { it.size == 3 && it[0] == (0xb0 or ch) && it[1] == cc }?.get(2)
    }
    private fun snapshot(v: Int = 127, p: Int = 64, e: Int = 127, r: Int = 40, c: Int = 0) = intArrayOf(7,v,10,p,11,e,91,r,93,c)
    private fun assertNoPanic(events: List<List<Int>>) {
        assertFalse(events.any { it.size == 3 && it[0] and 240 == 176 && it[1] in listOf(120,121,123,64) })
    }

    private fun isBankOrProgram(event: List<Int>) =
        event[0] and 240 == 0xc0 || (event.size == 3 && event[0] and 240 == 0xb0 && event[1] in listOf(0,32))

    @Test fun keyboardEnableNeverReplaysCachedBankProgramButKeepsCC11() {
        val r=Recorder(); r.router.setEnabled(false)
        for (ch in 0..3) { r.router.program(ch,0,0,0); r.router.update(ch,intArrayOf(11,70+ch)) }
        r.events.clear(); r.router.setEnabled(true)
        assertFalse(r.events.any(::isBankOrProgram))
        for (ch in 0..3) assertEquals(70+ch,r.cc(ch,11))
    }
    @Test fun keyboardReconnectDoesNotReplayEvenExplicitlySelectedVoices() {
        val r=Recorder()
        for (ch in 0..3) { r.router.program(ch,48+ch,2,3); r.router.update(ch,intArrayOf(11,80+ch)) }
        r.router.setConnected(false); r.events.clear(); r.router.setConnected(true)
        assertFalse(r.events.any(::isBankOrProgram))
        for (ch in 0..3) assertEquals(80+ch,r.cc(ch,11))
    }
    @Test fun explicitKeyboardVoiceSelectionStillSendsBankProgramForEveryPart() {
        for (ch in 0..3) {
            val r=Recorder(); r.router.program(ch,48,2,3)
            assertEquals(listOf(listOf(0xb0 or ch,0,2),listOf(0xb0 or ch,32,3),listOf(0xc0 or ch,48)),r.events.take(3))
            assertEquals(127,r.cc(ch,11)); assertTrue(r.events.all { it[0] and 15 == ch })
        }
    }
    @Test fun styleBankProgramReplayIsPreservedAndStopNotesAreIdentical() {
        val r=Recorder(); r.router.setChannels(setOf(10)); r.router.program(10,33,0,1)
        r.router.program(1,48,2,3); r.router.setEnabled(false); r.events.clear(); r.router.setEnabled(true)
        assertEquals(listOf(listOf(0xba,0,0),listOf(0xba,32,1),listOf(0xca,33)),r.events.filter(::isBankOrProgram))
        r.events.clear()
        for (ch in 0..3) { r.router.noteOn(ch,60+ch,99); r.router.noteOff(ch,60+ch) }
        assertEquals((0..3).flatMap { listOf(listOf(0x90 or it,60+it,99),listOf(0x80 or it,60+it,0)) },r.events)
    }
    @Test fun productionManagerRetainsExplicitVoiceButOmitsAutoKeyboardBankProgram() {
        val (midi,events)=manager()
        midi.sendProgramChange(2,49,2,3)
        assertEquals(listOf(listOf(0xb2,0,2),listOf(0xb2,32,3),listOf(0xc2,49)),events.take(3))
        midi.setMixerControllers(2,intArrayOf(11,83)); events.clear()
        midi.midiOutEnabled=false; midi.midiOutEnabled=true
        assertFalse(events.any(::isBankOrProgram)); assertTrue(events.contains(listOf(0xb2,11,83)))
    }

    @Test fun reconnectDoesNotInventUntouchedVolumePanOrEffectValues() {
        val r=Recorder(); r.router.setChannels(setOf(10)); r.router.update(10,intArrayOf(11,80))
        r.router.setConnected(false); r.events.clear(); r.router.setConnected(true)
        assertTrue(r.events.all { it.size==3 && it[1]==11 })
        assertEquals(80,r.cc(10,11)); assertNull(r.cc(10,7)); assertNull(r.cc(0,91))
    }
    @Test fun productionOutputToggleReplaysWithoutWritingNativeMixer() {
        val (midi,events)=manager(); val bridge=mock(NativeAudioBridge::class.java)
        val engine=AudioEngineManager(bridge, object : SampleProvider { override fun sampleForNote(midiNote: Int): NoteSample? = null })
        engine.onMixerControllers=midi::setMixerControllers
        engine.setChannelExpression(1,73); clearInvocations(bridge); events.clear()
        midi.midiOutEnabled=false; midi.midiOutEnabled=true
        assertTrue(events.contains(listOf(0xb1,11,73)))
        verifyNoInteractions(bridge)
    }

    @Test fun fiveControllersReachOnlyTheirTargetPart() {
        for (ch in 0..3) {
            val r = Recorder(); r.router.update(ch, snapshot(91,32,82,30,20))
            assertTrue(r.events.all { it[0] == 0xb0 or ch })
            for ((cc,value) in listOf(7 to 91,10 to 32,11 to 82,91 to 30,93 to 20)) assertEquals(value, r.cc(ch,cc))
            assertNoPanic(r.events)
        }
    }
    @Test fun zeroControllerFadeDoesNotReleaseOrSuppressStyleNotes() {
        val r=Recorder(); r.router.setChannels(setOf(10)); r.router.noteOn(10,60,100); r.events.clear()
        r.router.update(10,intArrayOf(7,0,11,0)); r.router.noteOn(10,64,100)
        r.router.update(10,intArrayOf(7,100,11,90))
        assertFalse(r.events.any { it[0] and 240 == 0x80 })
        assertTrue(r.events.contains(listOf(0x9a,64,100)))
        r.events.clear(); r.router.noteOff(10,60); r.router.noteOff(10,64)
        assertEquals(listOf(listOf(0x8a,60,0),listOf(0x8a,64,0)),r.events)
    }
    @Test fun masterZeroIsGainOnlyAndRestoresHeldNotesWithoutRetrigger() {
        val r=Recorder(); r.router.noteOn(0,60,100); r.events.clear()
        r.router.setMaster(0); r.router.noteOn(1,64,100); r.router.setMaster(100)
        assertFalse(r.events.any { it[0] and 240 == 0x80 })
        assertTrue(r.events.contains(listOf(0x91,64,100)))
        assertEquals(127,r.cc(0,11)); assertNoPanic(r.events)
    }

    @Test fun styleMuteRetainsSourceAutomationAndStopsOnlyItsOwners() {
        val r=Recorder(); r.router.setChannels(setOf(9,10)); r.router.noteOn(9,36,100); r.router.noteOn(10,60,100)
        r.events.clear(); r.router.setMutedChannels(setOf(10)); r.router.update(10,snapshot(55,30,80,45,20))
        assertEquals(1,r.events.count { it==listOf(0x8a,60,0) })
        assertEquals(0,r.cc(10,7)); assertTrue(r.events.all { it[0] and 15 == 10 })
        r.events.clear(); r.router.noteOn(10,64,100); assertTrue(r.events.isEmpty())
        r.router.setMutedChannels(emptySet()); assertEquals(55,r.cc(10,7))
        assertFalse(r.events.any { it[0] and 240 == 0x90 }); assertNoPanic(r.events)
    }

    @Test fun expressionAutomationNeverRestoresRawStyleVolume() {
        val r=Recorder(); r.router.setChannels(setOf(10)); r.router.update(10,snapshot(39,64,93)); r.events.clear()
        r.router.update(10,intArrayOf(11,72))
        assertEquals(listOf(listOf(0xba,11,72)), r.events)
    }
    @Test fun masterIsExpressionTrimForManagedChannelsOnly() {
        val r=Recorder(); r.router.setChannels(setOf(8,10,14))
        for (ch in listOf(0,1,2,3,8,10,14)) r.router.update(ch,snapshot(75,64,80))
        r.events.clear(); r.router.setMaster(50)
        assertEquals((listOf(0,1,2,3,8,10,14)).map { listOf(0xb0 or it,11,40) }, r.events)
        r.events.clear(); r.router.setMaster(127)
        assertEquals(listOf(0,1,2,3,8,10,14).map { listOf(0xb0 or it,11,80) },r.events)
        assertNoPanic(r.events)
    }
    @Test fun unusedStyleChannelsNeverReceiveCCOrProgramOrNotes() {
        val r=Recorder(); r.router.setChannels(setOf(9)); r.events.clear()
        for (ch in (4..15).filter { it != 9 }) {
            r.router.update(ch,snapshot(40,32,60)); r.router.program(ch,24,1,2); r.router.noteOn(ch,60,100)
        }
        assertTrue(r.events.isEmpty())
    }
    @Test fun keyDownMuteKeyUpReleasesOwnedNoteAndKeepsOtherParts() {
        for (target in 0..3) {
            val r=Recorder(); for (ch in 0..3) r.router.noteOn(ch,60,100)
            r.events.clear(); r.router.setMutedChannels(setOf(target)); r.router.update(target,intArrayOf(11,0)); r.router.noteOff(target,60)
            assertEquals(2,r.events.count { it == listOf(0x80 or target,60,0) })
            assertTrue(r.events.all { (it[0] and 15) == target })
            r.events.clear(); r.router.noteOn(target,62,100); assertTrue(r.events.isEmpty())
            r.router.setMutedChannels(emptySet()); r.router.update(target,intArrayOf(11,100)); assertFalse(r.events.any { it[0] and 240 == 0x90 })
            assertNoPanic(r.events)
        }
    }
    @Test fun keyUpThenMuteReleaseTailUsesScopedGainWithoutPanic() {
        val r=Recorder(); r.router.noteOn(2,60,100); r.router.noteOff(2,60); r.events.clear()
        r.router.setMutedChannels(setOf(2)); r.router.update(2,intArrayOf(11,0))
        assertEquals(listOf(listOf(0xb2,11,0)),r.events)
    }
    @Test fun multiSoloRetainsManualMuteAndFlushesOnlyExcludedOwners() {
        val r=Recorder(); r.router.setChannels(setOf(10)); for (ch in 0..3) r.router.noteOn(ch,60,100)
        r.router.noteOn(10,36,100); r.events.clear()
        val focus=MixerConsoleState().toggleMute(1).toggleSolo(0).toggleSolo(10)
        r.router.setMutedChannels((0..3).filter { focus.muted(it) }.toSet())
        for (ch in 0..3) r.router.update(ch,intArrayOf(11,focus.keyboardGain(ch,95)))
        assertEquals(setOf(1,2,3),r.events.filter { it[0] and 240 == 0x80 }.map { it[0] and 15 }.toSet())
        r.events.clear(); val restored=focus.copy(solo=emptySet())
        r.router.setMutedChannels((0..3).filter { restored.muted(it) }.toSet())
        for (ch in 0..3) r.router.update(ch,intArrayOf(11,restored.keyboardGain(ch,95)))
        assertEquals(95,r.cc(2,11)); assertEquals(95,r.cc(3,11)); assertNull(r.cc(1,11))
        assertFalse(r.events.any { it[0] and 240 == 0x90 }); assertNoPanic(r.events)
    }
    @Test fun disablingOutputFlushesNotesBeforeBlockingLateKeyUp() {
        val r=Recorder(); r.router.noteOn(1,60,100); r.events.clear()
        r.router.setEnabled(false); r.router.noteOff(1,60)
        assertEquals(listOf(listOf(0x81,60,0)),r.events)
        r.router.update(1,snapshot(110,50,60,12,9)); r.events.clear(); r.router.setEnabled(true)
        assertEquals(60,r.cc(1,11)); assertEquals(110,r.cc(1,7)); assertEquals(50,r.cc(1,10))
        assertFalse(r.events.any { it[0] and 240 == 0x90 }); assertNoPanic(r.events)
    }
    @Test fun reconnectReplaysSnapshotAndMasterButNotHeldNotes() {
        val r=Recorder(); r.router.setChannels(setOf(12)); r.router.setMaster(50)
        r.router.update(12,snapshot(73,27,90,45,8)); r.router.noteOn(12,65,100); r.events.clear()
        r.router.setConnected(false); assertEquals(listOf(listOf(0x8c,65,0)),r.events)
        r.events.clear(); r.router.update(12,intArrayOf(11,80)); assertTrue(r.events.isEmpty())
        r.router.setConnected(true)
        assertEquals(40,r.cc(12,11)); assertEquals(73,r.cc(12,7)); assertEquals(27,r.cc(12,10))
        assertFalse(r.events.any { it[0] and 240 == 0x90 }); assertNoPanic(r.events)
    }
    @Test fun removedStylePartReleasesOnlyItsTrackedNotes() {
        val r=Recorder(); r.router.setChannels(setOf(9,10)); r.router.noteOn(9,36,100); r.router.noteOn(10,60,100)
        r.events.clear(); r.router.setChannels(setOf(10)); assertEquals(listOf(listOf(0x89,36,0)),r.events)
        r.events.clear(); r.router.update(9,snapshot(20)); assertTrue(r.events.isEmpty())
    }
    @Test fun programChangeRestoresItsControllersEvenWhenModuleResetsThem() {
        val r=Recorder(); r.router.update(1,snapshot(90,18,70,32,21)); r.events.clear()
        r.router.program(1,48,2,3)
        assertEquals(listOf(listOf(0xb1,0,2),listOf(0xb1,32,3),listOf(0xc1,48)),r.events.take(3))
        assertEquals(90,r.cc(1,7)); assertEquals(70,r.cc(1,11)); assertTrue(r.events.all { it[0] and 15 == 1 })
    }
    private fun manager(): Pair<MidiInputManager,MutableList<List<Int>>> {
        val events=mutableListOf<List<Int>>()
        val midi=MidiInputManager(mock(Context::class.java))
        val port=object: MidiInputPort() { override fun send(data:ByteArray,offset:Int,count:Int) { events += data.slice(offset until offset+count).map { it.toInt() and 255 } } }
        MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible=true }.set(midi,port)
        val output=MidiInputManager::class.java.getDeclaredField("mixerOutput").apply { isAccessible=true }.get(midi) as MidiMixerOutput
        output.setConnected(true); midi.midiOutEnabled=true; events.clear()
        return midi to events
    }
    @Test fun productionManagerAndEngineMirrorExactNativeControllerTargets() {
        val (midi,events)=manager(); val bridge=mock(NativeAudioBridge::class.java); val engine=AudioEngineManager(bridge, object : SampleProvider { override fun sampleForNote(midiNote: Int): NoteSample? = null })
        engine.onMixerControllers=midi::setMixerControllers
        for (ch in 0..3) {
            events.clear(); engine.setChannelMixer(ch,95,27,82,30,12)
            verify(bridge).nativeSetChannelMixer(ch,95,27,82,30,12)
            assertEquals(listOf(listOf(0xb0 or ch,7,95),listOf(0xb0 or ch,10,27),listOf(0xb0 or ch,11,82),listOf(0xb0 or ch,91,30),listOf(0xb0 or ch,93,12)),events)
        }
        events.clear(); midi.sendNoteOn(1,60,100); midi.setMixerMutedChannels(setOf(1)); engine.setChannelExpression(1,0); midi.sendNoteOff(1,60)
        assertEquals(listOf(listOf(0x91,60,100),listOf(0x81,60,0),listOf(0xb1,11,0),listOf(0x81,60,0)),events)
        assertNoPanic(events); verify(bridge,never()).nativeAllNotesOff()
    }
    @Test fun sourceAutomationAndMainFillRestoreKeepBothOutputsBalanced() {
        val (midi,events)=manager(); midi.setMixerStyleChannels(setOf(10)); val bridge=mock(NativeAudioBridge::class.java)
        val engine=AudioEngineManager(bridge, object : SampleProvider { override fun sampleForNote(midiNote: Int): NoteSample? = null }); engine.onMixerControllers=midi::setMixerControllers
        val seq=StyleSequencer(engine,midi,CoroutineScope(Dispatchers.Default))
        seq.setChannelMixer(10,64,null,100,null,null); seq.setStyleBusTrim(64)
        val policy=CasmPolicyModel(5,10,"Bass",0,0,0,0,127,0,127,0,false)
        fun section(name:String,v:Int,e:Int)=StyleSectionModel(name,480,listOf(StylePartModel("Bass", listOf(StyleNoteEvent(0,false,7,v,5,0xb5),StyleNoteEvent(0,false,11,e,5,0xb5)),casm=policy,program=33)))
        val setup=StyleSequencer::class.java.getDeclaredMethod("applyVoicesFromCasm",StyleSectionModel::class.java).apply { isAccessible=true }
        val control=StyleSequencer::class.java.getDeclaredMethod("applyStyleController",Int::class.javaPrimitiveType,StyleNoteEvent::class.java).apply { isAccessible=true }
        setup.invoke(seq,section("Main A",100,100))
        fun cc(c:Int)=events.last { it.size==3 && it[0]==0xba && it[1]==c }[2]
        assertEquals(50,cc(7)); assertEquals(40,cc(11))
        events.clear(); control.invoke(seq,10,StyleNoteEvent(120,false,7,80,5,0xb5)); assertEquals(40,cc(7))
        events.clear(); control.invoke(seq,10,StyleNoteEvent(240,false,11,80,5,0xb5))
        assertEquals(listOf(listOf(0xba,11,32)),events)
        setup.invoke(seq,section("Fill In AA",120,127)); assertEquals(60,cc(7)); assertEquals(50,cc(11))
        setup.invoke(seq,section("Main A",100,100)); assertEquals(50,cc(7)); assertEquals(40,cc(11))
        verify(bridge,atLeastOnce()).nativeSetChannelMixer(10,50,64,40,40,0)
        assertTrue(events.all { it[0] and 15 == 10 }); assertNoPanic(events)
    }
    @Test fun auxiliaryStyleTrimCovers4To7WithoutDoubleTrimming8To15() {
        val (midi,events)=manager(); midi.setMixerStyleChannels(setOf(4,7,10)); val bridge=mock(NativeAudioBridge::class.java)
        val engine=AudioEngineManager(bridge, object : SampleProvider { override fun sampleForNote(midiNote: Int): NoteSample? = null }); engine.onMixerControllers=midi::setMixerControllers
        engine.setAuxiliaryStyleBusTrim(64)
        engine.setChannelMixer(4,90,64,100,40,0); engine.setChannelExpression(7,80)
        engine.setChannelMixer(10,90,64,50,40,0)
        verify(bridge).nativeSetChannelMixer(4,90,64,50,40,0)
        verify(bridge).nativeSetChannelExpression(7,40)
        verify(bridge).nativeSetChannelMixer(10,90,64,50,40,0)
        events.clear(); engine.setAuxiliaryStyleBusTrim(127)
        assertEquals(listOf(listOf(0xb4,11,100),listOf(0xb7,11,80)),events)
    }
    @Test fun failedNoteOffIsRetriedBeforeUnmuteRestoresGain() {
        val events=mutableListOf<List<Int>>(); var failOff=false
        val router=MidiMixerOutput { data ->
            val event=data.map { it.toInt() and 255 }
            if (event[0] and 240 == 0x80 && failOff) { false }
            else { events += event; true }
        }
        router.setConnected(true); router.setEnabled(true); router.noteOn(1,60,100); events.clear()
        failOff=true; router.setMutedChannels(setOf(1)); router.update(1,intArrayOf(11,0))
        assertEquals(listOf(listOf(0xb1,11,0)),events)
        events.clear(); failOff=false; router.setMutedChannels(emptySet()); router.update(1,intArrayOf(11,95))
        assertEquals(listOf(listOf(0x81,60,0),listOf(0xb1,11,95)),events)
        assertNoPanic(events)
    }
    @Test fun repeatedNoteOwnershipIsReleasedWithoutAffectingOtherPitches() {
        val r=Recorder(); r.router.noteOn(2,60,100); r.router.noteOn(2,60,100); r.router.noteOn(0,62,100)
        r.events.clear(); r.router.setMutedChannels(setOf(2)); r.router.update(2,intArrayOf(11,0))
        assertEquals(2,r.events.count { it==listOf(0x82,60,0) })
        assertTrue(r.events.all { it[0] and 15 == 2 }); assertNoPanic(r.events)
    }

    @Test fun concurrentMasterAndStyleCCWritersFinishWithCurrentSnapshot() {
        val r=Recorder(); r.router.setChannels(setOf(10))
        val a=Thread { for (i in 0..100) r.router.update(10,intArrayOf(11,i)); r.router.update(10,intArrayOf(11,120)) }
        val b=Thread { for (i in 0..100) r.router.setMaster(i); r.router.setMaster(50) }
        a.start(); b.start(); a.join(5000); b.join(5000)
        assertFalse(a.isAlive); assertFalse(b.isAlive); assertEquals(60,r.cc(10,11)); assertNoPanic(r.events)
    }
}

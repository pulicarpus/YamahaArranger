package com.yourapp.yamahaarranger.arranger

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

class F12LatencyDiagnosticTest {
    @Test fun disabledTimingAndExactWaitHold() {
        var now=1_000_000L;val t=F12Timing { now }
        assertEquals(0L,t.begin());t.record(0,now,77000,5);assertFalse(t.report().contains("F12_STYLE_ROW"))
        t.arm();val request=t.begin();now+=77_000_000;t.finish(0,request,now)
        assertTrue(t.report().contains("maxWaitUs=77000 maxHoldUs=0"))
        t.stop();val before=t.report();now+=1_000_000;t.record(0,now,1000,1000,true);assertEquals(before,t.report())
    }
    @Test fun boundedRecentRowsExpiryAndStaleTokens() {
        var now=4_294_967_000_000L;val t=F12Timing { now };t.arm();val old=t.begin()
        repeat(200){now+=1_000_000;t.record(2,now,0,100000)}
        assertEquals(64,t.report().lineSequence().count { it.startsWith("F12_STYLE_ROW") })
        assertTrue(t.report().contains("overwritten=136"));assertTrue(t.report().contains("atUs=199704"));assertTrue(t.report().toByteArray().size<12000)
        t.arm();t.record(2,old,0,100000);assertFalse(t.report().contains("F12_STYLE_ROW"))
        now+=61_000_000_000;t.record(2,now,0,100000);assertFalse(t.report().contains("F12_STYLE_ROW"))
    }
    @Test fun priorityAndFirstChordSlowRowsSurviveFloodWithBoundedCorrelationExport() {
        var now=1_000_000L;val t=F12Timing { now };t.arm();t.chord(42);val mark=t.begin();now+=640_000;t.finish(5,mark,marker=true)
        t.record(0,now,24339,100)
        repeat(5000){now+=1000;t.record(2,now,0,61686)}
        t.stop();val style=t.report()
        assertTrue(style.contains("pool=priority"));assertTrue(style.contains("kind=5"));assertTrue(style.contains("waitUs=24339"))
        assertTrue(style.contains("slowDrop=4996"));assertTrue(style.contains("overwritten=4937"))
        val native="F12_NATIVE active=0 priorityDrop=0\nF12_NATIVE_ROW pool=priority order=1 kind=7 atUs=1400 waitUs=0 holdUs=0 chordId=42\nF12_NATIVE_ROW pool=focus order=1 kind=5 atUs=1500 waitUs=4600 holdUs=100 chordId=42"
        val export=ChordReportBounds.capture("test",style,native+"\n"+"old legacy row\n".repeat(10000))
        assertTrue(export.contains("CORRELATION_READY"));assertTrue(export.contains("offsetLowerUs=-242 offsetUpperUs=402 uncertaintyUs=644"))
        assertTrue(export.contains("pool=priority"));assertTrue(export.toByteArray().size<=49152)
        assertTrue(ChordReportBounds.capture("test",style,"missing native").contains("CORRELATION_INCOMPLETE"))
        assertTrue(ChordReportBounds.capture("test",style,native.replace("priorityDrop=0","priorityDrop=1")).contains("CORRELATION_INCOMPLETE"))
        val wrapped=style.replace("kind=5 atUs=1000 waitUs=0 holdUs=640", "kind=5 atUs=4294967200 waitUs=0 holdUs=20")
        val wrapExport=ChordReportBounds.capture("test",wrapped,native.replace("atUs=1400","atUs=10"))
        assertTrue(wrapExport.contains("offsetLowerUs=84 offsetUpperUs=108 uncertaintyUs=24"))
        t.arm();repeat(100){t.record(5,t.begin(),0,1,true)};t.stop();assertTrue(t.report().contains("priorityDrop=36"))
    }
    private fun trace(armed:Boolean):List<String> {
        val audio=mock(AudioEngineManager::class.java);val midi=mock(MidiInputManager::class.java)
        val scope=CoroutineScope(SupervisorJob());val seq=StyleSequencer(audio,midi,scope)
        try {
            if(armed)seq.armChordDiagnostic()
            seq.currentChord=DetectedChord(0,0,ChordQuality.MAJOR)
            val melodic=CasmPolicyModel(4,11,"Piano",0,0,0,0,11,0,127,3,false,-1)
            val drum=CasmPolicyModel(9,9,"Drum",0,0,0,0,11,0,127,1,false,-1)
            val section=StyleSectionModel("MainA",4,listOf(
                StylePartModel("melody",listOf(StyleNoteEvent(0,true,60,96,4)),melodic,listOf(melodic)),
                StylePartModel("rhythm",listOf(StyleNoteEvent(0,true,36,96,9)),drum,listOf(drum))))
            val done=CountDownLatch(1);seq.playSeamless(section,1_000_000_000,1){done.countDown()}
            assertTrue(done.await(20,TimeUnit.SECONDS));seq.currentChord=DetectedChord(5,5,ChordQuality.MAJOR)
            if(armed){seq.stopChordDiagnostic();assertTrue(seq.compactChordDiagnosticReport().contains("F12_STYLE_SUM kind=0 count="))}
            val events=mockingDetails(audio).invocations.mapNotNull {
                when(it.method.name){
                    "noteOnStyleChannel","noteOnChannel" -> "ON "+it.arguments.take(3).joinToString()
                    "noteOffStyleChannel","noteOffChannel" -> "OFF "+it.arguments.take(2).joinToString()
                    "setChannelProgram","setChannelMixer" -> it.method.name+" "+it.arguments.joinToString()
                    else -> null
                }
            }+mockingDetails(midi).invocations.filter { it.method.name in listOf("sendNoteOn","sendNoteOff","sendProgramChange") }.map { it.method.name+" "+it.arguments.joinToString() }
            assertTrue(events.any { it.startsWith("OFF") });return events
        } finally {seq.stop();scope.cancel()}
    }
    @Test fun actualSequencerCaptureOffOnPreservesNotesControllersAndMidi() { assertEquals(trace(false),trace(true)) }
    @Test fun nativeTimingAndStrictSourceOverlay() {
        val root=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"tools/test_f12_timing.py").isFile }
        val p=ProcessBuilder("python3","-B",File(root,"tools/test_f12_timing.py").path).directory(root).redirectErrorStream(true).start()
        val text=p.inputStream.bufferedReader().readText();assertTrue(text,p.waitFor(60,TimeUnit.SECONDS));assertEquals(text,0,p.exitValue())
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Exercise the real held-note/chord setter; compare MIDI behavior with capture OFF/ON. */
class StyleChordDiagnosticRegressionTest {
    private val c = DetectedChord(0, 0, ChordQuality.MAJOR)
    private val f = DetectedChord(5, 5, ChordQuality.MAJOR)
    private val g = DetectedChord(7, 7, ChordQuality.MAJOR)
    private fun policy(rtr: Int = 1, dst: Int = 13, mask: Long = -1L) = CasmPolicyModel(
        5, dst, "Strings1", 0, 0, 0, 0, 11, 0, 127, rtr, false, mask)
    private class Fixture {
        val audio = mock(AudioEngineManager::class.java)
        val midi = mock(MidiInputManager::class.java)
        val sequencer = StyleSequencer(audio, midi, CoroutineScope(SupervisorJob()))
        val events = ArrayList<String>()
        val identities = ArrayList<List<Any?>>()
        fun collect() {
            events.clear(); identities.clear()
            mockingDetails(audio).invocations.forEach { call ->
                val a = call.arguments
                when (call.method.name) {
                    "noteOffChannel", "noteOffStyleChannel" -> events.add("OFF ${a[0]} ${a[1]}")
                    "noteOnChannel", "noteOnStyleChannel" -> {
                        events.add("ON ${a[0]} ${a[1]} ${a[2]}")
                        if (call.method.name == "noteOnStyleChannel") identities.add(a.toList())
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun seed(policy: CasmPolicyModel, alternatives: List<CasmPolicyModel> = listOf(policy), output: Int = 60,
                 src:Int=5, destination:Int=13, onId:Long=100L, sourceNote:Int=60,
                 velocity:Int=26, sourceBank:Int=1029, headerPc:Int=49):Any {
            val field = StyleSequencer::class.java.getDeclaredField("activeTransposedNotes").apply { isAccessible = true }
            val cls = Class.forName("com.yourapp.yamahaarranger.arranger.StyleSequencer\$ActiveTransposedNote")
            val constructor = cls.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            val active = constructor.newInstance(src, sourceNote, destination, output, velocity, policy, alternatives,
                "Strings source", "MainD", sourceBank, headerPc, 200L, onId)
            (field.get(sequencer) as MutableMap<String, Any>)["$src:$sourceNote"] = active
            return active
        }
        fun retarget(owner:Any, chord:DetectedChord) {
            StyleSequencer::class.java.getDeclaredMethod("retargetActive",owner.javaClass,DetectedChord::class.java)
                .apply { isAccessible=true }.invoke(sequencer,owner,chord)
        }
        fun scheduledOff(src:Int, note:Int):Boolean =
            StyleSequencer::class.java.getDeclaredMethod("endScheduledNote",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                .apply { isAccessible=true }.invoke(sequencer,src,note) as Boolean
        fun release(owner:Any,id:Long) {
            StyleSequencer::class.java.getDeclaredMethod("releaseActive",owner.javaClass,Long::class.javaPrimitiveType)
                .apply { isAccessible=true }.invoke(sequencer,owner,id)
        }
        fun normalPianoOn(p:CasmPolicyModel) {
            val complete=CountDownLatch(1)
            val section=StyleSectionModel("MainD",0,listOf(StylePartModel("Piano",
                listOf(StyleNoteEvent(0,true,65,81,11)),casm=p,program=0,bankMsb=104,bankLsb=21)))
            sequencer.playSeamless(section,1920,loopLimit=1,onComplete={ complete.countDown() })
            assertTrue("real scheduler completes",complete.await(5,TimeUnit.SECONDS))
        }
    }
    private fun run(armed: Boolean, p: CasmPolicyModel, chords: List<DetectedChord>, output: Int = 60,
                    alternatives: List<CasmPolicyModel> = listOf(p)): Fixture {
        val t = Fixture(); t.sequencer.currentChord = c; t.seed(p, alternatives, output)
        if (armed) t.sequencer.armChordDiagnostic()
        clearInvocations(t.audio, t.midi)
        chords.forEach { t.sequencer.currentChord = it }; t.collect()
        return t
    }
    @Test fun cFGCReplacementMessagesAndVelocityAreUnchangedForEveryRtrMode() {
        for (rtr in 0..6) {
            val a = run(false, policy(rtr), listOf(f,g,c))
            val b = run(true, policy(rtr), listOf(f,g,c))
            assertEquals("RTR=$rtr", a.events, b.events)
            assertEquals("external MIDI RTR=$rtr", mockingDetails(a.midi).invocations.map { it.method.name to it.arguments.toList() },
                mockingDetails(b.midi).invocations.map { it.method.name to it.arguments.toList() })
            assertTrue(mockingDetails(b.audio).invocations.none { it.method.name.startsWith("setChannel") })
        }
    }
    @Test fun retargetReplacementCarriesSourceAndSameChordEventIdAsOldOff() {
        val t = run(true, policy(), listOf(f))
        assertEquals(listOf("OFF 13 60", "ON 13 65 ${26/127f}"), t.events)
        val on = t.identities.single()
        assertEquals(listOf<Any>(5,60,1029,200L), on.subList(3,7))
        assertTrue((on[7] as Long)>0); assertTrue((on[9] as Long)>0); assertEquals(1,on[10])
        val off = mockingDetails(t.audio).invocations.single { it.method.name == "noteOffStyleChannel" }.arguments
        assertEquals(on[7], off[6]); assertEquals(on[9],off[7])
        val report=t.sequencer.chordDiagnosticReport()
        assertTrue(report.contains("old=0/MAJOR new=5/MAJOR"))
        assertTrue(report.contains("part='Strings source' src=5 original=60 dst=13"))
        assertTrue(report.contains("stage=REPLACEMENT_OFF"))
    }
    @Test fun changedDestinationPreservesExistingOffOffOnOrderWithoutPresetActivation() {
        val p=policy(dst=14)
        val a=run(false,p,listOf(f)); val b=run(true,p,listOf(f))
        assertEquals(listOf("OFF 13 60", "OFF 14 60", "ON 14 65 ${26/127f}"), b.events)
        assertEquals(a.events,b.events)
        assertTrue(b.sequencer.chordDiagnosticReport().contains("oldDst=13 newDst=14"))
        assertTrue(mockingDetails(b.audio).invocations.none { it.method.name.startsWith("setChannel") })
    }
    @Test fun casmMaskMissStillReleasesAndNeverSendsReplacement() {
        val a=run(false,policy(mask=0L),listOf(f));val b=run(true,policy(mask=0L),listOf(f))
        assertEquals(listOf("OFF 13 60"),b.events);assertEquals(a.events,b.events)
        assertTrue(b.sequencer.chordDiagnosticReport().contains("POLICY_MISS_RELEASE"))
    }
    @Test fun unchangedPitchAndDeferredModeStillDoNotRetrigger() {
        val same=run(true,policy(),listOf(f),output=65)
        assertTrue(same.events.isEmpty());assertTrue(same.sequencer.chordDiagnosticReport().contains("UNCHANGED_NO_SEND"))
        val deferred=run(true,policy(5),listOf(f))
        assertTrue(deferred.events.isEmpty());assertTrue(deferred.sequencer.chordDiagnosticReport().contains("RTR_DEFERRED_NO_SEND"))
    }
    @Test fun diagnosticDoesNotAddReservedChannelOrMuteGateToExistingRetarget() {
        // This records current behavior for investigation; it does not endorse a routing fix.
        val a=run(false,policy(dst=0),listOf(f));val b=run(true,policy(dst=0),listOf(f))
        assertEquals(a.events,b.events)
        assertTrue(b.sequencer.chordDiagnosticReport().contains("reserved=true"))
    }
    @Test fun compactReportExcludesUnrelatedPlaybackAndPreservesPianoSourceIdentity() {
        var now=1L; val t=ChordChangeDiagnostic { now };t.arm()
        t.record { "SCHEDULED id=1 dst=11 stage=FORWARD" }
        t.record { "RETARGET id=11 dst=12 stage=SCHEDULED_OFF" }
        assertTrue(t.report().contains("rows=0 "))
        t.begin("C","F",1)
        t.record { "SCHEDULED id=2 chordId=3 part='Piano rt' src=11 original=58 output=57 dst=11 stage=FORWARD" }
        t.record { "SCHEDULED id=4 dst=9 stage=FORWARD" }
        val report=t.compactReport()
        assertTrue(report.contains("part='Piano rt' src='11' original='58' dst='11' output='57'"))
        assertFalse(report.contains("id='4'"))
        now+=800_000_000L;t.record { "SCHEDULED id=5 dst=11 stage=FORWARD" }
        t.record { "RETARGET id=12 dst=11 stage=SCHEDULED_OFF" }
        assertFalse(t.compactReport().contains("id='5'"));assertFalse(t.compactReport().contains("id='12'"))
    }
    @Test fun compactByteLimitPreservesPianoAndWholeUtf8RowsWithExplicitOmissions() {
        val t=ChordChangeDiagnostic();t.arm();t.begin("C","F",1)
        repeat(1500) { t.record { "RETARGET id=$it part='${"界".repeat(100)}' src=13 dst=13 original=60 output=65 stage=REPLACE" } }
        t.record { "RETARGET id=9999 part='Piano' src=11 dst=11 original=58 output=57 stage=REPLACE" }
        val report=t.compactReport()
        assertTrue(report.toByteArray(Charsets.UTF_8).size<=16*1024)
        assertTrue(report.contains("id='9999'"));assertFalse(report.contains("exportOmittedRows=0 "))
        val small=ChordReportBounds.lines("header\n",List(500) { "row=$it ${"界".repeat(100)}" },2048)
        assertTrue(small.toByteArray(Charsets.UTF_8).size<=2048)
        assertTrue(small.endsWith("captureDropped is separate.\n"));assertFalse(small.contains("\uFFFD"))
        val hugeHeader=ChordReportBounds.lines("界".repeat(1000), emptyList(),256)
        assertTrue(hugeHeader.toByteArray(Charsets.UTF_8).size<=256)
    }
    @Test fun compactFileCompositionRemainsUnder48KiBAndJoinsBothLayers() {
        val report=ChordReportBounds.lines("CHORD FILE\n",listOf("STYLE chordId=7 id=8", "NATIVE chordId=7 id=8 NOTE_PRE", "NATIVE chordId=7 id=8 NOTE_POST") + List(5000) { "row="+"a".repeat(300) })
        assertTrue(report.toByteArray(Charsets.UTF_8).size<=48*1024)
        assertTrue(report.contains("STYLE chordId=7 id=8"));assertTrue(report.contains("NOTE_POST"))
        assertFalse(report.contains("exportOmittedRows=0 "))
    }
    @Test fun coalescingSourceNotesAreObservedWithoutDeduplicationOrAdditionalSends() {
        fun collision(armed:Boolean):Fixture {
            val t=Fixture();t.sequencer.currentChord=c
            t.seed(policy(dst=11),src=11,destination=11,onId=100L)
            t.seed(policy(dst=11),src=12,destination=11,onId=101L)
            if(armed)t.sequencer.armChordDiagnostic()
            clearInvocations(t.audio,t.midi);t.sequencer.currentChord=f;t.collect();return t
        }
        val plain=collision(false);val observed=collision(true)
        assertEquals(plain.events,observed.events)
        assertEquals(2,observed.events.count { it.startsWith("ON 11 65 ") })
        val report=observed.sequencer.chordDiagnosticReport()
        assertTrue(report.contains("RTR=1 stage=REPLACE method=OFF_ON"))
        assertTrue(report.contains("targetPeers=0"));assertTrue(report.contains("targetPeers=1"))
        assertTrue(report.contains("owners='11:60:100'") || report.contains("owners='12:60:101'"))
        assertTrue(report.contains("originTick=200"))
    }
    @Test fun compactPrioritizesLateRetargetAboveNormalContextAndPreservesPreChordTiming() {
        var now=1L;val t=ChordChangeDiagnostic { now };t.arm()
        t.record { "SCHEDULED id=77 dst=11 src=11 original=60 output=60 tick=240 lagUs=0 stage=FORWARD" }
        now+=100_000_000L;val chord=t.begin("C","F",1)
        assertTrue(t.compactReport().contains("id='77'"))
        assertTrue(t.compactReport().contains("tick='240' lagUs='0'"))
        assertTrue(t.compactReport().contains("contextId='$chord'"))
        repeat(1000) { t.record { "SCHEDULED id=$it dst=11 part='${"界".repeat(64)}' stage=FORWARD" } }
        t.record { "RETARGET id=99999 chordId=$chord dst=11 originTick=240 RTR=1 stage=REPLACE method=OFF_ON targetPeers=1 owners='11:63:78'" }
        val report=t.compactReport()
        assertTrue(report.contains("id='99999'"));assertTrue(report.contains("targetPeers='1' owners='11:63:78'"))
        assertTrue(report.indexOf("id='99999'")<report.indexOf("id='77'"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size<=16*1024)
        now+=800_000_000L
        t.record { "RETARGET id=99998 dst=11 stage=SCHEDULED_OFF method=decision_or_off targetPeers=0" }
        assertFalse(t.compactReport().contains("id='99998'"))
    }
    @Test fun captureDefaultsOffExpiresBoundsAndRetainsReportAfterStop() {
        var now=1L;val t=ChordChangeDiagnostic { now }
        assertFalse(t.active());t.arm();t.begin("C","F",1)
        repeat(2050) { t.record { "RETARGET id=$it dst=13 stage=REPLACE" } }
        assertTrue(t.report().contains("rows=2048 cap=2048 dropped=3"))
        now+=60_000_000_000L;assertFalse(t.active());assertEquals(0L,t.chordId())
        val after=t.report();t.record { "LATE" };assertEquals(after,t.report())
        t.stop();assertTrue(t.report().contains("CHORD_CHANGE"))
        t.arm();assertTrue(t.report().contains("rows=0 cap=2048 dropped=0"))
    }
    private fun pianoPolicy()=CasmPolicyModel(11,11,"Piano",0,0,1,2,11,0,127,1,false)
    @Test fun endedGToCOwnerCannotAddReplacementFourMillisecondsBeforeNormalPianoOn() {
        val t=Fixture();val p=pianoPolicy();t.sequencer.currentChord=g
        val snapshotOwner=t.seed(p,output=67,src=11,destination=11,onId=1977L,sourceNote=65,
            velocity=73,sourceBank=13333,headerPc=0)
        t.sequencer.armChordDiagnostic();clearInvocations(t.audio,t.midi)
        // Deterministic event clock reproduces report +109ms stale / +113ms
        // normal. No real-time sleeps or change to the production clock.
        val eventMs=AtomicLong(1)
        val ons=Collections.synchronizedList(mutableListOf<Pair<Long,Int>>())
        doAnswer { call -> ons.add(eventMs.get() to call.getArgument<Int>(1));null }
            .`when`(t.audio).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
        assertTrue(t.scheduledOff(11,65))
        t.sequencer.currentChord=c
        eventMs.set(109);t.retarget(snapshotOwner,c)
        t.collect();assertEquals(listOf("OFF 11 67"),t.events)
        assertTrue(t.sequencer.chordDiagnosticReport().contains("stage=STALE_OWNER_SKIP"))
        eventMs.set(113);t.normalPianoOn(p);t.collect()
        assertEquals(listOf(113L to 64),ons.toList())
        assertEquals(listOf("OFF 11 67","ON 11 64 ${81/127f}"),t.events)
        val normal=t.identities.single()
        assertEquals(65,normal[4]);assertEquals(0,normal[10])
        verify(t.midi,times(1)).sendNoteOn(11,64,81)
        verify(t.midi,never()).sendNoteOn(11,64,73)
    }
    @Test fun validReplacementCannotBeEndedBetweenOwnerValidationAndNewOn() {
        val t=Fixture();val p=pianoPolicy();t.sequencer.currentChord=g
        val owner=t.seed(p,output=67,src=11,destination=11,onId=1977L,sourceNote=65,velocity=73)
        t.sequencer.armChordDiagnostic();clearInvocations(t.audio,t.midi)
        val offEntered=CountDownLatch(1);val allowReplacement=CountDownLatch(1)
        val scheduledStarted=CountDownLatch(1);val scheduledDone=CountDownLatch(1)
        val failure=AtomicReference<Throwable>()
        doAnswer { call ->
            if(call.getArgument<Int>(0)==11 && call.getArgument<Int>(1)==67) {
                offEntered.countDown();check(allowReplacement.await(5,TimeUnit.SECONDS))
            };null
        }.`when`(t.audio).noteOffStyleChannel(anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyLong(),anyInt())
        val replacement=thread(name="RTR-owner",isDaemon=true) {
            try { t.retarget(owner,c) } catch(e:Throwable) { failure.compareAndSet(null,e) }
        }
        var scheduled:Thread?=null
        try {
            assertTrue(offEntered.await(5,TimeUnit.SECONDS))
            scheduled=thread(name="scheduled-off",isDaemon=true) {
                scheduledStarted.countDown()
                try { check(t.scheduledOff(11,65)) } catch(e:Throwable) { failure.compareAndSet(null,e) }
                finally { scheduledDone.countDown() }
            }
            assertTrue(scheduledStarted.await(5,TimeUnit.SECONDS))
            assertFalse("off cannot interleave inside replacement",scheduledDone.await(100,TimeUnit.MILLISECONDS))
        } finally {
            allowReplacement.countDown();replacement.join(5000);scheduled?.join(5000)
        }
        assertFalse(replacement.isAlive);assertFalse(scheduled?.isAlive==true);assertNull(failure.get())
        t.collect()
        assertEquals(listOf("OFF 11 67","ON 11 64 ${73/127f}","OFF 11 64"),t.events)
        assertEquals(listOf("sendNoteOff","sendNoteOn","sendNoteOff"),mockingDetails(t.midi).invocations.map { it.method.name })
    }
    @Test fun replacedOwnerWithEqualFieldsIsSkippedByIdentityWithoutPitchOrEventDeduplication() {
        val t=Fixture();val p=pianoPolicy();t.sequencer.currentChord=g
        val old=t.seed(p,output=67,src=11,destination=11,onId=1977L,sourceNote=65,velocity=73)
        val current=t.seed(p,output=67,src=11,destination=11,onId=1977L,sourceNote=65,velocity=73)
        assertEquals(old,current);assertNotSame(old,current)
        t.sequencer.armChordDiagnostic();clearInvocations(t.audio,t.midi)
        t.retarget(old,c);t.release(old,1977L);t.collect();assertTrue(t.events.isEmpty())
        t.retarget(current,c);t.collect()
        assertEquals(listOf("OFF 11 67","ON 11 64 ${73/127f}"),t.events)
    }
}

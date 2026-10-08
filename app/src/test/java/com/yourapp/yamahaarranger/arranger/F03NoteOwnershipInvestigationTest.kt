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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine

/** S5 observes defects/terminations of unchanged production. Dispatch != PCM.
 * No proposed ownership algorithm, transform, or musical oracle is executed.
 */
class F03NoteOwnershipInvestigationTest {
    private fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }
    private fun owners(seq:StyleSequencer):Map<String,Any> {
        @Suppress("UNCHECKED_CAST")
        return (field(seq,"activeTransposedNotes").get(seq) as Map<String,Any>).toMap()
    }
    private fun ownerTick(owner:Any)=field(owner,"sourceTick").get(owner) as Long
    private fun policy(src:Int=4,dst:Int=11,rtr:Int=1,ntr:Int=0,ntt:Int=0)=CasmPolicyModel(src,dst,"Piano",0,0,ntr,ntt,11,0,127,rtr,false,-1)
    private fun on(t:Int=0,n:Int=60,s:Int=4,v:Int=100)=StyleNoteEvent(t,true,n,v,s)
    private fun off(t:Int,n:Int=60,s:Int=4)=StyleNoteEvent(t,false,n,0,s)
    private fun section(name:String,events:List<StyleNoteEvent>,ps:List<CasmPolicyModel> = listOf(policy()),length:Int=20):StyleSectionModel =
        StyleSectionModel(name,length,listOf(StylePartModel("source${events.firstOrNull()?.channel}",events,ps.firstOrNull(),ps)))
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private inner class Run:AutoCloseable {
        val audio=mock(AudioEngineManager::class.java)
        val midi=mock(MidiInputManager::class.java)
        val scope=CoroutineScope(SupervisorJob())
        val seq=StyleSequencer(audio,midi,scope)
        val allDecisions=mutableListOf<String>()
        init {
            // Existing observer retains mostly dst11 context. Test-only spy
            // reads every pure diagnostic string and still calls real observer.
            // Selection, registry, scheduling and dispatch are never stubbed.
            val observer=spy(ChordChangeDiagnostic { 0L })
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val message=invocation.arguments[0] as ()->String
                allDecisions.add(message())
                invocation.callRealMethod()
            }.`when`(observer).record(any<()->String>() ?: { "" })
            field(seq,"chordTrace").set(seq,observer)
            seq.armChordDiagnostic()
            seq.currentChord=DetectedChord(0,0,ChordQuality.MAJOR)
        }
        // Executes real private suspend scheduler synchronously. Clock origin0
        // makes events already due; timing/races are deliberately NOT certified.
        fun once(section:StyleSectionModel,start:Long=0L,phase:Long=0L) {
            field(seq,"masterClockStartedAtNanos").setLong(seq,0L)
            val method=StyleSequencer::class.java.getDeclaredMethod("playOnce",StyleSectionModel::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Continuation::class.java).apply { isAccessible=true }
            runBlocking { suspendCoroutine<Any> { c ->
                val result=method.invoke(seq,section,1_000_000_000,start,phase,c)
                if(result!==COROUTINE_SUSPENDED)c.resumeWith(Result.success(result))
            } }
        }
        fun calls(boundary:Any=audio):List<String> = mockingDetails(boundary).invocations.sortedBy { it.sequenceNumber }.mapNotNull { call ->
            val a=call.arguments
            when(call.method.name) {
                "noteOnStyleChannel","noteOnChannel","sendNoteOn" -> "ON ${a[0]} ${a[1]}"
                "noteOffStyleChannel","noteOffChannel","sendNoteOff" -> "OFF ${a[0]} ${a[1]}"
                "allNotesOff" -> "ALL_OFF"
                else -> null
            }
        }
        fun lifecycle():List<String> = allDecisions.asSequence().filter { it.startsWith("SCHEDULED ")||it.startsWith("RETARGET ") }.mapNotNull { line ->
            val stage=Regex("stage=([A-Z_]+)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            if(stage.isNotEmpty()) {
                fun value(key:String)=Regex("(?:^| )$key=([^ ]+)").find(line)?.groupValues?.get(1)?:"-"
                "$stage src=${value("src")} note=${value("original")} dst=${value("dst")} output=${value("output")} tick=${value("tick")} origin=${value("originTick")}"
            } else null
        }.toList()
        fun trace(label:String):String {
            val detail=mockingDetails(audio).invocations.sortedBy { it.sequenceNumber }.filter { it.method.name=="noteOnStyleChannel" }.map { call ->
                val a=call.arguments
                "src=${a[3]} note=${a[4]} tick=${a[6]} dst=${a[0]} output=${a[1]} velocity=${kotlin.math.round((a[2] as Float)*127).toInt()}"
            }
            val external=mockingDetails(midi).invocations.sortedBy { it.sequenceNumber }.filter { it.method.name=="sendNoteOn" }.map { it.arguments.toList() }
            val internal=mockingDetails(audio).invocations.sortedBy { it.sequenceNumber }.filter { it.method.name=="noteOnStyleChannel" }.map { listOf(it.arguments[0],it.arguments[1],kotlin.math.round((it.arguments[2] as Float)*127).toInt()) }
            assertEquals("dispatch ON pitch/channel/velocity equivalence",internal,external)
            return "CASE $label\nAUDIO ${calls()}\nMIDI ${calls(midi)}\nDISPATCH $detail\nOWNER ${owners(seq).mapValues { ownerTick(it.value) }.toSortedMap()}\n"+lifecycle().joinToString("\n")+"\n"
        }
        override fun close(){scope.cancel()}
    }
    private fun assertBoth(r:Run,expected:List<String>) {
        assertEquals(expected,r.calls());assertEquals(expected,r.calls(r.midi))
    }

    @Test fun AandB_secondOnReplacesOldOwner_firstOffConsumesNewOwner_lastOffIsOrphan() {
        Run().use { r ->
            r.once(section("SYNTHETIC_A",listOf(on(0),on(1))))
            assertBoth(r,listOf("ON 11 60","OFF 11 60","ON 11 60"))
            val second=owners(r.seq).getValue("4:60");assertEquals(1L,ownerTick(second))
            r.once(section("SYNTHETIC_B",listOf(off(0))),2)
            assertTrue(owners(r.seq).isEmpty());assertBoth(r,listOf("ON 11 60","OFF 11 60","ON 11 60","OFF 11 60"))
            r.once(section("SYNTHETIC_B",listOf(off(0))),3)
            assertTrue(r.lifecycle().last().startsWith("OFF_NO_ACTIVE_LEDGER"))
            println(r.trace("A_B_SYNTHETIC_IDENTITY_COLLISION_OWNER_REPLACEMENT_ORPHAN"))
        }
    }

    @Test fun C_twoSourcesSameDestinationSamePitchKeepTwoLedgerOwnersButOffHasNoOwnerToken() {
        Run().use { r ->
            val parts=listOf(StylePartModel("source4",listOf(on(0)),policy(4),listOf(policy(4))),StylePartModel("source5",listOf(on(0,s=5)),policy(5),listOf(policy(5))))
            r.once(StyleSectionModel("SYNTHETIC_C",20,parts))
            assertEquals(setOf("4:60","5:60"),owners(r.seq).keys)
            r.once(section("SYNTHETIC_C",listOf(off(0,s=5)),listOf(policy(5))),1)
            assertEquals(setOf("4:60"),owners(r.seq).keys)
            assertBoth(r,listOf("ON 11 60","ON 11 60","OFF 11 60"))
            println(r.trace("C_SYNTHETIC_SOURCE5_OFF_SHARED_DEST_PITCH"))
        }
    }

    @Test fun C_twoSourcesDifferentPitchesAreNegativeControlForOwnershipCollision() {
        Run().use { r ->
            r.once(StyleSectionModel("SYNTHETIC_C_CONTROL",20,listOf(
                StylePartModel("source4",listOf(on(0),off(2)),policy(),listOf(policy())),
                StylePartModel("source5",listOf(on(1,64,5),off(3,64,5)),policy(5),listOf(policy(5))))))
            assertBoth(r,listOf("ON 11 60","ON 11 64","OFF 11 60","OFF 11 64"));assertTrue(owners(r.seq).isEmpty())
        }
    }

    private fun transitionSequence(names:List<String>):String {
        Run().use { r ->
            val done=CountDownLatch(1)
            val visited=mutableListOf<String>()
            val queued=names.drop(1).mapIndexed { i,name -> section(name,listOf(on(0,n=62+i),off(2,n=62+i)),length=4) }
            // Install transition at exact test tick1 inside actual ON dispatch.
            // Existing production coroutine consumes/release/queues all sections.
            doAnswer { invocation ->
                if(invocation.arguments[4]==60) {
                    val pendingClass=Class.forName(StyleSequencer::class.java.name+"\$PendingSection")
                    val pendingCtor=pendingClass.declaredConstructors.single { it.parameterCount==5 }.apply { isAccessible=true }
                    val queue=queued.map { s ->
                        val complete:(()->Unit)?=if(s==queued.last())({done.countDown()})else null
                        val started:()->Unit={visited.add(s.name)}
                        pendingCtor.newInstance(s,1_000_000_000,1,complete,started)
                    }
                    val transitionClass=Class.forName(StyleSequencer::class.java.name+"\$PendingTransition")
                    val ctor=transitionClass.declaredConstructors.single { it.parameterCount==2 }.apply { isAccessible=true }
                    field(r.seq,"pendingTransition").set(r.seq,ctor.newInstance(queue,1L))
                }
                null
            }.`when`(r.audio).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
            r.seq.playSeamless(section(names.first(),listOf(on(0),off(3)),length=4),1_000_000_000,1)
            assertTrue(done.await(20,TimeUnit.SECONDS))
            runBlocking { r.scope.coroutineContext[Job]!!.cancelAndJoin() }
            assertEquals(names.drop(1),visited)
            assertEquals(listOf("ON 11 60","OFF 11 60")+queued.indices.flatMap { i->listOf("ON 11 ${62+i}","OFF 11 ${62+i}") },r.calls())
            assertEquals(r.calls(),r.calls(r.midi));assertTrue(owners(r.seq).isEmpty())
            assertTrue(r.lifecycle().any { it.startsWith("RELEASE_OFF") })
            return r.trace(names.joinToString("->")+"_SYNTHETIC_EXPECTED_TRANSITION_TERMINATION")
        }
    }
    @Test fun D_MainDFillBFillAMainAUsesActualCoroutineTransitionReleaseAndQueues() {
        println(transitionSequence(listOf("MainD","FillBB","FillAA","MainA")))
    }
    @Test fun E_MainDFillBMainBUsesActualCoroutineTransitionReleaseAndQueues() {
        println(transitionSequence(listOf("MainD","FillBB","MainB")))
    }

    @Test fun naturalSectionBoundaryDoesNotClearOwner_andSameKeyIncomingOffConsumesIt() {
        Run().use { r ->
            r.once(section("SYNTHETIC_MainD",listOf(on(0)),length=4))
            assertEquals(1,owners(r.seq).size)
            r.once(section("SYNTHETIC_FillBB",listOf(off(0))),4)
            assertBoth(r,listOf("ON 11 60","OFF 11 60"));assertTrue(owners(r.seq).isEmpty())
            println(r.trace("NATURAL_BOUNDARY_SYNTHETIC_OFF_FROM_INCOMING_SECTION"))
        }
    }

    @Test fun F_chordRetargetPreservesIdentityAndScheduledOffReleasesUpdatedPitch() {
        Run().use { r ->
            r.once(section("SYNTHETIC_F",listOf(on(0))))
            val owner=owners(r.seq).getValue("4:60")
            r.seq.currentChord=DetectedChord(5,5,ChordQuality.MAJOR)
            assertSame(owner,owners(r.seq).getValue("4:60"))
            r.once(section("SYNTHETIC_F",listOf(off(0))),1)
            assertBoth(r,listOf("ON 11 60","OFF 11 60","ON 11 65","OFF 11 65"))
            println(r.trace("F_SYNTHETIC_RTR1_EXISTING_OFF_ON_RETARGET"))
        }
    }
    @Test fun F_RTRStopAndNoChordAreExpectedTerminationsNotAutomaticDefectClaims() {
        for(rtr in listOf(0,1))Run().use { r ->
            r.once(section("SYNTHETIC_F_STOP",listOf(on()),listOf(policy(rtr=rtr))))
            if(rtr==0)r.seq.currentChord=DetectedChord(5,5,ChordQuality.MAJOR) else r.seq.currentChord=null
            assertBoth(r,listOf("ON 11 60","OFF 11 60"));assertTrue(owners(r.seq).isEmpty())
        }
    }

    @Test fun G_staleOwnerObjectCannotReleaseReplacement_butScheduledOffHasNoGeneration() {
        Run().use { r ->
            r.once(section("SYNTHETIC_G",listOf(on())))
            val stale=owners(r.seq).getValue("4:60")
            r.seq.stop();assertTrue(owners(r.seq).isEmpty())
            r.once(section("SYNTHETIC_G_RESTART",listOf(on())),10)
            val active=owners(r.seq).getValue("4:60")
            val release=StyleSequencer::class.java.getDeclaredMethod("releaseActive",stale.javaClass,Long::class.javaPrimitiveType).apply { isAccessible=true }
            release.invoke(r.seq,stale,0L)
            assertSame(active,owners(r.seq).getValue("4:60"))
            assertFalse(active.javaClass.declaredFields.any { it.name.contains("generation",true) })
            // Injection demonstrates API identity gap, not a proven stale
            // coroutine delivery/race in a real stop/restart session.
            r.once(section("SYNTHETIC_OLD_OFF_INJECTION",listOf(off(0))),11)
            assertTrue(owners(r.seq).isEmpty())
            assertBoth(r,listOf("ON 11 60","ALL_OFF","ON 11 60","OFF 11 60"))
            println(r.trace("G_SYNTHETIC_GENERATION_GAP_ACTUAL_RACE_UNKNOWN"))
        }
    }

    @Test fun H_sameTickOnFirstOrderingIsExistingF02NotANewF03Fix() {
        Run().use { r ->
            r.once(section("SYNTHETIC_H",listOf(on(0),off(1),on(1),off(2))))
            assertBoth(r,listOf("ON 11 60","OFF 11 60","ON 11 60","OFF 11 60"))
            assertTrue(r.lifecycle().last().startsWith("OFF_NO_ACTIVE_LEDGER"))
            println(r.trace("H_SYNTHETIC_F02_F03_INTERACTION"))
        }
    }

    @Test fun I_outputOffUsesOwnerDestinationEvenWhenIncomingOffPolicyRemapsChannel() {
        Run().use { r ->
            r.once(section("SYNTHETIC_I",listOf(on()),listOf(policy(dst=11))))
            r.once(section("SYNTHETIC_I_OTHER_POLICY",listOf(off(0)),listOf(policy(dst=13))),1)
            assertBoth(r,listOf("ON 11 60","OFF 11 60"));assertTrue(owners(r.seq).isEmpty())
        }
    }

    @Test fun all523RealReducedWindowsUseActualPolicyDecoderTransformerSchedulerAndBothDispatches() {
        val text=javaClass.getResource("/sff5/overlap_cases.tsv")!!.readText()
        val digest=javaClass.getResource("/sff5/overlap_cases.sha256")!!.readText().trim()
        assertEquals(digest,sha(text))
        val repo=mock(StyleRepository::class.java,CALLS_REAL_METHODS)
        val decode=StyleRepository::class.java.getDeclaredMethod("parseCasmPolicies",String::class.java).apply { isAccessible=true }
        val eventDecode=StyleRepository::class.java.getDeclaredMethod("decodePackedEvents",IntArray::class.java).apply { isAccessible=true }
        val traces=mutableListOf<String>();val styles=mutableSetOf<String>();var total=0
        for(block in text.split("END\n").filter { it.isNotBlank() }) {
            val rows=block.trim().lines();val head=rows.first().split('\t');styles.add(head[2]);total++
            @Suppress("UNCHECKED_CAST")
            val policies=decode.invoke(repo,rows.filter { it.startsWith("POLICY\t") }.joinToString(";") {
                val values=it.substringAfter('\t').split(' ').toMutableList()
                values[2]=String(values[2].chunked(2).map { byte->byte.toInt(16).toByte() }.toByteArray(),Charsets.UTF_8)
                values.joinToString("|")
            }) as List<CasmPolicyModel>
            assertEquals(rows.count { it.startsWith("POLICY\t") },policies.size)
            val eventRows=rows.filter { it.startsWith("EVENT\t") }.map { it.split('\t') }
            val packed=eventRows.flatMap { a->listOf(a[3].toInt(),a[4].toInt(),a[6].toInt(),a[7].toInt(),0,0) }.toIntArray()
            @Suppress("UNCHECKED_CAST")
            val events=eventDecode.invoke(repo,packed) as List<StyleNoteEvent>
            assertEquals(eventRows.size,events.size)
            for((i,e) in events.withIndex())assertEquals(eventRows[i][5].toInt(),e.channel)
            assertTrue(events.isNotEmpty())
            for(root in listOf(0,5))Run().use { r ->
                r.seq.currentChord=DetectedChord(root,root,ChordQuality.MAJOR)
                r.once(section(head[3],events,policies,head[6].toInt()))
                assertEquals("both boundaries ${head[2]}/${head[3]}",r.calls(),r.calls(r.midi))
                traces.add(r.trace("${head[1]} ${head[2]}/${head[3]} root=$root"))
            }
        }
        assertEquals(523,total);assertEquals(47,styles.size);assertEquals(1046,traces.size)
        val observed=traces.joinToString("")
        System.getProperty("sff5.output")?.let { File(it).writeText(observed) }
        val f04Candidate=StyleSequencer::class.java.declaredMethods.any { it.name=="hasOnlyMelodicDeclarations" }
        val expected=javaClass.getResource(if(f04Candidate) "/f04/overlap_candidate.sha256" else "/sff5/overlap_dispatch.sha256")
        if(System.getProperty("sff5.record")=="true") {
            assertNull("initial evidence recording cannot overwrite reference",expected)
        } else assertEquals("unchanged baseline dispatch/lifecycle, NOT desired Yamaha output",expected!!.readText().trim(),sha(observed))
        System.getProperty("sff5.output")?.let { File(it).writeText(observed) }
        println("S5_REAL_WINDOWS cases=523 styles=47 roots=2 traces=1046 digest=${sha(observed)} nativeAcceptance=NOT_MEASURED PCM=NOT_MEASURED")
    }
}

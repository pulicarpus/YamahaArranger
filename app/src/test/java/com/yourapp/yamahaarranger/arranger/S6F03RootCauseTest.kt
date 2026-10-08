package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.*
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine

/** Diagnostic-only: run unchanged production, assert current counterexamples.
 * Event IDs/generations are fixture-side labels, never injected into playback.
 * Explicit OFF attribution below is a synthetic fixture contract, not Yamaha.
 */
class S6F03RootCauseTest {
    private fun field(x:Any,n:String)=x.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun on(t:Int,v:Int=100)=StyleNoteEvent(t,true,60,v,4)
    private fun off(t:Int)=StyleNoteEvent(t,false,60,0,4)
    private fun section(n:String,d:Int,events:List<StyleNoteEvent>):StyleSectionModel {
        val p=CasmPolicyModel(4,d,"Piano",0,0,0,0,11,0,127,1,false,-1)
        return StyleSectionModel(n,4,listOf(StylePartModel("src4",events,p,listOf(p))))
    }
    private inner class Run:AutoCloseable {
        val audio=mock(AudioEngineManager::class.java)
        val midi=mock(MidiInputManager::class.java)
        val scope=CoroutineScope(SupervisorJob())
        val seq=StyleSequencer(audio,midi,scope)
        init { seq.armChordDiagnostic();seq.currentChord=DetectedChord(0,0,ChordQuality.MAJOR) }
        @Suppress("UNCHECKED_CAST")
        fun owner()=(field(seq,"activeTransposedNotes").get(seq) as Map<String,Any>)["4:60"]
        fun snapshot():String {
            val o=owner() ?: return "EMPTY"
            return listOf("sourceSection","sourceTick","destinationChannel","outputNote").joinToString(" ") { "$it=${field(o,it).get(o)}" }
        }
        fun once(s:StyleSectionModel,start:Long) {
            field(seq,"masterClockStartedAtNanos").setLong(seq,0L)
            val m=StyleSequencer::class.java.getDeclaredMethod("playOnce",StyleSectionModel::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Continuation::class.java).apply { isAccessible=true }
            runBlocking { suspendCoroutine<Any> { c ->
                val result=m.invoke(seq,s,1_000_000_000,start,0L,c)
                if(result!==COROUTINE_SUSPENDED)c.resumeWith(Result.success(result))
            } }
        }
        fun calls(boundary:Any=audio)=mockingDetails(boundary).invocations.sortedBy { it.sequenceNumber }.mapNotNull {
            val a=it.arguments
            when(it.method.name) {
                "noteOnStyleChannel","noteOnChannel","sendNoteOn" -> "ON ${a[0]} ${a[1]}"
                "noteOffStyleChannel","noteOffChannel","sendNoteOff" -> "OFF ${a[0]} ${a[1]}"
                "allNotesOff" -> "ALL_OFF"
                else -> null
            }
        }
        fun both(expected:List<String>) { assertEquals(expected,calls());assertEquals(expected,calls(midi)) }
        override fun close(){ scope.cancel() }
    }

    @Test fun naturalChain_oldSectionOffRemovesNewSectionOwnerWithDifferentDestination() {
        Run().use { r ->
            // g0 E0 ON MainD tick0; g1 E1 ON FillBB tick4. E2 OFF at5
            // is explicitly attributed by this synthetic fixture to E0.
            r.once(section("MainD",11,listOf(on(0))),0)
            val old=r.owner()!!
            assertEquals("sourceSection=MainD sourceTick=0 destinationChannel=11 outputNote=60",r.snapshot())
            r.once(section("FillBB",12,listOf(on(0))),4)
            assertNotSame(old,r.owner())
            assertEquals("sourceSection=FillBB sourceTick=4 destinationChannel=12 outputNote=60",r.snapshot())
            // Same CASM selector still maps this MainD event to dst11;
            // scheduled OFF ignores that destination and removes dst12 owner.
            r.once(section("MainD",11,listOf(off(1))),4)
            assertEquals("EMPTY",r.snapshot())
            r.both(listOf("ON 11 60","OFF 11 60","ON 12 60","OFF 12 60"))
            r.once(section("FillAA",13,listOf(on(0),off(2))),8)
            r.once(section("MainA",14,listOf(on(0),off(2))),12)
            r.both(listOf("ON 11 60","OFF 11 60","ON 12 60","OFF 12 60","ON 13 60","OFF 13 60","ON 14 60","OFF 14 60"))
            println("S6 NATURAL_WITH_EXPLICIT_OLD_OFF_INJECTION E0=g0/MainD/t0/d11/p60 E1=g1/FillBB/t4/d12/p60 E2=g0/MainD/OFF/t5 actualRelease=g1/d12/p60 final=${r.snapshot()} spontaneousLateDelivery=UNKNOWN")
        }
    }

    @Test fun naturalBoundaryCarryIsNotAutomaticallyAPlaybackBug() {
        Run().use { r ->
            r.once(section("MainD",11,listOf(on(0))),0)
            val held=r.owner()
            r.once(section("FillBB",12,emptyList()),4)
            assertSame(held,r.owner())
            // Fixture explicitly intends a cross-section release of E0.
            r.once(section("FillAA",13,listOf(off(0))),8)
            r.both(listOf("ON 11 60","OFF 11 60"));assertNull(r.owner())
            println("S6 NATURAL_INTENDED_CARRY g0/MainD/ON/t0 releasedBy=g2/FillAA/OFF/t8 dst11/p60 EXPECTED_BY_SYNTHETIC_CONTRACT")
        }
    }

    private fun interrupted(names:List<String>) {
        Run().use { r ->
            val done=CountDownLatch(1);val visited=mutableListOf<String>()
            val queued=names.drop(1).mapIndexed { i,n -> section(n,12+i,listOf(on(0),on(1,80),off(2),off(3))) }
            doAnswer {
                if(it.arguments[0]==11) {
                    val pc=Class.forName(StyleSequencer::class.java.name+"\$PendingSection").declaredConstructors.single { c->c.parameterCount==5 }.apply { isAccessible=true }
                    val queue=queued.map { s ->
                        val complete:(()->Unit)?=if(s==queued.last())({done.countDown()})else null
                        val started:()->Unit={visited.add(s.name)}
                        pc.newInstance(s,1_000_000_000,1,complete,started)
                    }
                    val tc=Class.forName(StyleSequencer::class.java.name+"\$PendingTransition").declaredConstructors.single { c->c.parameterCount==2 }.apply { isAccessible=true }
                    field(r.seq,"pendingTransition").set(r.seq,tc.newInstance(queue,1L))
                }
                null
            }.`when`(r.audio).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
            r.seq.playSeamless(section(names.first(),11,listOf(on(0),off(3))),1_000_000_000,1)
            assertTrue("production coroutine completes",done.await(20,TimeUnit.SECONDS))
            runBlocking { r.scope.coroutineContext[Job]!!.cancelAndJoin() }
            assertEquals(names.drop(1),visited)
            r.both(listOf("ON 11 60","OFF 11 60")+queued.indices.flatMap { i->listOf("ON ${12+i} 60","OFF ${12+i} 60","ON ${12+i} 60","OFF ${12+i} 60") })
            val actualOnTicks=mockingDetails(r.audio).invocations.sortedBy { it.sequenceNumber }
                .filter { it.method.name=="noteOnStyleChannel" }.map { it.arguments[6] as Long }
            assertEquals(listOf(0L)+queued.indices.flatMap { i->listOf(1L+4*i,2L+4*i) },actualOnTicks)
            assertNull(r.owner())
            println("S6 INTERRUPTED ${names.joinToString("->")} boundary=t1 absoluteOnTicks=$actualOnTicks outgoingRelease=EXPECTED innerOverlap=REPLACEMENT firstOFF=NEW_OWNER lastOFF=ORPHAN AUDIO=${r.calls()} MIDI=${r.calls(r.midi)}")
        }
    }
    @Test fun interruptedMainDFillBFillAMainASeparatesCleanupFromInnerOverlap() {
        interrupted(listOf("MainD","FillBB","FillAA","MainA"))
    }
    @Test fun interruptedMainCFillDMainBIsIndependentVariation() {
        interrupted(listOf("MainC","FillDD","MainB"))
    }
}

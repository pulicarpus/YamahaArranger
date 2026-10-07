package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sun.misc.Unsafe

/**
 * S1 captures current defects, NOT desired Yamaha semantics. Executes unchanged
 * repository decoders, StyleSequencer and CasmNoteTransformer. Native fixtures
 * must be regenerated/verified by audit_sff1_corpus.py against the real corpus.
 * Audio/MIDI boundaries are mocks: dispatch != native acceptance != PCM.
 */
class Sff1ProductionPipelineRegressionTest {
    private data class Call(val order: Int, val boundary: String, val name: String, val args: List<Any?>)
    private class Run : AutoCloseable {
        val audio = mock(AudioEngineManager::class.java)
        val midi = mock(MidiInputManager::class.java)
        private val scope = CoroutineScope(SupervisorJob())
        val seq = StyleSequencer(audio, midi, scope)
        fun play(section: StyleSectionModel, chord: DetectedChord? = DetectedChord(0,0,ChordQuality.MAJOR), diagnostic:Boolean=false) {
            if(diagnostic) {
                // Only the existing observer's clock is fixed; scheduler clock,
                // comparator, owner lifecycle and synth args remain production.
                StyleSequencer::class.java.getDeclaredField("chordTrace").apply { isAccessible=true }
                    .set(seq,ChordChangeDiagnostic { 0L })
                seq.armChordDiagnostic()
            }
            seq.currentChord = chord
            seq.setVoiceMap(emptyMap())
            val done = CountDownLatch(1)
            seq.playSeamless(section, 1_000_000_000, 1) { done.countDown() }
            assertTrue("production scheduler timeout ${section.name}", done.await(20,TimeUnit.SECONDS))
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        fun calls(): List<Call> = listOf("AUDIO" to audio, "MIDI" to midi).flatMap { (boundary,mock) ->
            mockingDetails(mock).invocations.map { Call(it.sequenceNumber,boundary,it.method.name,it.arguments.toList()) }
        }.sortedBy { it.order }
        override fun close() { scope.cancel() }
    }
    private fun on(tick:Int,key:Int=60,src:Int=4) = StyleNoteEvent(tick,true,key,100,src)
    private fun off(tick:Int,key:Int=60,src:Int=4) = StyleNoteEvent(tick,false,key,0,src)
    private fun section(events:List<StyleNoteEvent>,dst:Int=11,withPolicy:Boolean=true):StyleSectionModel {
        val policy = CasmPolicyModel(4,dst,"Piano",0,0,0,0,11,0,127,1,false,-1)
        return StyleSectionModel("S1Synthetic",5,listOf(StylePartModel("src4",events,
            if(withPolicy)policy else null,if(withPolicy)listOf(policy) else emptyList(),0)))
    }
    private fun functional(calls:List<Call>,boundary:String):List<String> = calls.filter { it.boundary==boundary }.mapNotNull { call ->
        val a=call.args
        when(call.name) {
            "noteOnStyleChannel","noteOnChannel","sendNoteOn" -> "ON ${a[0]} ${a[1]}"
            "noteOffStyleChannel","noteOffChannel","sendNoteOff" -> "OFF ${a[0]} ${a[1]}"
            "setChannelProgram","sendProgramChange" -> "PC ${a[0]} ${a[1]}"
            else -> null
        }
    }
    private fun activeOwners(seq:StyleSequencer):Int = StyleSequencer::class.java
        .getDeclaredField("activeTransposedNotes").apply { isAccessible=true }.get(seq).let { (it as Map<*,*>).size }

    @Test fun baselineKnownFailureF02SameTickOffTruncatesNewOwner() {
        Run().use { r ->
            r.play(section(listOf(on(0),off(1),on(1),off(2))),diagnostic=true)
            val expected=listOf("PC 11 0","ON 11 60","OFF 11 60","ON 11 60","OFF 11 60")
            assertEquals("BASELINE_KNOWN_FAILURE F02: review any drift; not a musical oracle",expected,functional(r.calls(),"AUDIO"))
            assertEquals(expected,functional(r.calls(),"MIDI"))
            assertEquals(0,activeOwners(r.seq))
            val ticks=r.calls().filter { it.name=="noteOnStyleChannel" }.map { it.args[6] }
            assertEquals(listOf(0L,1L),ticks)
            val stages=lifecycleDecisions(r.seq)
            assertEquals(listOf("SCHEDULED_REPLACE_OFF originTick=0","SCHEDULED_OFF originTick=1","OFF_NO_ACTIVE_LEDGER tick=2"),stages)
            println("SFF1 F02 BASELINE_KNOWN_FAILURE exactLifecycle=$stages")
            observe("F02_same_tick_off","BASELINE_KNOWN_FAILURE",r.calls(),stages)
        }
    }
    @Test fun baselineKnownFailureF02ProgramFollowsSameTickAttack() {
        Run().use { r ->
            r.play(section(listOf(StyleNoteEvent(1,false,40,0,4,0xC4),on(1),off(2))))
            val expected=listOf("PC 11 0","ON 11 60","PC 11 40","OFF 11 60")
            assertEquals("BASELINE_KNOWN_FAILURE F02 PC after attack",expected,functional(r.calls(),"AUDIO"))
            assertEquals(expected,functional(r.calls(),"MIDI"))
            println("SFF1 F02 BASELINE_KNOWN_FAILURE programOrder=${functional(r.calls(),"AUDIO")}")
            observe("F02_same_tick_program","BASELINE_KNOWN_FAILURE",r.calls())
        }
    }
    @Test fun baselineKnownFailureF03OverlapReplacesFirstAndLosesFinalOff() {
        Run().use { r ->
            r.play(section(listOf(on(0),on(1),off(2),off(3))),diagnostic=true)
            val expected=listOf("PC 11 0","ON 11 60","OFF 11 60","ON 11 60","OFF 11 60")
            assertEquals("BASELINE_KNOWN_FAILURE F03 single source:key slot",expected,functional(r.calls(),"AUDIO"))
            assertEquals(expected,functional(r.calls(),"MIDI"))
            assertEquals(0,activeOwners(r.seq))
            val stages=lifecycleDecisions(r.seq)
            assertEquals(listOf("SCHEDULED_REPLACE_OFF originTick=0","SCHEDULED_OFF originTick=1","OFF_NO_ACTIVE_LEDGER tick=3"),stages)
            println("SFF1 F03 BASELINE_KNOWN_FAILURE exactLifecycle=$stages")
            observe("F03_overlap","BASELINE_KNOWN_FAILURE",r.calls(),stages)
        }
    }
    @Test fun baselineKnownFailureF05RhythmAndNoPolicyOffAreNotDispatched() {
        Run().use { r ->
            r.play(section(listOf(on(0,42),off(1,42)),dst=9))
            assertEquals(listOf("PC 9 0","ON 9 42"),functional(r.calls(),"AUDIO"))
            assertEquals(listOf("PC 9 0","ON 9 42"),functional(r.calls(),"MIDI"))
            assertEquals(0,activeOwners(r.seq))
            observe("F05_rhythm_off","BASELINE_KNOWN_FAILURE",r.calls())
        }
        Run().use { r ->
            // A fallback is admitted in the no-chord state, but has no owner.
            r.play(section(listOf(on(0),off(1)),withPolicy=false),chord=null)
            assertEquals(listOf("ON 4 60"),functional(r.calls(),"AUDIO"))
            assertEquals(listOf("ON 4 60"),functional(r.calls(),"MIDI"))
            observe("F05_no_policy_off","BASELINE_KNOWN_FAILURE",r.calls())
        }
    }
    @Test fun baselineKnownFailureF06NonNoteDispatchIsPartial() {
        Run().use { r ->
            r.play(section(listOf(StyleNoteEvent(1,false,0,64,4,0xE4),
                StyleNoteEvent(1,false,7,80,4,0xB4),on(2),off(3))))
            val mixes=r.calls().filter { it.name=="setChannelMixer" }
            assertEquals(listOf(127,80),mixes.map { it.args[1] })
            assertEquals(listOf("PC 11 0","ON 11 60","OFF 11 60"),functional(r.calls(),"MIDI"))
            assertTrue(r.calls().none { it.name.contains("bend",ignoreCase=true)||it.name.contains("control",ignoreCase=true) })
            observe("F06_non_note","BASELINE_KNOWN_FAILURE",r.calls())
        }
    }

    private data class NativeFixture(val path:String,val fileSha:String,val nativeSectionSha:String,
                                     val section:StyleSectionModel,val origins:Map<Pair<Int,Int>,Pair<Int,String>>)
    private val fixtures by lazy { nativeFixtures() }

    @Test fun productionParserRepositorySequencerGoldenMainDCMajorCaptures() {
        for(f in fixtures) Run().use { r ->
            r.play(f.section)
            verifyGolden(f,r,"C_MAJOR")
        }
    }
    @Test fun productionGoldenFMajorCapturesRemainIndependentOfCMajorPitches() {
        for(f in fixtures) Run().use { r ->
            r.play(f.section,DetectedChord(5,5,ChordQuality.MAJOR))
            verifyGolden(f,r,"F_MAJOR")
        }
    }
    private fun verifyGolden(f:NativeFixture,r:Run,chord:String) {
        val expected=GOLDEN.getValue(f.path)
        val calls=r.calls()
        val on=calls.filter { it.name=="noteOnStyleChannel" }
        val actual=on.groupingBy { it.args[0] as Int }.eachCount().toSortedMap()
        val raw=f.section.parts.sumOf { p->p.events.count { it.isNoteOn } }
        assertEquals(f.path+" raw ON",expected.first,raw)
        assertEquals(f.path+" dispatched ON, not eligible/native/PCM count",expected.second,actual)
        assertEquals(actual.values.sum(),calls.count { it.name=="sendNoteOn" })
        assertEquals(f.section.parts.sumOf { it.events.size },f.origins.size)
        assertTrue(f.origins.values.all { it.first>=0 && it.second in listOf("AUTHORED_SECTION_EVENT","COPIED_PRE_SECTION_CHANNEL_SETUP") })
        if(f.path.contains("LoveSong3")) {
            val density=f.section.parts.flatMap { it.events }.filter { it.isNoteOn }.groupingBy { it.channel }.eachCount()
            assertEquals(mapOf(2 to 19,3 to 70,4 to 1,5 to 192,6 to 19,8 to 117,9 to 80,10 to 3,12 to 2),density)
            assertEquals(192,on.count { it.args[3]==5 && it.args[0]==12 })
            // Difference20 is gate-selected alternatives, not20 audible missing notes.
        }
        if(f.path.contains("8BeatPiano1")) assertEquals(setOf(9,10,11,12),actual.keys)
        if(f.path.contains("BaroqueAir1")||f.path.contains("Unplugged2")) {
            val declared=f.section.parts.associate { p -> p.casmPolicies.first().sourceChannel to p.casmPolicies.map { it.destinationChannel }.toSet() }
            val phantom=on.filter { it.args[0] in listOf(8,9) && it.args[0] !in declared.getValue(it.args[3] as Int) }
                .groupingBy { (it.args[3] as Int) to (it.args[0] as Int) }.eachCount()
            val expectedPhantom=if(f.path.contains("BaroqueAir1")) mapOf((8 to 8) to 36,(9 to 9) to 8)
                else mapOf((8 to 8) to 128,(9 to 9) to 128)
            assertEquals("BASELINE_KNOWN_FAILURE F04 declared vs dispatched destination",expectedPhantom,phantom)
            println("SFF1 F04 BASELINE_KNOWN_FAILURE ${f.path} $chord phantom=$phantom")
        }
        val label="GOLDEN_${f.path}_$chord"
        observe(label,"BASELINE_OBSERVATION_NOT_MUSICAL_ORACLE",calls)
        println("SFF1 GOLDEN ${f.path} $chord raw=$raw dispatched=${on.size} byDestination=$actual nativeAcceptance=NOT_MEASURED PCM=NOT_MEASURED")
    }

    /** Call actual private repository decoding/setup functions without JNI load.
     * Unsafe allocates only the repository shell; loadStyle/bridge are NOT called.
     * This avoids adding test seams or touching production constructors/playback.
     */
    private fun nativeFixtures():List<NativeFixture> {
        val unsafe=Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null) as Unsafe
        val repository=unsafe.allocateInstance(StyleRepository::class.java)
        val decode=StyleRepository::class.java.getDeclaredMethod("decodePackedEvents",IntArray::class.java).apply { isAccessible=true }
        val policyDecode=StyleRepository::class.java.getDeclaredMethod("parseCasmPolicies",String::class.java).apply { isAccessible=true }
        val setup=StyleRepository::class.java.getDeclaredMethod("extractVoiceSetup",List::class.java).apply { isAccessible=true }
        val result=mutableListOf<NativeFixture>()
        var path="";var fileSha="";var nativeSha="";var name="";var length=0
        var partIndex=-1;var partName="";var packed=mutableListOf<Int>();var policyStrings=mutableListOf<String>()
        var parts=mutableListOf<StylePartModel>();var origins=mutableMapOf<Pair<Int,Int>,Pair<Int,String>>()
        fun part() {
            if(partIndex<0)return
            @Suppress("UNCHECKED_CAST") val events=decode.invoke(repository,packed.toIntArray()) as List<StyleNoteEvent>
            @Suppress("UNCHECKED_CAST") val policies=policyDecode.invoke(repository,policyStrings.joinToString(";")) as List<CasmPolicyModel>
            val state=setup.invoke(repository,events)
            fun field(key:String)=state.javaClass.getDeclaredMethod("get$key").apply { isAccessible=true }.invoke(state) as Int
            parts+=StylePartModel(partName,events,policies.firstOrNull(),policies,field("Program"),field("BankMsb"),field("BankLsb"),
                field("Volume"),field("Pan"),field("Expression"),field("ReverbSend"),field("ChorusSend"))
            partIndex=-1;packed=mutableListOf();policyStrings=mutableListOf()
        }
        fun style() {
            part()
            if(path.isNotEmpty()) result+=NativeFixture(path,fileSha,nativeSha,StyleSectionModel(name,length,parts.toList()),origins.toMap())
            parts=mutableListOf();origins=mutableMapOf()
        }
        val stream=javaClass.getResourceAsStream("/sff1_main_d_native.tsv") ?: error("run audit_sff1_corpus.py to create/verify native golden fixture")
        stream.bufferedReader().useLines { lines -> lines.forEach { line ->
            val fields=line.split('\t');fun n(index:Int)=fields[index].toInt()
            when(fields[0]) {
                "STYLE"->{style();path=fields[1];fileSha=fields[2];nativeSha=fields[3]}
                "HEADER"->{assertEquals(0,n(1));assertEquals(1,n(2));assertEquals(1920,n(3))}
                "SECTION"->{name=fields[1];length=n(2);assertEquals("MainD",name)}
                "PART"->{part();partIndex=n(1);partName=String(unhex(fields[3]),Charsets.UTF_8);assertEquals(parts.size,partIndex)}
                "POLICY"->{assertEquals(policyStrings.size,n(1));val values=fields.drop(2).toMutableList();values[2]=String(unhex(values[2]),Charsets.UTF_8);policyStrings+=values.joinToString("|")}
                "EVENT"->{assertTrue(partIndex>=0);val payload=unhex(fields[8]);packed.addAll(listOf(n(2),n(3),n(5),n(6),n(7),payload.size));packed.addAll(payload.map { it.toInt() and 255 })}
                "ORIGIN"->{val key=n(1) to n(2);assertFalse(origins.containsKey(key));origins[key]=n(3) to fields[4]}
                else->error("unknown fixture row ${fields[0]}")
            }
        } };style()
        assertEquals(GOLDEN.keys,result.map { it.path }.toSet())
        return result
    }
    private fun unhex(value:String)=value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(value:ByteArray)=value.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun lifecycleDecisions(seq:StyleSequencer):List<String> = seq.chordDiagnosticReport().lines().mapNotNull { row ->
        val stage=Regex("\\bstage=([A-Z_]+)").find(row)?.groupValues?.get(1)
        if(stage !in listOf("SCHEDULED_REPLACE_OFF","SCHEDULED_OFF","OFF_NO_ACTIVE_LEDGER")) return@mapNotNull null
        val key=if(stage=="OFF_NO_ACTIVE_LEDGER") "tick" else "originTick"
        val value=Regex("\\b$key=([0-9]+)").find(row)?.groupValues?.get(1) ?: error("missing lifecycle provenance $row")
        "$stage $key=$value"
    }

    /** Stable musical API arguments only. Excludes diagnostic id/sample flags,
     * global Mockito sequence numbers, wall clock and coroutine wait latency.
     */
    private fun canonicalCalls(calls:List<Call>):String = calls.mapNotNull { call ->
        val args=when(call.name) {
            "noteOnStyleChannel"->call.args.take(7)+call.args[10]
            "noteOffStyleChannel"->call.args.take(6)+call.args[8]
            "noteOnChannel","noteOffChannel","sendNoteOn","sendNoteOff","setChannelProgram","sendProgramChange","setChannelMixer","setChannelExpression","allNotesOff"->call.args
            else->return@mapNotNull null
        }
        val encoded=args.map { value -> when(value) {
            null->"null"
            is Float->"floatBits:${java.lang.Float.floatToRawIntBits(value)}"
            is String->"utf8:${hex(value.toByteArray(Charsets.UTF_8))}"
            else->value.toString()
        } }
        (listOf(call.boundary,call.name)+encoded).joinToString("\t")
    }.joinToString("\n",postfix="\n")
    private fun observe(label:String,status:String,calls:List<Call>,decisions:List<String> = emptyList()) {
        val canonical=canonicalCalls(calls)+decisions.joinToString("\n",postfix=if(decisions.isEmpty()) "" else "\n") { "DIAGNOSTIC\t$it" }
        val sha=hex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        val row=listOf(label,status,sha,canonical.lineSequence().count { it.isNotEmpty() }.toString()).joinToString("\t")
        synchronized(OBSERVATIONS) {
            assertFalse("duplicate observation label",OBSERVATIONS.containsKey(label))
            OBSERVATIONS[label]=row
            val output=File(System.getProperty("sff1.output","build/sff1_pipeline_observed.tsv"))
            output.parentFile.mkdirs();output.writeText(OBSERVATIONS.toSortedMap().values.joinToString("\n",postfix="\n"))
        }
        val expected=javaClass.getResourceAsStream("/sff1_pipeline_digests.tsv")
        if(System.getProperty("sff1.record.observations")!="true") {
            assertNotNull("missing digest fixture; explicitly record initial S1 observation only",expected)
            val rows=expected!!.bufferedReader().use { it.readLines() }.associateBy { it.substringBefore('\t') }
            assertEquals("baseline transcript drift $label; review before changing expected (not a musical oracle)",rows[label],row)
        } else {
            expected?.close()
            println("SFF1 OBSERVATION_RECORDING_NOT_REGRESSION_PASS $row")
        }
        println("SFF1 CAPTURE $row")
    }
    companion object {
        private val OBSERVATIONS=mutableMapOf<String,String>()
        private val GOLDEN=mapOf(
            "Ballad/LoveSong3.S687.prs" to (503 to mapOf(8 to 117,9 to 80,10 to 19,11 to 70,12 to 192,13 to 3,14 to 2)),
            "Latin/Forro.S729.prs" to (357 to mapOf(8 to 105,9 to 50,10 to 9,11 to 72,12 to 64,14 to 21)),
            "Ballad/PopWaltz2.S662.bcs" to (236 to mapOf(8 to 69,9 to 47,10 to 9,11 to 14,12 to 56,13 to 3,14 to 33)),
            "Movie&Show/BaroqueAir1.S145.sst" to (280 to mapOf(8 to 36,9 to 8,10 to 64,11 to 72,12 to 48,15 to 8)),
            "Ballad/8BeatSoft.S686.bcs" to (153 to mapOf(8 to 26,9 to 14,10 to 15,11 to 41,12 to 18,13 to 3,14 to 24)),
            "Pop&Rock/Unplugged2.T151.prs" to (1737 to mapOf(8 to 256,9 to 233,10 to 48,11 to 672,14 to 144)),
            "Ballad/8BeatPiano1.T107.pcs" to (72 to mapOf(9 to 32,10 to 8,11 to 24,12 to 8))
        )
    }
}

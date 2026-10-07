package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.*
import com.yourapp.yamahaarranger.chord.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.security.MessageDigest

/** Executable semantic preservation; all musical expectations remain S1. */
class SffCasmSemanticPreservationTest {
    private val repo=mock(StyleRepository::class.java,CALLS_REAL_METHODS)
    private val decode=StyleRepository::class.java.getDeclaredMethod("parseCasmPolicies",String::class.java).apply { isAccessible=true }
    private val attach=StyleRepository::class.java.getDeclaredMethod("withCasmSemanticMetadata",ParsedStyle::class.java,String::class.java).apply { isAccessible=true }
    private fun resource(name:String)=javaClass.getResource("/sff3/$name.tsv")!!.readText()
    @Suppress("UNCHECKED_CAST")
    private fun policies(raw:String)=decode.invoke(repo,raw) as List<CasmPolicyModel>
    private fun source(protocol:String):ParsedStyle {
        val rows=protocol.lineSequence().filter { it.isNotEmpty() }.map { it.split('\t') }.toList()
        assertEquals(listOf("S3","1","1"),rows.first())
        val sections=linkedMapOf<String,StyleSectionModel>()
        rows.filter { it[0]=="L" }.groupBy { it[1] }.forEach { (name,parts) ->
            sections[name]=StyleSectionModel(name,parts.first()[4].toInt(),parts.map { p ->
                val list=policies(rows.filter { it[0]=="P"&&it[1]==name&&it[2]==p[2] }.joinToString(";") { it[4] })
                StylePartModel("source${p[3]}",emptyList(),list.firstOrNull(),list)
            },StyleDialectIdentity.fromNativeCode(1))
        }
        return ParsedStyle("verified-corpus",1920,sections,dialectIdentity=StyleDialectIdentity.fromNativeCode(1))
    }
    private fun wire(protocol:String)=protocol.lineSequence().filter { it.startsWith("S3\t")||it.startsWith("D\t")||it.startsWith("B\t") }.joinToString("\n",postfix="\n")
    private fun tagged(style:ParsedStyle,raw:String)=attach.invoke(repo,style,raw) as ParsedStyle
    private fun loaded(name:String):ParsedStyle { val p=resource(name);return tagged(source(p),wire(p)) }
    private fun score(policy:CasmPolicyModel,root:Int=0):Int? {
        val m=StyleSequencer::class.java.getDeclaredMethod("policyScore",CasmPolicyModel::class.java,Int::class.javaPrimitiveType,DetectedChord::class.java,Integer::class.java).apply { isAccessible=true }
        val seq=StyleSequencer(mock(com.yourapp.yamahaarranger.audio.AudioEngineManager::class.java),mock(com.yourapp.midi.MidiInputManager::class.java),kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
        return m.invoke(seq,policy,60,DetectedChord(root,root,ChordQuality.MAJOR),policy.sourceChannel) as Int?
    }
    @Test fun everyMatrixPreservesNativeParsedProjectedAndSequencerVisiblePolicyReferences() {
        val dir=System.getProperty("sff3.protocols")
        val index=javaClass.getResource("/sff3/fixture_index.txt")!!.readText().trim().lines()
        val samples=index.map { line ->
            val f=line.split('\t');val text=javaClass.getResource("/sff3/${f[0]}")!!.readText()
            assertEquals(f[1],MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) });text
        }
        val corpus=if(dir==null)samples else File(dir).walkTopDown().filter { it.isFile&&it.extension=="tsv" }.sortedBy { it.path }.map { it.readText() }.toList().also { assertEquals(503,it.size) }
        var count=0
        for(protocol in corpus) {
            val original=source(protocol);val result=tagged(original,wire(protocol));val snapshot=result.casmSemanticSnapshot
            assertEquals(CasmSemanticStatus.PRESERVED,snapshot.status)
            assertEquals(original.dialectIdentity,result.dialectIdentity)
            for(binding in snapshot.bindings) {
                val raw=snapshot.descriptors[binding.descriptorIndex].ctab!!;val policy=binding.effectivePolicy
                assertSame(snapshot.descriptors[binding.descriptorIndex],binding.descriptor)
                assertEquals(raw,binding.descriptor.ctab)
                assertSame(snapshot.descriptors[binding.cnttDescriptorIndex],binding.cnttDescriptor)
                assertEquals(raw.source,policy.sourceChannel);assertEquals(raw.destination,policy.destinationChannel)
                assertEquals(raw.ntr.raw,policy.ntr);assertEquals(raw.rtr.raw,policy.rtr)
                assertEquals(raw.sourceChordRoot,policy.sourceChordRoot);assertEquals(raw.sourceChordType,policy.sourceChordType)
                assertEquals(raw.highKey,policy.highKey);assertEquals(raw.noteLow,policy.noteLimitLow);assertEquals(raw.noteHigh,policy.noteLimitHigh)
                assertEquals(raw.chordFieldHex.toLong(16),policy.chordMuteMask)
                assertSame(original.sections.getValue(binding.section).parts[binding.partIndex].casmPolicies[binding.policyIndex],policy)
                assertSame(result.sections.getValue(binding.section).parts,original.sections.getValue(binding.section).parts)
                assertTrue(result.sections.getValue(binding.section).casmSemanticBindings.contains(binding))
                count++
            }
            assertSame(original.voiceMap,result.voiceMap);assertSame(original.meter,result.meter)
        }
        if(dir!=null)assertEquals(71239,count)
        println("S3_JVM_MATRIX styles=${corpus.size} policyBindings=$count legacyDecoder=true sectionVisible=true playbackObjectsSame=true")
        verifyNoInteractions(repo)
    }
    @Test fun rootCounterexamplesPreserveExactOpaqueWordsAndOffsetsWithoutSelectorChanges() {
        val zero=loaded("CowboyBoogie1.S626.bcs");val descriptor=zero.casmSemanticSnapshot.descriptors.single { it.payloadOffset==18918L }
        assertEquals("033235436c477472200b01000000000000000008000003004a0100",descriptor.rawHex)
        assertEquals(0,descriptor.ctab!!.rootSelectionWordRaw)
        val b=zero.casmSemanticSnapshot.bindings.first { it.descriptorIndex==descriptor.index&&it.section=="IntroC" }
        assertEquals(3,b.effectivePolicy.sourceChannel);assertEquals(11,b.effectivePolicy.destinationChannel)
        assertEquals(0,zero.sections.getValue("IntroC").casmSemanticBindings.first { it.descriptorIndex==descriptor.index }.descriptor.ctab!!.rootSelectionWordRaw)
        val unplugged=loaded("Unplugged2.T151.prs")
        val root3=unplugged.casmSemanticSnapshot.descriptors.single { it.payloadOffset==85411L }
        assertEquals(3,root3.ctab!!.rootSelectionWordRaw)
        val policy=unplugged.casmSemanticSnapshot.bindings.first { it.descriptorIndex==root3.index }.effectivePolicy
        assertEquals(1_010_012,score(policy,0));assertEquals(1_010_012,score(policy,5))
        assertFalse(CasmPolicyModel::class.java.declaredFields.any { it.name.contains("rootSelection",true) })
        val rhumba=loaded("Rhumba.T006.bcs");assertEquals(8,rhumba.casmSemanticSnapshot.descriptors.single { it.payloadOffset==24015L }.ctab!!.rootSelectionWordRaw)
    }
    @Test fun actualCnttOverridesRemainAuthoritativeAndBothRawStatesSurvive() {
        val snapshot=loaded("MovieBallad2.S795.sst").casmSemanticSnapshot
        var changed=false
        for(b in snapshot.bindings) {
            val raw=snapshot.descriptors[b.descriptorIndex].ctab!!
            val override=snapshot.descriptors[b.cnttDescriptorIndex].rawHex
            val byte=override.substring(2,4).toInt(16)
            assertEquals(byte and 127,b.effectivePolicy.ntt);assertEquals(byte and 128 != 0,b.effectivePolicy.bassOn)
            if(raw.nttByte.executionCode!=b.effectivePolicy.ntt)changed=true
        }
        assertTrue("Real corpus Ctab/Cntt disagreement required",changed)
    }
    @Test fun unattachedDescriptorsSurviveWithoutCreatingSourcePartsOrNotes() {
        val protocol=resource("16BeatBallad02.S790.bcs");val original=source(protocol);val result=tagged(original,wire(protocol))
        val s=result.casmSemanticSnapshot
        assertEquals(2,s.descriptors.count { s.descriptorStatus(it.index)==CasmSemanticStatus.PARSED_BUT_UNATTACHED })
        val absent=s.descriptors.single { it.payloadOffset==32934L }
        assertEquals(6,absent.ctab!!.source);assertEquals(12,absent.ctab!!.destination)
        assertEquals(CasmSemanticStatus.PARSED_BUT_UNATTACHED,s.descriptorStatus(absent.index))
        for(name in original.sections.keys)assertSame(original.sections.getValue(name).parts,result.sections.getValue(name).parts)
        assertEquals(original.sections.values.sumOf { it.parts.size },result.sections.values.sumOf { it.parts.size })
    }
    @Test fun unknownMalformedAndUnsupportedMetadataFailClosedWithoutPlaybackMutation() {
        val p=resource("8BeatPiano1.T107.pcs");val original=source(p)
        for(raw in listOf("S3\t1\t0\n", "S3\t1\t1\nQ\tunknown\n", "S3\t1\t1\nD\t0\t0\tCtab\t1\t0\n")) {
            val result=tagged(original,raw)
            assertEquals(CasmSemanticStatus.UNKNOWN,result.casmSemanticSnapshot.status)
            assertEquals(raw,result.casmSemanticSnapshot.rawProtocol)
            for(name in original.sections.keys)assertSame(original.sections.getValue(name).parts,result.sections.getValue(name).parts)
        }
        assertEquals(CasmSemanticStatus.UNKNOWN,tagged(original.copy(dialectIdentity=StyleDialectIdentity()),wire(p)).casmSemanticSnapshot.status)
        assertEquals(CasmSemanticStatus.UNSUPPORTED,CasmSemanticDescriptor(0,0,"Ctb2",0,"0000").status)
        assertNull(CasmSemanticDescriptor(0,0,"Ctb2",0,"0000").ctab)
        assertEquals("UNVERIFIED_NEEDS_FIXTURE",StyleDialectIdentity.SFF2_DETECTION_STATUS)
    }
    @Test fun immutableSnapshotsDoNotLeakAcrossLoads() {
        val p=resource("8BeatPiano1.T107.pcs");val base=source(p)
        val first=tagged(base,wire(p));val unknown=tagged(base,"S3\t1\t0\n");val last=tagged(base,wire(p))
        assertEquals(first.casmSemanticSnapshot,last.casmSemanticSnapshot);assertTrue(unknown.casmSemanticSnapshot.descriptors.isEmpty())
        try { (first.casmSemanticSnapshot.descriptors as MutableList).clear();fail("Mutable descriptor registry") } catch (_: UnsupportedOperationException) {}
        try { (first.casmSemanticSnapshot.bindings as MutableList).clear();fail("Mutable bindings") } catch (_: UnsupportedOperationException) {}
        assertTrue(first.casmSemanticSnapshot.descriptors.isNotEmpty())
    }
    @Test fun rawEnumsRemainDistinctWhileExistingExecutionFallbackCollapseRemainsFrozen() {
        assertNotEquals(CasmRawNtt(6),CasmRawNtt(9));assertFalse(CasmRawNtt(6).hasExplicitExecutionBranch);assertFalse(CasmRawNtt(9).hasExplicitExecutionBranch)
        assertNotEquals(CasmRawNtr(0),CasmRawNtr(128));assertEquals(CasmRawNtr(0).executionCode,CasmRawNtr(128).executionCode)
        assertFalse(CasmRawRtr(127).hasExplicitExecutionBranch)
        val p=CasmPolicyModel(4,11,"raw",0,2,1,0,11,0,127,1,false)
        for(code in listOf(0,6,9))assertEquals(60,CasmNoteTransformer.transform(60,DetectedChord(0,0,ChordQuality.MAJOR),p.copy(ntt=code)))
    }
    @Test fun preservedPolicyCanStillBeFilteredByExistingChordGate() {
        val s=loaded("BaroqueAir1.S145.sst").casmSemanticSnapshot
        val binding=s.bindings.first { it.section=="MainD"&&it.effectivePolicy.sourceChannel==8 }
        assertEquals(12,binding.effectivePolicy.destinationChannel)
        assertNotNull(s.descriptors[binding.descriptorIndex].ctab)
        assertNull(score(binding.effectivePolicy)) // Observe F04; no routing repair.
    }
}

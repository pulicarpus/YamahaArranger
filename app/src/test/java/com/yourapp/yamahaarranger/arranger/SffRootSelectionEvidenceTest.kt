package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.*
import com.yourapp.yamahaarranger.chord.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.security.MessageDigest

/** S4 observations of existing code. No candidate mask is a playback oracle. */
class SffRootSelectionEvidenceTest {
    private val repo=mock(StyleRepository::class.java,CALLS_REAL_METHODS)
    private val decode=StyleRepository::class.java.getDeclaredMethod("parseCasmPolicies",String::class.java).apply { isAccessible=true }
    private val attach=StyleRepository::class.java.getDeclaredMethod("withCasmSemanticMetadata",ParsedStyle::class.java,String::class.java).apply { isAccessible=true }
    private val audio=mock(com.yourapp.yamahaarranger.audio.AudioEngineManager::class.java)
    private val midi=mock(com.yourapp.midi.MidiInputManager::class.java)
    private val seq=StyleSequencer(audio,midi,kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
    private val score=StyleSequencer::class.java.getDeclaredMethod("policyScore",CasmPolicyModel::class.java,Int::class.javaPrimitiveType,DetectedChord::class.java,Integer::class.java).apply { isAccessible=true }
    private val select=StyleSequencer::class.java.getDeclaredMethod("selectPolicy",List::class.java,Int::class.javaPrimitiveType,DetectedChord::class.java,Integer::class.java).apply { isAccessible=true }
    private fun resource(path:String)=javaClass.getResource(path)!!.readText()
    private fun samples()=resource("/sff4/root_records.tsv").trim().lines().map { it.split('\t') }
    @Suppress("UNCHECKED_CAST")
    private fun policies(text:String)=decode.invoke(repo,text) as List<CasmPolicyModel>
    private fun protocols():List<String> {
        val dir=System.getProperty("sff3.protocols")
        if(dir!=null)return File(dir).walkTopDown().filter { it.isFile&&it.extension=="tsv" }.sortedBy { it.path }.map { it.readText() }.toList().also { assertEquals(503,it.size) }
        return listOf(resource("/sff4/PopBallad4.S281.bcs.tsv"))+listOf("CowboyBoogie1.S626.bcs","Rhumba.T006.bcs","Unplugged2.T151.prs").map { resource("/sff3/$it.tsv") }
    }
    private fun tagged(protocol:String):ParsedStyle {
        val rows=protocol.trim().lines().map { it.split('\t') }
        val sections=rows.filter { it[0]=="L" }.groupBy { it[1] }.mapValues { (name,parts) ->
            StyleSectionModel(name,parts.first()[4].toInt(),parts.map { part ->
                val ps=policies(rows.filter { it[0]=="P"&&it[1]==name&&it[2]==part[2] }.joinToString(";") { it[4] })
                StylePartModel("source${part[3]}",emptyList(),ps.firstOrNull(),ps)
            },StyleDialectIdentity.fromNativeCode(1))
        }
        val original=ParsedStyle("original-corpus",1920,sections,dialectIdentity=StyleDialectIdentity.fromNativeCode(1))
        val wire=rows.filter { it[0] in listOf("S3","D","B") }.joinToString("\n",postfix="\n") { it.joinToString("\t") }
        val result=attach.invoke(repo,original,wire) as ParsedStyle
        assertEquals(CasmSemanticStatus.PRESERVED,result.casmSemanticSnapshot.status)
        for(name in sections.keys)assertSame(sections.getValue(name).parts,result.sections.getValue(name).parts)
        return result
    }

    @Test fun all53RawWordsHaveOriginalOffsetsAndRemainOpaqueInS3Descriptor() {
        val rows=samples();assertEquals(53,rows.size);assertEquals(53,rows.map { it[0] }.distinct().size)
        val digest=MessageDigest.getInstance("SHA-256").digest(resource("/sff4/root_records.tsv").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(resource("/sff4/root_records.sha256").trim(),digest)
        for(row in rows) {
            val descriptor=CasmSemanticDescriptor(row[4].toInt(),row[3].toInt(),"Ctab",row[5].toLong(),row[6])
            val raw=descriptor.ctab!!;val policy=policies(row[8]).single()
            assertEquals(row[0].toInt(16),raw.rootSelectionWordRaw)
            assertEquals(row[0].lowercase(),row[6].substring(22,26))
            assertEquals(54,row[6].length);assertTrue(descriptor.payloadOffset>0)
            assertEquals(raw.source,policy.sourceChannel);assertEquals(raw.destination,policy.destinationChannel)
            assertEquals(raw.sourceChordRoot,policy.sourceChordRoot);assertEquals(raw.sourceChordType,policy.sourceChordType)
            assertEquals(raw.ntr.raw,policy.ntr);assertEquals(raw.rtr.raw,policy.rtr)
        }
        assertEquals(0,samples().first { it[0]=="0000" }[0].toInt(16))
        assertFalse(CasmPolicyModel::class.java.declaredFields.any { it.name.contains("rootSelection",true) })
        verifyNoInteractions(audio,midi)
    }

    @Test fun unchangedPolicyScoreIsIndependentOfAll12RootsForEveryCorpusScoreSignature() {
        val text=protocols()
        val observed=text.flatMap { p -> p.lineSequence().filter { it.startsWith("P\t") }.map { policies(it.split('\t')[4]).single() }.toList() }
        val ps=observed+samples().map { policies(it[8]).single() }
        val signatures=ps.distinctBy { listOf(it.sourceChannel,it.sourceChordRoot,it.sourceChordType,it.sourceNoteLow,it.sourceNoteHigh,it.chordMuteMask) }
        var checks=0
        for(policy in signatures)for(quality in ChordQuality.values())for(note in listOf(0,60,127)) {
            val expected=score.invoke(seq,policy,note,DetectedChord(0,0,quality),policy.sourceChannel)
            for(root in 0..11) {
                assertEquals(expected,score.invoke(seq,policy,note,DetectedChord(root,root,quality),policy.sourceChannel))
                checks++
            }
        }
        if(System.getProperty("sff3.protocols")!=null)assertEquals(71239,observed.size)
        println("S4_SCORE_OBSERVATION styles=${text.size} policies=${observed.size} uniqueSignatures=${signatures.size} qualities=${ChordQuality.values().size} roots=12 checks=$checks rootWordConsumed=false YamahaOracle=false")
        verifyNoInteractions(audio,midi)
    }

    @Test fun realCandidateListsRetainRootIndependentSelectionWithoutCreatingAnOracle() {
        val names=listOf("/sff4/PopBallad4.S281.bcs.tsv","/sff3/Unplugged2.T151.prs.tsv","/sff3/Rhumba.T006.bcs.tsv","/sff3/CowboyBoogie1.S626.bcs.tsv")
        var checks=0
        for(path in names) {
            val style=tagged(resource(path))
            for(section in style.sections.values)for(part in section.parts) {
                val ps=part.casmPolicies
                if(ps.isEmpty())continue
                for(quality in ChordQuality.values()) {
                    val expected=select.invoke(seq,ps,60,DetectedChord(0,0,quality),ps.first().sourceChannel)
                    for(root in 0..11) {
                        assertSame(expected,select.invoke(seq,ps,60,DetectedChord(root,root,quality),ps.first().sourceChannel))
                        checks++
                    }
                }
            }
        }
        assertTrue(checks>0)
        println("S4_CANDIDATE_OBSERVATION realStyles=4 checks=$checks legacySelectionUnchanged=true YamahaOracle=false")
        verifyNoInteractions(audio,midi)
    }

    @Test fun PopBallad4AlternatingWordsReachSectionBindingsButNotEffectivePolicy() {
        val style=tagged(resource("/sff4/PopBallad4.S281.bcs.tsv"))
        for((offset,word) in listOf(26470L to 0x0555,26540L to 0x0AAA)) {
            val descriptor=style.casmSemanticSnapshot.descriptors.single { it.payloadOffset==offset }
            assertEquals(word,descriptor.ctab!!.rootSelectionWordRaw)
            val binding=style.sections.getValue("FillBB").casmSemanticBindings.single { it.descriptorIndex==descriptor.index }
            assertSame(descriptor,binding.descriptor)
            assertSame(binding.effectivePolicy,style.sections.getValue("FillBB").parts[binding.partIndex].casmPolicies[binding.policyIndex])
            assertFalse(binding.effectivePolicy.javaClass.declaredFields.any { it.name.contains("rootSelection",true) })
        }
        verifyNoInteractions(audio,midi)
    }

    @Test fun changingOnlyOpaqueRootMetadataCannotChangeLegacyScoringOrDispatch() {
        val style=tagged(resource("/sff3/Unplugged2.T151.prs.tsv"))
        val binding=style.casmSemanticSnapshot.bindings.first { it.descriptor.payloadOffset==85411L }
        val policy=binding.effectivePolicy
        for(word in listOf(0x0000,0x0008,0x0555,0x0AAA,0x0003,0x0FFF)) {
            val raw=binding.descriptor.rawHex
            val descriptor=binding.descriptor.copy(rawHex=raw.take(22)+"%04x".format(word)+raw.drop(26))
            val carrier=binding.copy(descriptor=descriptor)
            assertEquals(word,carrier.descriptor.ctab!!.rootSelectionWordRaw)
            assertSame(policy,carrier.effectivePolicy)
            for(root in 0..11)assertEquals(1_010_012,score.invoke(seq,carrier.effectivePolicy,60,DetectedChord(root,root,ChordQuality.MAJOR),5))
        }
        verifyNoInteractions(audio,midi)
    }
}

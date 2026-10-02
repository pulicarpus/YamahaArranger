package com.yourapp.audio

import org.junit.Assert.*
import org.junit.Test

/** Replays the eight exact audited metadata relations; no SF2 samples, PCM inference or synth. */
class DrumSemanticEvidenceRegistryTest {
    private val p=DrumShadowPlanner
    private val catalog get()=DrumSemanticEvidenceRegistry.bundled()
    private fun text()=javaClass.getResourceAsStream("/drum_shadow_audit770_v1.tsv")!!.bufferedReader().use {it.readText()}
    private fun fonts():List<DrumShadowPlanner.Font> {
        val rows=javaClass.getResourceAsStream("/drum_audit770_zone_rows.tsv")!!.bufferedReader().use {it.readLines()}
        return rows.map {it.split('|',limit=2)}.groupBy {it[0]}.map {(fp,rows)->
            DrumShadowPlanner.Font(fp,"fixture-$fp","opaque-name.sf2",rows.map {p.zoneFromMetadata(fp,it[1])})
        }
    }
    private fun target(id:String)=catalog.targets.single {it.id==id}
    private fun request(t:DrumSemanticEvidenceRegistry.Target,velocity:Int=42)=DrumShadowPlanner.Request(
        "Main","Rhythm",9,9,t.msb,t.lsb,t.rawPc,t.key,null,velocity,10,3,false,true)
    private fun snapshot()=DrumShadowPlanner.Snapshot(listOf(DrumShadowPlanner.Production(9,128,73,null,128,0,true,37)),emptyList(),"37","GEN value=37")
    private fun plan(id:String,velocity:Int=42,reg:DrumSemanticEvidenceRegistry.Registry=catalog,
        fonts:List<DrumShadowPlanner.Font> = fonts(),extra:List<DrumShadowPlanner.Evidence> = emptyList())=
        p.compile(listOf(request(target(id),velocity)),fonts,extra,DrumShadowPlanner.Policy(),snapshot(),reg)
    @Test fun bundledRegistryContainsOnlySixAuditedClaimsAndNoExactOrRuntimeProof() {
        val r=catalog;assertEquals(3,r.targets.size);assertEquals(6,r.evidence.size)
        assertEquals(2,r.evidence.count {it.classification==DrumShadowPlanner.Classification.UNKNOWN})
        assertEquals(4,r.evidence.count {it.classification==DrumShadowPlanner.Classification.COMPATIBLE})
        assertFalse(r.evidence.any {it.classification==DrumShadowPlanner.Classification.EXACT})
        assertTrue(r.evidence.all {!it.proof.pitch && !it.proof.choke && !it.proof.ownership && !it.proof.resourceReady})
        assertTrue(r.evidence.all {it.confidence==0 && it.layerHashes.isNotEmpty() && it.pcmProvenance.contains("wavSHA256=")})
    }
    @Test fun auditedClosedHatPcmAndCoverageNeverPromoteUnprovenEdge() {
        val d=plan("hat-edge").decisions.single()
        assertEquals(DrumShadowPlanner.Classification.UNKNOWN,d.classification);assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action)
        assertNull(d.candidate);assertEquals(2,d.candidates.size)
        assertTrue(d.candidates.all {"AUDITED_LAYER_SIGNATURES_MATCH" in it.passedGates && "UNKNOWN_SEMANTIC_IDENTITY_OR_EVIDENCE" in it.failedGates})
    }
    @Test fun pedalCandidatesAreCrossKeyCompatibleButAmbiguousAndUnsafe() {
        val d=plan("hat-pedal-closed").decisions.single()
        assertEquals(DrumShadowPlanner.Classification.COMPATIBLE,d.classification);assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action);assertNull(d.candidate)
        assertTrue(d.candidates.all {it.evidence.candidate.key!=d.request.sourceKey && it.evidence.candidate.key==44})
        assertTrue(d.candidates.all {"AMBIGUOUS_EVIDENCE_NO_TIE_WINNER" in it.failedGates && "UNSAFE_OR_UNKNOWN_CHOKE_RELATIONSHIPS" in it.failedGates && "UNPROVEN_NOTE_OWNERSHIP" in it.failedGates && "MISSING_OR_NOT_READY_RESOURCE" in it.failedGates})
    }
    @Test fun genericSnareClaimsRemainTwoCompatibleCandidatesWithoutWinner() {
        val d=plan("snare-hit",110).decisions.single()
        assertEquals(setOf(40,91),d.candidates.map {it.evidence.candidate.key}.toSet());assertNull(d.candidate)
        assertEquals(DrumShadowPlanner.Classification.COMPATIBLE,d.classification)
        assertTrue(d.candidates.all {it.proposedAction==DrumShadowPlanner.Action.ABSTAIN});assertTrue("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER" in d.reasons)
    }
    @Test fun matchingNamesAndBanksCannotEscapeFingerprintMismatch() {
        val reg=catalog;val e=reg.evidence.first {it.target!!.id=="hat-pedal-closed"}
        val bad="f".repeat(64)
        val fs=fonts().map {f->if(f.sha256==e.candidate.sha256)f.copy(sha256=bad,zones=f.zones.map {it.copy(fontId=bad)}) else f}
        val d=plan("hat-pedal-closed",reg=reg.copy(evidence=listOf(e)),fonts=fs).decisions.single()
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action);assertTrue("FINGERPRINT_MISMATCH_OR_FONT_ABSENT" in d.reasons)
    }
    @Test fun auditedLayerSignatureAndMultiplicityMustMatch() {
        val reg=catalog;val e=reg.evidence.first {it.target!!.id=="hat-pedal-closed"}
        val fs=fonts().map {f->if(f.sha256==e.candidate.sha256)f.copy(zones=f.zones.map {it.copy(raw=it.raw+" ")}) else f}
        val d=plan("hat-pedal-closed",reg=reg.copy(evidence=listOf(e)),fonts=fs).decisions.single()
        assertTrue("AUDITED_LAYER_MISMATCH" in d.reasons);assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action)
    }
    @Test fun velocityMetadataCoversOtherDemandButIsNotAuditionProof() {
        for(v in listOf(28,35,36)) {
            val d=plan("hat-pedal-closed",v).decisions.single()
            assertTrue(d.candidates.all {"ELIGIBLE_VELOCITY_LAYERS_PRESENT" in it.passedGates && "AUDITED_LAYER_SIGNATURES_MATCH" in it.passedGates})
            assertTrue(d.candidates.all {"REQUEST_VELOCITY_NOT_AUDITIONED" in it.failedGates});assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action)
        }
    }
    @Test fun chokeAndOwnershipVetoEvenHypotheticalOtherProofs() {
        val reg=catalog;val e=reg.evidence.first {it.target!!.id=="hat-pedal-closed"}
        val updated=e.copy(proof=DrumShadowPlanner.Proof(true,false,false,true))
        val d=plan("hat-pedal-closed",reg=reg.copy(evidence=listOf(updated))).decisions.single()
        assertTrue("UNSAFE_OR_UNKNOWN_CHOKE_RELATIONSHIPS" in d.reasons);assertTrue("UNPROVEN_NOTE_OWNERSHIP" in d.reasons)
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action)
    }
    @Test fun unknownEdgeDoesNotBecomeCompatibleEvenWithHypotheticalEngineeringProofs() {
        val reg=catalog;val claims=reg.evidence.filter {it.target!!.id=="hat-edge"}.map {it.copy(proof=DrumShadowPlanner.Proof(true,true,true,true))}
        val d=plan("hat-edge",reg=reg.copy(evidence=claims)).decisions.single()
        assertEquals(DrumShadowPlanner.Classification.UNKNOWN,d.classification);assertEquals(DrumShadowPlanner.Action.ABSTAIN,d.action)
    }
    @Test fun onlyUnambiguousFullyProvenFixtureCanProduceAdvisorySubstitute() {
        val reg=catalog;val e=reg.evidence.first {it.target!!.id=="hat-pedal-closed"}.copy(proof=DrumShadowPlanner.Proof(true,true,true,true))
        val d=plan("hat-pedal-closed",reg=reg.copy(evidence=listOf(e))).decisions.single()
        assertEquals(DrumShadowPlanner.Action.SUBSTITUTE,d.action);assertTrue("PROPOSED_ONLY_NO_DISPATCH_CAPABILITY" in d.reasons)
    }
    @Test fun registryVersionProvenanceAndTargetReferenceInvalidateWorkerCache() {
        val reg=catalog;val reqs=listOf(request(target("hat-pedal-closed")));val fs=fonts();val cache=DrumShadowPlanner.Cache();val pol=DrumShadowPlanner.Policy();val snap=snapshot()
        val a=cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg)
        assertSame(a,cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg))
        assertNotSame(a,cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg.copy(version=reg.version+"-new")))
        assertNotSame(a,cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg.copy(analysisSha256="b".repeat(64))))
        assertNotSame(a,cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg.copy(targets=reg.targets.map {it.copy(referenceVersion="new-ref")})))
        assertNotSame(a,cache.prepare(reqs,fs,emptyList(),pol,snap,registry=reg.copy(evidence=reg.evidence.map {it.copy(provenance="new-review")})))
    }
    @Test fun unreviewedExactTextCannotPromoteReviewedUnknownTarget() {
        val t=target("hat-edge");val e=catalog.evidence.first {it.target!!.id==t.id};val b=e.candidate
        val extra=p.evidence("${t.msb}|${t.lsb}|${t.rawPc}|${t.key}|${b.sha256}|${b.bank}|${b.pc}|${b.key}|EXACT|99|unsupported assertion")
        val d=plan(t.id,extra=extra).decisions.single()
        assertEquals(DrumShadowPlanner.Classification.UNKNOWN,d.classification);assertNull(d.candidate)
        assertTrue(d.candidates.none {it.evidence.classification==DrumShadowPlanner.Classification.EXACT})
        assertTrue(d.candidates.any {"UNREVIEWED_SUPPLEMENTAL_AUTHORITY" in it.failedGates})
    }
    @Test fun genericDataParserHandlesOtherBankProgramAndKeyIdentities() {
        val synthetic=text().replace("|127|0|73|","|121|3|12|")
        val reg=DrumSemanticEvidenceRegistry.parse(synthetic);val t=reg.targets.first();val r=request(t)
        val d=p.compile(listOf(r),fonts(),emptyList(),DrumShadowPlanner.Policy(),snapshot(),reg).decisions.single()
        assertEquals(121,d.request.msb);assertEquals(12,d.request.rawPc);assertEquals(t.label,d.semanticTarget!!.label)
    }
    @Test fun malformedOrAmbiguousRegistryIsRejectedAtomically() {
        val valid=text()
        for(bad in listOf(valid.replace("DRUM_SHADOW_EVIDENCE|2|","DRUM_SHADOW_EVIDENCE|9|"),valid+valid.lineSequence().first {it.startsWith("TARGET|")}+"\n",valid.replace("CANDIDATE|audit770-","BAD_RECORD|audit770-"))) {
            try {DrumSemanticEvidenceRegistry.parse(bad);fail("invalid data must not silently lose evidence")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun compactExportKeepsTargetAndCandidateEvidenceAheadOfUnknownDetail() {
        val reqs=List(1000){request(target("hat-edge")).copy(sourceKey=50,part="unresolved-$it",tick=it)}+
            listOf(request(target("hat-edge")),request(target("hat-pedal-closed")),request(target("snare-hit"),110))
        val reg=catalog;val fs=fonts();val result=p.compile(reqs,fs,emptyList(),DrumShadowPlanner.Policy(),snapshot(),reg)
        val out=p.export(result,fs,snapshot(),reg)
        assertTrue(out.toByteArray().size<=DrumShadowPlanner.EXPORT_BYTES)
        for(t in reg.targets) {assertTrue(out.contains("TARGET_SUMMARY id=${t.id}"));assertTrue(out.contains("target=${t.id} hits="))}
        assertTrue(out.contains("SUMMARY action=ABSTAIN class=COMPATIBLE notes=2"));assertTrue(out.contains("omittedDemandNotes="))
        assertTrue(out.contains("CANDIDATE_EVAL"));assertTrue(out.contains("passedGates="));assertTrue(out.contains("failedGates="));assertTrue(out.contains("LAYER evidence="))
    }
    @Test fun stopSnapshotAndAuditionRemainSeparateFromActualRuntimeIdentity() {
        val reg=catalog;val fs=fonts();val result=plan("snare-hit",110);val out=p.export(result,fs,snapshot(),reg)
        assertTrue(out.contains("ACTUAL_RUNTIME_DISPATCH=UNKNOWN"));assertTrue(out.contains("NOT_ACTUAL_PER_NOTE_PROOF"))
        assertTrue(out.contains("CURRENT_MANAGED_BYTES_PATH_CORRELATION_NOT_LOADED_SAMPLE_PROOF"));assertTrue(out.contains("selectedWinner=NONE"))
    }
}

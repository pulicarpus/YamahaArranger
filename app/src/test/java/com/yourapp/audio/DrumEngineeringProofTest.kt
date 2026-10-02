package com.yourapp.audio

import com.yourapp.yamahaarranger.style.*
import org.junit.Assert.*
import org.junit.Test

class DrumEngineeringProofTest {
    private val p=DrumShadowPlanner
    private val ep=DrumEngineeringProof
    private val reg get()=DrumSemanticEvidenceRegistry.bundled()
    private fun evidence(target:String)=reg.evidence.filter { it.target!!.id==target }
    private fun fonts():List<DrumShadowPlanner.Font> {
        val rows=javaClass.getResourceAsStream("/drum_audit770_zone_rows.tsv")!!.bufferedReader().use { it.readLines() }
        return rows.map { it.split('|',limit=2) }.groupBy { it[0] }.map { (sha,rows) ->
            val zones=rows.map { p.zoneFromMetadata(sha,it[1]) }
            DrumShadowPlanner.Font(sha,"file:///managed/$sha.sf2","opaque.sf2",zones,zones.associate { it.sampleId to DrumShadowPlanner.Sample(it.sampleId,it.original,44100,it.sampleType,it.link,0,100) })
        }
    }
    private fun snapshot(generation:String="37",raw:String="GEN value=$generation\n")=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),generation,raw)
    private fun layers(e:DrumShadowPlanner.Evidence)=fonts().single { it.sha256==e.candidate.sha256 }.zones.filter { it.bank==e.candidate.bank && it.pc==e.candidate.pc && it.eligible(e.candidate.key,42) }
    private fun style(target:String="hat-pedal-closed",velocities:List<Int> = listOf(28,35,36,42)):ParsedStyle {
        val t=evidence(target).first().target!!
        return ParsedStyle("immutable",1920,mapOf("Main" to StyleSectionModel("Main",200,listOf(StylePartModel("Rhythm",
            velocities.flatMapIndexed { i,v -> listOf(StyleNoteEvent(i*20,true,t.key,v,9),StyleNoteEvent(i*20+10,false,t.key,0,9)) },program=t.rawPc,bankMsb=t.msb,bankLsb=t.lsb)))))
    }
    private fun audit(style:ParsedStyle=style(),fs:List<DrumShadowPlanner.Font> = fonts(),before:DrumShadowPlanner.Snapshot=snapshot(),after:DrumShadowPlanner.Snapshot=before):DrumEngineeringProof.Report {
        val plan=p.compile(p.requests(style),fs,emptyList(),DrumShadowPlanner.Policy(),before,reg)
        return ep.audit(plan,fs,before,after,style)
    }
    private fun event(tick:Int,logical:String,physical:String?,on:Boolean,section:String="Main",order:Int=tick)=DrumEngineeringProof.OwnerEvent(section,tick,order,logical,physical,on)
    @Test fun generationMismatchOrHandleChangeFailsClosed() {
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.generation(snapshot(),snapshot("38")).status)
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.generation(snapshot(),snapshot(raw="GEN value=37\nFONT handle=999")).status)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,ep.generation(snapshot("unavailable"),snapshot()).status)
        assertEquals(DrumEngineeringProof.Status.PASS,ep.generation(snapshot(),snapshot()).status)
        assertTrue(audit(after=snapshot("38")).rows.all { it.resourceReady.status==DrumEngineeringProof.Status.FAIL && it.eligibility==DrumEngineeringProof.Eligibility.BLOCKED })
    }
    @Test fun rootOverridesKeepBothSnareCrossKeysAtNativePitch() {
        for(e in evidence("snare-hit")) {
            val result=ep.pitch(e.candidate,layers(e))
            assertEquals(DrumEngineeringProof.Status.PASS,result.gate.status)
            assertTrue(result.layers.all { it.contains("keyTuneOffsetCents=0") })
        }
    }
    @Test fun unoverriddenOriginalKeyCanDisproveNativePitchHypothesis() {
        val e=evidence("snare-hit").first();val z=layers(e).single()
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.pitch(e.candidate,listOf(z.copy(root=null))).gate.status)
    }
    @Test fun scaleFixedKeyAndCoarseFineAreAccountedFor() {
        val e=evidence("snare-hit").first();val z=layers(e).single()
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.pitch(e.candidate,listOf(z.copy(ig=z.ig+mapOf(51 to 1,52 to 3)))).gate.status)
        assertEquals(DrumEngineeringProof.Status.PASS,ep.pitch(e.candidate,listOf(z.copy(root=null,ig=z.ig+mapOf(56 to 0)))).gate.status)
        assertEquals(DrumEngineeringProof.Status.PASS,ep.pitch(e.candidate,listOf(z.copy(root=null,ig=z.ig+mapOf(46 to z.original)))).gate.status)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,ep.pitch(e.candidate,listOf(z.copy(original=255,root=null))).gate.status)
    }
    @Test fun relevantModulatorsAndMissingTablesPreventPitchSafetyClaim() {
        val e=evidence("snare-hit").first();val z=layers(e).single()
        for(raw in listOf(z.raw.replace("mods=none","mods=0:51:0:0:100"),z.raw.replace("modsKnown=true","modsKnown=false")))
            assertEquals(DrumEngineeringProof.Status.UNKNOWN,ep.pitch(e.candidate,listOf(z.copy(raw=raw))).gate.status)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,ep.pitch(e.candidate,listOf(z.copy(ig=z.ig+mapOf(5 to 12)))).gate.status)
    }
    @Test fun headerCorrectionIsNotMistakenForCrossKeyTranspose() {
        val e=evidence("snare-hit").first();val z=layers(e).single().copy(correction=-12)
        val result=ep.pitch(e.candidate,listOf(z));assertEquals(DrumEngineeringProof.Status.PASS,result.gate.status)
        assertTrue(result.layers.single().contains("headerCorrection=-12"));assertTrue(result.gate.detail.contains("productionPitchSafe=UNKNOWN"))
    }
    @Test fun exclusiveClassDoesNotProveCrossFontLaneChoke() {
        val e=evidence("hat-pedal-closed").first();val fs=fonts().single { it.sha256==e.candidate.sha256 }
        val choke=ep.choke(e,layers(e),fs)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,choke.status);assertTrue(choke.detail.contains("exclusiveClasses=[1]"))
        assertTrue(choke.detail.contains("STATIC_EXCLUSIVE_GROUP_PRESENT_NOT_RUNTIME_SAFETY"));assertTrue(choke.detail.contains("family_runtime_colocation=UNKNOWN"))
    }
    @Test fun absentExclusiveClassRemainsUnknownRatherThanUsingShortEnvelopeAsProof() {
        val e=evidence("hat-pedal-closed").last();val result=ep.choke(e,layers(e),fonts().last())
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,result.status);assertTrue(result.detail.contains("NO_EXPLICIT_EXCLUSIVE_CLASS"))
    }
    @Test fun simultaneousManyToOneIsCounterexampleToSimpleRewrite() {
        val r=ep.ownership(listOf(event(0,"a","P",true),event(1,"b","P",true),event(5,"a",null,false),event(6,"b",null,false)),true)
        assertEquals(1,r.overlaps);assertEquals(DrumEngineeringProof.Status.FAIL,r.gate.status);assertEquals(0,r.unmatchedOff)
    }
    @Test fun retriggerRequiresDistinctOwnerTokensEvenWhenAllOffsExist() {
        val r=ep.ownership(listOf(event(0,"a","P",true),event(1,"a","P",true),event(2,"a",null,false),event(3,"a",null,false)),true)
        assertEquals(1,r.retriggers);assertEquals(1,r.overlaps);assertEquals(0,r.unterminated)
        assertEquals(DrumEngineeringProof.Status.FAIL,r.gate.status)
    }
    @Test fun crossKeyBalancedPairingStillNeedsProductionTokenProof() {
        val r=ep.ownership(listOf(event(0,"a","P",true),event(2,"a",null,false)),true)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,r.gate.status);assertTrue(r.gate.detail.contains("tokenRequired=true"))
    }
    @Test fun noteOffUsesCapturedOwnerRatherThanCurrentPresetOrBinding() {
        val r=ep.ownership(listOf(event(0,"a","P-old",true),event(2,"a","P-new",false)),true)
        assertEquals(0,r.unmatchedOff);assertEquals(0,r.unterminated);assertEquals(DrumEngineeringProof.Status.UNKNOWN,r.gate.status)
    }
    @Test fun mainFillBoundariesAreNotFlattenedIntoFalseOwnershipProof() {
        val r=ep.ownership(listOf(event(0,"a","P",true,"Main"),event(1,"a",null,false,"Fill")),true)
        assertEquals(1,r.unterminated);assertEquals(1,r.unmatchedOff);assertEquals(1,r.boundaryOwners)
        assertEquals(DrumEngineeringProof.Status.FAIL,r.gate.status)
    }
    @Test fun sameTickOrderingAndZeroVelocityNoteOffStayOffline() {
        val s=style(velocities=listOf(42));val section=s.sections.getValue("Main");val part=section.parts.single()
        val zeroOff=part.copy(events=part.events.map { if(!it.isNoteOn)it.copy(isNoteOn=true,status=0x99,velocity=0) else it })
        val report=audit(s.copy(sections=mapOf("Main" to section.copy(parts=listOf(zeroOff)))))
        assertTrue(report.rows.all { it.ownershipSafe.detail.contains("unmatchedOff=0;unterminated=0") })
        val events=listOf(event(0,"a",null,false,order=0),event(0,"a","P",true,order=1))
        assertEquals(1,ep.ownership(events,true).unmatchedOff)
    }
    @Test fun sameRegionVelocityInferenceDoesNotInventUnauditionedPcm() {
        val report=audit();assertEquals(2,report.rows.size)
        for(c in report.rows) {
            assertEquals(DrumEngineeringProof.Status.PASS,c.velocityCoverage.status)
            assertTrue(c.velocityCoverage.detail.contains("isolatedPCMObserved=[42]"))
            assertTrue(c.velocityCoverage.detail.contains("sameRegionLayerInference=[28, 35, 36]"))
            assertEquals(DrumEngineeringProof.Eligibility.AMBIGUOUS,c.eligibility)
            assertEquals(DrumShadowPlanner.Classification.COMPATIBLE,c.semantic)
        }
    }
    @Test fun changedLayerOrMissingVelocityRegionFailsInference() {
        val e=evidence("hat-pedal-closed").first();val font=fonts().first();val z=layers(e).single()
        val absent=font.copy(zones=font.zones.map { if(it==z)it.copy(vl=40) else it })
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.velocity(e,setOf(28,42),absent).status)
        val changed=font.copy(zones=font.zones.map { if(it==z)it.copy(raw=it.raw+" ") else it })
        assertEquals(DrumEngineeringProof.Status.FAIL,ep.velocity(e,setOf(28,42),changed).status)
    }
    @Test fun managedPresenceIsNotLoadedFingerprintOrSampleReadiness() {
        val report=audit();assertTrue(report.rows.all { it.resourceReady.status==DrumEngineeringProof.Status.UNKNOWN })
        assertTrue(report.rows.all { it.resourceReady.detail.contains("managedBytes=PASS") && it.resourceReady.detail.contains("loadedFingerprint=UNKNOWN") })
        assertTrue(audit(fs=emptyList()).rows.all { it.resourceReady.status==DrumEngineeringProof.Status.FAIL })
        val noSamples=fonts().map { it.copy(samples=emptyMap()) }
        assertTrue(audit(fs=noSamples).rows.all { it.resourceReady.status==DrumEngineeringProof.Status.FAIL })
    }
    @Test fun noTieWinnerEvenWhenMetadataPitchAndVelocityPass() {
        val report=audit(style("snare-hit",listOf(110)))
        assertEquals(2,report.rows.size);assertTrue(report.rows.all { it.pitchSafe.status==DrumEngineeringProof.Status.PASS })
        assertTrue(report.rows.all { it.ambiguity.detail.contains("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER") && it.eligibility==DrumEngineeringProof.Eligibility.AMBIGUOUS })
    }
    @Test fun edgeArticulationRemainsUnknownAfterStaticEngineeringPasses() {
        val report=audit(style("hat-edge",listOf(42)))
        assertTrue(report.rows.all { it.semantic==DrumShadowPlanner.Classification.UNKNOWN && it.eligibility==DrumEngineeringProof.Eligibility.UNKNOWN })
        assertTrue(report.rows.all { it.pitchSafe.status==DrumEngineeringProof.Status.PASS && it.velocityCoverage.status==DrumEngineeringProof.Status.PASS })
    }
    @Test fun cacheDigestInvalidatesOnGenerationHandleStyleAndFontIdentity() {
        val s=style();val a=audit(s)
        assertEquals(a.cacheDigest,audit(s).cacheDigest)
        assertNotEquals(a.cacheDigest,audit(s,before=snapshot("38")).cacheDigest)
        assertNotEquals(a.cacheDigest,audit(s,before=snapshot(raw="GEN value=37\nFONT handle=77")).cacheDigest)
        assertNotEquals(a.cacheDigest,audit(s.copy(fileName="changed")).cacheDigest)
        assertNotEquals(a.cacheDigest,audit(s,fs=fonts().map { it.copy(identity=it.identity+".replaced") }).cacheDigest)
    }
    @Test fun postAuditGenerationChangeSealsResourceFailureAndInvalidatesDigest() {
        val report=audit();val sealed=ep.seal(report,snapshot(),snapshot("38"))
        assertNotEquals(report.cacheDigest,sealed.cacheDigest)
        assertTrue(sealed.rows.all { it.resourceReady.status==DrumEngineeringProof.Status.FAIL && it.eligibility==DrumEngineeringProof.Eligibility.BLOCKED })
    }
    @Test fun boundedExportKeepsAllSixEngineeringProofsAheadOfRawUnknownDemand() {
        val ss=listOf(style("hat-edge",listOf(42)),style(),style("snare-hit",listOf(110)))
        val combined=ss.first().copy(sections=ss.flatMapIndexed { i,s -> s.sections.map { (k,v)-> "$k-$i" to v.copy(name="$k-$i") } }.toMap())
        val fs=fonts();val snap=snapshot();val plan=p.compile(p.requests(combined),fs,emptyList(),DrumShadowPlanner.Policy(),snap,reg)
        val report=ep.audit(plan,fs,snap,snap,combined);val out=p.export(plan,fs,snap,reg,report)
        assertEquals(6,report.rows.size);assertTrue(out.toByteArray().size<=p.EXPORT_BYTES)
        assertEquals(6,out.lineSequence().count { it.startsWith("ENGINEERING_PROOF id=") })
        assertTrue(out.contains("omittedMetadataRows="));assertTrue(out.contains("futureStage3Eligibility=AMBIGUOUS"))
        assertFalse(out.contains("futureStage3Eligibility=SAFE_FOR_FUTURE_STAGE3"))
    }
    @Test fun diagnosticCannotTurnEvenHypotheticalAllPassMetadataIntoStage3Activation() {
        val pass=DrumEngineeringProof.Gate(DrumEngineeringProof.Status.PASS,"metadata","fixture")
        assertEquals(DrumEngineeringProof.Eligibility.UNKNOWN,ep.eligibility(DrumShadowPlanner.Classification.COMPATIBLE,listOf(pass),false))
        assertEquals(DrumEngineeringProof.Eligibility.UNKNOWN,ep.eligibility(DrumShadowPlanner.Classification.UNKNOWN,listOf(pass),false))
        assertEquals(DrumEngineeringProof.Eligibility.BLOCKED,ep.eligibility(DrumShadowPlanner.Classification.INCOMPATIBLE,listOf(pass),false))
    }
}

package com.yourapp.audio

import org.junit.Assert.*
import org.junit.Test

class GenericDrumResolverTest {
    private val p=DrumShadowPlanner
    private val g=GenericDrumResolver
    private val registry get()=DrumSemanticEvidenceRegistry.bundled()
    private fun font(e:DrumShadowPlanner.Evidence):DrumShadowPlanner.Font {
        val lines=javaClass.getResourceAsStream("/drum_audit770_zone_rows.tsv")!!.bufferedReader().use { it.readLines() }
        val zones=lines.map { it.split('|',limit=2) }.filter { it[0]==e.candidate.sha256 }.map { p.zoneFromMetadata(it[0],it[1]) }
        return DrumShadowPlanner.Font(e.candidate.sha256,"managed-${e.candidate.sha256}","opaque",zones,zones.associate { z -> z.sampleId to DrumShadowPlanner.Sample(z.sampleId,z.original,44100,z.sampleType,z.link,0,100) })
    }
    private fun req(e:DrumShadowPlanner.Evidence,v:Int=110)=DrumShadowPlanner.Request("Main","Rhythm",9,9,e.msb,e.lsb,e.pc,e.key,e.key,v,10,0,false,true)
    private fun snap(gen:String="7")=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),gen,"GEN value=$gen\n")
    private fun ticket(e:DrumShadowPlanner.Evidence)=GenericDrumResolver.RuntimeTicket(e.candidate,"7",101,e.candidate.sha256,true,true,true,true,true,true,true,true,"native-contract-fixture_ONLY_not_device_evidence")
    private fun seed()=registry.evidence.first { it.target!!.family=="SNARE" }
    private fun compile(e:List<DrumShadowPlanner.Evidence>,requests:List<DrumShadowPlanner.Request> = listOf(req(e.first())),tickets:List<GenericDrumResolver.RuntimeTicket> = e.map { ticket(it) },policy:GenericDrumResolver.Policy=GenericDrumResolver.Policy()):GenericDrumResolver.Plan =
        g.compile(requests,e.map { font(it) },registry.copy(evidence=e),snap(),snap(),policy,tickets)
    @Test fun exactCompatibleApproximationRankingIsDeterministicWithoutBindingOrderWinner() {
        val e=seed();for(cls in listOf(DrumShadowPlanner.Classification.EXACT,DrumShadowPlanner.Classification.COMPATIBLE)) {
            val changed=e.copy(classification=cls);val plan=compile(listOf(changed))
            assertEquals(cls.name,plan.rows.single().outcome.name)
            assertEquals(e.candidate,plan.rows.single().selected)
        }
        val approximation=e.copy(classification=DrumShadowPlanner.Classification.APPROXIMATION)
        assertEquals(GenericDrumResolver.Outcome.ABSTAIN,compile(listOf(approximation)).rows.single().outcome)
        assertEquals(GenericDrumResolver.Outcome.APPROXIMATION,compile(listOf(approximation),policy=GenericDrumResolver.Policy(true)).rows.single().outcome)
    }
    @Test fun equalSafeSemanticBindingsAbstainRegardlessOfFontOrder() {
        val e=registry.evidence.filter { it.target!!.family=="SNARE" }
        for(xs in listOf(e,e.reversed())) {
            val row=compile(xs).rows.single();assertNull(row.selected);assertEquals(GenericDrumResolver.Outcome.ABSTAIN,row.outcome)
            assertTrue(row.reasons.contains("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER"))
        }
    }
    @Test fun onlyProvenSafetyExclusionCanRemoveTieNotCoverageOrLoudness() {
        val es=registry.evidence.filter { it.target!!.family=="SNARE" };val bad=ticket(es.last()).copy(pitchNeutral=false)
        assertEquals(es.first().candidate,compile(es,tickets=listOf(ticket(es.first()),bad)).rows.single().selected)
    }
    @Test fun everyMissingNativeGateAbstainsWithoutStoppingOtherNotes() {
        val e=seed();val good=ticket(e)
        val bad=listOf(good.copy(samplesReady=false),good.copy(generation="8"),good.copy(handle=0),good.copy(loadedSHA256="f".repeat(64)),good.copy(pitchNeutral=false),good.copy(velocityResponseVerified=false),good.copy(ownerAdapterVerified=false),good.copy(controllerLaneVerified=false))
        for(t in bad)assertNull(compile(listOf(e),tickets=listOf(t)).rows.single().selected)
        val unknown=compile(listOf(e),tickets=emptyList()).rows.single();assertNull(unknown.selected)
        assertEquals(DrumEngineeringProof.Status.UNKNOWN,unknown.candidates.single().gates["NATIVE_LOADED_RESOURCE"])
    }
    @Test fun generationChangeInvalidatesEveryHypotheticalNativeTicket() {
        val e=seed();val plan=g.compile(listOf(req(e)),listOf(font(e)),registry.copy(evidence=listOf(e)),snap(),snap("8"),tickets=listOf(ticket(e)))
        assertNull(plan.rows.single().selected);assertEquals(DrumEngineeringProof.Status.FAIL,plan.rows.single().candidates.single().gates["STABLE_GENERATION"])
    }
    @Test fun unknownEdgeCannotBePromotedByAllEngineeringTicketsOrNameHints() {
        val e=registry.evidence.first { it.target!!.technique=="EDGE" }
        val row=compile(listOf(e),requests=listOf(req(e,42))).rows.single()
        assertEquals(DrumShadowPlanner.Classification.UNKNOWN,row.semanticClaim);assertNull(row.selected)
    }
    @Test fun dataDrivenOtherBankPcAndSourceTargetKeysWorkWithoutThreeCaseConstants() {
        val e=seed();val target=e.target!!.copy(msb=121,lsb=4,rawPc=11,key=87)
        val changed=e.copy(msb=target.msb,lsb=target.lsb,pc=target.rawPc,key=target.key,target=target)
        val plan=g.compile(listOf(req(changed)),listOf(font(e)),registry.copy(targets=listOf(target),evidence=listOf(changed)),snap(),snap(),tickets=listOf(ticket(changed)))
        assertEquals(e.candidate,plan.rows.single().selected)
        assertNotEquals(plan.rows.single().request.sourceKey,plan.rows.single().selected!!.key)
    }
    @Test fun wholeDemandBothRhythmChannelsAndUnknownTargetsAreIncluded() {
        val e=seed();val requests=listOf(req(e),req(e).copy(rhythmChannel=8),req(e).copy(sourceKey=1,logicalKey=1),req(e).copy(sourceKey=100,logicalKey=100))
        val plan=compile(listOf(e),requests,tickets=listOf(ticket(e),ticket(e).copy(rhythmChannel=8)))
        assertEquals(4,plan.rows.size);assertEquals(2,plan.rows.count { it.selected!=null });assertEquals(2,plan.counts()[GenericDrumResolver.Outcome.ABSTAIN])
    }
    @Test fun rawLogicalKeyAndRoutingUncertaintyCannotBeInventedAway() {
        val e=seed()
        for(r in listOf(req(e).copy(logicalKey=null),req(e).copy(routingKnown=false),req(e).copy(logicalKey=33)))assertNull(compile(listOf(e),listOf(r)).rows.single().selected)
    }
    @Test fun velocityLayerMultiplicityAndStereoPairingMustMatch() {
        val e=seed();val f=font(e);val z=f.zones.first { it.eligible(e.candidate.key,110) }
        for(fs in listOf(f.copy(samples=emptyMap()),f.copy(zones=f.zones+z),f.copy(zones=f.zones.map { if(it==z)it.copy(vh=50) else it }))) {
            val plan=g.compile(listOf(req(e)),listOf(fs),registry.copy(evidence=listOf(e)),snap(),snap(),tickets=listOf(ticket(e)))
            assertNull(plan.rows.single().selected)
        }
    }
    @Test fun velocityRegionInferenceSupportsOtherVelocitiesWithoutConfidencePromotion() {
        val e=seed();val plan=compile(listOf(e),listOf(req(e,28),req(e,42),req(e,110)))
        assertTrue(plan.rows.all { it.selected!=null });assertTrue(plan.rows.all { it.candidates.single().evidence.confidence==e.confidence })
    }
    @Test fun hiHatRequiresExplicitClassAndCompleteVerifiedFamilyLane() {
        val e=registry.evidence.first { it.target!!.technique=="PEDAL_CLOSED" };val good=ticket(e)
        assertNotNull(compile(listOf(e),listOf(req(e,42))).rows.single().selected)
        for(t in listOf(good.copy(chokeFamilyClosed=false),good.copy(chokeSameLane=false),good.copy(chokeEngineVerified=false)))
            assertNull(compile(listOf(e),listOf(req(e,42)),listOf(t)).rows.single().selected)
        val noClass=registry.evidence.last { it.target!!.technique=="PEDAL_CLOSED" }
        assertNull(compile(listOf(noClass),listOf(req(noClass,42))).rows.single().selected)
    }
    @Test fun namesOnlyHintsNeverInventSemanticCompatibleClaims() {
        val e=seed();val f=font(e);val target=e.target!!
        val plan=g.compile(listOf(req(e)),listOf(f),registry.copy(evidence=emptyList(),targets=listOf(target)),snap(),snap())
        assertEquals(DrumShadowPlanner.Classification.UNKNOWN,plan.rows.single().semanticClaim);assertNull(plan.rows.single().selected)
        assertTrue(plan.rows.single().hints.isNotEmpty())
    }
    @Test fun articulationLexiconSeparatesPedalEdgeOpenAndClosed() {
        val z=font(seed()).zones.first()
        for((name,expected) in listOf("HiHat Foot(R)" to "PEDAL_CLOSED","hi-hat edge" to "EDGE","HiHat Half-Open" to "PARTIAL_OR_SPLASH","HiHat Open" to "OPEN","HiHat Closed" to "CLOSED"))
            assertEquals(expected,g.nameHint(z.copy(sample=name,instrument="opaque"))!!.articulation)
    }
    @Test fun unknownSemanticTargetsDoNotBecomeGmByMidiKeyNumber() {
        val e=seed();val plan=compile(listOf(e),listOf(req(e).copy(msb=127,rawPc=74,sourceKey=44,logicalKey=44)))
        assertEquals(GenericDrumResolver.Outcome.ABSTAIN,plan.rows.single().outcome);assertTrue(plan.rows.single().reasons.contains("NO_AUTHORITATIVE_YAMAHA_NOTE_SEMANTICS"))
    }
    @Test fun cacheIdentityIncludesPolicyGenerationEvidenceAndFontPath() {
        val e=seed();val a=compile(listOf(e));assertEquals(a.digest,compile(listOf(e)).digest)
        assertNotEquals(a.digest,compile(listOf(e),policy=GenericDrumResolver.Policy(true)).digest)
        val f=font(e);val changed=g.compile(listOf(req(e)),listOf(f.copy(identity="replaced")),registry.copy(evidence=listOf(e)),snap(),snap(),tickets=listOf(ticket(e)))
        assertNotEquals(a.digest,changed.digest)
    }
    @Test fun productionActivationIsBlockedDespiteHypotheticalSafeFixture() {
        val plan=compile(listOf(seed()));assertNotNull(plan.rows.single().selected)
        assertFalse(DrumExperimentalActivation.evaluate(true,plan).enabled)
        assertEquals("FLAG_OFF_LEGACY_UNCHANGED",DrumExperimentalActivation.evaluate(false,plan).reason)
    }
    @Test fun contradictoryIncompatibleEvidenceVetoesEvenAnExactClaim() {
        val e=seed();val exact=e.copy(classification=DrumShadowPlanner.Classification.EXACT)
        val rejected=e.copy(evidenceId=e.evidenceId+"-rejected",classification=DrumShadowPlanner.Classification.INCOMPATIBLE)
        val plan=compile(listOf(exact,rejected),tickets=listOf(ticket(e)))
        assertNull(plan.rows.single().selected);assertEquals(DrumShadowPlanner.Classification.INCOMPATIBLE,plan.rows.single().semanticClaim)
    }
    @Test fun ticketsAreScopedToRhythmLaneAndFullSourceDigest() {
        val e=seed();val t=ticket(e)
        assertNull(compile(listOf(e),tickets=listOf(t.copy(rhythmChannel=8))).rows.single().selected)
        assertNull(compile(listOf(e),tickets=listOf(t.copy(sourceDigest="controller_style_changed"))).rows.single().selected)
    }
}

package com.yourapp.audio

import org.junit.Assert.*
import org.junit.Test

class ProductionDrumPlanTest {
    @Test fun repeatedNotesHaveFifoOwnersAndLegacyMarkersCannotReleaseSubsequentMappedNotes() {
        val owners=ProductionDrumOwners(8)
        owners.commit(owners.reserve(9,31),0)
        owners.commit(owners.reserve(9,31),41)
        owners.commit(owners.reserve(9,31),42)
        assertEquals(0L,owners.pop(9,31));assertEquals(41L,owners.pop(9,31));assertEquals(42L,owners.pop(9,31));assertEquals(-1L,owners.pop(9,31))
    }
    @Test fun simultaneousRhythmsAndSourceKeysAndTracksHaveIndependentOwnersForManyToOneMapping() {
        val owners=ProductionDrumOwners(8)
        for((i,source) in listOf(8 to 31,9 to 31,9 to 32).withIndex())owners.commit(owners.reserve(source.first,source.second),i+1L)
        owners.commit(owners.reserve(9,31,1),10)
        assertEquals(10L,owners.pop(9,31,1));assertEquals(3L,owners.pop(9,32));assertEquals(1L,owners.pop(8,31));assertEquals(2L,owners.pop(9,31))
        assertEquals(0,owners.size)
    }
    @Test fun boundedCapacityCannotOverwriteAnOwnerAndStopsAdmissionUntilBoundary() {
        val owners=ProductionDrumOwners(2)
        owners.commit(owners.reserve(9,31),71);owners.commit(owners.reserve(9,32),72)
        assertEquals(-1,owners.reserve(9,33));assertTrue(owners.overflow)
        assertEquals(71L,owners.pop(9,31));assertEquals(72L,owners.pop(9,32))
        owners.clear {fail("already released")};assertFalse(owners.overflow)
    }
    @Test fun sectionEndingAndRapidBoundaryFlushUseCapturedTokensAndIgnoreLegacyMarkers() {
        val owners=ProductionDrumOwners(32);val released=mutableListOf<Long>()
        for(section in listOf("Intro","Main","Fill","Main","Fill","Main","Ending")) {
            owners.commit(owners.reserve(9,31),0)
            owners.commit(owners.reserve(9,31),section.length.toLong())
            owners.commit(owners.reserve(8,31),100)
            owners.clear {released+=it};assertEquals(0,owners.size);assertEquals(-1L,owners.pop(9,31))
        }
        assertEquals(14,released.size);assertFalse(released.contains(0))
    }
    @Test fun lookupPreservesFullRawBankProgramKeyVelocityAndRhythmIdentity() {
        val a=ProductionDrumPlan.address(9,9,127*128,73,31,110)
        val table=ProductionDrumPlan.Table(longArrayOf(a),intArrayOf(3))
        assertEquals(3,table.route(9,9,127*128,73,31,110))
        for(values in listOf(intArrayOf(9,8,127*128,73,31,110),intArrayOf(9,9,128,73,31,110),
            intArrayOf(9,9,127*128,72,31,110),intArrayOf(9,9,127*128,73,40,110),intArrayOf(9,9,127*128,73,31,109)))
            assertEquals(0,table.route(values[0],values[1],values[2],values[3],values[4],values[5]))
    }
    private fun fixtures():Pair<List<DrumShadowPlanner.Font>,com.yourapp.yamahaarranger.style.ParsedStyle> {
        val source=GenericDrumGlobalReplayTest()
        @Suppress("UNCHECKED_CAST") val fonts=source.javaClass.getDeclaredMethod("fonts").apply {isAccessible=true}.invoke(source) as List<DrumShadowPlanner.Font>
        val style=source.javaClass.getDeclaredMethod("style").apply {isAccessible=true}.invoke(source) as com.yourapp.yamahaarranger.style.ParsedStyle
        return fonts to style
    }
    @Test fun entire1054DemandHas48ConditionalSnareRoutesAnd1006LegacyWithEitherExplicitResourceScope() {
        val (fonts,style)=fixtures();val p=DrumShadowPlanner;val g=GenericDrumResolver
        val requests=p.requests(style).map {it.copy(logicalKey=it.sourceKey)}
        val registry=DrumSemanticEvidenceRegistry.bundled();val digest=p.styleDigest(style)
        val snapshot=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),"37","GEN value=37\n")
        for(sha in registry.evidence.filter {it.target!!.family=="SNARE"}.map {it.candidate.sha256}.distinct()) {
            val policy=GenericDrumResolver.Policy(resourceFingerprint=sha)
            val preflight=g.compile(requests,fonts,registry,snapshot,snapshot,policy,sourceDigest=digest)
            val preparable=preflight.rows.flatMap {row->row.candidates.filter {it.evidence.candidate.sha256==sha && ProductionDrumPlan.preparable(it)}.map {it.evidence.candidate to row.request.rhythmChannel}}.distinct()
            assertEquals(1,preparable.size)
            val tickets=preparable.map {(binding,rhythm)->GenericDrumResolver.RuntimeTicket(binding,"37",100,sha,true,true,true,true,true,false,false,false,
                "NATIVE_CONTRACT_FIXTURE_NOT_DEVICE_READINESS",rhythm,digest)}
            val prepared=g.compile(requests,fonts,registry,snapshot,snapshot,policy,tickets,digest)
            assertEquals(48,prepared.rows.count {it.selected!=null});assertEquals(1006,prepared.rows.count {it.selected==null})
            assertTrue(prepared.rows.filter {it.request.sourceKey in listOf(16,21)}.all {it.selected==null})
            assertTrue(prepared.rows.filter {it.selected!=null}.all {it.request.sourceKey==31})
            val production=ProductionDrumPlan.from(prepared,preparable.associateWith {1})
            assertEquals(48,production.safeRows);assertEquals(1054,production.totalRows)
            println("STAGE3_CONTRACT scope=$sha conditionalSafeRows=48 ABSTAIN=1006 EXACT=0 COMPATIBLE=48 APPROXIMATION=0 nativeDeviceAttestation=PENDING actualNativeAdapter=HOST_MOCK_TESTED")
        }
    }
    @Test fun unresolvedHatChokeAndUnknownArticulationNeverReachNativePreloadEvenWithCompatibleMetadata() {
        val (fonts,style)=fixtures();val registry=DrumSemanticEvidenceRegistry.bundled()
        val snapshot=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),"37","GEN value=37\n")
        val requests=DrumShadowPlanner.requests(style).map {it.copy(logicalKey=it.sourceKey)}
        val plan=GenericDrumResolver.compile(requests,fonts,registry,snapshot,snapshot)
        assertTrue(plan.rows.filter {it.request.sourceKey in listOf(16,21)}.flatMap {it.candidates}.none {ProductionDrumPlan.preparable(it)})
    }
    @Test fun missingLayerOrVelocityAndStaleGenerationCannotBeAdmittedByNativeTicket() {
        val (fonts,style)=fixtures();val registry=DrumSemanticEvidenceRegistry.bundled()
        val s=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),"37","GEN value=37\n")
        val e=registry.evidence.first {it.target!!.family=="SNARE"}
        val r=DrumShadowPlanner.requests(style).first {it.sourceKey==31}.copy(logicalKey=31)
        val t=GenericDrumResolver.RuntimeTicket(e.candidate,"38",100,e.candidate.sha256,true,true,true,true,true,false,false,false,"stale",9)
        val stale=GenericDrumResolver.compile(listOf(r),fonts,registry,s,s,tickets=listOf(t))
        assertNull(stale.rows.single().selected)
        assertEquals(DrumEngineeringProof.Status.FAIL,stale.rows.single().candidates.first {it.evidence==e}.gates["NATIVE_LOADED_RESOURCE"])
    }
}

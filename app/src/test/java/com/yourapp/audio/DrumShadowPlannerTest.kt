package com.yourapp.audio

import com.yourapp.yamahaarranger.style.*
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class DrumShadowPlannerTest {
    private val p=DrumShadowPlanner
    private val hash="a".repeat(64)
    private val proof=DrumShadowPlanner.Proof(true,true,true,true)
    private fun req(key:Int=60,velocity:Int=42)=DrumShadowPlanner.Request("Main","Rhythm",9,9,121,3,12,key,null,velocity,10,0,false,true)
    private fun zone(key:Int=62,vl:Int=1,vh:Int=127,type:Int=1,link:Int=0,id:Int=0)=DrumShadowPlanner.Zone(hash,128,4,"OpaqueKit","OpaqueInstrument","OpaqueSample",id,key,key,vl,vh,60,key,-3,type,link,mapOf(51 to 2),mapOf(52 to -7,56 to 0,57 to 1,58 to key),"zone")
    private fun font(zones:List<DrumShadowPlanner.Zone> = listOf(zone()))=DrumShadowPlanner.Font(hash,"id","opaque.sf2",zones)
    private fun snapshot(pc:Int=4,bank:Int=128,originalPc:Int=12,originalBank:Int=121*128+3)=DrumShadowPlanner.Snapshot(listOf(DrumShadowPlanner.Production(9,originalBank,originalPc,hash,bank,pc,true,7)),listOf(DrumShadowPlanner.Normalized(hash,999,6)),"7","GEN value=7")
    private fun ev(c:DrumShadowPlanner.Classification=DrumShadowPlanner.Classification.COMPATIBLE,key:Int=62,proof:DrumShadowPlanner.Proof=this.proof,target:Int=60)=DrumShadowPlanner.Evidence(121,3,12,target,DrumShadowPlanner.Binding(hash,128,4,key),c,80,"reviewed fixture authority",proof)
    private fun plan(evidence:List<DrumShadowPlanner.Evidence> = listOf(ev()),requests:List<DrumShadowPlanner.Request> = listOf(req()),fonts:List<DrumShadowPlanner.Font> = listOf(font()),policy:DrumShadowPlanner.Policy=DrumShadowPlanner.Policy(),snapshot:DrumShadowPlanner.Snapshot=this.snapshot())=p.compile(requests,fonts,evidence,policy,snapshot)
    @Test fun nativeRoutePreservationDoesNotMislabelAddressAsExact() {
        val r=req().copy(msb=1,lsb=0,rawPc=4,sourceKey=62)
        val result=plan(requests=listOf(r),snapshot=snapshot(originalPc=4,originalBank=128)).decisions.single()
        assertEquals(DrumShadowPlanner.Action.PASSTHROUGH,result.action);assertEquals(DrumShadowPlanner.Classification.UNKNOWN,result.classification)
    }
    @Test fun originalIdentitySurvivesFallbackAndCandidateIsOnlyAProposal() {
        val result=plan().decisions.single()
        assertEquals(121,result.request.msb);assertEquals(3,result.request.lsb);assertEquals(12,result.request.rawPc)
        assertEquals(4,result.production!!.pc);assertEquals(62,result.candidate!!.key);assertEquals(DrumShadowPlanner.Action.SUBSTITUTE,result.action);assertTrue(result.crossKey)
    }
    @Test fun equalCoverageWrongSemanticIsRejected() {
        val result=plan(evidence=listOf(ev(DrumShadowPlanner.Classification.INCOMPATIBLE))).decisions.single()
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,result.action);assertTrue(result.reasons.contains("INCOMPATIBLE"))
    }
    @Test fun unknownAndAmbiguousAbstainWithoutCoverageWinner() {
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=listOf(ev(DrumShadowPlanner.Classification.UNKNOWN))).decisions.single().action)
        assertTrue(plan(evidence=listOf(ev(),ev(key=63))).decisions.single().reasons.contains("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER"))
    }
    @Test fun approximationRequiresExplicitPolicy() {
        val evidence=listOf(ev(DrumShadowPlanner.Classification.APPROXIMATION))
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=evidence).decisions.single().action)
        assertEquals(DrumShadowPlanner.Action.SUBSTITUTE,plan(evidence=evidence,policy=DrumShadowPlanner.Policy(true)).decisions.single().action)
    }
    @Test fun exactClaimsPrecedeCompatibleButNeverBypassGates() {
        assertEquals(DrumShadowPlanner.Classification.EXACT,plan(evidence=listOf(ev(),ev(DrumShadowPlanner.Classification.EXACT))).decisions.single().classification)
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=listOf(ev(DrumShadowPlanner.Classification.EXACT,proof=DrumShadowPlanner.Proof()))).decisions.single().action)
    }
    @Test fun velocityAndAllOverlappingLayersKeepRootTuningAndStereoMetadata() {
        val zones=listOf(zone(vl=1,vh=50),zone(vl=20,vh=127,id=1))
        val low=plan(fonts=listOf(font(zones))).decisions.single();assertEquals(2,low.bundle.size)
        assertEquals(-7,low.bundle[0].ig[52]);assertEquals(62,low.bundle[0].root);assertEquals(-3,low.bundle[0].correction)
        assertEquals(1,plan(requests=listOf(req(velocity=90)),fonts=listOf(font(zones))).decisions.single().bundle.size)
        assertTrue(plan(fonts=listOf(font(listOf(zone(type=4,link=1))))).decisions.single().reasons.contains("INVALID_STEREO_SAMPLE_PAIR"))
    }
    @Test fun unsafeChokeIsVetoed() {assertTrue(plan(evidence=listOf(ev(proof=proof.copy(choke=false)))).decisions.single().reasons.contains("UNSAFE_OR_UNKNOWN_CHOKE_RELATIONSHIPS"))}
    @Test fun manyToOneAndOriginalKeyOwnerCollisionAreVetoed() {
        val pair=plan(requests=listOf(req(),req(key=61)),evidence=listOf(ev(),ev(target=61)))
        assertTrue(pair.decisions.all {it.action==DrumShadowPlanner.Action.ABSTAIN && "MANY_TO_ONE_OWNERSHIP_COLLISION" in it.reasons})
        assertTrue(plan(requests=listOf(req(),req(key=62))).decisions.first().reasons.contains("MANY_TO_ONE_OWNERSHIP_COLLISION"))
    }
    @Test fun rawAndVirtualBankRemainDistinct() {
        val raw="GEN value=7\nFONT source=6964 handle=5 readiness=UNKNOWN\nBANK source=6964 raw=999 virtual=6\nLIVE ch=9 inputBank=15491 inputPC=12 source=6964 bank=6 pc=4 verified=1\n"
        val snap=p.snapshot(raw,listOf(font()));assertEquals(999,snap.normalized.single().rawBank);assertEquals(6,snap.normalized.single().virtualBank);assertEquals(hash,snap.production.single().sha256);assertEquals(999,snap.production.single().rawBank)
    }
    @Test fun missingAndNotReadyResourcesVetoSubstitution() {
        assertTrue(plan(fonts=emptyList()).decisions.single().reasons.contains("MISSING_ZONE_OR_FONT"))
        assertTrue(plan(evidence=listOf(ev(proof=proof.copy(resourceReady=false)))).decisions.single().reasons.contains("MISSING_OR_NOT_READY_RESOURCE"))
    }
    private fun style(section:String="Main"):ParsedStyle {
        fun event(t:Int,status:Int,n:Int,v:Int)=StyleNoteEvent(t,status==0x99,n,v,9,status)
        return ParsedStyle("fixture",1920,mapOf(section to StyleSectionModel(section,100,listOf(StylePartModel("Rhythm",listOf(
            event(0,0xb9,0,121),event(0,0xb9,32,3),event(0,0xc9,12,0),event(10,0x99,60,42),
            event(20,0xb9,0,120),event(21,0xb9,32,4),event(22,0xc9,11,0),event(23,0x99,61,36),event(24,0x89,61,0)),program=9)))))
    }
    @Test fun dynamicBankProgramContextPreservesPreCoercionRawRequest() {
        val requests=p.requests(style());assertEquals(2,requests.size)
        assertEquals(listOf(121,3,12),requests.first().let {listOf(it.msb,it.lsb,it.rawPc)})
        assertEquals(listOf(120,4,11),requests.last().let {listOf(it.msb,it.lsb,it.rawPc)});assertTrue(requests.last().dynamic)
        assertNotEquals(requests.first().context,requests.last().context)
    }
    @Test fun introMainFillBreakEndingAllIncludedWithoutChangingStyle() {
        val sections=listOf("IntroA","MainB","FillAB","Break","EndingC").associateWith {style(it).sections.getValue(it)}
        val original=ParsedStyle("fixture",1920,sections);val before=original.toString()
        assertEquals(sections.keys,p.requests(original).map {it.section}.toSet());assertEquals(before,original.toString())
    }
    @Test fun cacheInvalidatesEveryIdentityDimension() {
        val c=DrumShadowPlanner.Cache();val reqs=listOf(req());val fonts=listOf(font());val evidence=listOf(ev());val pol=DrumShadowPlanner.Policy();val snap=snapshot()
        val a=c.prepare(reqs,fonts,evidence,pol,snap,"styleA");assertSame(a,c.prepare(reqs,fonts,evidence,pol,snap,"styleA"))
        assertNotSame(a,c.prepare(reqs,fonts,evidence,pol,snap,"styleB"))
        assertNotSame(c.prepare(reqs,fonts,evidence,pol,snap),c.prepare(listOf(req(key=61)),fonts,evidence,pol,snap))
        assertNotSame(c.prepare(reqs,fonts,evidence,pol,snap),c.prepare(reqs,listOf(font().copy(sha256="b".repeat(64))),evidence,pol,snap))
        assertNotSame(c.prepare(reqs,fonts,evidence,pol,snap),c.prepare(reqs,fonts,listOf(ev(proof=proof.copy(choke=false))),pol,snap))
        assertNotSame(c.prepare(reqs,fonts,evidence,pol,snap),c.prepare(reqs,fonts,evidence,pol.copy(version=2),snap))
        assertNotSame(c.prepare(reqs,fonts,evidence,pol,snap),c.prepare(reqs,fonts,evidence,pol,snap.copy(generation="8",raw="GEN value=8")))
    }
    @Test fun realInventoryRetainsLayerRangesPitchModsAndBoundedExport() {
        val discovery=Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("id","fixture") {ByteArrayInputStream(Sf2SemanticInventoryTest.fixture())}))
        val fonts=p.inventory(discovery);assertEquals(4,fonts.single().zones.size);assertEquals(3,fonts.single().samples.size)
        assertTrue(fonts.single().zones.first().raw.contains("mods=258:8:0:0:0"))
        val result=p.compile(List(1000){req().copy(tick=it)},fonts,emptyList(),DrumShadowPlanner.Policy(),snapshot())
        val out=p.export(result,fonts,snapshot());assertTrue(out.toByteArray().size<=DrumShadowPlanner.EXPORT_BYTES);assertTrue(out.contains("omittedRows="));assertTrue(out.contains("productionDispatch=UNCHANGED"))
    }
    @Test fun importedEvidenceCannotAssertRuntimeCapabilityOrDefaultWinner() {
        assertTrue(p.evidence("").isEmpty())
        val text="121|3|12|60|$hash|128|4|62|COMPATIBLE|80|external reviewed claim"
        val e=p.evidence(text).single();assertFalse(e.proof.resourceReady);assertFalse(e.proof.choke)
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=listOf(e)).decisions.single().action)
    }
    @Test fun explicitLegacyIsLabelledAndWrongSemanticCannotEscapeIntoIt() {
        assertEquals(DrumShadowPlanner.Action.LEGACY_ONLY,plan(evidence=emptyList(),fonts=listOf(font(listOf(zone(key=60)))),policy=DrumShadowPlanner.Policy(retainLegacy=true)).decisions.single().action)
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=listOf(ev(DrumShadowPlanner.Classification.INCOMPATIBLE,key=60)),fonts=listOf(font(listOf(zone(key=60)))),policy=DrumShadowPlanner.Policy(retainLegacy=true)).decisions.single().action)
    }
    @Test fun stereoPairsAndImmutableBundlesPreserveBothChannels() {
        val zones=listOf(zone(type=4,link=1,id=0),zone(type=2,link=0,id=1))
        val headers=mapOf(0 to DrumShadowPlanner.Sample(0,60,44100,4,1,0,100),1 to DrumShadowPlanner.Sample(1,60,44100,2,0,100,200))
        val d=plan(fonts=listOf(font(zones).copy(samples=headers))).decisions.single()
        assertEquals(DrumShadowPlanner.Action.SUBSTITUTE,d.action);assertEquals(2,d.bundle.size)
        try {(d.bundle as MutableList<DrumShadowPlanner.Zone>).clear();fail("must be immutable")} catch(_:UnsupportedOperationException) {}
        assertEquals(2,d.bundle.size)
    }
    @Test fun negativeEvidenceCannotBePromotedByPositiveClaimForSameBinding() {
        assertEquals(DrumShadowPlanner.Action.ABSTAIN,plan(evidence=listOf(ev(),ev(DrumShadowPlanner.Classification.INCOMPATIBLE))).decisions.single().action)
    }

    @Test fun normalizedLookupUsesRawInventoryBankAndFontHandleScope() {
        val raw="GEN value=7\nBANK source=6964 raw=999 virtual=6 handle=5\nLIVE ch=9 inputBank=999 inputPC=4 source=6964 handle=5 bank=6 pc=4 verified=1\n"
        val f=font(listOf(zone(key=60).copy(bank=999)))
        val snap=p.snapshot(raw,listOf(f))
        val result=plan(requests=listOf(req().copy(msb=7,lsb=103,rawPc=4)),fonts=listOf(f),snapshot=snap).decisions.single()
        assertEquals(DrumShadowPlanner.Action.PASSTHROUGH,result.action);assertEquals(999,result.bundle.single().bank)
        val dedicated=p.snapshot(raw.replace("source=6964 handle=5 bank=6","source=6964 handle=8 bank=6"),listOf(f))
        assertEquals(6,dedicated.production.single().rawBank)
    }

}

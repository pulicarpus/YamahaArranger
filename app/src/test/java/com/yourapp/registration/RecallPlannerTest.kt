package com.yourapp.yamahaarranger.registration
import org.junit.Assert.*
import org.junit.Test

class RecallPlannerTest {
    val voice=Sf2VoiceRef("a.sf2",65535,24,"Strings")
    val external=ExternalVoice(0,112,5,"E343")
    fun current()=RecallSnapshot(List(4){PartSetup(null,null,true,100)},ReleaseSetup(64,true),120,0)
    fun slot(mode: OutputMode=OutputMode.BOTH)=RegistrationSlot("Test",mode,List(4){PartSetup(voice,external,true,100)},ReleaseSetup(64,true),null,null,0)
    fun plan(s: RegistrationSlot=slot(),f: FreezeFlags=FreezeFlags(),c: RecallSnapshot=current(),catalog:List<Sf2VoiceRef> = listOf(voice),active:Boolean=true)=RecallPlanner.plan(c,s,f,catalog,active)
    @Test fun R1_exactFileBankProgramNameIncludingNativeHighBanks() {
        for(bank in listOf(1025,65535)) { val v=voice.copy(bank=bank); val p=plan(slot().copy(parts=List(4){PartSetup(v,null,true,100)}),catalog=listOf(voice.copy(bank=0),v))
            assertEquals(List(4){v},p.steps.filterIsInstance<RecallStep.Sf2>().map{it.voice}) }
    }
    @Test fun R2_missingOrAmbiguousSf2PreservesPartVoice() { for(cat in listOf(emptyList(),listOf(voice,voice))) { val p=plan(catalog=cat); assertEquals(4,p.issues.size); assertTrue(p.steps.none{it is RecallStep.Sf2}) } }
    @Test fun R3_identicalWithoutExternalHasNoSteps() { val s=slot(OutputMode.INTERNAL); assertTrue(plan(s,c=current().copy(parts=s.parts)).steps.isEmpty()) }
    @Test fun R4_externalHasZeroSf2Steps() { assertTrue(plan(slot(OutputMode.EXTERNAL)).steps.none{it is RecallStep.Sf2}) }
    @Test fun R5_internalHasZeroExternalSteps() { assertTrue(plan(slot(OutputMode.INTERNAL)).steps.none{it is RecallStep.External}) }
    @Test fun R6_bothSf2PrecedesExternalPerPart() { val p=plan().steps; for(part in Part.values()) assertTrue(p.indexOf(RecallStep.Sf2(part,voice))<p.indexOf(RecallStep.External(part,external))) }
    @Test fun R7_outputOffSkipsExternalWithoutQueue() { val p=plan(slot(OutputMode.EXTERNAL),active=false); assertTrue(p.steps.isEmpty()); assertTrue(p.issues.all{it.reason=="SKIPPED_MIDI_OUT_OFF"}) }
    @Test fun R8_identicalExternalStillSentExplicitly() { assertEquals(4,plan(c=current().copy(parts=slot().parts)).steps.filterIsInstance<RecallStep.External>().size) }
    @Test fun R9_orderOffSf2ExternalVolumeReleaseOnTransposeTempo() {
        val parts=slot().parts.mapIndexed{i,p->p.copy(enabled=i!=0,volume=80)}
        val c=current().copy(parts=current().parts.mapIndexed{i,p->p.copy(enabled=i!=1)})
        val p=plan(slot().copy(parts=parts,release=ReleaseSetup(127,false),tempoBpm=140,transpose=3),FreezeFlags(tempo=false),c).steps
        fun first(type:Class<*>)=p.indexOfFirst{type.isInstance(it)}
        assertTrue(first(RecallStep.Enabled::class.java)<first(RecallStep.Sf2::class.java))
        assertTrue(first(RecallStep.Sf2::class.java)<first(RecallStep.External::class.java))
        assertTrue(first(RecallStep.External::class.java)<first(RecallStep.Volume::class.java))
        assertTrue(first(RecallStep.Volume::class.java)<first(RecallStep.Release::class.java))
        assertTrue(p.indexOf(RecallStep.Enabled(Part.RIGHT2,true))>first(RecallStep.Release::class.java))
        assertTrue(p.indexOf(RecallStep.Transpose(3))<p.indexOf(RecallStep.Tempo(140)))
    }
    @Test fun R10_allFreezeGroupsSuppressTheirSteps() {
        val s=slot().copy(parts=slot().parts.map{it.copy(enabled=false,volume=0)},release=ReleaseSetup(0,false),transpose=4,tempoBpm=150)
        assertTrue(plan(s,FreezeFlags(true,true,true,true,true,true)).steps.isEmpty())
        assertTrue(plan(s,FreezeFlags(voices=true)).steps.none{it is RecallStep.Sf2 || it is RecallStep.External})
    }
    @Test fun R10_eachFreezeGroupIndependentlySuppressesItsParameter() {
        val s=slot().copy(parts=slot().parts.map{it.copy(enabled=false,volume=0)},release=ReleaseSetup(0,false),transpose=4,tempoBpm=150)
        val cases=listOf(
            FreezeFlags(tempo=true) to listOf(RecallStep.Tempo::class.java),
            FreezeFlags(tempo=false,transpose=true) to listOf(RecallStep.Transpose::class.java),
            FreezeFlags(tempo=false,release=true) to listOf(RecallStep.Release::class.java),
            FreezeFlags(tempo=false,voices=true) to listOf(RecallStep.Sf2::class.java,RecallStep.External::class.java),
            FreezeFlags(tempo=false,volumes=true) to listOf(RecallStep.Volume::class.java),
            FreezeFlags(tempo=false,partOnOff=true) to listOf(RecallStep.Enabled::class.java))
        for((flags,types) in cases) { val p=plan(s,flags);assertTrue(p.steps.isNotEmpty());assertTrue(p.steps.none{step->types.any{it.isInstance(step)}}) }
    }
    @Test fun R11_leftUsesSnapshotBrainEnabled() { val p=plan(slot(OutputMode.INTERNAL),c=current().copy(parts=current().parts.mapIndexed{i,v->if(i==3)v.copy(enabled=false) else v})); assertTrue(p.steps.contains(RecallStep.Enabled(Part.LEFT,true))) }
}

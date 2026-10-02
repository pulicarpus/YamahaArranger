package com.yourapp.audio

import org.junit.Assert.*
import org.junit.Test

class ShadowDrumRuntimeTest {
    private fun legacy(key:Int=21,lane:Int=9)=ShadowDrumRuntime.Route("legacy",1,"7",128,0,key,lane,legacy=true)
    private fun candidate(key:Int=44,lane:Int=9,cls:Int=0,font:String="fp",pc:Int=2)=ShadowDrumRuntime.Route(font,2,"7",128,pc,key,lane,cls)
    @Test fun flagOffExactlyPreservesLegacyOnOffEvenWithCandidate() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>();val old=legacy()
        r.on(1,old,candidate(),42,false,trace::add);r.off(1,trace::add)
        assertEquals(listOf(ShadowDrumRuntime.Command(ShadowDrumRuntime.Kind.ON,1,old,42),ShadowDrumRuntime.Command(ShadowDrumRuntime.Kind.OFF,1,old,0)),trace)
    }
    @Test fun crossKeyOffKeepsFontPresetHandleGenerationAndKeyOfOriginalOn() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>();val original=candidate()
        r.on(1,legacy(),original,42,true,trace::add)
        r.on(2,legacy(),candidate(91,8,font="another",pc=35),110,true,trace::add)
        r.off(1,trace::add);assertSame(original,trace.last().route);assertEquals(44,trace.last().route.key)
        r.off(2,trace::add);assertEquals(0,r.active)
    }
    @Test fun manyToOneAndSimultaneousNotesHaveIndependentTokens() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>();val same=candidate()
        r.on(1,legacy(21),same,42,true,trace::add);r.on(2,legacy(22),same,35,true,trace::add)
        assertEquals(2,r.active);r.off(1,trace::add);assertEquals(1,r.active);r.off(2,trace::add);assertEquals(0,r.active)
        assertEquals(listOf(1L,2L),trace.filter { it.kind==ShadowDrumRuntime.Kind.OFF }.map { it.token })
    }
    @Test fun repeatedNotesUseDistinctTokensAndReplacingSameTokenReleasesOldRouteFirst() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>()
        r.on(1,legacy(),candidate(40),110,true,trace::add);r.on(1,legacy(),candidate(91),110,true,trace::add)
        assertEquals(listOf(ShadowDrumRuntime.Kind.ON,ShadowDrumRuntime.Kind.OFF,ShadowDrumRuntime.Kind.ON),trace.map { it.kind })
        assertEquals(40,trace[1].route.key);r.off(1,trace::add);assertEquals(91,trace.last().route.key)
    }
    @Test fun closedPedalOpenShareOnlyVerifiedExclusiveVoiceDomain() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>()
        r.on(1,legacy(46),candidate(46,cls=1),42,true,trace::add)
        r.on(2,legacy(42),candidate(42,cls=1),42,true,trace::add)
        r.on(3,legacy(21),candidate(44,cls=1),35,true,trace::add)
        assertEquals(listOf(1L,2L),trace.filter { it.kind==ShadowDrumRuntime.Kind.CHOKE }.map { it.token })
        assertFalse(r.off(1,trace::add));assertEquals(1,r.active);r.off(3,trace::add);assertEquals(0,r.active)
    }
    @Test fun noChokeAcrossRhythm1Rhythm2FontPresetOrGeneration() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>()
        val routes=listOf(candidate(46,8,1),candidate(42,9,1),candidate(44,9,1,font="other"),candidate(42,9,1,pc=24),candidate(42,9,1).copy(generation="8"))
        routes.forEachIndexed { i,x -> r.on(i.toLong()+1,legacy(),x,42,true,trace::add) }
        assertFalse(trace.any { it.kind==ShadowDrumRuntime.Kind.CHOKE });assertEquals(5,r.active)
    }
    @Test fun mainFillFillMainIntroMainEndingLoopAndRapidTransitionsKeepOwners() {
        val transitions=listOf("Main-Fill","Fill-Main","Intro-Main","Main-Ending","loop","rapid-Main-Fill-Main-Fill")
        for(t in transitions) {
            val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>();val old=candidate()
            r.on(1,legacy(),old,42,true,trace::add);repeat(12) { r.boundary(false,trace::add) }
            r.on(2,legacy(),candidate(91,8,font="new"),110,true,trace::add)
            r.off(1,trace::add);assertSame(t,old,trace.last().route);r.off(2,trace::add);assertEquals(t,0,r.active)
        }
    }
    @Test fun endingStopFlushReleasesOnlyCapturedOwnersAndIgnoresLateOff() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>()
        r.on(1,legacy(),candidate(),42,true,trace::add);r.on(2,legacy(31),candidate(40),110,true,trace::add)
        r.boundary(true,trace::add);assertEquals(0,r.active);assertFalse(r.off(1,trace::add));assertEquals(2,trace.count { it.kind==ShadowDrumRuntime.Kind.OFF })
    }
    @Test fun flagChangeAndPlanGenerationChangeDoNotLoseHeldOwner() {
        val r=ShadowDrumRuntime();val trace=mutableListOf<ShadowDrumRuntime.Command>();val old=candidate()
        r.on(1,legacy(),old,42,true,trace::add);assertTrue(r.hasOwnersForGeneration("7"))
        r.on(2,legacy().copy(generation="8"),candidate().copy(generation="8"),42,false,trace::add)
        r.off(1,trace::add);assertSame(old,trace.last().route);assertFalse(r.hasOwnersForGeneration("7"))
    }
    @Test fun ownerCapacityFailsBeforeOnWithoutChangingExistingOwner() {
        val r=ShadowDrumRuntime(1);val trace=mutableListOf<ShadowDrumRuntime.Command>()
        assertTrue(r.on(1,legacy(),candidate(),42,true,trace::add));assertFalse(r.on(2,legacy(),candidate(),42,true,trace::add))
        assertEquals(1,trace.size);assertTrue(r.off(1,trace::add));assertEquals(0,r.active)
    }
}

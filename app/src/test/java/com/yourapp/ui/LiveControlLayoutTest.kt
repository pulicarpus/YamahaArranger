package com.yourapp.yamahaarranger.ui
import com.yourapp.yamahaarranger.registration.*
import org.junit.Assert.*
import org.junit.Test

class LiveControlLayoutTest {
    @Test fun U1_landscapeGeometryHasEightSlotsAndTouchTargetsWithoutOverlap() {
        for(w in listOf(960f,1024f,1280f,1333f)) for(h in listOf(600f,700f,800f)) {
            val g=LiveControlGeometry(w,h);assertTrue(g.supported)
            assertTrue(g.mainButtonWidth>=96f);assertTrue(g.mainButtonHeight>=96f)
            assertTrue(g.regWidth>=64f);assertTrue(g.regHeight>=64f);assertTrue(g.partButtonWidth>=64f)
            assertTrue(g.middleHeight>=g.partControlsHeight)
            assertEquals(g.innerWidth,g.styleWidth+g.mainWidth+g.partWidth+g.gap*2,0.01f)
            assertEquals(g.innerWidth,g.bankWidth+g.regWidth*8+g.gap*8,0.01f)
            assertEquals(h,g.padding*2+g.header+g.middleHeight+g.regHeight+g.gap*2,0.01f)
        }
    }
    @Test fun U1_smallWindowDoesNotSilentlyShrinkTargets() {assertFalse(LiveControlGeometry(800f,600f).supported);assertFalse(LiveControlGeometry(1280f,480f).supported)}
    @Test fun U2_modeAndSkippedBadgesAreExplicitAndIndependent() {
        assertEquals("E",liveModeBadge(OutputMode.EXTERNAL));assertEquals("I",liveModeBadge(OutputMode.INTERNAL));assertEquals("E+I",liveModeBadge(OutputMode.BOTH));assertEquals("—",liveModeBadge(null))
        for(mode in listOf(OutputMode.EXTERNAL,OutputMode.BOTH)) assertEquals("EXT SKIPPED: MIDI OUT OFF",liveOutputStatus(mode,false,true))
        assertEquals("MIDI OUT OFF",liveOutputStatus(OutputMode.INTERNAL,false,true));assertEquals("MIDI OUT OFF",liveOutputStatus(OutputMode.EXTERNAL,false,false))
        assertEquals("MIDI OUT ON",liveOutputStatus(OutputMode.BOTH,true,true))
    }
    @Test fun U5_initialHomeExplicitLiveAndMixerReturnsToOrigin() {
        val n=LiveNavigation();assertEquals(LivePage.HOME,n.page)
        assertEquals(LivePage.HOME,n.mixer().back().page)
        assertEquals(LivePage.LIVE,n.live().mixer().back().page)
        assertEquals(LivePage.HOME,n.live().back().page)
        assertEquals(LivePage.LIVE,n.live().mixer().mixer().back().page)
        assertEquals(LivePage.HOME,n.live().home().page)
    }
    @Test fun bankNavigationNeverSelectsOrInventsSlots() {
        val b=RegistrationBank("a","A",List(8){null});val s=LiveRegistrationState(banks=listOf(b,b.copy(id="b")))
        assertNull(s.selectedSlot);assertNull(s.move(1).selectedSlot);assertNull(s.move(1).report)
        assertEquals(1,s.move(Int.MAX_VALUE).bankIndex);assertEquals(0,s.move(Int.MIN_VALUE).bankIndex)
        assertNull(s.slot(-1));assertNull(s.slot(8));assertNull(s.slot(0));assertFalse(s.canPrevious);assertTrue(s.canNext)
        assertEquals(s,LiveRegistrationState().move(1).copy(banks=s.banks))
    }
    @Test fun freezeReadOnlyAndTempoHoldUseAuditedDefaults() {
        assertEquals("TEMPO",liveFreezeLabel(FreezeFlags()));assertEquals("NONE",liveFreezeLabel(FreezeFlags(tempo=false)))
        assertEquals(1,liveTempoStep(999));assertEquals(5,liveTempoStep(1000))
    }
}

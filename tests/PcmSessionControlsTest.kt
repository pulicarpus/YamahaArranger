package com.yourapp.yamahaarranger.audio
import org.junit.Assert.*
import org.junit.Test
class PcmSessionControlsTest {
    @Test fun repeatedSetterIsNotMuteChangeAndKeyboardIsIncluded() {
        PcmSessionControls.observeMute(0,false)
        val start=PcmSessionControls.snapshot { it }
        PcmSessionControls.observeMute(0,true); PcmSessionControls.observeMute(0,true)
        val marked=PcmSessionControls.snapshot { it }
        assertEquals(start.changes+1,marked.changes)
        assertEquals(1,marked.muteMask and 1)
        PcmSessionControls.observeMute(0,false)
        assertEquals(start.changes+2,PcmSessionControls.snapshot { it.changes })
    }
    @Test fun parallelChannelUpdatesAndSnapshotsAreCoherent() {
        (0..15).forEach { PcmSessionControls.observeMute(it,false) }
        val before=PcmSessionControls.snapshot { it.changes }
        val threads=(0..15).map { channel -> Thread { repeat(200) { PcmSessionControls.observeMute(channel,true);PcmSessionControls.snapshot { assertTrue(it.changes>=before) };PcmSessionControls.observeMute(channel,false) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        PcmSessionControls.snapshot { assertEquals(0,it.muteMask);assertEquals(before+6400,it.changes);assertEquals(65535,it.knownMask) }
    }
}

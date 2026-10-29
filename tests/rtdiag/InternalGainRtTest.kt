package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Production chain with the JNI mock: the gain control reaches the bridge only, never MIDI OUT or the note velocities (RIGHT alone and RIGHT + Style). */
class InternalGainRtTest {
    private fun rig(): RtRig { DebugLog.clear(); return RtRig(true, 240) }

    private fun play(r: RtRig, withStyle: Boolean): Pair<List<Int>, List<Triple<Int, Int, Int>>> {
        if (withStyle) { r.brain.startStop(); Thread.sleep(150) }
        val before = r.nativeNoteOnVel.size; val wr = r.usbWrites.size
        for (v in listOf(20, 48, 57, 100, 127)) { r.noteOn(72, v); r.settle(5); r.noteOff(72); r.settle(5) }
        if (withStyle) { r.brain.startStop(); Thread.sleep(150) }
        val vel = r.nativeNoteOnVel.toList().drop(before).filter { it.first == 0 }.map { it.third }
        val midi = r.usbWrites.toList().drop(wr).filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == 0 }.map { Triple(it.status, it.d1, it.d2) }
        return vel to midi
    }

    @Test fun gainReachesTheBridgeOnlyAndLeavesVelocityAndMidiOutAlone() {
        for (withStyle in listOf(false, true)) {
            val base = rig(); val a = play(base, withStyle); base.close()
            val r = rig(); r.engine.setInternalGainDb(9f)
            Mockito.verify(r.bridge).nativeSetInternalGainDb(9f)
            assertEquals(9f, r.engine.internalGainDb, 0f)
            val b = play(r, withStyle); r.close()
            assertEquals("engine note velocities identical with/without gain (style=$withStyle)", a.first, b.first)
            assertEquals("RIGHT velocities are the raw ones", listOf(20, 48, 57, 100, 127), b.first)
            assertEquals("MIDI OUT NOTE_ON identical (style=$withStyle)", a.second, b.second)
            println("RTDIAG IGAIN style=$withStyle velocitiesEngine=${b.first} midiOutVel=${b.second.map { it.third }}")
        }
    }

    @Test fun gainIsClampedAndDefaultsToLegacy() {
        val r = rig()
        assertEquals(0f, r.engine.internalGainDb, 0f)
        r.engine.setInternalGainDb(40f); assertEquals(12f, r.engine.internalGainDb, 0f)
        r.engine.setInternalGainDb(-3f); assertEquals(0f, r.engine.internalGainDb, 0f)
        Mockito.verify(r.bridge).nativeSetInternalGainDb(12f)
        r.close()
    }
}

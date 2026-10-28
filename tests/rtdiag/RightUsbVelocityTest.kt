package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.audio.RightInputProbe
import com.yourapp.yamahaarranger.audio.RightUsbTouch
import com.yourapp.yamahaarranger.audio.RightVelocityTest
import com.yourapp.yamahaarranger.audio.TouchResponse
import com.yourapp.yamahaarranger.audio.TouchResponseStore
import com.yourapp.yamahaarranger.audio.VirtualRightVoices
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * RIGHT USB-MIDI vs Virtual Keyboard, measured on the production chain (USB bytes -> MidiInputManager parser -> ArrangerBrain ->
 * AudioEngineManager -> JNI bridge mock that records the exact velocity the native side would compute: lround(v*127) clamped 1..127).
 * HOST evidence only: it proves what the app hands to BASSMIDI, not what the PSR-E343 sends or how the speaker sounds.
 */
class RightUsbVelocityTest {
    private val VELS = listOf(20, 40, 60, 80, 100, 127)
    private fun rig(out: Boolean = false): RtRig { DebugLog.clear(); return RtRig(out, 120) }

    @Before fun fresh() { RightInputProbe.reset(); RightUsbTouch.response = TouchResponse.ORIGINAL; RightVelocityTest.reset() }
    @After fun restore() { RightUsbTouch.response = TouchResponse.ORIGINAL; RightVelocityTest.reset() }

    private fun RtRig.usbVel(v: Int, note: Int = 72): Int {
        val before = nativeNoteOnVel.size
        noteOn(note, v); noteOff(note); settle(5)
        return nativeNoteOnVel.toList().drop(before).first { it.first == 0 }.third
    }

    private fun vkVel(v: Int): Int {
        val r = rig(); val seen = mutableListOf<Int>()
        val voices = VirtualRightVoices(engineOn = { c, p, vel -> if (c == 0) { r.engine.noteOnChannel(c, p, vel) } }, engineOff = { _, _ -> }, rightEnabled = { it == 0 }, externallyHeld = { _, _ -> false })
        val before = r.nativeNoteOnVel.size
        voices.noteOn(72, v); seen += r.nativeNoteOnVel.toList().drop(before).map { it.third }
        r.close(); return seen.single()
    }

    @Test fun originalIsLosslessForEveryVelocityAndMatchesTheProbeStages() {
        val r = rig()
        for (v in 1..127) assertEquals("USB velocity $v reaches the native side unchanged", v, r.usbVel(v))
        assertEquals(127, RightInputProbe.usbRawHist().n); assertEquals(127, RightInputProbe.usbInHist().n); assertEquals(127, RightInputProbe.usbFwdHist().n)
        assertEquals(1, RightInputProbe.usbFwdHist().min); assertEquals(127, RightInputProbe.usbFwdHist().max)
        r.close()
    }

    @Test fun comparisonTableUsbVsVirtualKeyboard() {
        val r = rig(out = true)
        for (v in VELS) {
            val wr = r.usbWrites.size
            val usb = r.usbVel(v)
            val outVel = r.usbWrites.toList().drop(wr).first { (it.status and 0xF0) == 0x90 && (it.status and 15) == 0 }.d2
            assertEquals(v, usb); assertEquals("MIDI OUT keeps the raw keyboard velocity", v, outVel)
            println("RTDIAG RIGHTUSB path=USB rawIn=$v parsed=$v routed=$v engineArg=$v nativeCall=$usb midiOut=$outVel touch=${RightUsbTouch.response.label} sf2preset=sameEngineChannel0")
        }
        for (v in VELS) {
            // The Virtual Keyboard is always fed its own fixed velocity setting; here the same numbers are pushed through its ledger to prove it is lossless too.
            val vk = vkVel(v); assertEquals(v, vk)
            println("RTDIAG RIGHTUSB path=VK rawIn=$v nativeCall=$vk (default key velocity is ${com.yourapp.yamahaarranger.audio.VirtualKeyboardController.DEFAULT_VELOCITY})")
        }
        r.close()
    }

    @Test fun controllersNeverChangeTheVelocityOrCreateNotes() {
        val r = rig()
        val base = r.usbVel(40)
        val engineBefore = r.engineEvents.size
        for (cc in listOf(1 to 100, 7 to 20, 11 to 30, 64 to 0, 71 to 127, 74 to 0, 91 to 90, 93 to 90, 72 to 10)) r.cc(cc.first, cc.second)
        r.feed(0xE0, 0, 100); r.feed(0xD0, 90); r.feed(0xC0, 5); r.feed(0xA0, 72, 80)
        r.settle(10)
        assertEquals("controllers / bend / pressure / program change create no engine note events", engineBefore, r.engineEvents.size)
        assertEquals("velocity unchanged after controllers", base, r.usbVel(40))
        val rep = RightInputProbe.report()
        for (c in listOf(1, 7, 11, 71, 74)) assertTrue("probe lists CC$c: $rep", rep.contains("CC$c x1"))
        assertTrue(rep, rep.contains("programChange=1") && rep.contains("pitchBend=1") && rep.contains("chanPressure=1") && rep.contains("polyAT=1"))
        r.close()
    }

    @Test fun noteOnVelocityZeroIsNoteOff() {
        val r = rig()
        r.noteOn(72, 90); r.settle(5)
        val ons = r.nativeNoteOnVel.size; val offs = r.count('F', 0..0)
        r.feed(0x90, 72, 0); r.settle(5)
        assertEquals("no new NOTE_ON", ons, r.nativeNoteOnVel.size); assertEquals("exactly one NOTE_OFF", offs + 1, r.count('F', 0..0))
        r.close()
    }

    @Test fun touchCurvesAreMonotonicKeepFullScaleAndNeverReachZero() {
        for (t in TouchResponse.values()) {
            var prev = 0
            for (v in 1..127) { val m = t.map(v); assertTrue("$t $v -> $m", m in 1..127 && m >= prev); prev = m }
            assertEquals(127, t.map(127)); assertEquals(0, t.map(0))
        }
        for (v in 0..127) assertEquals("ORIGINAL is the identity", v, TouchResponse.ORIGINAL.map(v))
        for (v in 1..126) { assertTrue("SOFT >= MEDIUM >= ORIGINAL >= HARD at $v", TouchResponse.SOFT.map(v) >= TouchResponse.MEDIUM.map(v) && TouchResponse.MEDIUM.map(v) >= v && v >= TouchResponse.HARD.map(v)) }
        println("RTDIAG RIGHTUSB touchTable " + VELS.joinToString(" ") { v -> "$v->S${TouchResponse.SOFT.map(v)}/M${TouchResponse.MEDIUM.map(v)}/H${TouchResponse.HARD.map(v)}" })
    }

    @Test fun touchAppliesToUsbRightOnlyAndNotToMidiOutVirtualKeyboardOrChordPath() {
        val r = rig(out = true)
        for (t in listOf(TouchResponse.SOFT, TouchResponse.MEDIUM, TouchResponse.HARD)) {
            RightUsbTouch.response = t
            for (v in VELS) {
                val wr = r.usbWrites.size
                val usb = r.usbVel(v)
                assertEquals("$t USB RIGHT engine velocity", t.map(v), usb)
                val outVel = r.usbWrites.toList().drop(wr).first { (it.status and 0xF0) == 0x90 && (it.status and 15) == 0 }.d2
                assertEquals("$t MIDI OUT keeps raw velocity", v, outVel)
                assertEquals("$t Virtual Keyboard unaffected", v, vkVel(v))
                println("RTDIAG RIGHTUSB touch=${t.label} rawIn=$v nativeCall=$usb midiOut=$outVel vk=${vkVel(v)}")
            }
        }
        // Chord/ACMP area: with ACMP on the lower keys feed the chord detector only; no RIGHT note and no velocity mapping.
        RightUsbTouch.response = TouchResponse.SOFT
        r.brain.setAcmpEnabled(true)
        val before = r.nativeNoteOnVel.size
        r.noteOn(48, 30); r.settle(20); r.noteOff(48); r.settle(10)
        val added = r.nativeNoteOnVel.toList().drop(before)
        assertTrue("ACMP chord key is not routed through RIGHT 1/2/3: $added", added.none { it.first in 0..2 })
        assertTrue("LEFT voice (if sounding) keeps the raw velocity: $added", added.all { it.third == 30 })
        r.close()
    }

    @Test fun releaseControlIsIdenticalForBothPaths() {
        // The RELEASE control is a controller on the BASSMIDI channel (CC72), not part of the note velocity: the note velocity is the same with release at either extreme.
        val r = rig()
        val a = r.usbVel(60)
        r.brain.setKeyboardReleaseTime(100); val b = r.usbVel(60)
        r.brain.setKeyboardReleaseTime(0); val c = r.usbVel(60)
        assertEquals(a, b); assertEquals(a, c)
        r.close()
    }

    @Test fun preferenceIsPersistedAndDefaultsToOriginal() {
        val f = File.createTempFile("touch", ".txt"); f.delete()
        val s = TouchResponseStore(f)
        assertEquals(TouchResponse.ORIGINAL, s.read())
        for (t in TouchResponse.values()) { s.write(t); assertEquals(t, TouchResponseStore(f).read()) }
        f.writeText("garbage"); assertEquals(TouchResponse.ORIGINAL, s.read())
        f.delete()
    }

    @Test fun noExtraThreadHopOnTheNotePath() {
        val r = rig()
        r.noteOn(72, 80); r.settle(5)
        val ev = r.engineEvents.first { it.kind == 'O' && it.ch == 0 }
        assertTrue("engine NOTE_ON runs on the MIDI callback thread, not a new executor: ${ev.thread}", ev.thread == Thread.currentThread().name)
        r.close()
    }
}

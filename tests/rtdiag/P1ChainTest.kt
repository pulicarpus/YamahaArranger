package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * P1 race / STOP-START / ownership tests on the PRODUCTION chain (same file runs on the baseline and on P1; it uses no P1-only API).
 * Oracle: BASS_MIDI_NOTEOFF1 semantics on the recording bridge (stuck, orphan OFF) plus a per-key prefix check "never more OFFs than ONs".
 */
class P1ChainTest {
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50); private val Am = listOf(45, 48, 52)
    private fun rig(nativeUs: Long = 100, usbUs: Long = 50): RtRig { DebugLog.clear(); return RtRig(true, 240).also { it.nativeCallCostUs = nativeUs; it.usbWriteCostUs = usbUs } }

    private fun prefixBalanced(r: RtRig, what: String) {
        val bal = HashMap<Int, Int>()
        for (e in r.engineEvents.toList().sortedBy { it.t }) {
            val k = e.ch * 256 + e.note
            if (e.kind == 'O') bal.merge(k, 1, Int::plus) else if (e.kind == 'F') { val v = (bal[k] ?: 0) - 1; assertTrue("$what: NOTE_OFF before its NOTE_ON on ch${e.ch} note ${e.note}", v >= 0); bal[k] = v } else if (e.kind == 'A') bal.clear()
        }
        val usb = HashMap<Int, Int>()
        for (w in r.usbWrites.toList().sortedBy { it.t }) {
            val cmd = w.status and 0xF0; val ch = w.status and 0x0F; val k = ch * 256 + w.d1
            if (cmd == 0x90 && w.d2 > 0) usb.merge(k, 1, Int::plus) else if (cmd == 0x80 || (cmd == 0x90 && w.d2 == 0)) { val v = (usb[k] ?: 0) - 1; assertTrue("$what: MIDI OUT NOTE_OFF before NOTE_ON ch$ch note ${w.d1}", v >= 0); usb[k] = v }
            else if (cmd == 0xB0 && w.d1 == 123) usb.keys.filter { it / 256 == ch }.forEach { usb[it] = 0 }
        }
    }

    @Test fun randomisedRaceStressLeavesNoStuckNoOrphanAndKeepsPerKeyOrder() {
        for (seed in 1L..3L) {
            val r = rig(); val rnd = Random(seed)
            val chords = listOf(C, F, G, Am); var held: List<Int> = emptyList()
            repeat(2) { cycle ->                                       // two START..STOP sessions
                r.brain.startStop(); Thread.sleep(100)
                val rightRun = java.util.concurrent.atomic.AtomicBoolean(true)
                val rt = Thread({ var n = 0; while (rightRun.get()) { val k = 72 + (n++ % 7); r.noteOn(k); Thread.sleep(25); r.noteOff(k); Thread.sleep(15) } }, "right").apply { isDaemon = true }; rt.start()
                val end = System.nanoTime() + 3_500_000_000L
                while (System.nanoTime() < end) {
                    when (rnd.nextInt(10)) {
                        0, 1, 2, 3, 4 -> { held.forEach { r.noteOff(it) }; held = chords[rnd.nextInt(4)]; held.forEach { r.noteOn(it) } }
                        5 -> r.brain.selectMainVariation(listOf(ArrangerSection.MainA, ArrangerSection.MainB, ArrangerSection.MainC)[rnd.nextInt(3)])
                        6 -> r.brain.setAcmpEnabled(rnd.nextBoolean())
                        7 -> r.brain.setLeftVoiceEnabled(rnd.nextBoolean())
                        8 -> r.brain.setKeyboardTranspose(rnd.nextInt(7) - 3)
                        else -> r.brain.setKeyboardTranspose(0)
                    }
                    Thread.sleep(20L + rnd.nextInt(80))
                }
                rightRun.set(false); rt.join(500); held.forEach { r.noteOff(it) }; held = emptyList(); Thread.sleep(60)
                r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(true)
                r.brain.startStop(); Thread.sleep(120)                 // STOP with every key released
            }
            prefixBalanced(r, "seed $seed")
            assertEquals("seed $seed stuck=${r.oracle.stuck()}", 0, r.oracle.stuckOnChannels(0..15)); assertEquals("seed $seed orphan OFF", 0L, r.oracle.orphanOffs)
            r.close()
        }
    }

    @Test fun stopWhileARetargetCallIsBlockedLeavesNothingAndSkipsTheStaleRest() {
        val r = rig(); r.brain.startStop(); Thread.sleep(150)
        C.forEach { r.noteOn(it) }; Thread.sleep(120)                    // harmony sounding
        C.forEach { r.noteOff(it) }; Thread.sleep(30)
        val onsBefore = r.engineEvents.count { it.kind == 'O' }
        r.stallThread = "MainSim"; r.stallOnceMs = 150                   // the first native call of the next retarget blocks for 150 ms
        F.forEach { r.noteOn(it) }
        Thread.sleep(30); F.forEach { r.noteOff(it) }                     // keys up again (a key held across STOP is cut by the baseline's global kill)
        Thread.sleep(30)                                                  // retarget (15 ms settle) is now stuck inside that call
        val t0 = System.nanoTime(); r.brain.startStop()                   // STOP while it is in flight
        val stopMs = (System.nanoTime() - t0) / 1_000_000
        val stopReturnedAt = System.nanoTime()
        Thread.sleep(400)
        val lateOns = r.engineEvents.count { it.kind == 'O' && it.t > stopReturnedAt }
        println("CCS scenario=p1_stop_during_blocked_retarget stopReturnedAfterMs=$stopMs onsAfterStop=$lateOns onsBefore=$onsBefore stuck=${r.oracle.stuckOnChannels(0..15)} orphan=${r.oracle.orphanOffs}")
        assertEquals("no NOTE_ON of the old session may execute after STOP returned", 0, lateOns)
        assertEquals(0, r.oracle.stuckOnChannels(0..15))
        prefixBalanced(r, "stop-during-blocked-retarget")
        // a new session works normally on the same keys
        r.brain.startStop(); Thread.sleep(150); G.forEach { r.noteOn(it) }; Thread.sleep(300)
        assertTrue("new session plays harmony", r.engineEvents.toList().any { it.kind == 'O' && it.ch in 10..14 && it.t > stopReturnedAt })
        G.forEach { r.noteOff(it) }; r.brain.startStop(); Thread.sleep(100)
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    @Test fun acmpToggleAndFillDuringBlockedRetargetKeepOwnership() {
        val r = rig(); r.brain.startStop(); Thread.sleep(150)
        C.forEach { r.noteOn(it) }; Thread.sleep(120); C.forEach { r.noteOff(it) }
        r.stallThread = "MainSim"; r.stallOnceMs = 120
        F.forEach { r.noteOn(it) }; Thread.sleep(50)
        r.brain.setAcmpEnabled(false)                                    // releases harmonic owners while the retarget is blocked
        r.brain.selectMainVariation(ArrangerSection.MainB)
        Thread.sleep(300); F.forEach { r.noteOff(it) }
        r.brain.setAcmpEnabled(true); G.forEach { r.noteOn(it) }; Thread.sleep(300); G.forEach { r.noteOff(it) }
        r.brain.startStop(); Thread.sleep(150)
        prefixBalanced(r, "acmp/fill during blocked retarget")
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); assertEquals(0L, r.oracle.orphanOffs); r.close()
    }
}

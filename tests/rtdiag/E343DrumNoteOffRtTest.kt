package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.arranger.E343DrumNoteOff
import com.yourapp.yamahaarranger.arranger.E343DrumNoteOffMode
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * E343 drum NOTE OFF experiment through the PRODUCTION chain (sequencer -> MidiInputManager -> MidiMixerOutput -> USB port). HOST evidence only.
 * EnglishWaltz2 (SGAP_STYLE_TSV): LEGACY / AUTHORED / SHORT_GATE, pairing on retrigger, STOP, section transition, MIDI OUT disable, mode change.
 * ROSITA (ROSITA_TSV only): every mode leaves the wire exactly as LEGACY (out of scope by Style).
 * Rows: `E343NOFF ...`.
 */
class E343DrumNoteOffRtTest {
    private val tempo = 280
    private fun row(s: String) = println("E343NOFF $s")
    private fun style(): ParsedStyle = StyleTsv.load().also { assumeTrue("style TSV not set", it != null) }!!
    private fun ew2() = style().also { assumeTrue("not EnglishWaltz2", it.fileName.contains("EnglishWaltz2")) }

    @After fun reset() { E343DrumNoteOff.setMode(E343DrumNoteOffMode.LEGACY); E343DrumNoteOff.probe = null; E343DrumNoteOff.setGate(120) }

    private class Run(val usb: List<RtRig.Wr>, val engine: List<RtRig.Ev>, val marks: Map<String, Long>, val report: String, val openAfter: Int)

    private fun run(st: ParsedStyle, sec: String, mode: E343DrumNoteOffMode, gate: Int = 120, totalMs: Long, wired: Boolean = true, during: (RtRig, MutableMap<String, Long>) -> Unit = { _, _ -> }): Run {
        val r = RtRig(true, 120, st)
        val marks = HashMap<String, Long>()
        try {
            E343DrumNoteOff.probe = if (wired) r.midi::midiOutNoteProbe else null   // unwired = the baseline path (experiment can never act)
            E343DrumNoteOff.setGate(gate); E343DrumNoteOff.setMode(mode); E343DrumNoteOff.resetStats()
            r.brain.setTempo(tempo)
            r.brain.selectSection(ArrangerSection.valueOf(sec))
            marks["start"] = System.nanoTime(); r.brain.startStop()
            val t0 = System.nanoTime()
            during(r, marks)
            while (System.nanoTime() < t0 + totalMs * 1_000_000) Thread.sleep(3)
            marks["stop"] = System.nanoTime(); r.brain.startStop(); Thread.sleep(300)
            return Run(r.usbAll.toList().sortedBy { it.t }, r.engineEvents.toList().sortedBy { it.t }, marks, E343DrumNoteOff.report(), E343DrumNoteOff.openCount())
        } finally { r.close(); E343DrumNoteOff.setMode(E343DrumNoteOffMode.LEGACY); E343DrumNoteOff.probe = null }
    }

    private fun Run.ch(c: Int) = usb.filter { (it.status and 15) == c }
    private fun isOn(w: RtRig.Wr) = (w.status and 0xF0) == 0x90 && w.d2 > 0
    private fun isOff(w: RtRig.Wr) = (w.status and 0xF0) == 0x80 || ((w.status and 0xF0) == 0x90 && w.d2 == 0)
    private fun Run.onsBefore(c: Int, t: Long) = ch(c).filter { isOn(it) && it.t < t }
    private fun Run.offsBefore(c: Int, t: Long) = ch(c).filter { isOff(it) && it.t < t }

    /** Every NOTE ON of [note] on ch10 is closed by exactly one NOTE OFF before the next NOTE ON (or before STOP's CC123). */
    private fun assertPaired(r: Run, note: Int, label: String) {
        val seq = r.ch(9).filter { (isOn(it) || isOff(it)) && it.d1 == note }
        var openNow = false
        for (w in seq) {
            if (isOn(w)) { assertFalse("$label: NOTE ON $note while the previous one is still open", openNow); openNow = true }
            else { assertTrue("$label: NOTE OFF $note without an open NOTE ON", openNow); openNow = false }
        }
        assertFalse("$label: a NOTE ON $note was left open", openNow)
        assertEquals("$label: experiment ledger empty after STOP", 0, r.openAfter)
    }
    /** NOTE ON -> NOTE OFF gaps of [note] on ch10 that closed during playback (the STOP release is excluded). */
    private fun gatesMs(r: Run, note: Int): List<Long> {
        val seq = r.ch(9).filter { (isOn(it) || isOff(it)) && it.d1 == note && it.t < r.marks["stop"]!! }
        return seq.zipWithNext().filter { (a, b) -> isOn(a) && isOff(b) }.map { (a, b) -> (b.t - a.t) / 1_000_000 }
    }
    private fun stat(r: Run, key: String) = Regex("$key=(\\d+)").find(r.report)!!.groupValues[1].toInt()

    @Test fun legacyDefaultSendsNoDrumNoteOff() {
        val st = ew2()
        assertEquals("default mode", E343DrumNoteOffMode.LEGACY, E343DrumNoteOff.mode)
        val r = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500)
        val stop = r.marks["stop"]!!
        row("LEGACY MainA ch10 on=${r.onsBefore(9, stop).size} on26=${r.onsBefore(9, stop).count { it.d1 == 26 }} off=${r.offsBefore(9, stop).size} ${r.report}")
        assertEquals(0, r.offsBefore(9, stop).size); assertEquals(0, r.offsBefore(8, stop).size)
        assertTrue(r.onsBefore(9, stop).count { it.d1 == 26 } >= 6)
    }

    @Test fun authoredClosesNote26AtTheStylesOwnLengthAndChangesNothingElse() {
        val st = ew2()
        val legacy = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500)
        val a = run(st, "MainA", E343DrumNoteOffMode.AUTHORED, totalMs = 1500)
        val stop = a.marks["stop"]!!
        val g = gatesMs(a, 26)
        val expected = 1900.0 / st.ppq * 60_000.0 / tempo
        row("AUTHORED MainA gatesMs=${g.take(12)} expected~${"%.0f".format(expected)} ${a.report}")
        assertPaired(a, 26, "AUTHORED")
        assertTrue("NOTE OFFs were sent", g.size >= 6)
        assertTrue("gate follows the authored 1900 ticks (host jitter allowed): $g", g.all { it in (expected * 0.6).toLong()..(expected * 1.4).toLong() })
        assertTrue("only note 26 gets a NOTE OFF on ch10", a.offsBefore(9, stop).all { it.d1 == 26 })
        assertEquals("Rhythm1 untouched", 0, a.offsBefore(8, stop).size)
        // NOTE ON stream (note, velocity, order) identical to LEGACY
        val lo = legacy.onsBefore(9, legacy.marks["stop"]!!).map { it.d1 to it.d2 }; val ao = a.onsBefore(9, stop).map { it.d1 to it.d2 }
        val n = minOf(lo.size, ao.size); assertTrue(n >= 20)
        assertEquals("NOTE ON stream unchanged", lo.take(n), ao.take(n))
        assertEquals("velocity of NOTE OFF is 0 and never a NOTE ON with velocity 0", 0, a.ch(9).count { (it.status and 0xF0) == 0x90 && it.d2 == 0 })
        // bank/program and controllers unchanged
        fun setup(r: Run) = r.usb.filter { (it.status and 0xF0) == 0xC0 || ((it.status and 0xF0) == 0xB0 && it.d1 != 123) }.map { Triple(it.status, it.d1, it.d2) }
        assertEquals("Bank/Program/controllers unchanged", setup(legacy), setup(a))
        // internal engine: same NOTE ON/OFF events for the drum channel (the experiment never calls the engine)
        assertEquals("engine untouched", legacy.engine.count { it.ch == 9 && it.kind == 'F' }, a.engine.count { it.ch == 9 && it.kind == 'F' })
    }

    @Test fun shortGateClosesNote26AfterTheConfiguredTime() {
        val st = ew2()
        val r = run(st, "MainA", E343DrumNoteOffMode.SHORT_GATE, gate = 40, totalMs = 1500)
        val g = gatesMs(r, 26)
        row("SHORT_GATE 40ms MainA gatesMs=${g.take(12)} ${r.report}")
        assertPaired(r, 26, "SHORT_GATE")
        assertTrue(g.size >= 6)
        assertTrue("gate ~40 ms (host jitter allowed): $g", g.all { it in 25L..120L })
        assertTrue("gate fired, not the authored NOTE OFF", stat(r, "offGate") >= 6)
    }

    @Test fun stopWhileNote26IsHeldClosesItWithoutAGlobalCutOfOtherChannels() {
        val st = ew2()
        val r = run(st, "MainC", E343DrumNoteOffMode.AUTHORED, totalMs = 400)   // MainC holds note 26 for 11500 ticks
        val stop = r.marks["stop"]!!
        val off = r.ch(9).firstOrNull { isOff(it) && it.d1 == 26 && it.t >= stop }
        val cc123 = r.ch(9).firstOrNull { (it.status and 0xF0) == 0xB0 && it.d1 == 123 && it.t >= stop }
        row("STOP MainC off26=${off != null} beforeLegacyCC123=${off != null && cc123 != null && off.t <= cc123.t} ${r.report}")
        assertPaired(r, 26, "STOP")
        assertNotNull("NOTE OFF 26 at STOP", off)
        assertEquals("one release at STOP", 1, stat(r, "offRelease"))
        assertTrue("the experiment sends only note 26 NOTE OFFs", r.ch(9).filter { isOff(it) }.all { it.d1 == 26 })
    }

    @Test fun sectionTransitionClosesTheOutgoingNote() {
        val st = ew2()
        val r = run(st, "MainC", E343DrumNoteOffMode.AUTHORED, totalMs = 2600) { rig, m -> Thread.sleep(300); m["switch"] = System.nanoTime(); rig.brain.selectSection(ArrangerSection.MainA) }
        row("TRANSITION MainC->MainA ${r.report} gates=${gatesMs(r, 26).take(10)}")
        assertPaired(r, 26, "TRANSITION")
        val sw = r.marks["switch"]!!
        assertTrue("the held MainC note was closed at the section change", r.ch(9).any { isOff(it) && it.d1 == 26 && it.t > sw })
        assertTrue("MainA notes 26 then played and were closed", r.ch(9).count { isOn(it) && it.d1 == 26 && it.t > sw } >= 3)
    }

    @Test fun midiOutDisabledWhileHeldNeverProducesAnOrphanNoteOff() {
        val st = ew2()
        val r = run(st, "MainC", E343DrumNoteOffMode.AUTHORED, totalMs = 700) { rig, m ->
            Thread.sleep(300); rig.midi.midiOutEnabled = false; Thread.sleep(100); m["reenable"] = System.nanoTime(); rig.midi.midiOutEnabled = true }
        val re = r.marks["reenable"]!!; val stop = r.marks["stop"]!!
        val expOff = r.ch(9).filter { isOff(it) && it.d1 == 26 && it.t > re && it.t >= stop }
        row("DISABLE MainC offs26AfterReenable=${expOff.size} ${r.report}")
        assertTrue("the open note was dropped (router had released it)", stat(r, "dropped") >= 1)
        assertEquals("no experiment release was sent for it", 0, stat(r, "offRelease"))
        assertEquals(0, r.openAfter)
    }

    @Test fun modeChangeWhileHeldClosesImmediately() {
        val st = ew2()
        val r = run(st, "MainC", E343DrumNoteOffMode.AUTHORED, totalMs = 600) { _, m -> Thread.sleep(300); m["mode"] = System.nanoTime(); E343DrumNoteOff.setMode(E343DrumNoteOffMode.LEGACY) }
        val t = r.marks["mode"]!!
        val off = r.ch(9).firstOrNull { isOff(it) && it.d1 == 26 && it.t >= t }
        row("MODE CHANGE MainC off26 after ${off?.let { (it.t - t) / 1_000_000 }} ms ${r.report}")
        assertNotNull(off); assertTrue((off!!.t - t) < 50_000_000)
        assertPaired(r, 26, "MODE")
    }

    @Test fun rhythm1InIntroCIsNeverTouched() {
        val st = ew2()
        val r = run(st, "IntroC", E343DrumNoteOffMode.AUTHORED, totalMs = 1200)
        val stop = r.marks["stop"]!!
        row("IntroC ch9 on=${r.onsBefore(8, stop).size} off=${r.offsBefore(8, stop).size}")
        assertTrue(r.onsBefore(8, stop).isNotEmpty()); assertEquals(0, r.offsBefore(8, stop).size)
    }

    @Test fun rositaIsOutOfScopeInEveryMode() {
        val st = style(); assumeTrue("ROSITA / HardRock2 run only (any Style other than EnglishWaltz2)", !st.fileName.contains("EnglishWaltz2"))
        val base = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500)
        for ((m, gate) in listOf(E343DrumNoteOffMode.AUTHORED to 120, E343DrumNoteOffMode.SHORT_GATE to 40, E343DrumNoteOffMode.SHORT_GATE to 120)) {
            val r = run(st, "MainA", m, gate = gate, totalMs = 1500)
            val stop = r.marks["stop"]!!
            row("OUT-OF-SCOPE style=${st.fileName} mode=${m.name} gate=${gate}ms drumOffs=${r.offsBefore(8, stop).size + r.offsBefore(9, stop).size} ${r.report}")
            assertEquals("ROSITA ${m.name}: no drum NOTE OFF", 0, r.offsBefore(8, stop).size + r.offsBefore(9, stop).size)
            assertEquals("nothing tracked", 0, stat(r, "tracked"))
            for (c in 8..9) {
                val a = base.onsBefore(c, base.marks["stop"]!!).map { it.d1 to it.d2 }; val b = r.onsBefore(c, stop).map { it.d1 to it.d2 }
                val n = minOf(a.size, b.size); assertEquals("ROSITA ch${c + 1} NOTE ON stream unchanged", a.take(n), b.take(n))
            }
        }
    }

    // ---------------- Checkpoint regressions for the user-verified setting: EnglishWaltz2, SHORT_GATE 120 ms ----------------

    private fun perChannel(r: Run, chs: IntRange) = chs.associateWith { c -> r.ch(c).filter { it.t < r.marks["stop"]!! && (it.status and 0xF0) != 0xF0 }.map { Triple(it.status, it.d1, it.d2) } }

    @Test fun shortGate120ClosesNote26After120msAndLeavesEverythingElseAsLegacy() {
        val st = ew2()
        val legacy = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500)
        val r = run(st, "MainA", E343DrumNoteOffMode.SHORT_GATE, gate = 120, totalMs = 1500)
        val stop = r.marks["stop"]!!
        val g = gatesMs(r, 26)
        row("SHORT_GATE 120ms MainA gatesMs=${g.take(12)} ${r.report}")
        assertPaired(r, 26, "SHORT_GATE 120")
        assertTrue("NOTE OFFs were sent", g.size >= 6)
        assertTrue("gate ~120 ms, earlier than the authored ~212 ms at this tempo (host jitter allowed): $g", g.all { it in 105L..175L })
        assertTrue("the 120 ms gate fired (not the authored NOTE OFF)", stat(r, "offGate") >= 6)
        assertTrue("only note 26 gets a NOTE OFF on ch10", r.offsBefore(9, stop).all { it.d1 == 26 })
        assertEquals("Rhythm1 untouched", 0, r.offsBefore(8, stop).size)
        val lo = legacy.onsBefore(9, legacy.marks["stop"]!!).map { it.d1 to it.d2 }; val ao = r.onsBefore(9, stop).map { it.d1 to it.d2 }
        val n = minOf(lo.size, ao.size); assertTrue(n >= 20)
        assertEquals("NOTE ON stream (notes, velocities, order) unchanged", lo.take(n), ao.take(n))
        fun setup(x: Run) = x.usb.filter { (it.status and 0xF0) == 0xC0 || ((it.status and 0xF0) == 0xB0 && it.d1 != 123) }.map { Triple(it.status, it.d1, it.d2) }
        assertEquals("Bank Select / Program Change / controllers unchanged", setup(legacy), setup(r))
        fun eng(x: Run) = x.engine.filter { it.ch in 8..9 && it.t < x.marks["stop"]!! }.map { it.kind to it.note }
        val le = eng(legacy); val re = eng(r); val m = minOf(le.size, re.size); assertTrue(m >= 20)
        assertEquals("internal engine (BASSMIDI side) drum events identical", le.take(m), re.take(m))
        assertEquals("internal engine never receives a drum NOTE OFF", 0, r.engine.count { it.ch in 8..9 && it.kind == 'F' && it.t < stop })
    }

    @Test fun legacyIsIdenticalToTheUnwiredBaselinePathOnEveryStyleChannel() {
        val st = ew2()
        val baseline = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500, wired = false)
        val legacy = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1500)
        val b = perChannel(baseline, 4..15); val l = perChannel(legacy, 4..15)
        for (c in 4..15) {
            val n = minOf(b[c]!!.size, l[c]!!.size)
            assertEquals("ch${c + 1}: LEGACY wire == baseline wire", b[c]!!.take(n), l[c]!!.take(n))
        }
        row("LEGACY == baseline on ch5..16: " + (4..15).joinToString(" ") { "ch${it + 1}=${minOf(b[it]!!.size, l[it]!!.size)}" })
        assertEquals(0, legacy.offsBefore(9, legacy.marks["stop"]!!).size)
    }

    @Test fun englishWaltz3ByNameIsNeverTouchedEvenWithTheSavedShortGate120() {
        // The user's EnglishWaltz3 file is not in the repository; the same drum data under an EnglishWaltz3 file name proves the scope is by Style.
        val st = ew2().copy(fileName = "EnglishWaltz3.S520.prs")
        val r = run(st, "MainA", E343DrumNoteOffMode.SHORT_GATE, gate = 120, totalMs = 1500)
        val stop = r.marks["stop"]!!
        row("EnglishWaltz3 (name) SHORT_GATE 120: drumOffs=${r.offsBefore(8, stop).size + r.offsBefore(9, stop).size} on26=${r.onsBefore(9, stop).count { it.d1 == 26 }} ${r.report}")
        assertTrue("note 26 is played", r.onsBefore(9, stop).count { it.d1 == 26 } >= 6)
        assertEquals("no drum NOTE OFF", 0, r.offsBefore(8, stop).size + r.offsBefore(9, stop).size)
        assertEquals("nothing tracked", 0, stat(r, "tracked"))
    }

    @Test fun keyboardMidiInLeftRightSplitAndAcmpAreUnchangedByShortGate120() {
        val st = ew2()
        // LEFT chord below the default split (54) drives ACMP; RIGHT notes above it. Same input in both runs.
        val play: (RtRig, MutableMap<String, Long>) -> Unit = { rig, _ ->
            Thread.sleep(150); listOf(48, 52, 55).forEach { rig.noteOn(it) }                      // C major, LEFT
            Thread.sleep(250); listOf(72, 76).forEach { rig.noteOn(it, 100) }; Thread.sleep(200); listOf(72, 76).forEach { rig.noteOff(it) }
            Thread.sleep(200); listOf(48, 52, 55).forEach { rig.noteOff(it) }
            Thread.sleep(50); listOf(45, 48, 52).forEach { rig.noteOn(it) }                        // A minor, LEFT
            Thread.sleep(400); listOf(45, 48, 52).forEach { rig.noteOff(it) }
        }
        val legacy = run(st, "MainA", E343DrumNoteOffMode.LEGACY, totalMs = 1800, during = play)
        val r = run(st, "MainA", E343DrumNoteOffMode.SHORT_GATE, gate = 120, totalMs = 1800, during = play)
        fun kb(x: Run) = (0..3).associateWith { c -> x.engine.filter { it.ch == c && it.t < x.marks["stop"]!! }.map { it.kind to it.note } }
        assertEquals("RIGHT/LEFT engine events (MIDI IN -> split -> parts) identical", kb(legacy), kb(r))
        val lk = perChannel(legacy, 0..3); val rk = perChannel(r, 0..3)
        assertEquals("RIGHT/LEFT MIDI OUT bytes identical", lk, rk)
        fun acmp(x: Run) = (10..15).associateWith { c -> x.engine.filter { it.ch == c && it.kind == 'O' && it.t < x.marks["stop"]!! }.map { it.note }.toSortedSet() }
        assertEquals("ACMP chord-following parts play the same pitches", acmp(legacy), acmp(r))
        row("KEYBOARD/SPLIT/ACMP unchanged: rightLeft=" + (0..3).joinToString(" ") { "ch${it + 1}:${kb(r)[it]!!.size}" } + " acmp=" + acmp(r).values.sumOf { it.size } + " pitches")
        assertTrue("the chord really drove ACMP", acmp(r).values.any { it.isNotEmpty() })
    }
}

package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P1 regression (diagnostics/PSR_E343_MIDI_OUT_FILL_AUDIT.md @4e4871c, R1-R5): notes of a section that ended naturally (Fill -> Main from the
 * queue) are released at the boundary; transitions, same-section loops, ordering and MIDI OUT = BASSMIDI parity unchanged.
 * PRODUCTION chain via RtRig (fake USB port, mock synth). HOST evidence only. Chord keys are <= split point 54 so ACMP sees every change.
 */
class FillNoteReleaseP1RtTest {
    private fun row(s: String) = println("P1REG $s")
    private fun isOn(w: RtRig.Wr) = (w.status and 0xF0) == 0x90 && w.d2 > 0
    private fun isOff(w: RtRig.Wr) = (w.status and 0xF0) == 0x80 || ((w.status and 0xF0) == 0x90 && w.d2 == 0)
    private fun isPanic(w: RtRig.Wr) = (w.status and 0xF0) == 0xB0 && (w.d1 == 123 || w.d1 == 120)
    private fun style(): ParsedStyle = StyleTsv.load().also { assumeTrue("style TSV not set", it != null) }!!
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50); private val AM = listOf(45, 48, 52)

    private class Run(val usb: List<RtRig.Wr>, val eng: List<RtRig.Ev>, val sections: List<Pair<Long, String>>, val loops: List<Long>,
                      val chords: List<Pair<Long, Long>>, val stopAt: Long)
    private class Note(val ch: Int, val n: Int, val on: Long, var off: Long = Long.MAX_VALUE)

    private fun play(st: ParsedStyle, start: ArrangerSection, script: (RtRig, MutableList<Long>, Long) -> Unit): Run {
        val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
        val barMs = st.meter.numerator * 60_000L / tempo
        val r = RtRig(true, tempo, st)
        try {
            r.wireProductionMixerOutput()
            r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(false); r.settle(30)
            val seq = r.brain.javaClass.getDeclaredField("sequencer").also { it.isAccessible = true }.get(r.brain)
            val loopTick = seq.javaClass.getMethod("getLoopOriginTick")
            val playing = r.brain.javaClass.getDeclaredField("currentPlayingSection").also { it.isAccessible = true }
            val sections = ArrayList<Pair<Long, String>>(); val loops = ArrayList<Long>(); val applied = ArrayList<Pair<Long, String>>()
            val run = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread {
                var ls = ""; var lt = -1L; var lc = ""
                while (run.get()) {
                    val now = System.nanoTime()
                    val s = playing.get(r.brain).toString(); if (s != ls) { synchronized(sections) { sections += now to s }; ls = s }
                    val t = loopTick.invoke(seq) as Long; if (t != lt) { synchronized(loops) { loops += now }; lt = t }
                    val c = r.brain.state.value.currentChordLabel; if (c != lc) { synchronized(applied) { applied += now to c }; lc = c }
                    Thread.sleep(1)
                }
            }.also { it.isDaemon = true; it.start() }
            r.brain.selectSection(start); C.forEach { r.noteOn(it, 90) }; r.settle(60)
            r.brain.startStop()
            val keys = ArrayList<Long>()
            script(r, keys, barMs)
            val stopAt = System.nanoTime()
            if (r.brain.state.value.isPlaying) r.brain.startStop()
            r.settle(400); run.set(false); sampler.join(200)
            val ap = synchronized(applied) { applied.toList() }
            return Run(r.usbAll.toList().sortedBy { it.t }, r.engineEvents.toList().sortedBy { it.t }, synchronized(sections) { sections.toList() },
                synchronized(loops) { loops.toList() }, keys.map { k -> k to (ap.firstOrNull { it.first >= k }?.first ?: k) }, stopAt)
        } finally { r.close() }
    }
    private fun chord(r: RtRig, from: List<Int>, to: List<Int>, keys: MutableList<Long>) { from.forEach { r.noteOff(it) }; to.forEach { r.noteOn(it, 90) }; keys += System.nanoTime() }

    private fun notes(run: Run, chs: IntRange = 10..15): List<Note> {
        val open = ArrayList<Note>(); val all = ArrayList<Note>()
        for (w in run.usb) { val ch = w.status and 15; if (ch !in chs) continue
            if (isOn(w)) Note(ch, w.d1, w.t).also { open += it; all += it }
            else if (isOff(w)) open.firstOrNull { it.ch == ch && it.n == w.d1 }?.let { it.off = w.t; open.remove(it) }
            else if (isPanic(w)) open.filter { it.ch == ch }.forEach { it.off = w.t; open.remove(it) } }
        return all
    }
    private fun assertPairedAndParity(tag: String, run: Run) {
        for (ch in 10..15) {
            val open = notes(run, ch..ch).count { it.off == Long.MAX_VALUE }
            assertEquals("$tag ch${ch + 1} stuck after STOP", 0, open)
            val mOn = run.usb.count { (it.status and 15) == ch && isOn(it) }; val mOff = run.usb.count { (it.status and 15) == ch && isOff(it) }
            val eOn = run.eng.count { it.ch == ch && it.kind == 'O' }; val eOff = run.eng.count { it.ch == ch && it.kind == 'F' }
            assertEquals("$tag ch${ch + 1} MIDI OUT NOTE ON = BASSMIDI", eOn, mOn); assertEquals("$tag ch${ch + 1} MIDI OUT NOTE OFF = BASSMIDI", eOff, mOff)
        }
    }

    /** Fill notes still sounding 20 ms after the first NOTE ON of the Main that follows the Fill (R1/R2 metric). Also returns Fill notes cut > 30 ms before it. */
    private fun fillCarry(run: Run): Triple<String, List<Note>, List<Note>> {
        val fill = run.sections.firstOrNull { it.second.startsWith("Fill") } ?: return Triple("no fill", emptyList(), emptyList())
        val main = run.sections.firstOrNull { it.first > fill.first && it.second.startsWith("Main") } ?: return Triple("no main after ${fill.second}", emptyList(), emptyList())
        val firstOn = run.usb.firstOrNull { it.t >= main.first && isOn(it) }?.t ?: main.first
        val fillNotes = notes(run).filter { it.on in fill.first until main.first }
        val carried = fillNotes.filter { it.off > firstOn + 20_000_000 }
        val early = fillNotes.filter { it.off < firstOn - 30_000_000 && it.off > fill.first && run.usb.none { w -> isOff(w) && w.t == it.off } }
        return Triple("${fill.second}→${main.second}", carried, early)
    }

    /** R1: Main B -> Main C plays the target fill (FillCC where no FillBC exists); its notes must not ring into Main C. */
    @Test fun r1_fillToMain() {
        val st = style()
        val run = play(st, ArrangerSection.MainB) { r, _, bar -> r.settle(bar * 2 + bar / 4); r.brain.selectMainVariation(ArrangerSection.MainC); r.settle(bar * 4); C.forEach { r.noteOff(it) } }
        val (label, carried, _) = fillCarry(run)
        row("R1 '${st.fileName}' $label carried>20ms=${carried.size} " + carried.joinToString(",") { "ch${it.ch + 1}:${it.n}+${(it.off - it.on) / 1_000_000}ms" })
        assertPairedAndParity("R1", run)
        assertTrue("R1 '${st.fileName}' $label: Fill notes ringing into Main: ${carried.map { "ch${it.ch + 1}:${it.n}" }}", carried.isEmpty())
    }

    /** R2: the fills that were already clean (MainA -> MainB, MainB -> MainA) stay clean. */
    @Test fun r2_regularFills() {
        val st = style()
        for ((from, to) in listOf(ArrangerSection.MainA to ArrangerSection.MainB, ArrangerSection.MainB to ArrangerSection.MainA)) {
            val run = play(st, from) { r, _, bar -> r.settle(bar * 2 + bar / 4); r.brain.selectMainVariation(to); r.settle(bar * 4); C.forEach { r.noteOff(it) } }
            val (label, carried, _) = fillCarry(run)
            row("R2 '${st.fileName}' $label carried>20ms=${carried.size}")
            assertPairedAndParity("R2 $label", run)
            assertTrue("R2 '${st.fileName}' $label carried ${carried.size}", carried.isEmpty())
        }
    }

    /** R3: one Main looped three times: no note is released at a loop boundary unless the same key is struck again at once (re-strike, not a cut). */
    @Test fun r3_sameMainLoops() {
        val st = style()
        val mainC = st.sections["MainC"]; assumeTrue("no MainC", mainC != null)
        val loopMs = mainC!!.lengthTicks.toLong() * 60_000L / ((st.defaultTempoBpm.takeIf { it > 0 } ?: 120).toLong() * st.ppq)
        val run = play(st, ArrangerSection.MainC) { r, _, bar -> r.settle(loopMs * 3 + bar / 2); C.forEach { r.noteOff(it) } }   // three full loops of this Style's MainC
        val loopStarts = run.loops.drop(1)
        var cut = 0; var restrike = 0
        for (n in notes(run)) { if (n.off == Long.MAX_VALUE || n.off > run.stopAt) continue
            if (loopStarts.none { Math.abs(n.off - it) < 15_000_000 }) continue
            if (run.usb.any { isOn(it) && (it.status and 15) == n.ch && it.d1 == n.n && it.t in n.off..(n.off + 3_000_000) }) restrike++ else cut++ }
        row("R3 '${st.fileName}' MainC loops=${loopStarts.size + 1} notesEndingAtLoopBoundary: restruck=$restrike endedWithoutRestrike=$cut")
        assertPairedAndParity("R3", run)
        assertTrue("R3 needs >= 3 loops (got ${loopStarts.size + 1})", loopStarts.size >= 2)
    }

    /** R4 (observe only, F8 not fixed): NOTE ONs closed within 2 ms on the same key (same-tick ON/OFF ordering) per channel, for before/after comparison. */
    @Test fun r4_sameTickOrdering() {
        val st = style()
        val run = play(st, ArrangerSection.MainA) { r, k, bar -> r.settle(bar * 2); r.brain.selectMainVariation(ArrangerSection.MainB); r.settle(bar * 3); chord(r, C, F, k); r.settle(bar * 2); F.forEach { r.noteOff(it) } }
        val ns = notes(run).filter { it.off <= run.stopAt }
        val per = (10..15).associateWith { ch -> ns.count { it.ch == ch && it.off - it.on < 2_000_000 } }.filterValues { it > 0 }
        val retrig = (10..15).associateWith { ch -> ns.filter { it.ch == ch }.let { l -> l.count { a -> l.any { b -> b !== a && b.n == a.n && b.on in a.on until a.off } } } }.filterValues { it > 0 }
        row("R4 '${st.fileName}' zeroLengthNotes(<2ms)=$per sameKeyOverlap=$retrig")
        assertPairedAndParity("R4", run)
    }

    /** R5: MIDI OUT = BASSMIDI over Main -> Fill -> Main with chord changes in the Fill and after it; chord response unchanged (applied <= 40 ms, no old note > 150 ms). */
    @Test fun r5_parityAndChordResponse() {
        val st = style()
        val run = play(st, ArrangerSection.MainA) { r, k, bar ->
            r.settle(bar * 2 + bar / 4); r.brain.selectMainVariation(ArrangerSection.MainB); r.settle(bar * 3 / 4 + bar / 3)
            chord(r, C, F, k); r.settle(bar * 5 / 3); chord(r, F, G, k); r.settle(bar / 2); chord(r, G, AM, k); r.settle(bar * 2); AM.forEach { r.noteOff(it) } }
        assertPairedAndParity("R5", run)
        val ns = notes(run)
        for ((key, applied) in run.chords) {
            val old = ns.filter { it.on < key && it.off > applied + 150_000_000 && it.off <= run.stopAt }
            val lag = (applied - key) / 1_000_000
            row("R5 '${st.fileName}' chord applied +${lag}ms oldNotes>150ms=${old.size} " + old.joinToString(",") { "ch${it.ch + 1}:${it.n}" })
            assertTrue("R5 chord applied late: $lag ms", lag <= 40)
        }
    }
}

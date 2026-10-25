package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.ArrangerBrainClock
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Style Editor V2 on the production chain (RtRig = MIDI parser -> Brain -> Sequencer -> P1 ordered I/O -> engine bridge + MIDI OUT) with a REAL Style
 * (ROSITA.STY, or HardRock2.S578.prs when SGAP_STYLE_TSV points at it). Octave shift per melodic part and drum note maps are compared against a baseline run of the
 * same Style, same chord, same tempo, over windows that are defined by Style ticks (fill / first bar after the fill), so the compared content is identical.
 * HOST evidence only: nothing here says how a SoundFont or the PSR-E343 sounds.
 */
class StyleEditorV2Test {
    private fun loadStyle(): ParsedStyle? {
        val path = System.getenv("SGAP_STYLE_TSV") ?: System.getenv("ROSITA_TSV") ?: return null
        val sections = linkedMapOf<String, StyleSectionModel>(); var ppq = 480; var name = ""; var len = 0
        var parts = ArrayList<StylePartModel>(); var events = ArrayList<StyleNoteEvent>(); var policies = ArrayList<CasmPolicyModel>(); var partName: String? = null
        fun closePart() {
            val n = partName ?: return; var msb = 0; var lsb = 0; var program = -1
            events.filter { it.tick == 0 }.forEach { e -> when { e.isControlChange && e.note == 0 -> msb = e.velocity; e.isControlChange && e.note == 32 -> lsb = e.velocity; e.isProgramChange -> program = e.note } }
            parts += StylePartModel(n, events, policies.firstOrNull(), policies, program, msb, lsb); events = ArrayList(); policies = ArrayList(); partName = null
        }
        fun closeSection() { closePart(); if (name.isNotEmpty()) sections[name] = StyleSectionModel(name, len, parts); parts = ArrayList() }
        File(path).readLines(Charsets.ISO_8859_1).forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "PPQ" -> ppq = f[1].toInt(); "S" -> { closeSection(); name = f[1]; len = f[2].toInt() }; "P" -> { closePart(); partName = f.getOrElse(1) { "" }.trim() }
                "C" -> policies += CasmPolicyModel(f[1].toInt(), f[2].toInt(), f[3].trim(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12] == "1", f[13].toLong(), f[14].toInt(), f[15].toInt())
                "E" -> { val st = f[2].toInt(); if (st in 0x80..0xEF) events += StyleNoteEvent(f[1].toInt(), (st and 0xF0) == 0x90 && f[4].toInt() > 0, f[3].toInt(), f[4].toInt(), st and 0x0F, st) }
            }
        }
        closeSection(); return ParsedStyle(System.getenv("SGAP_STYLE_NAME") ?: "ROSITA.STY", ppq, sections, emptyMap(), 120)
    }

    /** One run of the same performance: ACMP chord C, Main A, Fill AA requested mid-bar, optional chord change inside the fill, STOP. */
    class Out(val ev: List<RtRig.Ev>, val usb: List<RtRig.Wr>, val fillFrom: Long, val fillTo: Long, val mainFrom: Long, val mainTo: Long, val stuck: Int, val orphan: Long)

    private fun run(style: ParsedStyle, midChord: Boolean = false, configure: (RtRig) -> Unit = {}): Out {
        DebugLog.clear()
        val r = RtRig(true, 120, style); r.brain.setTempo(280); r.wireProductionMixerOutput(); r.brain.setLeftVoiceEnabled(false)
        configure(r)
        val bar = style.ppq.toLong() * 4L
        val clock = ArrangerBrainClock(r.brain)
        fun transitions() = DebugLog.getFullAll().mapNotNull { Regex("MASTER CLOCK → (\\w+) at tick=(\\d+)").find(it)?.let { m -> m.groupValues[1] to m.groupValues[2].toLong() } }
        r.brain.startStop(); Thread.sleep(200)
        listOf(36, 40, 43).forEach { r.noteOn(it) }; Thread.sleep(60); listOf(36, 40, 43).forEach { r.noteOff(it) }
        var g = 0; while (g++ < 6000) { val t = clock.masterTickNow(); val d = (3840L - (((t % bar) + bar) % bar) + bar) % bar; if (d < 12) break; Thread.sleep(1) }
        r.brain.selectSection(ArrangerSection.FillAA)
        val deadline = System.currentTimeMillis() + 20_000
        while (transitions().size < 2 && System.currentTimeMillis() < deadline) {
            if (midChord && transitions().size == 1) { Thread.sleep(180); listOf(41, 45, 48).forEach { r.noteOn(it) }; Thread.sleep(60); listOf(41, 45, 48).forEach { r.noteOff(it) } }
            Thread.sleep(5)
        }
        val tr = transitions(); assertTrue("fill and the following main must both start: $tr", tr.size >= 2)
        val b1 = tr[0].second; val b2 = tr[1].second
        val n1 = clock.tickToNanos(b1)!!; val n2 = clock.tickToNanos(b2)!!; val n3 = clock.tickToNanos(b2 + bar)!!
        while (System.nanoTime() < n3 + 30_000_000L) Thread.sleep(5)
        val ev = r.engineEvents.toList(); val usb = r.usbAll.toList()
        r.brain.startStop(); Thread.sleep(400)
        val stuck = r.oracle.stuckOnChannels(0..15); val orphan = r.oracle.orphanOffs
        r.close()
        val m = 1_000_000L
        return Out(ev, usb, n1 - m, n2 - m, n2 - m, n3 - m, stuck, orphan)
    }

    private fun Out.fillOn(ch: Int) = ev.filter { it.kind == 'O' && it.ch == ch && it.t in fillFrom until fillTo }.sortedBy { it.t }.map { it.note }
    private fun Out.mainOn(ch: Int) = ev.filter { it.kind == 'O' && it.ch == ch && it.t in mainFrom until mainTo }.sortedBy { it.t }.map { it.note }
    private fun Out.fillUsbOn(ch: Int) = usb.filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == ch && it.d2 > 0 && it.t in fillFrom until fillTo }.sortedBy { it.t }
    private fun shifted(notes: List<Int>, octaves: Int) = notes.map { (it + 12 * octaves).coerceIn(0, 127) }.toSet()

    private fun assertBalanced(o: Out, what: String) {
        val bal = HashMap<Int, Int>()
        for (e in o.ev.sortedBy { it.t }) { val k = e.ch * 256 + e.note
            if (e.kind == 'O') bal.merge(k, 1, Int::plus) else if (e.kind == 'F') { val v = (bal[k] ?: 0) - 1; assertTrue("$what: NOTE_OFF before its NOTE_ON on ch${e.ch} note ${e.note}", v >= 0); bal[k] = v } else if (e.kind == 'A') bal.clear() }
        assertEquals("$what: stuck notes after STOP", 0, o.stuck); assertEquals("$what: orphan NOTE_OFF", 0L, o.orphan)
        // MIDI OUT: every NOTE_ON is released again (STOP/disable flush), and no NOTE_OFF precedes its NOTE_ON
        val u = HashMap<Int, Int>()
        for (w in o.usb.sortedBy { it.t }) { val ch = w.status and 15; val k = ch * 256 + w.d1
            when (w.status and 0xF0) { 0x90 -> if (w.d2 > 0) u.merge(k, 1, Int::plus) else u.merge(k, -1, Int::plus); 0x80 -> u.merge(k, -1, Int::plus) } }
        assertTrue("$what: MIDI OUT NOTE_OFF without NOTE_ON ${u.filterValues { it < 0 }}", u.values.none { it < 0 })
    }

    companion object { val baselines = HashMap<String, Out>() }
    private fun baseline(style: ParsedStyle): Out = synchronized(baselines) { baselines.getOrPut(style.fileName) { run(style) } }

    private fun twoMelodic(b: Out) = (10..15).filter { b.fillOn(it).isNotEmpty() }.sortedByDescending { b.fillOn(it).size }.take(2)
    private fun out(line: String) = println("SGAP v2 $line")

    @Test fun octaveShiftMovesOnlyTheChosenPartsAndOnlyAfterTheChordIsApplied() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val base = baseline(style); val (a, b) = twoMelodic(base).let { it[0] to it[1] }
        val s = run(style) { it.brain.setStyleOctaveShift(null, a, 1); it.brain.setStyleOctaveShift(null, b, -2) }
        out("octave style=${style.fileName} partA=ch$a(+1) partB=ch$b(-2) baseFillA=${base.fillOn(a).toSet().sorted()} shiftedFillA=${s.fillOn(a).toSet().sorted()}")
        assertEquals(shifted(base.fillOn(a), 1), s.fillOn(a).toSet()); assertEquals(shifted(base.fillOn(b), -2), s.fillOn(b).toSet())
        assertEquals(shifted(base.mainOn(a), 1), s.mainOn(a).toSet()); assertEquals(shifted(base.mainOn(b), -2), s.mainOn(b).toSet())
        for (ch in (4..15).filter { it != a && it != b }) { assertEquals("ch$ch untouched (fill)", base.fillOn(ch).toSet(), s.fillOn(ch).toSet()); assertEquals("ch$ch untouched (main)", base.mainOn(ch).toSet(), s.mainOn(ch).toSet()) }
        assertEquals("same number of notes: nothing added or dropped", base.fillOn(a).size, s.fillOn(a).size)
        assertEquals("MIDI OUT gets the same shifted pitches", s.fillOn(a).toSet(), s.fillUsbOn(a).map { it.d1 }.toSet())
        assertBalanced(s, "octave")
    }

    @Test fun sectionScopeBeatsPartWideAndASectionZeroOverridesIt() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val base = baseline(style); val a = twoMelodic(base)[0]
        val onlyFill = run(style) { it.brain.setStyleOctaveShift("FillAA", a, 1) }
        assertEquals("fill shifted", shifted(base.fillOn(a), 1), onlyFill.fillOn(a).toSet()); assertEquals("main untouched", base.mainOn(a).toSet(), onlyFill.mainOn(a).toSet())
        val zeroInFill = run(style) { it.brain.setStyleOctaveShift(null, a, 2); it.brain.setStyleOctaveShift("FillAA", a, 0) }
        assertEquals("explicit section 0 beats part-wide +2", base.fillOn(a).toSet(), zeroInFill.fillOn(a).toSet()); assertEquals("part-wide applies elsewhere", shifted(base.mainOn(a), 2), zeroInFill.mainOn(a).toSet())
        assertBalanced(onlyFill, "section octave"); assertBalanced(zeroInFill, "section zero")
    }

    @Test fun octaveSurvivesAChordChangeAndStopWithoutStuckOrOrphanNotes() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val base = baseline(style); val (a, b) = twoMelodic(base).let { it[0] to it[1] }
        val s = run(style, midChord = true) { it.brain.setStyleOctaveShift(null, a, 2); it.brain.setStyleOctaveShift(null, b, -1) }
        out("octaveChordChange style=${style.fileName} ch$a(+2) ch$b(-1) notesA=${s.fillOn(a).size} stuck=${s.stuck} orphan=${s.orphan}")
        assertBalanced(s, "octave + chord change")
        assertTrue("shifted notes were played", s.fillOn(a).isNotEmpty() && s.fillOn(b).isNotEmpty())
    }

    @Test fun invalidEditsAreRefusedAndChangeNothing() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val r = RtRig(false, 120, style)
        assertFalse(r.brain.setStyleOctaveShift(null, 9, 1)); assertFalse(r.brain.setStyleOctaveShift(null, 2, 1)); assertFalse(r.brain.setStyleOctaveShift(null, 10, 3)); assertFalse(r.brain.setStyleOctaveShift(null, 10, -3))
        assertFalse(r.brain.setStyleDrumMap(null, 10, 0, mapOf(36 to 40))); assertFalse(r.brain.setStyleDrumMap(null, 9, 2, mapOf(36 to 40)))
        assertFalse(r.brain.setStyleDrumMap(null, 9, 0, mapOf(36 to 128))); assertFalse(r.brain.setStyleDrumMap(null, 9, 0, mapOf(-1 to 3)))
        assertTrue(r.brain.setStyleOctaveShift(null, 10, 2)); assertTrue(r.brain.setStyleOctaveShift(null, 10, null)); assertTrue(r.brain.setStyleDrumMap(null, 9, 1, mapOf(36 to 40))); assertTrue(r.brain.setStyleDrumMap(null, 9, 1, null))
        r.close()
    }

    private fun pickUnused(used: Set<Int>, from: Int) = (from..127).first { it !in used }

    @Test fun drumMapChangesOnlyTheNoteNumberPerOutputKeepsOrderAndVelocityAndNeverDropsAnEvent() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val base = baseline(style)
        val ch = listOf(9, 8).first { base.fillOn(it).isNotEmpty() }
        val seq = base.fillOn(ch); val counts = seq.groupingBy { it }.eachCount()
        val top = counts.entries.sortedByDescending { it.value }.map { it.key }
        val n1 = top[0]; val n2 = top.getOrElse(1) { top[0] }
        val used = counts.keys; val t1 = pickUnused(used, 90); val t2 = pickUnused(used + t1, t1 + 1)
        val internal = mutableMapOf(n1 to t1).apply { if (n2 != n1) put(n2, t1) }   // many source notes -> one target
        val m = run(style) { it.brain.setStyleDrumMap(null, ch, 0, internal); it.brain.setStyleDrumMap(null, ch, 1, mapOf(n1 to t2)) }
        val expectedInternal = seq.map { internal[it] ?: it }
        out("drum style=${style.fileName} ch$ch ${n1}x${counts[n1]}${if (n2 != n1) "+${n2}x${counts[n2]}" else ""} internal->$t1 midiOut $n1->$t2 events=${seq.size}")
        assertEquals("engine: same events, same order, only the pitch number differs", expectedInternal, m.fillOn(ch))
        assertEquals("engine: several sources share one target without dropping events", (counts[n1] ?: 0) + (if (n2 != n1) counts[n2] ?: 0 else 0), m.fillOn(ch).count { it == t1 })
        val bu = base.fillUsbOn(ch); val mu = m.fillUsbOn(ch)
        assertEquals("MIDI OUT: separate map", bu.map { if (it.d1 == n1) t2 else it.d1 }, mu.map { it.d1 })
        assertEquals("MIDI OUT: velocities untouched", bu.map { it.d2 }, mu.map { it.d2 })
        assertTrue("MIDI OUT never receives the internal target", mu.none { it.d1 == t1 })
        for (other in (4..15).filter { it != ch }) assertEquals("ch$other untouched", base.fillOn(other).toSet(), m.fillOn(other).toSet())
        assertBalanced(m, "drum map")
    }

    @Test fun drumMapSectionScopeAndRemovalRestoreTheStyleExactly() {
        val style = loadStyle(); assumeTrue("style TSV not set", style != null); style!!
        val base = baseline(style); val ch = listOf(9, 8).first { base.fillOn(it).isNotEmpty() }
        val n = base.fillOn(ch).first(); val t = pickUnused(base.fillOn(ch).toSet() + base.mainOn(ch).toSet(), 90)
        val fillOnly = run(style) { it.brain.setStyleDrumMap("FillAA", ch, 0, mapOf(n to t)) }
        assertEquals(base.fillOn(ch).map { if (it == n) t else it }, fillOnly.fillOn(ch)); assertEquals("main bar untouched", base.mainOn(ch), fillOnly.mainOn(ch))
        val removed = run(style) { it.brain.setStyleDrumMap(null, ch, 0, mapOf(n to t)); it.brain.setStyleDrumMap(null, ch, 0, null) }
        assertEquals("removing the map restores the Style", base.fillOn(ch), removed.fillOn(ch))
        assertBalanced(fillOnly, "section drum map")
    }
}

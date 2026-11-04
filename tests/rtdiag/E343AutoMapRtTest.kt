package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.e343out.*
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PSR-E343 AUTO VOICE MAPPING + AUTO BALANCE through the PRODUCTION chain (ArrangerBrain -> StyleSequencer -> AudioEngineManager ->
 * MidiInputManager.e343Out -> MidiMixerOutput -> USB port fake). HOST evidence: what is WRITTEN to the cable, never what the E343 sounds like.
 * Style-specific checks run with --style "Love Song.T547.prs" (assumed otherwise); the catalog checks always run.
 */
class E343AutoMapRtTest {
    private val catalog by lazy { E343Catalog.parse(File("docs/yamaha/E343_VOICES.tsv").readText(), File("docs/yamaha/E343_DRUMKITS.tsv").readText()) }
    private val mapper by lazy { E343VoiceMapper(catalog) }
    private fun row(s: String) = println("E343AUTO $s")
    private fun loveSong(): ParsedStyle = StyleTsv.load().also { assumeTrue("Love Song TSV not set", it != null && it.fileName.contains("Love Song", true)) }!!
    private fun isOn(w: RtRig.Wr) = (w.status and 0xF0) == 0x90 && w.d2 > 0
    private fun isOff(w: RtRig.Wr) = (w.status and 0xF0) == 0x80 || ((w.status and 0xF0) == 0x90 && w.d2 == 0)

    @Test fun catalogAuditAndEveryTargetExists() {
        assertEquals("MIDI-selectable rows (523 = 550 list numbers minus 27 DUAL)", 523, catalog.voices.size)
        assertEquals(18, catalog.kits.size)
        assertEquals("program column is 1-based in the TSV, stored 0-based", 0, catalog.byName("Grand Piano")!!.program)
        assertEquals(33, catalog.byName("Finger Bass")!!.program)
        assertEquals(72, catalog.kitNotes(catalog.byName("Standard Kit 1")!!).size)
        for (t in mapper.characterTargets.toSet()) assertNotNull("character target '$t' must be an E343 list voice", catalog.byName(t))
        row("CATALOG voices=${catalog.voices.size} kits=${catalog.kits.size} characterTargets=${mapper.characterTargets.toSet().size} all in list")
    }

    @Test fun mappingRules() {
        fun m(ch: Int, msb: Int, lsb: Int, pc1: Int, name: String, drum: Boolean = false) = mapper.map(ch, msb, lsb, pc1 - 1, name, drum)
        assertEquals(E343MapStatus.ORIGINAL_COMPATIBLE, m(10, 0, 112, 34, "Bass").status)                     // listed: kept
        assertEquals("Finger Bass", m(10, 8, 4, 18, "Bass").target!!.name)                                    // Mega bass, name -> Bass family
        assertEquals("Grand Piano", m(11, 104, 21, 1, "Piano rt").target!!.name)
        assertEquals("String Ensemble", m(13, 8, 5, 50, "Strings1").target!!.name)
        assertEquals("Slow Strings", m(13, 8, 5, 50, "Slow Str").target!!.name)
        assertEquals("Folk Guitar", m(12, 8, 10, 2, "A.Guitar").target!!.name)
        assertEquals("Classical Guitar", m(12, 8, 10, 2, "Nylon Gtr").target!!.name)
        assertEquals("Clean Guitar", m(12, 8, 1, 28, "CleanGtr").target!!.name)
        assertEquals("Fretless Bass", m(10, 8, 0, 36, "Fretless").target!!.name)
        assertEquals("same GM program, E343 panel variation", "Finger Bass", m(10, 0, 114, 34, "").target!!.name)
        assertEquals("Mega program numbers are not GM families: no guess", E343MapStatus.NO_SAFE_CANDIDATE, m(12, 8, 10, 2, "").status)
        assertEquals("Bass part with nothing to go on: role fallback", E343MapStatus.ROLE_FALLBACK, m(10, 8, 4, 18, "").status)
        assertEquals("Standard Kit 1", m(8, 127, 0, 74, "Drums", true).target!!.name)
        assertEquals("Brush Kit", m(9, 127, 0, 74, "BrushKit", true).target!!.name)
        assertEquals(E343MapStatus.ORIGINAL_COMPATIBLE, m(9, 127, 0, 1, "Drums", true).status)
        // drum note audit: a kit candidate that leaves too many notes silent is not applied
        val u = E343PartUsage().also { it.drumNotes[100] = 50; it.drumNotes[36] = 5; it.velocity[100] = 55 }
        val k = mapper.map(9, 127, 0, 74, "Drums", true, u)
        assertEquals(E343MapStatus.NO_SAFE_CANDIDATE, k.status); assertEquals(127, k.outMsb); assertEquals(74, k.outProgram)
        // Mega velocity zones are reported
        val g = E343PartUsage().also { it.velocity[22] = 30; it.velocity[120] = 4 }
        assertTrue(mapper.map(12, 8, 10, 2, "A.Guitar", false, g).warnings.any { it.contains("106-127") })
    }

    @Test fun balancePlannerDoesNotBoostAndKeepsReference() {
        val lv = mapOf(10 to E343PartLevel(10, 10, 60, 60, 127), 11 to E343PartLevel(11, 10, 100, 120, 127), 12 to E343PartLevel(12, 10, 20, 60, 127))
        val b = E343BalancePlanner.plan(lv)
        assertEquals(127, b[10]!!.autoTrim); assertEquals(127, b[12]!!.autoTrim); assertTrue(b[11]!!.autoTrim < 127)
        assertTrue((8..15).all { b[it]!!.autoTrim in 1..127 })
    }

    @Test fun loveSongPlanMappingAndBalance() {
        val st = loveSong()
        val plan = E343StylePlanner.plan(st, mapper)
        for (d in plan.decisions.sortedBy { it.channel }) {
            row("MAP ${E343Roles.of(d.channel)} ch${d.channel + 1} request=${d.requestText} -> ${d.status.label} ${d.outText} | ${d.reason}" +
                d.warnings.joinToString("") { " | WARN $it" })
            if (d.status != E343MapStatus.NO_SAFE_CANDIDATE) assertTrue("sent voice in the E343 list: ${d.outText}", catalog.contains(d.outMsb, d.outLsb, d.outProgram))
        }
        for (ch in 8..15) plan.usage[ch]?.let { u -> if (ch <= 9) plan.decisions.firstOrNull { it.channel == ch && it.target != null }?.let { d ->
            row("KIT ${E343Roles.of(ch)} '${d.target!!.name}': ${mapper.kitNoteNames(d.target!!, u.drumNotes.keys)}") } }
        for (ch in 8..15) { val b = plan.balance.getValue(ch); val l = b.level
            row("BAL ${E343Roles.of(ch)} ch${ch + 1} " + (l?.let { "notes=${it.noteOns} velP50=${it.velocityMedian} CC7=${it.cc7} CC11=${it.cc11} level=%.1f dB ".format(it.levelDb) } ?: "") +
                "autoTrim=${b.autoTrim} | ${b.reason}") }
        // Fixture: the user's ear balance on the E343 (slider positions, NOT measurements): R1 127 R2 127 Bass 127 Chord1 63 Chord2 127 Pad 99 Phrase 97.
        val ear = mapOf(8 to 127, 9 to 127, 10 to 127, 11 to 63, 12 to 127, 13 to 99, 14 to 97, 15 to 97)
        for ((ch, v) in ear) { val auto = plan.balance.getValue(ch).autoTrim
            val diff = E343BalancePlanner.cutForTrim(auto) - E343BalancePlanner.cutForTrim(v)
            row("FIXTURE ${E343Roles.of(ch)} ear=$v auto=$auto diff=%.1f dB".format(-diff))
            if (plan.balance.getValue(ch).level != null) assertTrue("${E343Roles.of(ch)} within 3 dB of the ear balance (auto $auto vs $v)", Math.abs(diff) <= 3.0)
        }
        assertTrue("Piano (Chord 1) is lowered", plan.balance.getValue(11).autoTrim < 127)
        assertEquals("Bass reference untouched", 127, plan.balance.getValue(10).autoTrim)
    }

    private class Run(val usb: List<RtRig.Wr>, val state: List<E343OutAdapter.PartState>)
    private fun play(st: ParsedStyle, mode: E343OutMode, sections: List<ArrangerSection>, sliders: Map<Int, Int> = emptyMap(), manualTrims: Map<Int, Int> = emptyMap(),
                     barMs: Long = 4L * 60_000 / (st.defaultTempoBpm.takeIf { it > 0 } ?: 120)): Run {
        val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
        val r = RtRig(true, tempo, st)
        try {
            r.wireProductionMixerOutput()
            val out = r.midi.e343Out
            out.setCatalog(catalog); out.setStyle(E343StylePlanner.plan(st, mapper), emptyMap(), manualTrims); out.setMode(mode)
            out.mixerSlider = { ch -> sliders[ch] ?: 127 }
            sliders.forEach { (ch, v) -> r.brain.setStyleChannelVolume(ch, v) }
            r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(false); r.settle(30)
            r.brain.selectSection(sections.first())
            listOf(48, 52, 55).forEach { r.noteOn(it, 90) }; r.settle(60)
            r.brain.startStop()
            val chords = listOf(listOf(48, 52, 55), listOf(53, 57, 60), listOf(55, 59, 62))   // C -> F -> G
            var held = chords[0]
            for ((i, s) in sections.withIndex()) {
                if (i > 0) r.brain.selectSection(s)
                for (c in chords) { held.forEach { r.noteOff(it) }; c.forEach { r.noteOn(it, 90) }; held = c; r.settle(barMs / 2) }
                r.settle(barMs)
            }
            if (r.brain.state.value.isPlaying) r.brain.startStop()   // an Ending stops by itself
            r.settle(400)
            held.forEach { r.noteOff(it) }; r.settle(50)
            return Run(r.usbAll.toList().sortedBy { it.t }, out.state())
        } finally { r.close() }
    }

    private val tour = listOf(ArrangerSection.IntroA, ArrangerSection.MainA, ArrangerSection.MainB, ArrangerSection.FillBC, ArrangerSection.MainC, ArrangerSection.MainD, ArrangerSection.EndingA)

    @Test fun loveSongAutoBalanceOnTheWire() {
        val st = loveSong()
        val run = play(st, E343OutMode.AUTO_BALANCE, tour)
        val voices = HashMap<Int, MutableSet<Triple<Int, Int, Int>>>()
        val bank = IntArray(16); val lsb = IntArray(16)
        for (w in run.usb) { val ch = w.status and 15; if (ch !in 8..15) continue
            when (w.status and 0xF0) { 0xB0 -> if (w.d1 == 0) bank[ch] = w.d2 else if (w.d1 == 32) lsb[ch] = w.d2; 0xC0 -> voices.getOrPut(ch) { HashSet() } += Triple(bank[ch], lsb[ch], w.d1) } }
        for ((ch, set) in voices.toSortedMap()) for ((m, l, p) in set) {
            val v = catalog.find(m, l, p)
            row("WIRE ${E343Roles.of(ch)} ch${ch + 1} Bank/PC $m/$l/${p + 1} ${v?.name ?: "NOT IN E343 LIST"}")
            assertNotNull("ch${ch + 1} wrote $m/$l/${p + 1}, not an E343 voice", v)
        }
        assertTrue("Bass, Piano, Guitar, Strings and both drum parts got a voice", voices.keys.containsAll(listOf(8, 9, 10, 11, 12, 13)))
        // Stuck notes: every melodic NOTE ON closed (drums: E343 LEGACY sends no drum NOTE OFF by design, checked elsewhere)
        for (ch in 10..15) {
            val open = HashMap<Int, Int>()
            for (w in run.usb.filter { (it.status and 15) == ch }) { if (isOn(w)) open.merge(w.d1, 1, Int::plus) else if (isOff(w)) open.computeIfPresent(w.d1) { _, c -> (c - 1).takeIf { it > 0 } } else if ((w.status and 0xF0) == 0xB0 && (w.d1 == 123 || w.d1 == 120)) open.clear() }   // STOP panic CC123/CC120 also releases
            assertTrue("ch${ch + 1} stuck notes $open", open.isEmpty())
        }
        // CC7 written = Style CC7 x trim; CC11 untouched
        for (s in run.state) {
            row("STATE ${E343Roles.of(s.channel)} ch${s.channel + 1} decision=${s.decision?.status?.label ?: "-"} ${s.decision?.outText ?: ""} trim=${s.trim} (${s.trimSource}) styleCC7=${s.rawCc7} sentCC7=${s.sentCc7}")
            if (s.rawCc7 >= 0) assertEquals((s.rawCc7 * s.trim + 63) / 127, s.sentCc7)
        }
        val cc7 = run.usb.filter { it.status == 0xBB && it.d1 == 7 }.map { it.d2 }.distinct()
        row("WIRE Chord1 ch12 CC7 values $cc7")
        assertTrue("Piano CC7 lowered on the wire", cc7.isNotEmpty() && cc7.all { it < 81 })
    }

    @Test fun originalModeSendsTheStyleRequestsUnchanged() {
        val st = loveSong()
        val run = play(st, E343OutMode.ORIGINAL, listOf(ArrangerSection.MainA))
        val pcs = run.usb.filter { (it.status and 0xF0) == 0xC0 && (it.status and 15) == 10 }.map { it.d1 }
        assertEquals("Bass request (8/4/18) unchanged in ORIGINAL", listOf(17), pcs.distinct())
        assertEquals(listOf(52), run.usb.filter { it.status == 0xBA && it.d1 == 7 }.map { it.d2 }.distinct())
        assertEquals(listOf(81), run.usb.filter { it.status == 0xBB && it.d1 == 7 }.map { it.d2 }.distinct())
        assertTrue(run.state.all { it.decision == null && it.trim == 127 })
    }

    @Test fun manualMixerAndManualTrimWin() {
        val st = loveSong()
        // Mixer slider moved by the user on Chord1: no automatic trim there (the slider alone acts, as before)
        val a = play(st, E343OutMode.AUTO_BALANCE, listOf(ArrangerSection.MainA), sliders = mapOf(11 to 63))
        val p = a.state.first { it.channel == 11 }
        row("MANUAL slider Chord1=63 -> trim=${p.trim} (${p.trimSource}) styleCC7=${p.rawCc7} sent=${p.sentCc7}")
        assertEquals(127, p.trim); assertEquals(p.rawCc7, p.sentCc7); assertEquals((81 * 63 + 63) / 127, p.rawCc7)
        // Saved manual MIDI OUT trim beats auto
        val b = play(st, E343OutMode.AUTO_BALANCE, listOf(ArrangerSection.MainA), manualTrims = mapOf(13 to 110))
        val q = b.state.first { it.channel == 13 }
        assertEquals(110, q.trim); assertTrue(q.trimSource.startsWith("MANUAL"))
    }

    @Test fun modeSwitchWhilePlayingResendsAndRestores() {
        val st = loveSong()
        val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
        val r = RtRig(true, tempo, st)
        try {
            r.wireProductionMixerOutput()
            val out = r.midi.e343Out; out.setCatalog(catalog); out.setStyle(E343StylePlanner.plan(st, mapper), emptyMap(), emptyMap())
            r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(false); r.settle(30)
            listOf(48, 52, 55).forEach { r.noteOn(it, 90) }; r.settle(60)
            r.brain.startStop(); r.settle(800)
            val m0 = r.usbAll.size
            out.setMode(E343OutMode.AUTO_BALANCE); r.settle(300)
            val auto = r.usbAll.toList().drop(m0)
            val m1 = r.usbAll.size
            out.setMode(E343OutMode.ORIGINAL); r.settle(300)
            val back = r.usbAll.toList().drop(m1)
            r.brain.startStop(); r.settle(300); listOf(48, 52, 55).forEach { r.noteOff(it) }
            assertTrue("AUTO: Bass gets Finger Bass at once", auto.any { it.status == 0xCA && it.d1 == 33 })
            assertTrue("AUTO: Chord1 CC7 lowered at once", auto.any { it.status == 0xBB && it.d1 == 7 && it.d2 < 81 })
            assertTrue("ORIGINAL again: Style request restored", back.any { it.status == 0xCA && it.d1 == 17 })
            assertTrue("ORIGINAL again: CC7 restored", back.any { it.status == 0xBB && it.d1 == 7 && it.d2 == 81 })
        } finally { r.close() }
    }

    /** Any Style (CI: HardRock2, EnglishWaltz2, ROSITA): in AUTO E343 every Bank/Program written on ch9-16 is an E343 list voice unless the plan reports NO SAFE CANDIDATE. */
    @Test fun anyStyleAutoWritesOnlyListedVoicesOrReportsWhy() {
        val st = StyleTsv.load(); assumeTrue("Style TSV not set", st != null)
        val plan = E343StylePlanner.plan(st!!, mapper)
        for (d in plan.decisions.sortedBy { it.channel }) row("ANY '${st.fileName}' ${E343Roles.of(d.channel)} ${d.requestText} -> ${d.status.label} ${d.outText} | ${d.reason}${d.warnings.joinToString("") { " | WARN $it" }}")
        for (ch in 8..15) plan.balance[ch]?.let { b -> if (b.level != null) row("ANY '${st.fileName}' BAL ${E343Roles.of(ch)} autoTrim=${b.autoTrim} | ${b.reason}") }
        val mode = E343OutMode.valueOf(System.getenv("E343_MODE") ?: "AUTO_BALANCE")   // E343_MODE=ORIGINAL: same run as the baseline (voice check skipped)
        val run = play(st, mode, listOf(ArrangerSection.MainA, ArrangerSection.MainB))
        val bank = IntArray(16); val lsb = IntArray(16)
        for (w in run.usb) { val ch = w.status and 15; if (ch !in 8..15) continue
            when (w.status and 0xF0) {
                0xB0 -> if (w.d1 == 0) bank[ch] = w.d2 else if (w.d1 == 32) lsb[ch] = w.d2
                0xC0 -> if (mode != E343OutMode.ORIGINAL && !catalog.contains(bank[ch], lsb[ch], w.d1)) {
                    val d = plan.decision(ch, bank[ch], lsb[ch], w.d1)
                    row("ANY '${st.fileName}' ch${ch + 1} wrote ${bank[ch]}/${lsb[ch]}/${w.d1 + 1} (not listed): ${d?.status?.label ?: "unplanned"}")
                    assertTrue("ch${ch + 1} ${bank[ch]}/${lsb[ch]}/${w.d1 + 1}: only a reported NO SAFE CANDIDATE may stay unlisted", d?.status == E343MapStatus.NO_SAFE_CANDIDATE)
                }
            }
        }
        for (ch in 10..15) {
            val open = HashMap<Int, Int>()
            for (w in run.usb.filter { (it.status and 15) == ch }) { if (isOn(w)) open.merge(w.d1, 1, Int::plus) else if (isOff(w)) open.computeIfPresent(w.d1) { _, c -> (c - 1).takeIf { it > 0 } } else if ((w.status and 0xF0) == 0xB0 && (w.d1 == 123 || w.d1 == 120)) open.clear() }   // STOP panic CC123/CC120 also releases
            assertTrue("ch${ch + 1} stuck notes $open", open.isEmpty())
        }
    }
}

package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * EnglishWaltz2.S519 -> PSR-E343 drum audit (HOST evidence; the keyboard is not simulated).
 * Each section is played from STOP through the PRODUCTION chain (parser dump -> ArrangerBrain -> StyleSequencer -> MidiInputManager -> MidiMixerOutput -> USB port)
 * and every byte written on the two Rhythm channels (0-based 8/9, 1-based 9/10) is recorded: Bank MSB/LSB/Program, NOTE ON (note, velocity), NOTE OFF, time.
 * It also proves that the EXISTING Style Editor drum note map (MIDI OUT output only) can re-route one note on the wire without touching the internal engine.
 * Rows: `EW2E343 ...`. Read-only: nothing in production is changed or configured permanently.
 */
class EnglishWaltz2E343DrumTest {
    private fun style() = StyleTsv.load().also { assumeTrue("SGAP_STYLE_TSV not set", it != null) }!!
        .also { assumeTrue("not EnglishWaltz2", it.fileName.contains("EnglishWaltz2")) }
    private fun row(s: String) = println("EW2E343 $s")
    private val tempo = 280

    private class Pass(val usb: List<RtRig.Wr>, val engine: List<RtRig.Ev>, val t0: Long, val passNs: Long)

    private fun play(sec: String, configure: (RtRig) -> Unit = {}): Pass {
        val st = style()
        val model = st.sections[sec]!!
        val r = RtRig(true, 120, st)
        try {
            r.brain.setTempo(tempo); configure(r)
            r.brain.selectSection(ArrangerSection.valueOf(sec))
            val t0 = System.nanoTime()
            r.brain.startStop()
            val passNs = (model.lengthTicks.toDouble() / st.ppq * 60.0 / tempo * 1e9).toLong()
            while (System.nanoTime() < t0 + passNs + 250_000_000L) Thread.sleep(5)
            r.brain.startStop(); Thread.sleep(300)
            return Pass(r.usbAll.toList().sortedBy { it.t }, r.engineEvents.toList().sortedBy { it.t }, t0, passNs)
        } finally { r.close() }
    }

    /** Authored NOTE ON (note, velocity) per Rhythm destination for one pass of [sec]. */
    private fun authored(sec: String): Map<Int, List<Pair<Int, Int>>> {
        val out = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for (p in style().sections[sec]!!.parts) {
            val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }
            for (e in p.events.sortedBy { it.tick }) if (e.isNoteOn && e.velocity > 0) {
                val d = pol.firstOrNull { it.sourceChannel == e.channel }?.destinationChannel ?: continue
                if (d in 8..9) out.getOrPut(d) { ArrayList() } += e.note to e.velocity
            }
        }
        return out
    }

    @Test fun everySectionRhythmChannelsOnTheWire() {
        val st = style()
        for (sec in st.sections.keys) {
            if (runCatching { ArrangerSection.valueOf(sec) }.isFailure) { row("section=$sec not addressable by ArrangerSection: skipped"); continue }
            val p = play(sec); val a = authored(sec)
            val firstPassEnd = p.t0 + p.passNs
            for (ch in 8..9) {
                val bank = p.usb.filter { (it.status and 15) == ch && ((it.status and 0xF0) == 0xC0 || ((it.status and 0xF0) == 0xB0 && (it.d1 == 0 || it.d1 == 32))) }
                val pcs = bank.withIndex().filter { (it.value.status and 0xF0) == 0xC0 }.map { (i, m) ->
                    assertTrue("$sec ch${ch + 1}: Program Change preceded by CC0 then CC32", i >= 2 && bank[i - 2].d1 == 0 && bank[i - 1].d1 == 32)
                    "${bank[i - 2].d2}/${bank[i - 1].d2}/PC${m.d1}(list ${m.d1 + 1})"
                }.distinct()
                val ons = p.usb.filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == ch && it.d2 > 0 }
                val offs = p.usb.filter { (it.status and 15) == ch && ((it.status and 0xF0) == 0x80 || ((it.status and 0xF0) == 0x90 && it.d2 == 0)) }
                val first = ons.filter { it.t < firstPassEnd }
                val eng = p.engine.filter { it.kind == 'O' && it.ch == ch }.map { it.note }
                val counts = first.groupingBy { it.d1 }.eachCount().toSortedMap()
                val authoredCounts = (a[ch] ?: emptyList()).groupingBy { it.first }.eachCount().toSortedMap()
                row("section=$sec ch${ch + 1} bankPC=${if (pcs.isEmpty()) "-" else pcs.joinToString(",")} firstPassNoteOn=$counts authored=$authoredCounts totalNoteOn=${ons.size} noteOff=${offs.size}")
                // per-note velocity range and on->off duration on the wire
                for (n in counts.keys) {
                    val v = first.filter { it.d1 == n }.map { it.d2 }
                    val gaps = first.filter { it.d1 == n }.map { it.t }.zipWithNext { x, y -> (y - x) / 1_000_000 }
                    row("  section=$sec ch${ch + 1} note=$n count=${v.size} vel=${v.min()}-${v.max()} minRetriggerMs=${gaps.minOrNull()} (tempo $tempo)")
                }
                assertEquals("$sec ch${ch + 1}: MIDI OUT drum notes == internal drum notes (no remap on the default path)", eng.sorted(), ons.map { it.d1 }.sorted())
                assertTrue("$sec ch${ch + 1}: every (note, velocity) sent is authored by the Style", (a[ch] ?: emptyList()).toSet().containsAll(ons.map { it.d1 to it.d2 }.toSet()))
                // CURRENT BEHAVIOUR (documented, not a target): Rhythm notes are treated as one-shot; the sequencer forwards no NOTE OFF for them, to the engine or to MIDI OUT.
                assertEquals("$sec ch${ch + 1}: no NOTE OFF is written on a Rhythm channel during playback (baseline behaviour)", 0, offs.count { it.t < p.t0 + p.passNs })
            }
        }
    }

    @Test fun existingMidiOutDrumMapReroutesOneNoteOnTheWireOnly() {
        val base = play("MainA")
        val mapped = play("MainA") { it.brain.setStyleDrumMap(null, 9, 1, mapOf(26 to 85)) }
        fun usbOn(p: Pass) = p.usb.filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == 9 && it.d2 > 0 }
        fun engOn(p: Pass) = p.engine.filter { it.kind == 'O' && it.ch == 9 }.map { it.note }
        val b26 = usbOn(base).count { it.d1 == 26 }
        row("drumMap MIDI OUT ch10 26->85 MainA: base usb26=$b26 mapped usb26=${usbOn(mapped).count { it.d1 == 26 }} usb85=${usbOn(mapped).count { it.d1 == 85 }} engine26 base=${engOn(base).count { it == 26 }} mapped=${engOn(mapped).count { it == 26 }}")
        assertTrue("MainA sends note 26 on ch10", b26 > 0)
        assertEquals("MIDI OUT: no 26 left", 0, usbOn(mapped).count { it.d1 == 26 })
        assertTrue("MIDI OUT: 26 became 85 (same velocity)", usbOn(mapped).filter { it.d1 == 85 }.map { it.d2 }.toSet() == usbOn(base).filter { it.d1 == 26 }.map { it.d2 }.toSet())
        assertTrue("internal engine still plays 26", engOn(mapped).contains(26))
        assertEquals("other ch10 notes untouched", usbOn(base).filter { it.d1 != 26 }.map { it.d1 to it.d2 }, usbOn(mapped).filter { it.d1 != 85 }.map { it.d1 to it.d2 })
    }
}

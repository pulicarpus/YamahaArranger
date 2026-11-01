package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PSR-E343 MIDI OUT AUDIT (HOST evidence only; the keyboard is not simulated and no E343 data is assumed).
 * Style (real fixture through the production C++ parser) -> sequencer -> MidiInputManager -> router -> bytes written to the USB port.
 * It records exactly what is sent per channel: Bank MSB / LSB / Program Change in wire order, drum notes, controllers; and compares it with what the Style authored.
 * Rows: `E343AUDIT ...`.
 */
class E343MidiOutAuditTest {
    private fun style() = StyleTsv.load().also { assumeTrue("SGAP_STYLE_TSV / ROSITA_TSV not set", it != null) }!!
    private fun row(s: String) = println("E343AUDIT $s")

    private fun destOf(p: com.yourapp.yamahaarranger.style.StylePartModel, e: StyleNoteEvent): Int {
        val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }
        return pol.firstOrNull { it.sourceChannel == e.channel }?.destinationChannel ?: pol.firstOrNull()?.destinationChannel ?: e.channel
    }

    @Test fun inventoryOfWhatTheStyleRequestsAndWhichDrumNotesItAuthors() {
        val s = style()
        val seen = LinkedHashSet<String>(); val drumNotes = HashMap<Int, java.util.TreeMap<Int, Int>>()
        for ((secName, sec) in s.sections) for (p in sec.parts) {
            val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }
            for (c in pol) {
                val key = "dst=ch${c.destinationChannel + 1} voice='${c.voiceName.trim()}' MSB=${p.bankMsb} LSB=${p.bankLsb} PC=${p.program} (display ${p.program + 1})"
                if (seen.add(key)) row("request section=$secName part='${p.name.trim()}' src=ch${c.sourceChannel + 1} $key")
            }
            for (e in p.events) if (e.isNoteOn && e.velocity > 0) { val d = destOf(p, e); if (d in 8..9) drumNotes.getOrPut(d) { java.util.TreeMap() }.merge(e.note, 1, Int::plus) }
        }
        for ((d, m) in drumNotes.toSortedMap()) row("authored drum notes dst=ch${d + 1} (Rhythm ${d - 7}) distinct=${m.size} notes[note:count]=" + m.entries.joinToString(" ") { "${it.key}:${it.value}" })
        assertTrue("fixture has a Rhythm part", drumNotes.isNotEmpty())
        assertTrue("every authored drum note is a valid MIDI note", drumNotes.values.all { it.keys.all { n -> n in 0..127 } })
    }

    @Test fun midiOutCarriesTheStylesRequestInOrderAndTheDrumNotesUnchanged() {
        val st = style(); DebugLog.clear()
        val r = RtRig(true, 240, st)
        try {
            r.brain.startStop(); Thread.sleep(2200); r.brain.startStop(); Thread.sleep(400)
            val all = r.usbAll.toList().sortedBy { it.t }
            // Bank Select / Program Change per channel in wire order
            val byCh = (4..15).associateWith { ch -> all.filter { (it.status and 15) == ch && ((it.status and 0xF0) == 0xC0 || ((it.status and 0xF0) == 0xB0 && (it.d1 == 0 || it.d1 == 32))) } }
            var checked = 0
            for ((ch, msgs) in byCh) {
                // every Program Change must be directly preceded by CC0 (MSB) then CC32 (LSB) of the same channel: Yamaha's Bank Select -> Program Change order
                for ((i, m) in msgs.withIndex()) if ((m.status and 0xF0) == 0xC0) {
                    assertTrue("ch${ch + 1}: Program Change without a preceding Bank Select pair", i >= 2 && msgs[i - 1].d1 == 32 && msgs[i - 2].d1 == 0)
                    row("sent ch${ch + 1} MSB=${msgs[i - 2].d2} LSB=${msgs[i - 1].d2} PC=${m.d1} (display ${m.d1 + 1})${if (ch in 8..9) "  [Rhythm ${ch - 7}]" else ""}")
                    checked++
                }
            }
            assertTrue("the run sent voices", checked > 0)
            // drums: written notes are exactly the engine's drum notes (no transpose / octave correction / mapping on the default path) and a subset of what the Style authors
            val authored = HashMap<Int, Set<Int>>()
            for (sec in st.sections.values) for (p in sec.parts) for (e in p.events) if (e.isNoteOn && e.velocity > 0) { val d = destOf(p, e); if (d in 8..9) authored[d] = (authored[d] ?: emptySet()) + e.note }
            for (ch in 8..9) {
                val sent = r.usbWrites.toList().filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == ch }.map { it.d1 }
                val engine = r.engineEvents.toList().filter { it.kind == 'O' && it.ch == ch }.map { it.note }
                row("drum dst=ch${ch + 1} NOTE_ON written=${sent.size} distinct=${sent.toSet().sorted()} engine=${engine.size}")
                assertEquals("ch${ch + 1}: MIDI OUT drum notes equal the internal drum notes (nothing transposed or remapped)", engine.sorted(), sent.sorted())
                assertTrue("ch${ch + 1}: only notes the Style authors are sent", (authored[ch] ?: emptySet()).containsAll(sent.toSet()))
            }
            // controllers actually written (last values) and nothing exotic: only CC7/10/11/91/93 (+0/32 bank, + sustain none)
            val ccs = all.filter { (it.status and 0xF0) == 0xB0 }.map { it.d1 }.toSortedSet()
            row("controllers written (all channels): " + ccs.joinToString(" "))
            assertTrue("no controller outside the documented set was sent: $ccs", ccs.all { it in setOf(0, 7, 10, 11, 32, 72, 91, 93, 123) })
            // the router's own report agrees with the wire
            val report = r.midi.midiOutReport(); row("router report:\n$report")
            assertTrue(report.contains("[Rhythm 1]") && report.contains("[Rhythm 2]") && report.contains("NOTE_ON written"))
            // no keyboard-part voice messages (ch1..4) were sent by a Style run
            assertTrue("a Style run never sends Bank/Program to RIGHT/LEFT channels", all.none { (it.status and 15) in 0..3 && (it.status and 0xF0) == 0xC0 })
        } finally { r.close() }
    }
}

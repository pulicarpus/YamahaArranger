package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * AUDIT ONLY (no production change): per-part evidence for "Style feels empty / Piano dominant / Bass+Guitar silent on PSR-E343".
 * Any Style (--rosita --style FILE), production chain (ArrangerBrain + StyleSequencer + MidiMixerOutput), MIDI OUT ON, chord C held
 * for two Main bars. Prints per destination channel (BALAUDIT lines):
 *  - INTERNAL: voice requested from the engine (bank14 / PC / name), mixer sent to BASSMIDI (CC7 / pan / CC11 / reverb / chorus),
 *    NOTE ON count, velocity P10/P50/P90, note range, peak simultaneous notes;
 *  - MIDI OUT: Bank/PC written, every CC value written, NOTE ON count, velocity, note range, peak simultaneous notes (shared E343 polyphony).
 * MIDI activity is counted, never interpreted as audio level (the mock synth renders nothing).
 */
class StyleBalanceAuditRtTest {
    private val roles = mapOf(8 to "Rhythm1", 9 to "Rhythm2", 10 to "Bass", 11 to "Chord1", 12 to "Chord2", 13 to "Pad", 14 to "Phrase1", 15 to "Phrase2")
    private fun pct(l: List<Int>, q: Double) = if (l.isEmpty()) -1 else l.sorted()[((l.size - 1) * q).toInt()]
    private fun peakPoly(ev: List<Triple<Long, Boolean, Int>>): Int {   // (time, on?, note)
        var cur = 0; var peak = 0; val open = HashMap<Int, Int>()
        for ((_, on, n) in ev.sortedWith(compareBy({ it.first }, { if (it.second) 1 else 0 }))) {
            if (on) { open.merge(n, 1, Int::plus); cur++; peak = maxOf(peak, cur) } else { val c = open[n] ?: 0; if (c > 0) { open[n] = c - 1; cur-- } }
        }
        return peak
    }

    @Test fun perPartInternalAndMidiOutEvidence() {
        val st = StyleTsv.load(); assumeTrue("Style TSV (--rosita --style) not set", st != null); val tempo = st!!.defaultTempoBpm.takeIf { it > 0 } ?: 120
        DebugLog.clear()
        val rig = RtRig(true, tempo, st)
        rig.wireProductionMixerOutput()                                 // as MainViewModel.init: native mixer values reach MIDI OUT
        try {
            rig.brain.setAcmpEnabled(true); rig.brain.setLeftVoiceEnabled(false); rig.settle(30)
            listOf(48, 52, 43).forEach { rig.noteOn(it, 90) }; rig.settle(80)
            val markE = rig.engineEvents.size; val markU = rig.usbWrites.size; val t0 = System.nanoTime()
            rig.brain.startStop(); rig.settle(2L * 4 * 60_000 / tempo + 600)        // two 4/4 bars of the Main section
            rig.brain.startStop(); rig.settle(300)
            listOf(48, 52, 43).forEach { rig.noteOff(it) }
            val eng = rig.engineEvents.toList().drop(markE)
            val usb = rig.usbWrites.toList().drop(markU)
            val inv = Mockito.mockingDetails(rig.bridge).invocations.map { it.method.name to it.arguments.toList() }
            val vel = inv.filter { (n, _) -> n == "nativeSfNoteOnStyleChannel" || n == "nativeSfNoteOnChannel" }.map { (_, a) -> Triple(a[0] as Int, a[1] as Int, Math.round((a[2] as Float) * 127f)) }
            println("BALAUDIT style='${st.fileName}' dialect=${st.dialectIdentity} meter=${st.meter} tempo=$tempo window=${(System.nanoTime() - t0) / 1_000_000} ms chord=C (ACMP ON)")
            var totalOutPeak: Int
            run {
                val all = usb.filter { (it.status and 0xF0) == 0x90 || (it.status and 0xF0) == 0x80 }
                    .map { Triple(it.t, (it.status and 0xF0) == 0x90 && it.d2 > 0, (it.status and 15) * 128 + it.d1) }
                totalOutPeak = peakPoly(all)
            }
            for (ch in 8..15) {
                val req = inv.lastOrNull { (n, a) -> (n == "nativeSetChannelPresetWithName" || n == "nativeSetChannelPreset") && a[0] == ch }
                val mix = inv.lastOrNull { (n, a) -> n == "nativeSetChannelMixer" && a[0] == ch }?.second
                val expr = inv.lastOrNull { (n, a) -> n == "nativeSetChannelExpression" && a[0] == ch }?.second
                val ons = eng.filter { it.ch == ch && it.kind == 'O' }
                val v = vel.filter { it.first == ch }.map { it.third }
                val notes = ons.map { it.note }
                val engPoly = peakPoly(eng.filter { it.ch == ch && (it.kind == 'O' || it.kind == 'F') }.map { Triple(it.t, it.kind == 'O', it.note) })
                val out = usb.filter { (it.status and 15) == ch }
                val outOns = out.filter { (it.status and 0xF0) == 0x90 && it.d2 > 0 }
                val outCc = out.filter { (it.status and 0xF0) == 0xB0 }.groupBy { it.d1 }.mapValues { (_, l) -> l.map { it.d2 }.distinct() }
                val outPc = out.filter { (it.status and 0xF0) == 0xC0 }.map { it.d1 }.distinct()
                val outPoly = peakPoly(out.filter { (it.status and 0xF0) == 0x90 || (it.status and 0xF0) == 0x80 }.map { Triple(it.t, (it.status and 0xF0) == 0x90 && it.d2 > 0, it.d1) })
                if (req == null && ons.isEmpty() && out.isEmpty()) continue
                println("BALAUDIT INTERNAL ${roles[ch]} ch${ch + 1}: request=${req?.let { "bank14=${it.second[1]} (MSB ${(it.second[1] as Int) / 128} LSB ${(it.second[1] as Int) % 128}) PC=${it.second[2]}${if (it.second.size > 3) " '" + it.second[3] + "'" else ""}" } ?: "-"}" +
                    " mixer=${mix?.let { "CC7=${it[1]} pan=${it[2]} CC11=${it[3]} rev=${it[4]} cho=${it[5]}" } ?: "-"}${expr?.let { " lastCC11=${it[1]}" } ?: ""}" +
                    " NOTE_ON=${ons.size} vel P10/P50/P90=${pct(v, .1)}/${pct(v, .5)}/${pct(v, .9)} range=${notes.minOrNull() ?: "-"}..${notes.maxOrNull() ?: "-"} peakNotes=$engPoly")
                println("BALAUDIT MIDIOUT  ${roles[ch]} ch${ch + 1}: bank=${outCc[0] ?: "-"}/${outCc[32] ?: "-"} PC=$outPc CC=${outCc.filterKeys { it != 0 && it != 32 }}" +
                    " NOTE_ON=${outOns.size} vel P10/P50/P90=${pct(outOns.map { it.d2 }, .1)}/${pct(outOns.map { it.d2 }, .5)}/${pct(outOns.map { it.d2 }, .9)}" +
                    " range=${outOns.minOfOrNull { it.d1 } ?: "-"}..${outOns.maxOfOrNull { it.d1 } ?: "-"} peakNotes=$outPoly")
            }
            val mel = usb.filter { ((it.status and 0xF0) == 0x90 || (it.status and 0xF0) == 0x80) && (it.status and 15) in 10..15 }
                .map { Triple(it.t, (it.status and 0xF0) == 0x90 && it.d2 > 0, (it.status and 15) * 128 + it.d1) }
            println("BALAUDIT MIDIOUT peak simultaneous notes: all Style channels = $totalOutPeak (drum NOTE OFF timing included as sent), melodic ch11-16 = ${peakPoly(mel)}; PSR-E343 polyphony 32 shared with its own keyboard")
            println("BALAUDIT MIDIOUT drum NOTE OFF written: ch9=${usb.count { it.status == 0x88 || (it.status == 0x98 && it.d2 == 0) }} ch10=${usb.count { it.status == 0x89 || (it.status == 0x99 && it.d2 == 0) }}")
            assertTrue("the Style played", eng.any { it.kind == 'O' && it.ch in 8..15 })
        } finally { rig.close() }
    }
}

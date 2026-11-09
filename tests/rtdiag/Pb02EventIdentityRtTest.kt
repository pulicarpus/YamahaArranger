package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.Pb02
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P-B0.2 on the PRODUCTION chain (RtRig: real ArrangerBrain + StyleSequencer, fake USB port, mock synth): the probe OFF vs ON
 * produces the same engine NOTE events and the same MIDI OUT bytes in the same order (Main A -> fill -> Main B with a chord), and
 * the ON run's split is internally exact (no mismatch, buckets sum to the span, every event classified once). HOST evidence only.
 */
class Pb02EventIdentityRtTest {
    private fun style(): ParsedStyle = StyleTsv.load().also { assumeTrue("style TSV not set", it != null) }!!
    private class Run(val engine: List<Triple<Char, Int, Int>>, val wire: List<Triple<Int, Int, Int>>)

    private fun run(st: ParsedStyle, probe: Boolean): Run {
        val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
        val bar = st.meter.numerator * 60_000L / tempo
        Pb02.setEnabled(probe)
        val r = RtRig(true, tempo, st)
        try {
            r.wireProductionMixerOutput()
            r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(false); r.settle(30)
            r.brain.selectSection(ArrangerSection.MainA); listOf(36, 40, 43).forEach { r.noteOn(it, 90) }; r.settle(60)
            r.brain.startStop()
            r.settle(bar + bar / 4); r.brain.selectMainVariation(ArrangerSection.MainB); r.settle(bar * 3)
            val cut = System.nanoTime() - 400_000_000L                     // compare only events well before STOP
            r.brain.startStop(); r.settle(300)
            if (probe) {
                val c = Pb02.coreForTest()
                println("PB02RT " + c.report().lines().take(5).joinToString(" | "))
                assertEquals("per-event split exact", 0L, c.mismatches.get())
                assertTrue("events classified", c.events.get() > 100)
                assertEquals("every event in exactly one class", c.events.get(), c.early.get() + c.idleCase.get() + c.backlogCase.get() + c.boundaryCase.get())
                assertEquals("thread-time buckets sum to the span", c.span.get(), c.timeline.sumOf { it.get() })
                assertEquals("parts sum to lateness", c.latenessSum.get(), c.partSum.sumOf { it.get() })
            }
            return Run(r.engineEvents.filter { it.t < cut }.sortedBy { it.t }.map { Triple(it.kind, it.ch, it.note) },
                r.usbAll.filter { it.t < cut }.sortedBy { it.t }.map { Triple(it.status, it.d1, it.d2) })
        } finally { r.close(); Pb02.setEnabled(false) }
    }

    @Test fun probeDoesNotChangeEvents() {
        val st = style()
        val off = run(st, false); val on = run(st, true)
        val n = minOf(off.engine.size, on.engine.size); val m = minOf(off.wire.size, on.wire.size)
        println("PB02RT engineEvents off=${off.engine.size} on=${on.engine.size} wire off=${off.wire.size} on=${on.wire.size}")
        assertTrue("enough events", n > 200 && m > 200)
        assertTrue("same count within the cut (+-2 boundary events)", Math.abs(off.engine.size - on.engine.size) <= 2)
        assertEquals("engine NOTE events identical, same order", off.engine.take(n), on.engine.take(n))
        assertEquals("MIDI OUT bytes identical, same order", off.wire.take(m), on.wire.take(m))
    }
}

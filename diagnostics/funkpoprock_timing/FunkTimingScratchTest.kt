// SCRATCH DIAGNOSTIC FIXTURE (not compiled by any build or test runner from this folder). To run: copy into tests/rtdiag/ locally and use tools/test_rtdiag.py --style <FunkPopRock.S542.prs> --only com.yourapp.rtdiag.FunkTimingScratchTest. The Style file itself is not committed (user upload, SHA-256 9eee8d1d218f38296330fa967e222286dd249f5e55f6460510a23857212dde3e).
package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.LatencyProbe
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** SCRATCH (outside the repository): FunkPopRock part combinations x output/cost models. Writes engine+wire stamps and production LatencyProbe stats. Measures only. */
class FunkTimingScratchTest {
    private val outDir = File(System.getenv("FUNK_OUT") ?: "build/funk-timing")
    private val C = listOf(36, 40, 43)
    private val combos = linkedMapOf(
        "drum" to setOf(8, 9), "bass" to setOf(10), "chord" to setOf(11, 12), "drum+bass" to setOf(8, 9, 10), "drum+chord1" to setOf(8, 9, 11),
        "drum+padphrase" to setOf(8, 9, 13, 14), "accNoDrum" to setOf(10, 11, 12, 13, 14, 15), "all" to (8..15).toSet())
    private class Model(val name: String, val midiOut: Boolean, val nativeUs: Long, val usbUs: Long)
    private val models = listOf(Model("sink", false, 0, 0), Model("both0", true, 0, 0), Model("int300", false, 300, 0), Model("both300", true, 300, 150), Model("both2000", true, 2000, 150))

    private fun stat(s: LatencyProbe.Stat) = "${s.count()}\t${s.percentileUpperUs(0.5)}\t${s.percentileUpperUs(0.95)}\t${s.percentileUpperUs(0.99)}\t${s.maxUs()}"

    @Test fun combos() {
        val st: ParsedStyle = StyleTsv.load().also { assumeTrue("style TSV not set", it != null) }!!
        val only = System.getenv("FUNK_ONLY")?.split(",")?.toSet()
        for (m in models) for ((cname, chans) in combos) {
            if (only != null && "${m.name}-$cname" !in only && m.name !in only) continue
            val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
            val bar = st.meter.numerator * 60_000L / tempo
            val r = RtRig(true, tempo, st)
            try {
                r.wireProductionMixerOutput()
                r.midi.midiOutEnabled = m.midiOut
                r.nativeCallCostUs = m.nativeUs; r.usbWriteCostUs = m.usbUs
                r.brain.setAcmpEnabled(true); r.brain.setLeftVoiceEnabled(false)
                for (ch in 8..15) r.brain.setStyleChannelMute(ch, ch !in chans)
                r.settle(30)
                val seq = r.brain.javaClass.getDeclaredField("sequencer").also { it.isAccessible = true }.get(r.brain)
                val clk = seq.javaClass.getDeclaredField("masterClockStartedAtNanos").also { it.isAccessible = true }
                val playing = r.brain.javaClass.getDeclaredField("currentPlayingSection").also { it.isAccessible = true }
                val marks = ArrayList<String>(); val go = java.util.concurrent.atomic.AtomicBoolean(true)
                val sampler = Thread { var ls = ""; while (go.get()) { val s = playing.get(r.brain).toString(); if (s != ls) { synchronized(marks) { marks += "M\t${System.nanoTime()}\tSECTION\t$s" }; ls = s }; Thread.sleep(1) } }.also { it.isDaemon = true; it.start() }
                r.brain.selectSection(ArrangerSection.MainC); C.forEach { r.noteOn(it, 90) }; r.settle(60)
                LatencyProbe.reset()
                r.brain.startStop(); r.settle(20)
                val clock0 = clk.getLong(seq)
                r.settle(bar + bar / 4); r.brain.selectMainVariation(ArrangerSection.MainD); r.settle(bar * 4 - bar / 4 - 20)
                val lines = listOf("STAT\twake\t" + stat(LatencyProbe.schedulerWakeLateness), "STAT\tdispatch\t" + stat(LatencyProbe.schedulerDispatchLateness))
                r.brain.startStop(); r.settle(300); go.set(false); sampler.join(200); C.forEach { r.noteOff(it) }; r.settle(30)
                outDir.mkdirs()
                File(outDir, "${m.name}-$cname.tsv").printWriter().use { w ->
                    w.println("H\t$tempo\t$bar\t${st.ppq}\t$clock0\t${m.name}\t$cname\t${r.oracle.maxPolyphony}\t${r.oracle.stuckOnChannels(8..9)}")
                    lines.forEach(w::println); synchronized(marks) { marks.forEach(w::println) }
                    r.engineEvents.toList().sortedBy { it.t }.forEach { w.println("E\t${it.t}\t${it.kind}\t${it.ch}\t${it.note}\t${it.thread}") }
                    r.usbAll.toList().sortedBy { it.t }.forEach { w.println("W\t${it.t}\t${it.status}\t${it.d1}\t${it.d2}") }
                }
                println("FUNKT ${m.name}-$cname " + lines.joinToString(" | "))
            } finally { r.close() }
        }
    }
}

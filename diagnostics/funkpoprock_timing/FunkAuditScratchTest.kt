// SCRATCH DIAGNOSTIC FIXTURE (not compiled by any build or test runner from this folder). To run: copy into tests/rtdiag/ locally and use tools/test_rtdiag.py --style <FunkPopRock.S542.prs> --only com.yourapp.rtdiag.FunkTimingScratchTest. The Style file itself is not committed (user upload, SHA-256 9eee8d1d218f38296330fa967e222286dd249f5e55f6460510a23857212dde3e).
package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.e343out.*
import com.yourapp.yamahaarranger.style.ParsedStyle
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** SCRATCH (outside the repository): FunkPopRock S542 scenarios A-F, writes the raw MIDI OUT wire + engine events for offline analysis. Measures only. */
class FunkAuditScratchTest {
    private val outDir = File(System.getenv("FUNK_OUT") ?: "build/funk-runs")
    private val tag = System.getenv("FUNK_TAG") ?: "x"
    private val catalog by lazy { E343Catalog.parse(File("docs/yamaha/E343_VOICES.tsv").readText(), File("docs/yamaha/E343_DRUMKITS.tsv").readText()) }
    private val mapper by lazy { E343VoiceMapper(catalog) }
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50)

    private fun run(name: String, st: ParsedStyle, mode: E343OutMode, acmp: Boolean, start: ArrangerSection, holdChord: Boolean, releaseBeforeStart: Boolean,
                    script: (RtRig, Long, MutableList<String>) -> Unit) {
        val tempo = st.defaultTempoBpm.takeIf { it > 0 } ?: 120
        val barMs = st.meter.numerator * 60_000L / tempo
        val r = RtRig(true, tempo, st)
        val marks = ArrayList<String>()
        try {
            r.wireProductionMixerOutput()
            val out = r.midi.e343Out
            out.setCatalog(catalog); out.setStyle(E343StylePlanner.plan(st, mapper), emptyMap(), emptyMap()); out.setMode(mode)
            r.brain.setAcmpEnabled(acmp); r.brain.setLeftVoiceEnabled(false); r.settle(30)
            val playing = r.brain.javaClass.getDeclaredField("currentPlayingSection").also { it.isAccessible = true }
            val go = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread { var ls = ""; var lc = ""
                while (go.get()) { val now = System.nanoTime()
                    val s = playing.get(r.brain).toString(); if (s != ls) { synchronized(marks) { marks += "M\t$now\tSECTION\t$s" }; ls = s }
                    val c = r.brain.state.value.currentChordLabel; if (c != lc) { synchronized(marks) { marks += "M\t$now\tCHORD\t$c" }; lc = c }
                    Thread.sleep(1) } }.also { it.isDaemon = true; it.start() }
            r.brain.selectSection(start)
            if (holdChord) { C.forEach { r.noteOn(it, 90) }; r.settle(60); if (releaseBeforeStart) { C.forEach { r.noteOff(it) }; r.settle(60) } }
            synchronized(marks) { marks += "M\t${System.nanoTime()}\tSTART\t" }
            r.brain.startStop()
            script(r, barMs, marks)
            synchronized(marks) { marks += "M\t${System.nanoTime()}\tSTOP\t" }
            if (r.brain.state.value.isPlaying) r.brain.startStop()
            r.settle(400); go.set(false); sampler.join(200)
            if (holdChord && !releaseBeforeStart) C.forEach { r.noteOff(it) }
            r.settle(50)
            outDir.mkdirs()
            File(outDir, "$tag-$name.tsv").printWriter().use { w ->
                w.println("H\t${st.fileName}\t$tempo\t$barMs\t${mode.name}\tacmp=$acmp")
                synchronized(marks) { marks.forEach { w.println(it) } }
                r.usbAll.toList().sortedBy { it.t }.forEach { w.println("W\t${it.t}\t${it.status}\t${it.d1}\t${it.d2}") }
                r.engineEvents.toList().sortedBy { it.t }.forEach { w.println("E\t${it.t}\t${it.kind}\t${it.ch}\t${it.note}") }
            }
            println("FUNK wrote $tag-$name usb=${r.usbAll.size} eng=${r.engineEvents.size}")
        } finally { r.close() }
    }

    private fun style(): ParsedStyle = StyleTsv.load().also { assumeTrue("style TSV not set", it != null) }!!
    private fun mark(m: MutableList<String>, s: String) = synchronized(m) { m += "M\t${System.nanoTime()}\tKEY\t$s" }

    @Test fun scenarios() {
        val st = style()
        // kit plan (drum mapping evidence)
        val plan = E343StylePlanner.plan(st, mapper)
        for (d in plan.decisions) println("FUNK MAP ch${d.channel + 1} request=${d.requestText} -> ${d.status.label} ${d.outText} | ${d.reason}")
        for ((ch, u) in plan.usage) { val d = plan.decisions.firstOrNull { it.channel == ch && it.drum && it.target != null } ?: continue
            println("FUNK KIT ch${ch + 1} '${d.target!!.name}' cov=${mapper.coverage(d.target!!, u.drumNotes)} notes=${mapper.kitNoteNames(d.target!!, u.drumNotes.keys)}") }
        val c2d: (RtRig, Long, MutableList<String>) -> Unit = { r, bar, m -> r.settle(bar * 2 + bar / 4); mark(m, "selectMainD"); r.brain.selectMainVariation(ArrangerSection.MainD); r.settle(bar * 5) }
        val d4: (RtRig, Long, MutableList<String>) -> Unit = { r, bar, _ -> r.settle(bar * 4) }
        for (mode in listOf(E343OutMode.ORIGINAL, E343OutMode.AUTO_BALANCE)) {
            val mt = if (mode == E343OutMode.ORIGINAL) "orig" else "bal"
            run("A-$mt", st, mode, false, ArrangerSection.MainD, false, false, d4)
            run("B-$mt", st, mode, false, ArrangerSection.MainC, false, false, c2d)
            run("C-$mt", st, mode, true, ArrangerSection.MainD, true, false, d4)
            run("D-$mt", st, mode, true, ArrangerSection.MainC, true, false, c2d)
            run("E-$mt", st, mode, true, ArrangerSection.MainC, true, false) { r, bar, m -> r.settle(bar * 2 + bar / 4); mark(m, "selectMainD"); r.brain.selectMainVariation(ArrangerSection.MainD)
                r.settle(bar / 2); mark(m, "F"); C.forEach { r.noteOff(it) }; F.forEach { r.noteOn(it, 90) }; r.settle(bar); mark(m, "G"); F.forEach { r.noteOff(it) }; G.forEach { r.noteOn(it, 90) }
                r.settle(bar * 3); G.forEach { r.noteOff(it) } }
            run("F-$mt", st, mode, true, ArrangerSection.MainC, true, true, c2d)
            run("G-$mt", st, mode, true, ArrangerSection.MainC, false, false, c2d)
        }
    }
}

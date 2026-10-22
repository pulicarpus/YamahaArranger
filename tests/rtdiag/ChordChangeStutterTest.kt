package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.ArrangerBrainClock
import com.yourapp.yamahaarranger.audio.LatencyProbe
import com.yourapp.yamahaarranger.audio.MetronomeController
import com.yourapp.yamahaarranger.audio.MetronomeMode
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * CHORD-CHANGE STUTTER diagnostic (HOST evidence only). Production chain MidiInputManager parser -> ArrangerBrain -> ChordDetector ->
 * StyleSequencer -> AudioEngineManager -> MidiMixerOutput with fake USB ports and a recording JNI bridge.
 * The Style timeline is observed from the OUTSIDE (hi-hat NOTE_ON instants at the JNI boundary), so the observer adds nothing to the
 * realtime path. native/USB per-call costs are MODEL parameters (spin-wait), never measurements of the Redmi Pad.
 * Output lines: `CCS scenario=... key=value`.
 */
class ChordChangeStutterTest {
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50); private val Am = listOf(45, 48, 52)

    private class Cfg(
        val name: String, val intervalMs: Long, val seq: List<List<Int>> = listOf(listOf(36, 40, 43), listOf(41, 45, 48), listOf(43, 47, 50), listOf(36, 40, 43)),
        val midiOut: Boolean = true, val nativeUs: Long = 300, val usbUs: Long = 150, val alignPhase: Long = -1, val spreadMs: Long = 0,
        val release: Boolean = true, val left: Boolean = true, val right: Boolean = false, val fill: Boolean = false, val transpose: Boolean = false,
        val probe: Boolean = true, val stallMs: Long = 0, val stallThread: String = "MainSim", val changes: Int = 12, val style: ParsedStyle? = null, val tempo: Int = 120, val metronome: Boolean = false, val noChange: Boolean = false)

    private class Result(val lateMs: List<Double>, val lateInWindowMs: List<Double>, val gaps: Int, val bursts: Int, val waitsUs: List<Long>, val kbWaitsUs: List<Long>)

    private fun only(name: String) = assumeTrue("filtered", System.getenv("CCS_ONLY")?.split(",")?.contains(name) ?: true)
    private fun report(name: String, vararg kv: Pair<String, Any?>) = println("CCS scenario=$name " + kv.joinToString(" ") { "${it.first}=${it.second}" })
    private fun pms(v: List<Double>, p: Double): Double = if (v.isEmpty()) 0.0 else v.sorted()[((v.size - 1) * p).toInt()]
    private fun r2(d: Double) = Math.round(d * 100) / 100.0

    private fun allocatedBytes(): Long {
        val b = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return b.getThreadAllocatedBytes(b.allThreadIds).filter { it > 0 }.sum()
    }

    private fun field(o: Any, name: String): Any { val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(o)!! }

    private fun run(c: Cfg): Result {
        DebugLog.clear(); LatencyProbe.reset()
        val r = RtRig(c.midiOut, c.tempo, c.style)
        r.nativeCallCostUs = c.nativeUs; r.usbWriteCostUs = c.usbUs
        if (!c.left) r.brain.setLeftVoiceEnabled(false)
        if (c.right) { r.brain.setRightVoiceEnabled(1, true); r.brain.setRightVoiceEnabled(2, true) }
        val lcLock = field(field(r.brain, "sequencer"), "noteLifecycleLock"); val kbLock = field(r.brain, "keyboardLock")
        val waits = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>()); val kbWaits = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        val probing = java.util.concurrent.atomic.AtomicBoolean(true)
        val probe = Thread({
            while (probing.get()) {
                val t0 = System.nanoTime(); synchronized(lcLock) { }; val t1 = System.nanoTime(); waits.add(t0 to (t1 - t0))
                synchronized(kbLock) { }; kbWaits.add(t1 to (System.nanoTime() - t1))
                java.util.concurrent.locks.LockSupport.parkNanos(300_000)
            }
        }, "contender").apply { isDaemon = true }
        var ctl: MetronomeController? = null; val sink = RecSink()
        if (c.metronome) { ctl = MetronomeController(sink, { ArrangerBrainClock(r.brain) }); ctl.setMode(MetronomeMode.FOLLOW); ctl.setEnabled(true) }
        val clock = ArrangerBrainClock(r.brain)
        r.brain.startStop(); Thread.sleep(150)
        if (!c.noChange) c.seq[0].forEach { r.noteOn(it) }
        Thread.sleep(1500)
        val a0 = allocatedBytes(); val evFrom = r.engineEvents.size
        if (c.probe) probe.start()
        val changeAt = ArrayList<Long>()
        val rightRun = java.util.concurrent.atomic.AtomicBoolean(c.right)
        val rightThread = Thread({ var n = 0; while (rightRun.get()) { val k = 72 + (n++ % 5); r.noteOn(k); Thread.sleep(60); r.noteOff(k); Thread.sleep(40) } }, "right").apply { isDaemon = true }
        if (c.right) rightThread.start()
        if (c.alignPhase >= 0) { var g = 0; while (g++ < 4000) { val t = clock.masterTickNow(); val d = (c.alignPhase - (((t % 1920) + 1920) % 1920) + 1920) % 1920; if (d < 20) break; Thread.sleep(2) } }
        var next = System.nanoTime()
        for (n in 1..c.changes) {
            if (!c.noChange) {
                val prev = c.seq[(n - 1) % c.seq.size]; val cur = c.seq[n % c.seq.size]
                if (c.transpose) r.brain.setKeyboardTranspose(n % 3)
                changeAt.add(System.nanoTime())
                if (c.stallMs > 0) { r.stallThread = c.stallThread; r.stallOnceMs = c.stallMs }
                if (c.release) prev.forEach { r.noteOff(it) }
                cur.forEachIndexed { i, k -> r.noteOn(k); if (c.spreadMs > 0 && i < cur.size - 1) Thread.sleep(c.spreadMs) }
                if (!c.release) prev.forEach { r.noteOff(it) }
                if (c.fill && n % 2 == 0) r.brain.selectMainVariation(if ((n / 2) % 2 == 1) ArrangerSection.MainB else ArrangerSection.MainA)
            } else changeAt.add(System.nanoTime())
            next += c.intervalMs * 1_000_000L
            val s = (next - System.nanoTime()) / 1_000_000L; if (s > 0) Thread.sleep(s)
        }
        Thread.sleep(600)
        rightRun.set(false); probing.set(false)
        val a1 = allocatedBytes()
        val evTo = r.engineEvents.size
        if (!c.noChange) c.seq[c.changes % c.seq.size].forEach { r.noteOff(it) }
        r.brain.startStop(); Thread.sleep(250)
        ctl?.release()

        // ---- Style timeline from the outside: hi-hat (ch8 note 42, every 16th) on the synthetic style
        val ev = r.engineEvents.toList().subList(evFrom, evTo)
        val periodNs = 60e9 / c.tempo / 4.0
        val hats = ev.filter { it.kind == 'O' && it.ch == 8 && it.note == 42 }.map { it.t }.sorted()
        val late = ArrayList<Double>(); val inWindow = ArrayList<Double>(); var gaps = 0; var bursts = 0
        if (c.style == null && hats.size > 8) {
            val k = hats.map { Math.round((it - hats[0]) / periodNs) }
            val anchor = hats.indices.map { hats[it] - k[it] * periodNs }.sorted().let { it[it.size / 2] }
            for (i in hats.indices) {
                val l = (hats[i] - (anchor + k[i] * periodNs)) / 1e6; late.add(l)
                if (changeAt.any { hats[i] >= it && hats[i] <= it + 250_000_000L }) inWindow.add(l)
            }
            for (i in 1 until hats.size) { val d = hats[i] - hats[i - 1]; if (d > periodNs * 1.5) gaps++; if (d < periodNs * 0.5) bursts++ }
        }
        if (c.style != null) {
            val sec = c.style.sections["MainA"]
            val ticks = sec?.parts?.flatMap { it.events }?.filter { it.isNoteOn && it.channel in 8..9 }?.map { it.tick.toLong() }?.sorted() ?: emptyList()
            val obs = ev.filter { it.kind == 'O' && it.ch in 8..9 }.map { it.t }.sorted()
            if (sec != null && ticks.isNotEmpty() && obs.size > ticks.size) {
                val nsTick = 60e9 / c.tempo / c.style.ppq; val n = ticks.size; var bestScore = Double.MAX_VALUE; var bestRes: List<Double> = emptyList()
                for (o in 0 until n) {
                    val res = obs.indices.map { i -> obs[i] - (((i + o) / n) * sec.lengthTicks.toLong() + ticks[(i + o) % n]) * nsTick }
                    val med = res.sorted()[res.size / 2]; val dev = res.map { Math.abs(it - med) }.sorted()[res.size / 2]
                    if (dev < bestScore) { bestScore = dev; bestRes = res.map { it - med } }
                }
                late.addAll(bestRes.map { it / 1e6 })
                // where in the loop (tick) and when (relative to the nearest earlier chord change) do events > 4 ms late sit?
                val spikes = obs.indices.filter { bestRes[it] / 1e6 > 4.0 }.map { i ->
                    val tickInLoop = (((obs[i] - obs[0] - bestRes[i]) / nsTick).toLong()) % sec.lengthTicks.toLong()
                    val since = changeAt.lastOrNull { it <= obs[i] }?.let { (obs[i] - it) / 1e6 }
                    "tick${tickInLoop}/late${r2(bestRes[i] / 1e6)}ms/sinceChord${since?.let { r2(it) } ?: "none"}ms"
                }
                if (spikes.isNotEmpty()) report(c.name + "_spikes", "loopTicks" to sec.lengthTicks, "events" to spikes.take(12).joinToString(","))
                obs.indices.forEach { i -> if (changeAt.any { obs[i] >= it && obs[i] <= it + 250_000_000L }) inWindow.add(bestRes[i] / 1e6) }
            }
        }
        // ---- LatencyProbe histograms (production observer hooks, upper bucket bounds)
        val rows = LatencyProbe.report().lines()
        fun stat(name: String) = rows.firstOrNull { it.contains("stat=$name") }?.substringAfter("count=")?.substringBefore(" buckets") ?: "NA"
        val w = waits.map { it.second / 1000 }; val kbw = kbWaits.map { it.second / 1000 }
        val under = ev.count { (it.kind == 'O' || it.kind == 'F') && it.lcLock }; val onMain = ev.count { (it.kind == 'O' || it.kind == 'F') && it.thread == "MainSim" }
        val total = ev.count { it.kind == 'O' || it.kind == 'F' }
        val onsPerWindow = ev.filter { it.kind == 'O' }.map { it.t }.sorted().let { t -> var mx = 0; var j = 0; for (i in t.indices) { while (t[i] - t[j] > 5_000_000L) j++; mx = maxOf(mx, i - j + 1) }; mx }
        val worstChordWait = waits.filter { it.second > 2_000_000L }.size
        report(c.name, "changes" to c.changes, "intervalMs" to c.intervalMs, "midiOut" to c.midiOut, "modelNativeUs" to c.nativeUs, "modelUsbUs" to c.usbUs,
            "hats" to hats.size, "hatLateP50ms" to r2(pms(late, .5)), "hatLateP95ms" to r2(pms(late, .95)), "hatLateP99ms" to r2(pms(late, .99)), "hatLateMaxms" to r2(late.maxOrNull() ?: 0.0),
            "hatLateInChordWindowP95ms" to r2(pms(inWindow, .95)), "hatLateInChordWindowMaxms" to r2(inWindow.maxOrNull() ?: 0.0),
            "hatLateOver5ms" to late.count { it > 5 }, "hatLateOver10ms" to late.count { it > 10 }, "hatLateOver20ms" to late.count { it > 20 }, "missedHatGaps" to gaps, "catchUpBursts" to bursts,
            "lifecycleWaitSamples" to w.size, "lifecycleWaitP50us" to pct(w, .5), "lifecycleWaitP95us" to pct(w, .95), "lifecycleWaitP99us" to pct(w, .99), "lifecycleWaitMaxus" to (w.maxOrNull() ?: 0), "lifecycleWaitOver2ms" to worstChordWait,
            "keyboardLockWaitP99us" to pct(kbw, .99), "keyboardLockWaitMaxus" to (kbw.maxOrNull() ?: 0),
            "schedulerWakeLateness" to stat("styleEventWakeLatenessBeforeLifecycle"), "dispatchLateness" to stat("styleEventDispatchLatenessAfterLifecycle"),
            "chordApplyDuration" to stat("chordApplyDuration_handleChordChange"), "sectionFirstNoteLateness" to stat("sectionFirstNoteLatenessVsSchedule"),
            "globalAllNotesOffInWindow" to ev.count { it.kind == 'A' }, "nativeCalls" to total, "nativeCallsUnderLifecycleLock" to under, "nativeCallsOnMainThread" to onMain, "maxNoteOnWithin5ms" to onsPerWindow,
            "peakInstances1_5sTail" to r.peakInstances(1.5), "usbNoteOn" to r.usbCount(true), "usbNoteOff" to r.usbCount(false), "debugLogLines" to DebugLog.getFullAll().size,
            "allocKiB" to (a1 - a0) / 1024, "stuck" to r.oracle.stuckOnChannels(0..15), "orphanOff" to r.oracle.orphanOffs)
        if (c.metronome && hats.isNotEmpty()) {
            val kicks = ev.filter { it.kind == 'O' && it.ch == 8 && it.note == 36 }.map { it.t }.sorted()
            val clicks = sink.pushes.map { it.target }.distinct().sorted()
            val off = kicks.map { kk -> kk - (clicks.minByOrNull { Math.abs(it - kk) } ?: 0L) }.map { it / 1e6 }
            val ci = clicks.zipWithNext { a, b -> (b - a) / 1e6 }
            report(c.name + "_metronomeVsStyle", "kicks" to kicks.size, "clicks" to clicks.size, "kickMinusClickP50ms" to r2(pms(off, .5)), "kickMinusClickP95ms" to r2(pms(off, .95)),
                "kickMinusClickMaxms" to r2(off.maxOrNull() ?: 0.0), "clickIntervalMinms" to r2(ci.minOrNull() ?: 0.0), "clickIntervalMaxms" to r2(ci.maxOrNull() ?: 0.0))
        }
        val out = Result(late, inWindow, gaps, bursts, w, kbw)
        r.close(); return out
    }

    // ---------------------------------------------------------------- controls
    @Test fun controlNoChordChangeWithAndWithoutModelCosts() { only("controlNoChordChangeWithAndWithoutModelCosts");
        run(Cfg("control_noChange_hostRaw", 500, noChange = true, nativeUs = 0, usbUs = 0, midiOut = false, changes = 12))
        run(Cfg("control_noChange_model300_150_midiOut", 500, noChange = true, changes = 12))
    }

    // ---------------------------------------------------------------- intervals, model costs, MIDI OUT on/off
    @Test fun intervalSweepC_F_G_C() { only("intervalSweepC_F_G_C");
        for (iv in listOf(1000L, 500L, 250L, 125L, 80L)) run(Cfg("seq_CFGC_${iv}ms_model300_150_midiOut", iv, changes = (6000 / iv).toInt().coerceIn(8, 40)))
    }
    @Test fun intervalSweepC_Am_F_G() { only("intervalSweepC_Am_F_G");
        for (iv in listOf(500L, 125L)) run(Cfg("seq_CAmFG_${iv}ms_model300_150_midiOut", iv, seq = listOf(C, Am, F, G), changes = (6000 / iv).toInt().coerceIn(8, 40)))
    }
    @Test fun midiOutOnOffAndCostModels() { only("midiOutOnOffAndCostModels");
        run(Cfg("cost_hostRaw_midiOutOff_125ms", 125, midiOut = false, nativeUs = 0, usbUs = 0, changes = 30))
        run(Cfg("cost_hostRaw_midiOutOn_125ms", 125, midiOut = true, nativeUs = 0, usbUs = 0, changes = 30))
        run(Cfg("cost_model300_150_midiOutOff_125ms", 125, midiOut = false, changes = 30))
        run(Cfg("cost_model300_150_midiOutOn_125ms", 125, midiOut = true, changes = 30))
        run(Cfg("cost_model700_300_midiOutOn_125ms", 125, midiOut = true, nativeUs = 700, usbUs = 300, changes = 30))
        run(Cfg("cost_model700_300_midiOutOn_500ms", 500, midiOut = true, nativeUs = 700, usbUs = 300, changes = 12))
    }
    @Test fun phaseAndFingeringVariants() { only("phaseAndFingeringVariants");
        run(Cfg("phase_onBeat_500ms", 500, alignPhase = 0))
        run(Cfg("phase_midBeat_500ms", 500, alignPhase = 240))
        run(Cfg("phase_nearBarLine_500ms", 500, alignPhase = 1860))
        run(Cfg("barLine_changeEveryBar_2000ms_model300_150", 2000, alignPhase = 1900, changes = 16))
        run(Cfg("barLine_changeEveryBar_2000ms_model700_300", 2000, alignPhase = 1900, changes = 16, nativeUs = 700, usbUs = 300))
        run(Cfg("barLine_changeEveryBar_2000ms_hostRaw_midiOutOff", 2000, alignPhase = 1900, changes = 16, nativeUs = 0, usbUs = 0, midiOut = false))
        run(Cfg("barLine_noChange_2000ms_model300_150", 2000, alignPhase = 1900, changes = 16, noChange = true))
        run(Cfg("midBar_changeEveryBar_2000ms_model300_150", 2000, alignPhase = 960, changes = 16))
        run(Cfg("fingering_spread8ms_250ms", 250, spreadMs = 8, changes = 20))
        run(Cfg("fingering_pressBeforeRelease_250ms", 250, release = false, changes = 20))
    }
    @Test fun layeredAndSectionVariants() { only("layeredAndSectionVariants");
        run(Cfg("variant_leftOff_250ms", 250, left = false, changes = 20))
        run(Cfg("variant_acmpLeftOn_right123Playing_250ms", 250, right = true, changes = 20))
        run(Cfg("variant_mainFillSwitching_250ms", 250, fill = true, changes = 20))
        run(Cfg("variant_pendingTransposeEachChange_500ms", 500, transpose = true))
    }
    @Test fun metronomeAsReferenceDuringChordChanges() { only("metronomeAsReferenceDuringChordChanges");
        run(Cfg("metronome_noChange", 500, noChange = true, metronome = true, changes = 12))
        run(Cfg("metronome_chords_125ms", 125, metronome = true, changes = 30))
        run(Cfg("metronome_chords_125ms_model700_300", 125, metronome = true, nativeUs = 700, usbUs = 300, changes = 30))
    }

    // ---------------------------------------------------------------- real Style (ROSITA): polyphony, lock contention, probe histograms
    private fun rosita(): ParsedStyle? {
        val path = System.getenv("ROSITA_TSV") ?: return null
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
        closeSection(); return ParsedStyle("ROSITA.STY", ppq, sections, emptyMap(), 120)
    }

    @Test fun realStyleRositaPolyphonyAndContention() { only("realStyleRositaPolyphonyAndContention");
        val s = rosita(); assumeTrue("ROSITA_TSV not set", s != null)
        run(Cfg("rosita_noChange_model300_150", 500, noChange = true, style = s, changes = 12))
        run(Cfg("rosita_slow_1000ms_model300_150", 1000, style = s, changes = 8))
        run(Cfg("rosita_250ms_model300_150", 250, style = s, changes = 24))
        run(Cfg("rosita_125ms_model300_150", 125, style = s, changes = 40))
        run(Cfg("rosita_125ms_hostRaw_midiOutOff", 125, style = s, changes = 40, nativeUs = 0, usbUs = 0, midiOut = false))
        run(Cfg("rosita_250ms_right123_left_midiOut", 250, style = s, changes = 24, right = true))
    }

    // ---------------------------------------------------------------- repeated trials (no contender thread): is the tail heavier with chord changes than without?
    private fun trials(label: String, n: Int, mk: (Int) -> Cfg) {
        val all = ArrayList<Double>(); val win = ArrayList<Double>()
        repeat(n) { all.addAll(run(mk(it)).lateMs); }
        report("trials_$label", "trials" to n, "hats" to all.size, "p50ms" to r2(pms(all, .5)), "p95ms" to r2(pms(all, .95)), "p99ms" to r2(pms(all, .99)), "p999ms" to r2(pms(all, .999)),
            "maxms" to r2(all.maxOrNull() ?: 0.0), "over2ms" to all.count { it > 2 }, "over5ms" to all.count { it > 5 }, "over10ms" to all.count { it > 10 }, "over20ms" to all.count { it > 20 }, "over40ms" to all.count { it > 40 })
    }
    @Test fun repeatedTrialsControlVsChordChanges() { only("repeatedTrialsControlVsChordChanges");
        trials("control_noChange_model300_150", 6) { Cfg("t_ctl_$it", 500, noChange = true, probe = false, changes = 14) }
        trials("chords_500ms_model300_150", 6) { Cfg("t_c500_$it", 500, probe = false, changes = 14) }
        trials("chords_125ms_model300_150", 6) { Cfg("t_c125_$it", 125, probe = false, changes = 56) }
        trials("chords_125ms_model700_300", 6) { Cfg("t_c125h_$it", 125, probe = false, nativeUs = 700, usbUs = 300, changes = 56) }
        trials("chords_125ms_hostRaw_midiOutOff", 6) { Cfg("t_c125r_$it", 125, probe = false, nativeUs = 0, usbUs = 0, midiOut = false, changes = 56) }
        trials("control_noChange_hostRaw", 6) { Cfg("t_ctlr_$it", 500, noChange = true, probe = false, nativeUs = 0, usbUs = 0, midiOut = false, changes = 14) }
    }

    @Test fun rositaSectionEntryCosts() {
        only("rositaSectionEntryCosts")
        val s = rosita(); assumeTrue("ROSITA_TSV not set", s != null); s!!
        for (name in s.sections.keys.take(40)) {
            val sec = s.sections.getValue(name)
            val t0 = System.nanoTime()
            val merged = sec.parts.flatMap { part -> part.events.map { e -> Triple(((e.tick.toLong() % sec.lengthTicks.coerceAtLeast(1)) ).toInt(), e, part) } }.sortedWith(compareBy<Triple<Int, StyleNoteEvent, StylePartModel>> { it.first }.thenBy { it.second.isNoteOn.not() })
            val t1 = System.nanoTime()
            com.yourapp.yamahaarranger.arranger.StyleAudioPathDiagnostic.inventory(sec)
            val t2 = System.nanoTime()
            report("rosita_section_entry_$name", "events" to merged.size, "mergeSortUsHostCold" to (t1 - t0) / 1000, "inventoryFirstVisitUsHost" to (t2 - t1) / 1000)
        }
    }

    // ---------------------------------------------------------------- deterministic reproduction: ONE blocking native call inside the retarget
    @Test fun oneBlockedJniCallInTheMainThreadRetargetStallsTheStyleScheduler() {
        only("oneBlockedJniCallInTheMainThreadRetargetStallsTheStyleScheduler")
        val ctl = run(Cfg("stall_control_none", 500, probe = false, changes = 10))
        val res = HashMap<Long, Double>()
        for (ms in listOf(10L, 30L, 60L, 100L)) res[ms] = run(Cfg("stall_${ms}ms_inMainRetarget_500ms", 500, probe = false, stallMs = ms, changes = 10)).lateMs.maxOrNull() ?: 0.0
        println("CCS scenario=stall_summary controlMaxMs=${r2(ctl.lateMs.maxOrNull() ?: 0.0)} " + res.toSortedMap().entries.joinToString(" ") { "stall${it.key}msMaxLateMs=${r2(it.value)}" })
        // The Style event only waits for the REMAINING part of the block when it happens to be due inside it: lateness is spread over 0..block, so the
        // visible effect is intermittent and grows with the block length (it needs the block to overlap a scheduled event).
        assertTrue("a 100 ms block inside the Main-thread retarget must show up as >= 30 ms Style dispatch lateness (the monitor is shared)", (res[100L] ?: 0.0) >= 30.0)
        assertTrue("and it is far above the no-stall control", (res[100L] ?: 0.0) > 5 * (ctl.lateMs.maxOrNull() ?: 0.1))
    }

    // ---------------------------------------------------------------- deterministic structural reproduction
    @Test fun retargetHoldsTheStyleLifecycleLockWhileCallingJniAndUsb() { only("retargetHoldsTheStyleLifecycleLockWhileCallingJniAndUsb");
        val res = run(Cfg("repro_lock_hold_model700_300", 500, nativeUs = 700, usbUs = 300, changes = 10))
        val max = res.waitsUs.maxOrNull() ?: 0
        assertTrue("an external contender for the Style lifecycle monitor waited ${max}us during a retarget (JNI+USB inside the monitor)", max > 5_000)
    }

    @Test fun noChordChangeNeverBlocksTheLifecycleMonitorForLong() { only("noChordChangeNeverBlocksTheLifecycleMonitorForLong");
        val res = run(Cfg("repro_lock_control_model700_300_noChange", 500, nativeUs = 700, usbUs = 300, noChange = true, changes = 10))
        val max = res.waitsUs.maxOrNull() ?: 0
        println("CCS scenario=repro_lock_control_summary maxWaitUs=$max")
        assertTrue("sampled", res.waitsUs.isNotEmpty())
    }
}

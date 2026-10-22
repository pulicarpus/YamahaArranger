package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.ArrangerBrainClock
import com.yourapp.yamahaarranger.audio.LatencyProbe
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

/**
 * MAIN/FILL/MAIN TRANSITION GAP AUDIT (HOST evidence only; AUDIT, no production change).
 * Production chain with the real ROSITA.STY (unmodified C++ parser) at 280 BPM (fast host runs). For every section transition it compares
 *   authored MIDI (the Style's own events) -> due time (Style clock) -> dispatched engine NOTE_ON/OFF (JNI boundary instants),
 * per destination channel, and separates: authored gap (rest written in the Style), scheduling gap (target event later than due),
 * lifecycle gap (source notes cut at the boundary), and apparent audio gaps (NOT MEASURED on the host).
 * Rows: `SGAP scenario=...`.
 */
class SectionTransitionGapTest {
    private fun only(name: String) = assumeTrue("filtered", System.getenv("SGAP_ONLY")?.split(",")?.contains(name) ?: true)
    private fun out(line: String) = println("SGAP $line")
    private fun r2(d: Double) = Math.round(d * 100) / 100.0
    private fun pct(v: List<Double>, p: Double) = if (v.isEmpty()) Double.NaN else v.sorted()[((v.size - 1) * p).toInt()]

    private fun rosita(): ParsedStyle? {
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

    private fun prefixBalanced(r: RtRig, what: String) {
        val bal = HashMap<Int, Int>()
        for (e in r.engineEvents.toList().sortedBy { it.t }) {
            val k = e.ch * 256 + e.note
            if (e.kind == 'O') bal.merge(k, 1, Int::plus) else if (e.kind == 'F') { val v = (bal[k] ?: 0) - 1; assertTrue("$what: NOTE_OFF before its NOTE_ON on ch${e.ch} note ${e.note}", v >= 0); bal[k] = v } else if (e.kind == 'A') bal.clear()
        }
    }

    // ---------------------------------------------------------------- authored data (pure, no timing)
    /** dst channel -> sorted authored NOTE_ON ticks of the section (the Style's own events). */
    private fun authoredOns(sec: StyleSectionModel): Map<Int, List<Int>> {
        val m = HashMap<Int, ArrayList<Int>>()
        for (p in sec.parts) {
            val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }
            for (e in p.events) if (e.isNoteOn) { val dst = pol.firstOrNull { it.sourceChannel == e.channel }?.destinationChannel ?: pol.firstOrNull()?.destinationChannel ?: e.channel; m.getOrPut(dst) { ArrayList() }.add(e.tick) }
        }
        return m.mapValues { it.value.sorted() }
    }

    @Test fun authoredRestsAndLengthsOfEverySectionAreTheStylesOwn() {
        only("authoredRestsAndLengthsOfEverySectionAreTheStylesOwn")
        val s = rosita(); assumeTrue("ROSITA_TSV not set", s != null); s!!
        val ns = 60e9 / 120 / s.ppq
        for ((name, sec) in s.sections) {
            val on = authoredOns(sec)
            val lastEv = HashMap<Int, Int>()
            for (p in sec.parts) { val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }
                for (e in p.events) if (e.isNoteOn || (e.status and 0xF0) == 0x80) { val dst = pol.firstOrNull { it.sourceChannel == e.channel }?.destinationChannel ?: pol.firstOrNull()?.destinationChannel ?: e.channel; lastEv.merge(dst, e.tick, ::maxOf) } }
            val firsts = on.entries.sortedBy { it.key }.joinToString(" ") { "ch${it.key}:first@${r2(it.value.first() * ns / 1e6)}ms@120BPM(tick ${it.value.first()}),n=${it.value.size},tailRestAfterLastEvent=${r2((sec.lengthTicks - (lastEv[it.key] ?: 0)) * ns / 1e6)}ms@120BPM" }
            out("authored section=$name lengthTicks=${sec.lengthTicks} bars=${r2(sec.lengthTicks / (s.ppq * 4.0))} $firsts")
        }
    }

    // ---------------------------------------------------------------- measured transitions
    private class Trans(val section: String, val tick: Long)
    private class Ctl(val r: RtRig, val barTicks: Long) {
        val clock = ArrangerBrainClock(r.brain)
        fun waitPhase(phaseTicks: Long) { var g = 0; while (g++ < 6000) { val t = clock.masterTickNow(); val d = (phaseTicks - (((t % barTicks) + barTicks) % barTicks) + barTicks) % barTicks; if (d < 12) break; Thread.sleep(1) } }
        fun waitTransitions(n: Int, timeoutMs: Long = 25_000) { val end = System.currentTimeMillis() + timeoutMs; while (transitions().size < n && System.currentTimeMillis() < end) Thread.sleep(5) }
        fun transitions(): List<Trans> = DebugLog.getFullAll().mapNotNull { Regex("MASTER CLOCK → (\\w+) at tick=(\\d+)").find(it)?.let { m -> Trans(m.groupValues[1], m.groupValues[2].toLong()) } }
        fun bars(n: Double) = Thread.sleep((n * barTicks * 60_000.0 / (280 * 480)).toLong())
    }

    private class Cfg(val midiOut: Boolean = false, val nativeUs: Long = 0, val usbUs: Long = 0, val acmp: Boolean = true, val left: Boolean = false, val right: Boolean = false,
        val autoFill: Boolean = true, val stallMs: Long = 0, val chord: Boolean = true, val transposePending: Boolean = false, val prodMixer: Boolean = false)

    private fun scenario(name: String, c: Cfg = Cfg(), styleOverride: ParsedStyle? = null, body: (Ctl) -> Unit) {
        val style = styleOverride ?: rosita(); assumeTrue("ROSITA_TSV not set", style != null); style!!
        DebugLog.clear(); LatencyProbe.reset()
        val r = RtRig(c.midiOut, 120, style); r.nativeCallCostUs = c.nativeUs; r.usbWriteCostUs = c.usbUs
        if (c.prodMixer) r.wireProductionMixerOutput()
        r.brain.setTempo(280); r.brain.setAutoFill(c.autoFill)
        if (!c.acmp) r.brain.setAcmpEnabled(false)
        if (!c.left) r.brain.setLeftVoiceEnabled(false)
        if (c.right) { r.brain.setRightVoiceEnabled(1, true); r.brain.setRightVoiceEnabled(2, true) }
        val ctl = Ctl(r, style.ppq.toLong() * 4L * style.meter.numerator / style.meter.denominator)
        r.brain.startStop(); Thread.sleep(200)
        if (c.acmp && c.chord) { listOf(36, 40, 43).forEach { r.noteOn(it) }; Thread.sleep(60); listOf(36, 40, 43).forEach { r.noteOff(it) } }
        val rightRun = java.util.concurrent.atomic.AtomicBoolean(c.right)
        val rt = Thread({ var n = 0; while (rightRun.get()) { val k = 72 + (n++ % 7); r.noteOn(k); Thread.sleep(40); r.noteOff(k); Thread.sleep(30) } }, "right").apply { isDaemon = true }
        if (c.right) rt.start()
        val watcher = java.util.concurrent.atomic.AtomicBoolean(c.stallMs > 0)
        val w = Thread({   // arms ONE blocking native call on the scheduler thread right after each section switch is logged
            var seen = 0
            while (watcher.get()) { val n = DebugLog.getFullAll().count { it.contains("MASTER CLOCK →") }; if (n > seen) { seen = n; r.stallThread = "DefaultDispatcher"; r.stallOnceMs = c.stallMs }; Thread.sleep(0, 200_000) }
        }, "armer").apply { isDaemon = true }
        if (c.stallMs > 0) w.start()
        body(ctl)
        rightRun.set(false); watcher.set(false)
        if (c.right) rt.join(1000)   // let the live-note thread finish its NoteOn/NoteOff pair before STOP (a harness race, not an engine property)
        // ---- analysis while the clock still runs
        val trans = ctl.transitions(); val ns = 60e9 / 280 / style.ppq
        val ev = r.engineEvents.toList()
        var prevName = "MainA"; var prevStart = 0L
        val firstLate = ArrayList<Double>(); val allLate = ArrayList<Double>(); val drumFirstLate = ArrayList<Double>(); val engineGap = ArrayList<Double>()
        trans.forEachIndexed { i, t ->
            val sec = style.sections[t.section] ?: return@forEachIndexed
            val src = style.sections[prevName]
            val b = ArrangerBrainClock(r.brain).tickToNanos(t.tick) ?: return@forEachIndexed
            val nextB = trans.getOrNull(i + 1)?.let { ArrangerBrainClock(r.brain).tickToNanos(it.tick) } ?: Long.MAX_VALUE
            val posSrc = if (src != null && src.lengthTicks > 0) (t.tick - prevStart).mod(src.lengthTicks.toLong()) else -1L
            val auth = authoredOns(sec)
            val endNs = minOf(b + sec.lengthTicks * ns.toLong(), nextB)
            val cols = ArrayList<String>()
            for ((ch, ticks) in auth.entries.sortedBy { it.key }) {
                val actual = ev.filter { it.kind == 'O' && it.ch == ch && it.t >= b - 1_000_000L && it.t < endNs }.map { it.t }.sorted()
                val k = minOf(ticks.size, actual.size); if (k == 0) { cols.add("ch$ch:authoredN=${ticks.size},actualN=0"); continue }
                val late = (0 until k).map { (actual[it] - (b + (ticks[it] * ns).toLong())) / 1e6 }
                firstLate.add(late[0]); allLate.addAll(late); if (ch in 8..9) drumFirstLate.add(late[0])
                if (ticks.size != actual.size && ch in 10..15) out("countMismatch scenario=$name #$i $prevName->${t.section} ch$ch authoredOnsTick=${ticks.take(12)} actualOnsRelMsAndNote=${ev.filter { it.kind == 'O' && it.ch == ch && it.t >= b - 1_000_000L && it.t < endNs }.sortedBy { it.t }.take(12).map { "${r2((it.t - b) / 1e6)}:n${it.note}" }}")
                if (ch in 10..15) { // harmonic destination: last NOTE_OFF the engine sent before the new section's first NOTE_ON -> that first NOTE_ON
                    val firstOn = actual[0]; val lastOff = ev.filter { it.kind == 'F' && it.ch == ch && it.t in (b - 2_000_000L)..firstOn }.maxOfOrNull { it.t }
                    if (lastOff != null) { val gap = (firstOn - lastOff) / 1e6; val head = ticks[0] * ns / 1e6; engineGap.add(gap - head); cols.add("ch$ch:cutToNewMs=${r2(gap)}(authoredHead=${r2(head)},engineAdded=${r2(gap - head)})") }
                }
                cols.add("ch$ch:authRest=${r2(ticks[0] * ns / 1e6)}ms,firstLate=${r2(late[0])}ms,maxLate=${r2(late.max())}ms,n=${ticks.size}/${actual.size}")
            }
            val srcAuth = src?.let { sc -> authoredOns(sc).filterValues { l -> l.any { it <= posSrc } } }
            val cut = ev.filter { it.kind == 'F' && it.t >= b - 1_000_000L && it.t <= b + 60_000_000L }
            out("transition scenario=$name #$i $prevName->${t.section} boundaryTick=${t.tick} srcPosTick=$posSrc barAligned=${t.tick % ctl.barTicks == 0L} gridOffsetMs=${r2((((t.tick + ctl.barTicks / 2) % ctl.barTicks) - ctl.barTicks / 2) * ns / 1e6)} cutNoteOffs=${cut.size} cutOffLateMaxMs=${r2(((cut.maxOfOrNull { it.t } ?: b) - b) / 1e6)} " + cols.joinToString(" "))
            prevName = t.section; prevStart = t.tick
        }
        if (c.midiOut) burstAnalysis(name, r, trans, ev)
        val rows = LatencyProbe.report().lines()
        fun stat(n: String) = rows.firstOrNull { it.contains("stat=$n") }?.substringAfter("count=")?.substringBefore(" buckets") ?: "NA"
        val turn = try { val seq = r.brain.javaClass.getDeclaredField("sequencer").apply { isAccessible = true }.get(r.brain)!!; val io = seq.javaClass.getDeclaredField("io").apply { isAccessible = true }.get(seq)!!; (io.javaClass.getDeclaredMethod("getMaxTurnWaitNanos").invoke(io) as Long) / 1000 } catch (e: Exception) { -1L }
        out("summary scenario=$name transitions=${trans.size} firstEventLateMs(p50/p95/max)=${r2(pct(firstLate, .5))}/${r2(pct(firstLate, .95))}/${r2(firstLate.maxOrNull() ?: Double.NaN)} allLateMs(p50/p95/p99/max)=${r2(pct(allLate, .5))}/${r2(pct(allLate, .95))}/${r2(pct(allLate, .99))}/${r2(allLate.maxOrNull() ?: Double.NaN)} harmonicCutToNewEngineAddedMs(n/p50/p95/max)=${engineGap.size}/${r2(pct(engineGap, .5))}/${r2(pct(engineGap, .95))}/${r2(engineGap.maxOrNull() ?: Double.NaN)} drumFirstLateMaxMs=${r2(drumFirstLate.maxOrNull() ?: Double.NaN)} " +
            "boundaryToApplyStart=${stat("sectionBoundaryToVoiceApplyStart")} apply=${stat("sectionVoiceApplyDuration")} requestToBoundary=${stat("sectionRequestToBoundaryWake")} applyEndToFirstNote=${stat("sectionVoiceApplyEndToFirstNoteDispatch")} boundaryToFirstNote=${stat("sectionBoundaryToFirstNoteDispatch")} firstNoteLateness=${stat("sectionFirstNoteLatenessVsSchedule")} " +
            "wake=${stat("styleEventWakeLatenessBeforeLifecycle")} dispatch=${stat("styleEventDispatchLatenessAfterLifecycle")} turnWaitMaxUs=$turn nativeCalls=${ev.count { it.kind == 'O' || it.kind == 'F' }} usbWrites=${r.usbWrites.size} playRestarts=${DebugLog.getFullAll().count { it.contains("▶ PLAY") }} globalAllNotesOff=${ev.count { it.kind == 'A' }}")
        r.brain.startStop(); Thread.sleep(250)
        out("lifecycle scenario=$name stuck=${r.oracle.stuckOnChannels(0..15)} orphanOff=${r.oracle.orphanOffs}")
        assertEquals("$name: no stuck notes after STOP", 0, r.oracle.stuckOnChannels(0..15))
        assertEquals("$name: no orphan NOTE_OFF", 0L, r.oracle.orphanOffs)
        prefixBalanced(r, name)
        assertTrue("$name: every boundary is a bar line unless the late-request path was taken", trans.all { it.tick % ctl.barTicks == 0L || it.tick > 0 })
        r.close()
    }

    @Test fun mainFillMain_FillAA() { only("mainFillMain_FillAA"); scenario("MainA_FillAA_MainA") { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) } }
    @Test fun autoFillMainAToMainB() { only("autoFillMainAToMainB"); scenario("MainA_to_MainB_autoFillOn") { c -> c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainB); c.waitTransitions(2); c.bars(1.2) } }
    @Test fun mainDToMainBViaFill() { only("mainDToMainBViaFill"); scenario("MainD_to_MainB_autoFillOn") { c -> c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainD); c.waitTransitions(2); c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainB); c.waitTransitions(4); c.bars(1.2) } }
    @Test fun unfinishedFillRetarget() { only("unfinishedFillRetarget"); scenario("MainD_FillB_unfinished_to_MainA") { c ->
        c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainD); c.waitTransitions(2); c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainB)
        c.waitTransitions(3); c.r.brain.selectMainVariation(ArrangerSection.MainA); c.waitTransitions(5, 30_000); c.bars(1.2) } }
    @Test fun autoFillOff() { only("autoFillOff"); scenario("MainA_to_MainB_autoFillOff", Cfg(autoFill = false)) { c -> c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainB); c.waitTransitions(1); c.bars(1.2) } }
    @Test fun fillReplacedByFill() { only("fillReplacedByFill"); scenario("FillAA_replaced_by_FillBB") { c ->
        c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(1); c.r.brain.selectSection(ArrangerSection.FillBB); c.waitTransitions(3, 30_000); c.bars(1.2) } }
    @Test fun requestPhases() {
        only("requestPhases")
        for ((label, phase) in listOf("barStart" to 100L, "beat2" to 1920L + 100L, "midBar" to 3840L, "lateBar" to 7000L, "within25msOfBar" to 7500L, "within5msOfBar" to 7640L))
            scenario("MainA_FillAA_request_$label") { c -> c.waitPhase(phase); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
    }
    @Test fun acmpOffDrumOnly() { only("acmpOffDrumOnly"); scenario("FillAA_to_Main_ACMP_OFF_drumOnly", Cfg(acmp = false)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) } }
    @Test fun leftAndRightPlaying() { only("leftAndRightPlaying"); scenario("MainA_FillAA_LEFT_RIGHT123_playing", Cfg(left = true, right = true)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) } }
    @Test fun midiOutOffAndOnWithModelCosts() {
        only("midiOutOffAndOnWithModelCosts")
        scenario("MainA_FillAA_midiOut_OFF_model300_150", Cfg(midiOut = false, nativeUs = 300, usbUs = 150)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
        scenario("MainA_FillAA_midiOut_ON_model300_150", Cfg(midiOut = true, nativeUs = 300, usbUs = 150)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
        scenario("MainA_FillAA_midiOut_ON_model700_300", Cfg(midiOut = true, nativeUs = 700, usbUs = 300)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
    }
    @Test fun pendingTransposeAtTransition() { only("pendingTransposeAtTransition"); scenario("MainA_FillAA_pendingTranspose") { c -> c.r.brain.setKeyboardTranspose(5); c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) } }
    @Test fun nativeBlockAtTheBoundary() {
        only("nativeBlockAtTheBoundary")
        scenario("MainA_FillAA_100msNativeBlock_after_each_switch", Cfg(stallMs = 100)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
    }
    @Test fun repeatedRapidTransitions() {
        only("repeatedRapidTransitions")
        scenario("rapid_random_transitions_90s", Cfg(left = true, right = true, midiOut = true, nativeUs = 100, usbUs = 50)) { c ->
            val rnd = java.util.Random(7); val mains = listOf(ArrangerSection.MainA, ArrangerSection.MainB, ArrangerSection.MainC, ArrangerSection.MainD)
            val fills = listOf(ArrangerSection.FillAA, ArrangerSection.FillBB, ArrangerSection.FillCC, ArrangerSection.FillDD)
            val end = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < end) {
                if (rnd.nextInt(3) == 0) c.r.brain.selectSection(fills[rnd.nextInt(4)]) else c.r.brain.selectMainVariation(mains[rnd.nextInt(4)])
                Thread.sleep(250L + rnd.nextInt(1800))
            }
            c.bars(2.0)
        }
    }

    /** SYNTHETIC arithmetic check only (the repository has no 2/4, 3/4 or 6/8 Yamaha Style fixture): the transition boundary must be a bar line of the style's meter. */
    @Test fun syntheticMeterArithmeticOnly() {
        only("syntheticMeterArithmeticOnly")
        for ((num, den) in listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8)) {
            val base = RtRig(false, 120).also { it.close() }.loadedStyle
            val st = base.copy(meter = com.yourapp.yamahaarranger.style.StyleMeter(num, den, base.ppq))
            scenario("SYNTHETIC_meter_${num}_$den", Cfg(), st) { c ->
                c.waitPhase(c.barTicks / 2); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(0.5)
                val t = c.transitions(); out("meter scenario=SYNTHETIC_meter_${num}_$den barTicks=${c.barTicks} boundaries=${t.map { it.tick }} quantizedFirstBoundaryAligned=${t.firstOrNull()?.let { it.tick % c.barTicks == 0L }} (second boundary = first + the synthetic fill length 1920 ticks, which is not a bar multiple for 3/4 and 6/8: fixture property, not an engine result)")
                assertTrue("first (request-quantized) boundary is a bar line of the meter", t.firstOrNull()?.let { it.tick % c.barTicks == 0L } == true)
            }
        }
    }

    /** STOP issued while the scheduler is blocked inside the section-switch work: nothing of the old session may sound afterwards. */
    @Test fun stopDuringABlockedSectionSwitch() {
        only("stopDuringABlockedSectionSwitch")
        val style = rosita(); assumeTrue("ROSITA_TSV not set", style != null); style!!
        DebugLog.clear(); val r = RtRig(false, 120, style); r.brain.setTempo(280); r.brain.setLeftVoiceEnabled(false)
        val ctl = Ctl(r, style.ppq.toLong() * 4L)
        r.brain.startStop(); Thread.sleep(150); listOf(36, 40, 43).forEach { r.noteOn(it) }; Thread.sleep(60); listOf(36, 40, 43).forEach { r.noteOff(it) }
        ctl.waitPhase(3840); r.brain.selectSection(ArrangerSection.FillAA)
        val armer = Thread { while (DebugLog.getFullAll().none { it.contains("MASTER CLOCK →") }) Thread.sleep(0, 200_000); r.stallThread = "DefaultDispatcher"; r.stallOnceMs = 150 }.apply { isDaemon = true }; armer.start()
        val end = System.currentTimeMillis() + 20_000; while (r.stallStartedNs == 0L && System.currentTimeMillis() < end) Thread.sleep(1)
        Thread.sleep(40); r.brain.startStop()
        val stoppedAt = System.nanoTime(); Thread.sleep(500)
        val late = r.engineEvents.count { it.kind == 'O' && it.t > stoppedAt }
        out("stopBlocked onsAfterStop=$late stuck=${r.oracle.stuckOnChannels(0..15)} orphan=${r.oracle.orphanOffs}")
        assertEquals("no NOTE_ON after STOP returned", 0, late); assertEquals(0, r.oracle.stuckOnChannels(0..15)); prefixBalanced(r, "stopDuringABlockedSectionSwitch"); r.close()
    }

    /**
     * Deterministic reproduction of a timing artefact created by the engine, not by the Style: a Fill requested within 25 ms before the bar line is NOT
     * quantised ([StyleSequencer.nextBarTargetTick] returns null for wait <= 25 ms) and takes the "phase continuous" path, so the Fill - and the Main after it - start
     * at the current tick, i.e. before the bar line, and every pattern then repeats displaced from the bar grid.
     * SGAP_EXPECT=current asserts today's behaviour (boundary off the bar line, earlier than it); SGAP_EXPECT=fixed asserts the intended behaviour (on the bar line).
     */
    @Test fun requestWithin25msBeforeTheBarLineLeavesTheBarGrid() {
        only("requestWithin25msBeforeTheBarLineLeavesTheBarGrid")
        val style = rosita(); assumeTrue("ROSITA_TSV not set", style != null); style!!
        val offsets = ArrayList<Double>(); val ticks = ArrayList<Long>()
        repeat(4) { n ->
            scenario("OFFGRID_request_${n}_before_bar_line", Cfg()) { c ->
                c.waitPhase(c.barTicks - 140L); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(0.3)
                val t = c.transitions().first(); val ns = 60e9 / 280 / style.ppq
                offsets.add((((t.tick + c.barTicks / 2) % c.barTicks) - c.barTicks / 2) * ns / 1e6); ticks.add(t.tick)
            }
        }
        out("offgrid boundaryTicks=$ticks gridOffsetsMs=${offsets.map { r2(it) }} barTicks=${style.ppq * 4}")
        when (System.getenv("SGAP_EXPECT")) {
            "current" -> assertTrue("current engine: boundary is early and off the bar line $offsets", offsets.all { it < -5.0 && it > -26.0 })
            "fixed" -> assertTrue("intended: boundary on the bar line $offsets", offsets.all { Math.abs(it) < 0.5 })
        }
    }

    // ---------------------------------------------------------------- MIDI OUT setup burst per transition (MODEL write cost; the keyboard is NOT simulated)
    private fun kindOf(w: RtRig.Wr): String = when (w.status and 0xF0) {
        0xC0 -> "PC"; 0x90 -> "ON"; 0x80 -> "OFF"
        0xB0 -> when (w.d1) { 0 -> "BankMSB"; 32 -> "BankLSB"; 7 -> "CC7"; 10 -> "CC10"; 11 -> "CC11"; 91 -> "CC91"; 93 -> "CC93"; else -> "CC${w.d1}" }
        else -> "other"
    }
    private fun setupKind(k: String) = k != "ON" && k != "OFF" && k != "other"
    private fun burstAnalysis(name: String, r: RtRig, trans: List<Trans>, ev: List<RtRig.Ev>) {
        val all = r.usbAll.toList().sortedBy { it.t }
        val clock = ArrangerBrainClock(r.brain)
        val bounds = trans.mapNotNull { t -> clock.tickToNanos(t.tick)?.let { t to it } }
        // A burst = consecutive Bank/Program/mixer messages separated by < 20 ms (model write costs are <= 0.3 ms each).
        val setup = all.filter { setupKind(kindOf(it)) }
        val bursts = ArrayList<List<RtRig.Wr>>(); var cur = ArrayList<RtRig.Wr>()
        for (w in setup) { if (cur.isNotEmpty() && w.t - cur.last().t > 20_000_000L) { bursts.add(cur); cur = ArrayList() }; cur.add(w) }
        if (cur.isNotEmpty()) bursts.add(cur)
        val styleOns = ev.filter { it.kind == 'O' && it.thread != "right" }
        var idx = 0
        for (bu in bursts) {
            val t0 = bu.first().t; val t1 = bu.last().t
            val near = bounds.minByOrNull { Math.abs(it.second - t0) }?.takeIf { Math.abs(it.second - t0) < 60_000_000L }
            val label = if (near == null) "initialPlayOrOther" else "toward ${near.first.section}@tick${near.first.tick} burstStartMinusBoundaryMs=${r2((t0 - near.second) / 1e6)}"
            fun redundant(w: RtRig.Wr): Boolean {
                val isPc = (w.status and 0xF0) == 0xC0
                val before = all.lastOrNull { it.t < w.t && it.status == w.status && (isPc || it.d1 == w.d1) } ?: return false
                return if (isPc) before.d1 == w.d1 else before.d2 == w.d2
            }
            val sounding = HashMap<Int, Int>(); val soundingAtSetup = sortedSetOf<Int>()
            for (w in all) {
                if (w.t > t1) break
                val ch = w.status and 0x0F
                when (w.status and 0xF0) { 0x90 -> if (w.d2 > 0) sounding.merge(ch, 1, Int::plus) else sounding.merge(ch, -1, Int::plus); 0x80 -> sounding.merge(ch, -1, Int::plus) }
                if (w.t in t0..t1 && (kindOf(w) == "PC" || kindOf(w) == "BankMSB") && (sounding[ch] ?: 0) > 0) soundingAtSetup.add(ch)
            }
            // Notes the keyboard is still sounding on MIDI OUT when the burst starts: when were they started, and when is their NOTE_OFF sent?
            val hang = ArrayList<String>(); val live = HashMap<Int, ArrayList<RtRig.Wr>>()
            for (w in all) { if (w.t >= t0) break; val ch = w.status and 0x0F
                when (w.status and 0xF0) { 0x90 -> if (w.d2 > 0) live.getOrPut(ch * 128 + w.d1) { ArrayList() }.add(w) else live[ch * 128 + w.d1]?.removeLastOrNull(); 0x80 -> live[ch * 128 + w.d1]?.removeLastOrNull() } }
            val drumsOpen = live.entries.filter { it.key / 128 in 0..9 && it.key / 128 >= 8 }.sumOf { it.value.size }
            for ((k, v) in live.entries.sortedBy { it.key }) for (on in v.filter { k / 128 !in 8..9 }) {
                val off = all.firstOrNull { it.t > t0 && (it.status and 0x0F) == k / 128 && it.d1 == k % 128 && (((it.status and 0xF0) == 0x80) || ((it.status and 0xF0) == 0x90 && it.d2 == 0)) }
                hang.add("ch${k / 128}:n${k % 128}(onAge=${r2((t0 - on.t) / 1e6)}ms,offAt=${if (off == null) "NONE" else r2((off.t - t0) / 1e6).toString() + "ms"})")
            }
            if (hang.isNotEmpty() || drumsOpen > 0) out("hang scenario=$name burst#${idx} $label drumOrPercNoteOnsWithoutNoteOff(ch8,9)=$drumsOpen melodicSoundingAtBurstStart=${hang.size} ${hang.take(24).joinToString(" ")}")
            for (chx in hang.mapNotNull { Regex("^ch(\\d+):").find(it)?.groupValues?.get(1)?.toInt() }.filter { it in 10..15 }.toSortedSet()) {
                val tl = all.filter { (it.status and 0x0F) == chx && it.t >= t0 - 3_000_000L && it.t <= t0 + 60_000_000L && (kindOf(it) in setOf("ON", "OFF", "PC", "BankMSB")) }.map { "${kindOf(it)}${if (kindOf(it) == "PC") it.d1 else if (kindOf(it) == "ON" || kindOf(it) == "OFF") it.d1 else it.d2}@${r2((it.t - t0) / 1e6)}" }
                val nat = ev.filter { it.ch == chx && it.t >= t0 - 3_000_000L && it.t <= t0 + 60_000_000L }.map { "native${it.kind}${it.note}@${r2((it.t - t0) / 1e6)}[${it.thread.takeLast(8)}]" }
                out("hangTimeline scenario=$name burst#${idx} ch$chx relativeToBurstStartMs: ${(tl + nat).joinToString(" ")}")
            }
            val firstOn = styleOns.filter { it.t >= t0 }.minOfOrNull { it.t }
            val onsInside = styleOns.count { it.t in t0..t1 }
            val after = bu.count { firstOn != null && it.t >= firstOn }
            val byKind = bu.groupBy { kindOf(it) }.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.size}" }
            val byCh = bu.groupBy { it.status and 0x0F }.entries.sortedBy { it.key }.joinToString(",") { "ch${it.key}=${it.value.size}" }
            out("burst scenario=$name #${idx++} $label msgs=${bu.size} [$byKind] perChannel[$byCh] redundantVsLastSent=${bu.count { redundant(it) }}/${bu.size} spanMs=${r2((t1 - t0) / 1e6)} " +
                "burstStartToFirstStyleNoteOnMs=${if (firstOn == null) "NA" else r2((firstOn - t0) / 1e6).toString()} styleNoteOnsInsideBurst=$onsInside msgsAfterFirstStyleNoteOn=$after " +
                "channelsWithMidiOutNoteSoundingWhenBankPcArrived=$soundingAtSetup writers=${bu.map { it.thread }.toSortedSet()}")
        }
    }

    // ---------------------------------------------------------------- static comparison: voice setup of every section (authored)
    @Test fun voiceSetupInventoryPerSection() {
        only("voiceSetupInventoryPerSection")
        val s = rosita(); assumeTrue("style TSV not set", s != null); s!!
        data class Setup(val voice: String, val msb: Int, val lsb: Int, val program: Int, val mixer: String)
        val perSection = linkedMapOf<String, Map<Int, Setup>>()
        for ((n, sec) in s.sections) {
            val m = sortedMapOf<Int, Setup>(); var on = 0; var off = 0; val noteChannels = sortedSetOf<Int>()
            for (p in sec.parts) {
                val pol = p.casmPolicies.ifEmpty { listOfNotNull(p.casm) }.firstOrNull() ?: continue
                for (e in p.events) { if (e.isNoteOn) { on++; noteChannels.add(pol.destinationChannel) } else if ((e.status and 0xF0) == 0x80 || ((e.status and 0xF0) == 0x90 && e.velocity == 0)) off++ }
                val mix = p.events.filter { it.tick == 0 && it.isControlChange && it.channel == pol.sourceChannel && it.note in listOf(7, 10, 11, 91, 93) }.sortedBy { it.note }.joinToString("/") { "${it.note}=${it.velocity}" }
                m.putIfAbsent(pol.destinationChannel, Setup(pol.voiceName, p.bankMsb, p.bankLsb, p.program, mix))
            }
            perSection[n] = m
            out("setup section=$n lengthTicks=${sec.lengthTicks} parts=${sec.parts.size} destChannelsConfigured=${m.size} destChannelsWithNotes=${noteChannels.size}$noteChannels noteOn=$on noteOff=$off " +
                m.entries.joinToString(" ") { "dst${it.key}:${it.value.voice.trim()}@${it.value.msb}:${it.value.lsb}:${it.value.program}{${it.value.mixer}}" })
        }
        val mains = perSection.keys.filter { it.startsWith("Main") }; val fills = perSection.keys.filter { it.startsWith("Fill") }
        for (a in mains) for (b in fills) {
            val x = perSection[a]!!; val y = perSection[b]!!
            val common = x.keys.intersect(y.keys); val same = common.count { x[it] == y[it] }
            out("setupPair $a<->$b destInA=${x.size} destInB=${y.size} common=${common.size} identicalVoiceAndBankAndMixer=$same differing=${common.filter { x[it] != y[it] }}")
        }
    }

    @Test fun midiOutSetupBurstMainFillMain() {
        only("midiOutSetupBurstMainFillMain")
        for ((label, usb) in listOf("usb0us" to 0L, "usb150us" to 150L, "usb300us" to 300L))
            scenario("MainA_FillAA_MainA_midiOut_$label", Cfg(midiOut = true, usbUs = usb, prodMixer = true)) { c -> c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillAA); c.waitTransitions(2); c.bars(1.2) }
        scenario("MainD_FillDD_MainD_midiOut_usb300us_LEFT_RIGHT_manyChannels", Cfg(midiOut = true, usbUs = 300, left = true, right = true, prodMixer = true)) { c ->
            c.waitPhase(3840); c.r.brain.selectMainVariation(ArrangerSection.MainD); c.waitTransitions(2); c.waitPhase(3840); c.r.brain.selectSection(ArrangerSection.FillDD); c.waitTransitions(5, 40_000); c.bars(1.2) }
    }
}

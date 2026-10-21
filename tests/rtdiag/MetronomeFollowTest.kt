package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.FollowPlanState
import com.yourapp.yamahaarranger.audio.MetronomeController
import com.yourapp.yamahaarranger.audio.MetronomeFollowPlanner
import com.yourapp.yamahaarranger.audio.MetronomeMeter
import com.yourapp.yamahaarranger.audio.MetronomeMeters
import com.yourapp.yamahaarranger.audio.MetronomeMode
import com.yourapp.yamahaarranger.audio.MetronomeSink
import com.yourapp.yamahaarranger.audio.StyleClockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RecSink : MetronomeSink {
    class Push(val target: Long, val level: Int, val gen: Int)
    val pushes = CopyOnWriteArrayList<Push>(); val configs = CopyOnWriteArrayList<String>()
    @Volatile var gen = 0
    override fun configure(enabled: Boolean, mode: Int, running: Boolean, bpmX100: Int, numerator: Int, denominator: Int, volumePermille: Int) {
        configs += "enabled=$enabled mode=$mode running=$running bpmX100=$bpmX100 meter=$numerator/$denominator vol=$volumePermille"
    }
    override fun push(targetNs: Long, level: Int, generation: Int): Boolean { pushes += Push(targetNs, level, generation); return true }
    override fun generation() = gen
    override fun bumpGeneration(): Int { gen++; return gen }
    override fun beatPacked() = 0L
}

/** Deterministic clock: ticks map linearly to nanoseconds with a settable tempo segment list. */
class FakeClock(var meter: MetronomeMeter = MetronomeMeters.DEFAULT, val ppq: Int = 480) : StyleClockSource {
    var runningFlag = true; var origin = 0L; var revision = 0L; var nowNs = 0L
    /** segments: (startTick, startNs, bpm) */
    val segs = arrayListOf(Triple(0L, 1_000_000_000L, 120))
    override fun running() = runningFlag
    override fun meter() = meter
    override fun ppq() = ppq
    override fun loopOriginTick() = origin
    override fun tempoRevision() = revision
    override fun tickToNanos(tick: Long): Long? {
        val s = segs.last { it.first <= tick || it === segs.first() }
        return s.second + ((tick - s.first) * 60_000_000_000.0 / (s.third * ppq.toDouble())).toLong()
    }
    override fun masterTickNow(): Long {
        val s = segs.last { it.second <= nowNs || it === segs.first() }
        return s.first + ((nowNs - s.second) * s.third * ppq / 60_000_000_000.0).toLong()
    }
    fun setTempo(bpm: Int) { val t = masterTickNow(); segs += Triple(t, nowNs, bpm); revision++ }
}

class MetronomeFollowTest {
    private fun report(name: String, vararg kv: Pair<String, Any?>) = println("RTDIAG scenario=$name " + kv.joinToString(" ") { "${it.first}=${it.second}" })

    @Test fun plannerQueuesOnlyTheNextBeatsOnTheStyleGridWithAccents() {
        val c = FakeClock(); val st = FollowPlanState(); c.nowNs = 1_000_000_000L
        val all = ArrayList<com.yourapp.yamahaarranger.audio.PlannedClick>()
        for (step in 0 until 400) { c.nowNs = 1_000_000_000L + step * 10_000_000L; all += MetronomeFollowPlanner.plan(c, st, c.nowNs, 40_000_000L).clicks }
        // 4 s at 120 BPM = 8 quarter beats (ticks 0,480,...); nominal times 0.5 s apart; no duplicates, strictly increasing
        assertEquals(9, all.size)
        all.forEachIndexed { i, k -> assertEquals(1_000_000_000L + i * 500_000_000L, k.targetNs); assertEquals(if (i % 4 == 0) 2 else 0, k.level) }
        report("metronome_follow_grid", "clicks" to all.size, "exactGrid" to true)
    }

    @Test fun sixEightIsSixEighthPulsesInTwoGroups() {
        val c = FakeClock(MetronomeMeter(6, 8)); val st = FollowPlanState(); c.nowNs = 1_000_000_000L
        val all = ArrayList<com.yourapp.yamahaarranger.audio.PlannedClick>()
        for (step in 0 until 400) { c.nowNs = 1_000_000_000L + step * 10_000_000L; all += MetronomeFollowPlanner.plan(c, st, c.nowNs, 40_000_000L).clicks }
        assertEquals(listOf(2, 0, 0, 1, 0, 0, 2, 0), all.take(8).map { it.level })
        assertEquals(250_000_000L, all[1].targetNs - all[0].targetNs)       // eighth = half a quarter at 120 BPM
    }

    @Test fun tempoChangeReplansWithOneFlushAndNeverDoubleBooksABeat() {
        val c = FakeClock(); val st = FollowPlanState(); val sink = RecSink()
        val ctl = MetronomeController(sink, { c }, { c.nowNs })
        ctl.setMode(MetronomeMode.FOLLOW)                                        // not enabled: no thread, we drive tickOnce() by hand
        c.nowNs = 1_000_000_000L
        for (step in 0 until 100) { c.nowNs = 1_000_000_000L + step * 10_000_000L; ctl.tickOnce(); if (step == 40) c.setTempo(180) }
        val gens = sink.pushes.map { it.gen }.distinct()
        assertTrue("one generation bump for one tempo change", gens.size == 2)
        // after the change the spacing is 60/180 s = 333.3 ms
        val after = sink.pushes.filter { it.gen == gens.last() }.map { it.target }
        assertTrue(after.zipWithNext { a, b -> b - a }.all { Math.abs(it - 333_333_333L) < 2_000_000L })
        report("metronome_follow_tempo_change", "pushes" to sink.pushes.size, "generations" to gens.size, "spacingMsAfter" to (after.zipWithNext { a, b -> b - a }.average() / 1e6))
    }

    @Test fun styleStopFlushesAndStopsPlanning() {
        val c = FakeClock(); val sink = RecSink(); val ctl = MetronomeController(sink, { c }, { c.nowNs })
        c.nowNs = 1_000_000_000L; ctl.tickOnce(); val n = sink.pushes.size
        c.runningFlag = false; c.nowNs += 50_000_000; ctl.tickOnce(); c.nowNs += 600_000_000; ctl.tickOnce()
        assertEquals(n, sink.pushes.size); assertEquals(1, sink.gen)
    }

    @Test fun controllerNeverReachesMidiAndForwardsTempoMeterVolume() {
        val sink = RecSink(); val ctl = MetronomeController(sink, { null }, { 0L })
        ctl.setTempo(96); ctl.setMeter(MetronomeMeter(6, 8)); ctl.setVolumePermille(400); ctl.setEnabled(true)
        assertEquals("enabled=true mode=0 running=true bpmX100=9600 meter=6/8 vol=400", sink.configs.last())
        ctl.setTempo(140); assertEquals("enabled=true mode=0 running=true bpmX100=14000 meter=6/8 vol=400", sink.configs.last())
        ctl.setStandaloneRunning(false); assertTrue(sink.configs.last().contains("running=false"))
        ctl.release()
    }

    // ---- objective synchronisation against the REAL StyleSequencer on the host rig ----
    private fun runFollowAgainstStyle(tempoChangeTo: Int?, transition: Boolean, label: String) {
        val r = RtRig(false, 120)
        val sink = RecSink()
        val ctl = MetronomeController(sink, { com.yourapp.yamahaarranger.audio.ArrangerBrainClock(r.brain) })
        ctl.setMode(MetronomeMode.FOLLOW); ctl.setEnabled(true)
        r.brain.startStop(); Thread.sleep(150)
        if (tempoChangeTo != null) { Thread.sleep(1500); r.brain.setTempo(tempoChangeTo) }
        if (transition) { Thread.sleep(900); r.brain.selectMainVariation(ArrangerSection.MainB); Thread.sleep(1600); r.brain.selectMainVariation(ArrangerSection.MainA) }
        Thread.sleep(3500)
        r.brain.startStop(); Thread.sleep(300)
        val pushedBeforeStop = sink.pushes.size
        Thread.sleep(700)
        val pushedAfterStop = sink.pushes.size
        ctl.release()
        // kick drum (ch 8, note 36) sits on beats 1 and 3 of every bar of the synthetic style: compare its dispatch time with the nearest click
        val kicks = r.engineEvents.filter { it.kind == 'O' && it.ch == 8 && it.note == 36 }.map { it.t }.sorted()
        val clicks = sink.pushes.filter { it.gen == sink.pushes.last().gen || true }.map { it.target }.distinct().sorted()
        val offsets = kicks.map { k -> val nearest = clicks.minByOrNull { Math.abs(it - k) } ?: 0L; k - nearest }
        val intervals = clicks.zipWithNext { a, b -> b - a }
        val strongs = sink.pushes.count { it.level == 2 }
        report(label, "kicks" to kicks.size, "clicks" to clicks.size, "strongClicks" to strongs,
            "kickMinusClickP50ms" to pct(offsets, 0.5) / 1e6, "kickMinusClickP95ms" to pct(offsets, 0.95) / 1e6, "kickMinusClickMaxms" to (offsets.maxOrNull() ?: 0) / 1e6,
            "kickMinusClickMinms" to (offsets.minOrNull() ?: 0) / 1e6, "intervalMinMs" to (intervals.minOrNull() ?: 0) / 1e6, "intervalMaxMs" to (intervals.maxOrNull() ?: 0) / 1e6,
            "pushedAfterStopWindow" to (pushedAfterStop - pushedBeforeStop), "generationBumps" to sink.gen,
            "usbWritesNotCC123" to r.usbWrites.count { (it.status and 0xF0) != 0xB0 || it.d1 != 123 }, "cc123WritesWhileMidiOutOff" to r.usbWrites.count { (it.status and 0xF0) == 0xB0 && it.d1 == 123 }, "stuck" to r.oracle.stuckOnChannels(0..15))
        assertEquals("no click is planned after Style STOP", pushedBeforeStop, pushedAfterStop)
        assertTrue("clicks were planned", clicks.size >= 6); assertTrue(kicks.size >= 4)
        assertEquals("metronome produces no note/controller MIDI", 0, r.usbWrites.count { (it.status and 0xF0) != 0xB0 || it.d1 != 123 })   // only the pre-existing STOP/START CC123 exists
        r.close()
    }

    @Test fun followStaysOnTheStyleGridAtSteadyTempo() = runFollowAgainstStyle(null, false, "metronome_follow_vs_style_steady_120")
    @Test fun followSurvivesTempoChange() = runFollowAgainstStyle(168, false, "metronome_follow_vs_style_tempo_120_to_168")
    @Test fun followSurvivesMainFillMain() = runFollowAgainstStyle(null, true, "metronome_follow_vs_style_main_fill_main")

    @Test fun metronomeDoesNotChangeTheStyleEventStream() {
        fun trace(withMetronome: Boolean): List<String> {
            val r = RtRig(false, 150); val sink = RecSink(); val ctl = MetronomeController(sink, { com.yourapp.yamahaarranger.audio.ArrangerBrainClock(r.brain) })
            if (withMetronome) { ctl.setMode(MetronomeMode.FOLLOW); ctl.setEnabled(true) }
            r.brain.startStop(); Thread.sleep(4200); r.brain.startStop(); Thread.sleep(200); ctl.release()
            val seq = r.engineEvents.filter { it.kind != 'A' }.sortedBy { it.t }.map { "${it.kind}${it.ch}:${it.note}" }
            r.close(); return seq
        }
        val a = trace(false); val b = trace(true)
        val n = minOf(a.size, b.size, 150)
        report("metronome_does_not_change_style_stream", "eventsOff" to a.size, "eventsOn" to b.size, "comparedPrefix" to n, "identicalPrefix" to (a.take(n) == b.take(n)))
        assertEquals(a.take(n), b.take(n)); assertTrue(n >= 100)
    }
}

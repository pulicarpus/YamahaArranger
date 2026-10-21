package com.yourapp.yamahaarranger.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.locks.LockSupport

/**
 * Owns the metronome settings and, in FOLLOW mode, the planner thread. The planner only reads the Style clock and queues
 * future click times; it never sends MIDI, never touches BASSMIDI, the Style, ACMP, note ownership or MIDI OUT.
 * The thread is a plain background thread (not the UI thread, not an Android Timer/Handler).
 */
class MetronomeController(
    private val sink: MetronomeSink,
    private val clock: () -> StyleClockSource?,
    private val nanoTime: () -> Long = System::nanoTime,
    private val threadSetup: () -> Unit = {}
) {
    private val _state = MutableStateFlow(MetronomeUiState())
    val state: StateFlow<MetronomeUiState> = _state.asStateFlow()
    private val plan = FollowPlanState()
    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var generation = 0
    val lookaheadNs = 40_000_000L          // > the measured wake jitter of a normal thread; short enough that a tempo change moves at most one click
    val periodNs = 10_000_000L
    /** While the Style is stopped the planner only waits for START; polling faster keeps the first downbeat inside its grace window. */
    val idlePeriodNs = 4_000_000L

    fun setEnabled(enabled: Boolean) { _state.update { it.copy(enabled = enabled, standaloneRunning = if (enabled) true else it.standaloneRunning) }; apply() }
    fun setMode(mode: MetronomeMode) { _state.update { it.copy(mode = mode) }; apply() }
    fun setStandaloneRunning(running: Boolean) { _state.update { it.copy(standaloneRunning = running) }; apply() }
    fun setMeter(meter: MetronomeMeter) { _state.update { it.copy(meter = meter) }; apply() }
    fun setVolumePermille(v: Int) { _state.update { it.copy(volumePermille = v.coerceIn(0, 1000)) }; apply() }
    /** The YA main tempo (quarter notes per minute). Standalone follows it live; Follow reads the Style clock instead. */
    fun setTempo(bpm: Int) { if (_state.value.tempoBpm != bpm) { _state.update { it.copy(tempoBpm = bpm) }; apply() } }
    /** UI refresh of read-only facts (Style meter / running). Does not touch the engine. */
    fun refreshStyleFacts() {
        val c = clock(); val m = c?.meter(); val r = c?.running() ?: false
        _state.update { if (it.styleMeter == m && it.styleRunning == r) it else it.copy(styleMeter = m, styleRunning = r) }
    }

    private fun apply() {
        val s = _state.value
        val meter = if (s.mode == MetronomeMode.FOLLOW) (clock()?.meter() ?: s.meter) else s.meter
        sink.configure(s.enabled, s.mode.code, s.mode == MetronomeMode.STANDALONE && s.standaloneRunning, s.tempoBpm * 100, meter.numerator, meter.denominator, s.volumePermille)
        generation = sink.generation()
        if (s.enabled && s.mode == MetronomeMode.FOLLOW) startPlanner() else stopPlanner()
    }

    /** One planner pass; public so tests can drive it with a fake clock. Returns the number of clicks queued. */
    fun tickOnce(): Int {
        val src = clock() ?: return 0
        val now = nanoTime()
        val p = MetronomeFollowPlanner.plan(src, plan, now, lookaheadNs)
        if (p.flush) generation = sink.bumpGeneration()
        var pushed = 0
        for (c in p.clicks) if (sink.push(c.targetNs, c.level, generation)) pushed++
        return pushed
    }

    @Synchronized private fun startPlanner() {
        if (thread?.isAlive == true) return
        stopRequested = false
        val t = Thread({
            try { threadSetup() } catch (_: Throwable) {}
            while (!stopRequested) { tickOnce(); LockSupport.parkNanos(if (plan.initialized) periodNs else idlePeriodNs) }
        }, "metronome-follow")
        t.isDaemon = true; thread = t; t.start()
    }
    @Synchronized private fun stopPlanner() {
        stopRequested = true; thread = null
        plan.initialized = false; plan.lastTick = Long.MIN_VALUE
    }
    fun release() { _state.update { it.copy(enabled = false) }; apply() }
    fun plannerStats(): String = "METRONOME_PLANNER planned=${plan.planned} flushes=${plan.flushes} lookaheadMs=${lookaheadNs / 1_000_000} periodMs=${periodNs / 1_000_000}"
}

class AudioEngineMetronomeSink(private val engine: AudioEngineManager) : MetronomeSink {
    override fun configure(enabled: Boolean, mode: Int, running: Boolean, bpmX100: Int, numerator: Int, denominator: Int, volumePermille: Int) =
        engine.metronomeConfigure(enabled, mode, running, bpmX100, numerator, denominator, volumePermille)
    override fun push(targetNs: Long, level: Int, generation: Int) = engine.metronomePush(targetNs, level, generation)
    override fun generation() = engine.metronomeGeneration()
    override fun bumpGeneration() = engine.metronomeBumpGeneration()
    override fun beatPacked() = engine.metronomeBeat()
}

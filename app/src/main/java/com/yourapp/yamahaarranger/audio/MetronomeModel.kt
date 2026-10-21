package com.yourapp.yamahaarranger.audio

/**
 * Metronome: pure model + Follow-Style beat planner (no Android, no JNI, host-testable).
 *
 * STANDALONE is clocked inside the audio callback by counting output frames (native metronome_engine.h).
 * FOLLOW does NOT run a musical timer of its own: it reads the Style's tempo clock (the same TempoClock that schedules Style
 * notes, CLOCK_MONOTONIC) and hands the native engine absolute target times a few tens of milliseconds ahead. A late wake of the
 * planner thread therefore cannot move a click; it can only make the planner miss its lookahead (counted by the native engine).
 */
enum class MetronomeMode(val code: Int) { STANDALONE(0), FOLLOW(1) }

data class MetronomeMeter(val numerator: Int, val denominator: Int) {
    val label: String get() = "$numerator/$denominator"
    /** One pulse = one 1/denominator note; 6/8 therefore has 6 pulses per bar in two groups of three. */
    val pulsesPerBar: Int get() = numerator
}

object MetronomeMeters {
    val OPTIONS = listOf(MetronomeMeter(2, 4), MetronomeMeter(3, 4), MetronomeMeter(4, 4), MetronomeMeter(6, 8))
    val DEFAULT = MetronomeMeter(4, 4)
    const val STRONG = 2; const val MEDIUM = 1; const val WEAK = 0
    /** Same rule as metronome::levelFor in metronome_engine.h: Strong on 1; in x/8 meters with a multiple of 3 pulses, Medium on each further group of three. */
    fun levelFor(index: Int, numerator: Int, denominator: Int): Int {
        val n = numerator.coerceAtLeast(1)
        val i = ((index % n) + n) % n
        return when { i == 0 -> STRONG; denominator == 8 && n % 3 == 0 && i % 3 == 0 -> MEDIUM; else -> WEAK }
    }
}

data class MetronomeUiState(
    val enabled: Boolean = false,
    val mode: MetronomeMode = MetronomeMode.STANDALONE,
    val standaloneRunning: Boolean = true,
    val meter: MetronomeMeter = MetronomeMeters.DEFAULT,
    val volumePermille: Int = 600,
    val tempoBpm: Int = 120,
    /** Meter actually used in FOLLOW (the Style's own); null when no Style is loaded. */
    val styleMeter: MetronomeMeter? = null,
    val styleRunning: Boolean = false
)

/** Read-only view of the Style's musical clock. Implemented by ArrangerBrain over StyleSequencer; never changes sequencer state. */
interface StyleClockSource {
    /** True while the Style playback job is running (STOP, ENDING end or no Style: false). */
    fun running(): Boolean
    fun meter(): MetronomeMeter
    fun ppq(): Int
    /** Absolute master tick at which the current section loop started: the bar line origin for accents. */
    fun loopOriginTick(): Long
    /** CLOCK_MONOTONIC nanoseconds at which `tick` is due; honours every tempo change so far. Null when the clock is not running. */
    fun tickToNanos(tick: Long): Long?
    fun masterTickNow(): Long
    /** Changes whenever a tempo change inserted a new clock segment. */
    fun tempoRevision(): Long
}

class PlannedClick(val targetNs: Long, val level: Int, val tick: Long)

/** Planner state; owned by the single planner thread. */
class FollowPlanState {
    var initialized = false
    var lastTick = Long.MIN_VALUE
    var revision = Long.MIN_VALUE
    var meterKey = 0
    var flushes = 0L
    var planned = 0L
}

class FollowPlan(val clicks: List<PlannedClick>, /** The native queue must be cancelled before pushing `clicks` (tempo/meter change or Style stopped). */ val flush: Boolean)

object MetronomeFollowPlanner {
    /** Clicks due up to this long before `now` are still re-issued after a re-plan (native drops duplicates). */
    const val GRACE_NS = 8_000_000L
    /** The first beat after START (tick 0, the downbeat) is still clicked if the planner noticed the start up to this late. */
    const val START_GRACE_NS = 40_000_000L
    private const val MAX_PER_PASS = 24

    fun plan(src: StyleClockSource, st: FollowPlanState, nowNs: Long, lookaheadNs: Long): FollowPlan {
        if (!src.running()) {
            val flush = st.initialized
            st.initialized = false; st.lastTick = Long.MIN_VALUE; st.revision = Long.MIN_VALUE
            return FollowPlan(emptyList(), flush)
        }
        val meter = src.meter(); val ppq = src.ppq().coerceAtLeast(1)
        val beatTicks = (ppq.toLong() * 4L / meter.denominator.coerceAtLeast(1)).coerceAtLeast(1L)
        val origin = src.loopOriginTick()
        val key = meter.numerator * 100 + meter.denominator
        val rev = src.tempoRevision()
        var flush = false
        val hadPlan = st.initialized
        if (!st.initialized || st.revision != rev || st.meterKey != key) {
            flush = st.initialized          // a running plan was made with the old tempo/meter: cancel it
            st.lastTick = Long.MIN_VALUE
            st.revision = rev; st.meterKey = key; st.initialized = true
        }
        // First beat to plan: the first grid beat that is not older than GRACE before now; afterwards simply the next one.
        var tick: Long
        if (st.lastTick == Long.MIN_VALUE) {
            val t = src.masterTickNow()
            var first = if (t <= origin) origin else origin + ((t - origin + beatTicks - 1) / beatTicks) * beatTicks
            // step back while the previous beat is still within the grace window (it may not have fired before a flush)
            val grace = if (!hadPlan) START_GRACE_NS else GRACE_NS
            while (first - beatTicks >= origin) { val d = src.tickToNanos(first - beatTicks) ?: break; if (d >= nowNs - grace) first -= beatTicks else break }
            tick = first
        } else tick = st.lastTick + beatTicks
        val out = ArrayList<PlannedClick>(4)
        var guard = 0
        while (guard++ < MAX_PER_PASS) {
            val due = src.tickToNanos(tick) ?: break
            if (due > nowNs + lookaheadNs) break
            if (due >= nowNs - (if (hadPlan) GRACE_NS else START_GRACE_NS)) {
                val index = Math.floorDiv(tick - origin, beatTicks).toInt()
                out += PlannedClick(due, MetronomeMeters.levelFor(index, meter.numerator, meter.denominator), tick)
            }
            st.lastTick = tick; tick += beatTicks
        }
        st.planned += out.size
        if (flush) st.flushes++
        return FollowPlan(out, flush)
    }
}

/** Where the controller sends its decisions: the native engine in the app, a recorder in tests. */
interface MetronomeSink {
    fun configure(enabled: Boolean, mode: Int, running: Boolean, bpmX100: Int, numerator: Int, denominator: Int, volumePermille: Int)
    fun push(targetNs: Long, level: Int, generation: Int): Boolean
    fun generation(): Int
    fun bumpGeneration(): Int
    fun beatPacked(): Long
}

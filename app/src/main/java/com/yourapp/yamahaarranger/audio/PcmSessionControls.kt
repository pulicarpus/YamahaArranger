package com.yourapp.yamahaarranger.audio

/** Observational state only. Render never acquires this lock. Order: controls then native snapshot. */
object PcmSessionControls {
    data class Snapshot(val muteMask: Int, val knownMask: Int, val changes: Long, val lastChangeNs: Long)
    private var mask = 0
    private var changes = 0L
    private var lastChange = System.nanoTime()
    @Synchronized fun observeMute(channel: Int, muted: Boolean) {
        if (channel !in 0..15) return
        val next = if (muted) mask or (1 shl channel) else mask and (1 shl channel).inv()
        if (next != mask) { mask = next; changes++; lastChange = System.nanoTime() }
    }
    @Synchronized fun <T> snapshot(action: (Snapshot) -> T): T = action(Snapshot(mask,65535,changes,lastChange))
}

/**
 * LATENCY_PROBE (Kotlin side): observation only, never read by playback.
 *
 * Every stat is a fixed set of atomics, so recording allocates nothing, takes no lock and
 * never logs. Reports are formatted by the caller outside any realtime path.
 * Clock: System.nanoTime() = CLOCK_MONOTONIC, the same clock as native steady_clock, and the
 * clock Android MIDI uses for the `timestamp` argument of MidiReceiver.onSend.
 *
 * Dispatch time is NOT audible time. These stats end at the moment an event was handed to
 * BASSMIDI (or at a scheduling milestone); the native probe adds render, and Oboe's estimate
 * adds the output pipeline.
 */
object LatencyProbe {
    @Volatile var enabled = true

    // ---- Tuning bits (one APK, A/B on the device): bits 0-1 are native, 2-3 are Kotlin. ----
    const val TUNE_DRUM_REBIND_SKIP = 1
    const val TUNE_FONTS_NOOP_SKIP = 2
    const val TUNE_CHORD_OFF_MAIN = 4
    const val TUNE_MIDI_THREAD_PRIORITY = 8
    const val TUNE_ALL = 15
    @Volatile var tuning = TUNE_ALL
    fun tuned(flag: Int): Boolean = (tuning and flag) != 0

    /** Thread CPU time in ns; 0 means unavailable. The app installs android.os.Debug.threadCpuTimeNanos. */
    @Volatile var cpuClock: () -> Long = { 0L }
    /** Raises (true) or restores (false) the calling thread's priority; installed by the app. */
    @Volatile var threadPriority: ((Boolean) -> Unit)? = null

    class Stat(val name: String) {
        private val count = java.util.concurrent.atomic.AtomicLong()
        private val sumUs = java.util.concurrent.atomic.AtomicLong()
        private val maxUs = java.util.concurrent.atomic.AtomicLong()
        private val buckets = java.util.concurrent.atomic.AtomicLongArray(BOUNDS.size + 1)
        fun record(nanos: Long) {
            if (nanos < 0) return
            val us = nanos / 1000
            count.incrementAndGet(); sumUs.addAndGet(us)
            var old = maxUs.get()
            while (us > old && !maxUs.compareAndSet(old, us)) old = maxUs.get()
            var b = 0
            while (b < BOUNDS.size && us >= BOUNDS[b]) b++
            buckets.incrementAndGet(b)
        }
        fun reset() { count.set(0); sumUs.set(0); maxUs.set(0); for (i in 0 until buckets.length()) buckets.set(i, 0) }
        fun count(): Long = count.get()
        fun maxUs(): Long = maxUs.get()
        /** Smallest bucket upper bound (µs) covering `fraction` of the samples; 0 when empty. */
        fun percentileUpperUs(fraction: Double): Long {
            val total = count.get()
            if (total == 0L) return 0
            val need = Math.ceil(total * fraction).toLong().coerceAtLeast(1)
            var seen = 0L
            for (b in 0..BOUNDS.size) {
                seen += buckets.get(b)
                if (seen >= need) return if (b < BOUNDS.size) BOUNDS[b] else maxUs.get()
            }
            return maxUs.get()
        }
        fun line(): String {
            val n = count.get()
            if (n == 0L) return "LATENCY_KOTLIN stat=$name count=0"
            return "LATENCY_KOTLIN stat=$name count=$n meanUs=${sumUs.get() / n} p50UpperUs=${percentileUpperUs(0.50)} " +
                "p95UpperUs=${percentileUpperUs(0.95)} p99UpperUs=${percentileUpperUs(0.99)} maxUs=${maxUs.get()} " +
                "buckets=" + (0..BOUNDS.size).joinToString(",") { buckets.get(it).toString() }
        }
    }

    val BOUNDS = longArrayOf(250, 500, 1000, 2000, 4000, 8000, 16000, 32000, 64000)

    // Keyboard (RIGHT/LEFT-voice) path, on the Android MIDI thread.
    val midiIngressLag = Stat("midiUsbTimestampToOnSend")
    val midiOnSendTotal = Stat("midiOnSendTotal")
    val keyboardLockWait = Stat("keyboardLockWait")
    val rightIngressToHandoff = Stat("rightNoteOnSendToAllLayersHandedToNative")
    val rightHandoffCpu = Stat("rightNoteOnSendToHandoff_threadCpuTime")
    val jniKeyboardNote = Stat("jniKeyboardNoteOn_includesNativeLockWait")
    // Section transitions.
    val sectionRequestToBoundary = Stat("sectionRequestToBoundaryWake")
    val sectionBoundaryToApplyStart = Stat("sectionBoundaryToVoiceApplyStart")
    val sectionApply = Stat("sectionVoiceApplyDuration")
    val sectionApplyCpu = Stat("sectionVoiceApplyDuration_threadCpuTime")
    val sectionApplyEndToFirstNote = Stat("sectionVoiceApplyEndToFirstNoteDispatch")
    val sectionBoundaryToFirstNote = Stat("sectionBoundaryToFirstNoteDispatch")
    val firstNoteLateness = Stat("sectionFirstNoteLatenessVsSchedule")
    // Scheduler, all style events.
    val schedulerWakeLateness = Stat("styleEventWakeLatenessBeforeLifecycle")
    val schedulerWakeSteady = Stat("styleEventWakeLateness_steadyState")
    val schedulerWakePostSection = Stat("styleEventWakeLateness_within500msOfSectionStart")
    val schedulerDispatchLateness = Stat("styleEventDispatchLatenessAfterLifecycle")
    // ACMP.
    val chordMidiToApplyStart = Stat("chordLastNoteToApplyChordNow")
    val chordRetarget = Stat("chordApplyDuration_handleChordChange")
    val chordRetargetCpu = Stat("chordApplyDuration_threadCpuTime")
    // Every DebugLog.add call, including time spent waiting for its lock.
    val debugLogAdd = Stat("debugLogAddCall")
    // StyleSequencer noteLifecycleLock: wait to enter and time held (nested uses overlap; hold includes the work inside).
    val lifecycleWait = Stat("styleLifecycleMonitorWait")
    val lifecycleHold = Stat("styleLifecycleMonitorHold_includesDispatchWork")
    val chordMidiToRetargetEnd = Stat("chordLastNoteToRetargetDone")

    private val all = listOf(midiIngressLag, midiOnSendTotal, keyboardLockWait, rightIngressToHandoff, rightHandoffCpu,
        jniKeyboardNote, sectionRequestToBoundary, sectionBoundaryToApplyStart, sectionApply, sectionApplyCpu,
        sectionApplyEndToFirstNote, sectionBoundaryToFirstNote, firstNoteLateness, schedulerWakeLateness,
        schedulerWakeSteady, schedulerWakePostSection, schedulerDispatchLateness, chordMidiToApplyStart,
        chordRetarget, chordRetargetCpu, chordMidiToRetargetEnd, debugLogAdd, lifecycleWait, lifecycleHold)

    // Single-writer stamps: the MIDI thread, the Main thread or the sequencer thread each own theirs.
    @Volatile private var onSendEnterNs = 0L
    @Volatile private var usbTimestampNs = 0L
    @Volatile private var noteIngressNs = 0L
    @Volatile private var chordArrivalNs = 0L
    @Volatile private var sectionRequestNs = 0L
    @Volatile private var sectionBoundaryNs = 0L
    @Volatile private var voiceApplyStartNs = 0L
    @Volatile private var voiceApplyEndNs = 0L
    @Volatile private var awaitingFirstNote = false
    @Volatile private var noteIngressCpuNs = 0L
    @Volatile private var voiceApplyStartCpuNs = 0L
    @Volatile private var priorityBoostedThread = -1L

    fun reset() {
        all.forEach { it.reset() }
        // Drop in-flight stamps too so a new measurement window never pairs with an older event.
        noteIngressNs = 0L; noteIngressCpuNs = 0L; chordArrivalNs = 0L; sectionRequestNs = 0L; sectionBoundaryNs = 0L
        voiceApplyStartNs = 0L; voiceApplyEndNs = 0L; voiceApplyStartCpuNs = 0L; awaitingFirstNote = false
    }

    // ---- MIDI thread ----
    fun onSendEnter(timestampNs: Long) {
        applyThreadPriority()
        if (!enabled) return; usbTimestampNs = timestampNs; onSendEnterNs = System.nanoTime()
    }
    /** Raise the Android MIDI dispatch thread once when the tuning bit is on; restore it when off. */
    private fun applyThreadPriority() {
        val hook = threadPriority ?: return
        val id = Thread.currentThread().id
        val want = tuned(TUNE_MIDI_THREAD_PRIORITY)
        val boosted = priorityBoostedThread == id
        if (want == boosted) return
        try { hook(want); priorityBoostedThread = if (want) id else -1L } catch (_: Exception) { priorityBoostedThread = -1L }
    }
    fun onSendExit() { if (!enabled) return; val t = onSendEnterNs; if (t != 0L) midiOnSendTotal.record(System.nanoTime() - t) }
    /** A keyboard NOTE_ON on the chord/keyboard channel was parsed. */
    fun noteOnParsed() {
        if (!enabled) return
        val enter = onSendEnterNs; noteIngressNs = enter; noteIngressCpuNs = cpuClock()
        val usb = usbTimestampNs
        // MidiReceiver timestamp 0 means "now"; a stamp from the future or > 1 s old is not a usable ingress time.
        if (usb > 0 && enter >= usb && enter - usb < 1_000_000_000L) midiIngressLag.record(enter - usb)
    }
    fun keyboardLockAcquired(requestedNs: Long) { if (enabled) keyboardLockWait.record(System.nanoTime() - requestedNs) }
    fun rightHandedOff() {
        if (!enabled) return
        val t = noteIngressNs; if (t != 0L) rightIngressToHandoff.record(System.nanoTime() - t)
        val c0 = noteIngressCpuNs; val c1 = cpuClock(); if (c0 != 0L && c1 >= c0) rightHandoffCpu.record(c1 - c0)
    }
    fun jniKeyboardNote(startNs: Long) { if (enabled) jniKeyboardNote.record(System.nanoTime() - startNs) }
    fun chordNoteParsed() { if (enabled) chordArrivalNs = noteIngressNs }

    // ---- Main thread: ACMP ----
    private var chordCpuStartNs = 0L
    fun chordApplyStart(): Long {
        if (!enabled) return 0L
        val now = System.nanoTime(); val t = chordArrivalNs
        if (t != 0L) chordMidiToApplyStart.record(now - t)
        chordCpuStartNs = cpuClock()
        return now
    }
    fun chordApplyEnd(startNs: Long) {
        if (!enabled || startNs == 0L) return
        val now = System.nanoTime(); chordRetarget.record(now - startNs)
        val c0 = chordCpuStartNs; val c1 = cpuClock(); if (c0 != 0L && c1 >= c0) chordRetargetCpu.record(c1 - c0)
        val t = chordArrivalNs; if (t != 0L) chordMidiToRetargetEnd.record(now - t)
    }

    // ---- Sections ----
    fun sectionRequested() { if (enabled) sectionRequestNs = System.nanoTime() }
    /** The sequencer woke at the bar boundary and is switching sections. */
    fun sectionBoundary() {
        if (!enabled) return
        val now = System.nanoTime(); sectionBoundaryNs = now
        val r = sectionRequestNs; if (r != 0L) sectionRequestToBoundary.record(now - r)
    }
    fun voiceApplyStart() {
        if (!enabled) return
        val now = System.nanoTime(); voiceApplyStartNs = now; voiceApplyStartCpuNs = cpuClock()
        val b = sectionBoundaryNs; if (b != 0L && now >= b) sectionBoundaryToApplyStart.record(now - b)
    }
    fun voiceApplyEnd() {
        if (!enabled) return
        val now = System.nanoTime(); voiceApplyEndNs = now
        val s = voiceApplyStartNs; if (s != 0L) sectionApply.record(now - s)
        val c0 = voiceApplyStartCpuNs; val c1 = cpuClock(); if (c0 != 0L && c1 >= c0) sectionApplyCpu.record(c1 - c0)
        awaitingFirstNote = sectionBoundaryNs != 0L
    }
    /** Timestamp for a probe, or 0 when the probe is disabled. */
    fun stamp(): Long = if (enabled) System.nanoTime() else 0L
    fun lifecycleDone(requestedNs: Long, acquiredNs: Long) {
        if (requestedNs == 0L || acquiredNs == 0L) return
        val now = System.nanoTime()
        lifecycleWait.record(acquiredNs - requestedNs); lifecycleHold.record(now - acquiredNs)
    }
    fun schedulerWake(nowNs: Long, targetNs: Long) {
        if (!enabled || nowNs < targetNs) return
        val late = nowNs - targetNs
        schedulerWakeLateness.record(late)
        // Events right after a section switch inherit the switch's own delay; keep them apart from steady playback.
        val applied = voiceApplyEndNs
        if (applied != 0L && nowNs - applied < 500_000_000L) schedulerWakePostSection.record(late) else schedulerWakeSteady.record(late)
    }
    /** A style event is being dispatched under the lifecycle monitor. */
    fun styleEventDispatched(nowNs: Long, targetNs: Long, isNoteOn: Boolean) {
        if (!enabled) return
        if (nowNs >= targetNs) schedulerDispatchLateness.record(nowNs - targetNs)
        if (isNoteOn && awaitingFirstNote) {
            awaitingFirstNote = false
            val b = sectionBoundaryNs; val e = voiceApplyEndNs
            if (e != 0L && nowNs >= e) sectionApplyEndToFirstNote.record(nowNs - e)
            if (b != 0L && nowNs >= b) sectionBoundaryToFirstNote.record(nowNs - b)
            if (nowNs >= targetNs) firstNoteLateness.record(nowNs - targetNs)
            sectionBoundaryNs = 0L; sectionRequestNs = 0L // one measurement per transition
        }
    }

    private fun flag(f: Int) = if (tuned(f)) 1 else 0

    fun report(): String = buildString {
        appendLine("LATENCY_KOTLIN clock=System.nanoTime(=CLOCK_MONOTONIC) enabled=$enabled dispatch_is_not_audible_time=1 " +
            "cpuClock=${if (cpuClock() != 0L) "thread" else "unavailable"}")
        appendLine("LATENCY_TUNING kotlin=$tuning drumRebindSkip=${flag(TUNE_DRUM_REBIND_SKIP)} fontsNoopSkip=${flag(TUNE_FONTS_NOOP_SKIP)} " +
            "chordOffMain=${flag(TUNE_CHORD_OFF_MAIN)} midiThreadPriority=${flag(TUNE_MIDI_THREAD_PRIORITY)}")
        all.forEach { appendLine(it.line()) }
    }
}

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
    val jniKeyboardNote = Stat("jniKeyboardNoteOn_includesNativeLockWait")
    // Finer RIGHT stages (what is inside the old onSend->handoff gap).
    val midiOnSendCpu = Stat("midiOnSendThreadCpuTime_wallMinusCpu=blockedOrPreempted")
    val rightLockToRouting = Stat("rightKeyboardLockToStartRightNote_includesRIGHT_IN_log")
    val rightPreJniLogging = Stat("rightEngineNoteOnEntryToJniCall_includesTraceAudioFormat")
    // Section transitions.
    val sectionRequestToBoundary = Stat("sectionRequestToBoundaryWake")
    val sectionBoundaryToApplyStart = Stat("sectionBoundaryToVoiceApplyStart")
    val sectionApply = Stat("sectionVoiceApplyDuration")
    val sectionApplyEndToFirstNote = Stat("sectionVoiceApplyEndToFirstNoteDispatch")
    val sectionBoundaryToFirstNote = Stat("sectionBoundaryToFirstNoteDispatch")
    val firstNoteLateness = Stat("sectionFirstNoteLatenessVsSchedule")
    // Scheduler, all style events.
    val schedulerWakeLateness = Stat("styleEventWakeLatenessBeforeLifecycle")
    val schedulerDispatchLateness = Stat("styleEventDispatchLatenessAfterLifecycle")
    // ACMP.
    val chordMidiToApplyStart = Stat("chordLastNoteToApplyChordNow")
    val chordRetarget = Stat("chordApplyDuration_handleChordChange")
    val chordMidiToRetargetEnd = Stat("chordLastNoteToRetargetDone")

    private val all = listOf(midiIngressLag, midiOnSendTotal, midiOnSendCpu, keyboardLockWait, rightLockToRouting, rightPreJniLogging, rightIngressToHandoff, jniKeyboardNote,
        sectionRequestToBoundary, sectionBoundaryToApplyStart, sectionApply, sectionApplyEndToFirstNote,
        sectionBoundaryToFirstNote, firstNoteLateness, schedulerWakeLateness, schedulerDispatchLateness,
        chordMidiToApplyStart, chordRetarget, chordMidiToRetargetEnd)

    // Single-writer stamps: the MIDI thread, the Main thread or the sequencer thread each own theirs.
    @Volatile private var onSendEnterNs = 0L
    @Volatile private var onSendCpuEnterNs = 0L
    @Volatile private var lockAcquiredNs = 0L
    @Volatile private var usbTimestampNs = 0L
    @Volatile private var noteIngressNs = 0L
    @Volatile private var chordArrivalNs = 0L
    @Volatile private var sectionRequestNs = 0L
    @Volatile private var sectionBoundaryNs = 0L
    @Volatile private var voiceApplyStartNs = 0L
    @Volatile private var voiceApplyEndNs = 0L
    @Volatile private var awaitingFirstNote = false

    fun reset() { all.forEach { it.reset() } }

    // ---- MIDI thread ----
    fun onSendEnter(timestampNs: Long) {
        if (!enabled) return
        usbTimestampNs = timestampNs; onSendCpuEnterNs = android.os.Debug.threadCpuTimeNanos(); onSendEnterNs = System.nanoTime()
    }
    fun onSendExit() {
        if (!enabled) return
        val t = onSendEnterNs
        if (t != 0L) midiOnSendTotal.record(System.nanoTime() - t)
        val c = onSendCpuEnterNs; if (c != 0L) midiOnSendCpu.record(android.os.Debug.threadCpuTimeNanos() - c)
    }
    /** A keyboard NOTE_ON on the chord/keyboard channel was parsed. */
    fun noteOnParsed() {
        if (!enabled) return
        val enter = onSendEnterNs; noteIngressNs = enter
        val usb = usbTimestampNs
        // MidiReceiver timestamp 0 means "now"; a stamp from the future or > 1 s old is not a usable ingress time.
        if (usb > 0 && enter >= usb && enter - usb < 1_000_000_000L) midiIngressLag.record(enter - usb)
    }
    fun keyboardLockAcquired(requestedNs: Long) {
        if (!enabled) return
        val now = System.nanoTime(); lockAcquiredNs = now; keyboardLockWait.record(now - requestedNs)
    }
    fun rightRoutingStart() { if (enabled) { val t = lockAcquiredNs; if (t != 0L) rightLockToRouting.record(System.nanoTime() - t) } }
    fun preJniLogging(entryNs: Long, jniStartNs: Long) { if (enabled) rightPreJniLogging.record(jniStartNs - entryNs) }
    fun rightHandedOff() { if (enabled) { val t = noteIngressNs; if (t != 0L) rightIngressToHandoff.record(System.nanoTime() - t) } }
    fun jniKeyboardNote(startNs: Long) { if (enabled) jniKeyboardNote.record(System.nanoTime() - startNs) }
    fun chordNoteParsed() { if (enabled) chordArrivalNs = noteIngressNs }

    // ---- Main thread: ACMP ----
    fun chordApplyStart(): Long {
        if (!enabled) return 0L
        val now = System.nanoTime(); val t = chordArrivalNs
        if (t != 0L) chordMidiToApplyStart.record(now - t)
        return now
    }
    fun chordApplyEnd(startNs: Long) {
        if (!enabled || startNs == 0L) return
        val now = System.nanoTime(); chordRetarget.record(now - startNs)
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
        val now = System.nanoTime(); voiceApplyStartNs = now
        val b = sectionBoundaryNs; if (b != 0L && now >= b) sectionBoundaryToApplyStart.record(now - b)
    }
    fun voiceApplyEnd() {
        if (!enabled) return
        val now = System.nanoTime(); voiceApplyEndNs = now
        val s = voiceApplyStartNs; if (s != 0L) sectionApply.record(now - s)
        awaitingFirstNote = sectionBoundaryNs != 0L
    }
    fun schedulerWake(nowNs: Long, targetNs: Long) { if (enabled && nowNs >= targetNs) schedulerWakeLateness.record(nowNs - targetNs) }
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

    fun report(): String = buildString {
        appendLine("LATENCY_KOTLIN clock=System.nanoTime(=CLOCK_MONOTONIC) enabled=$enabled dispatch_is_not_audible_time=1")
        all.forEach { appendLine(it.line()) }
    }
}

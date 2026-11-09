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
        fun meanUs(): Long { val n = count.get(); return if (n > 0) sumUs.get() / n else 0 }
        /** Samples at or above BOUNDS[index] (e.g. index 7 = 32 ms, 8 = 64 ms). Read-only. */
        fun atLeast(index: Int): Long { var n = 0L; for (b in (index + 1)..BOUNDS.size) n += buckets.get(b); return n }
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
    // Chord transition, measured from the FIRST key of the chord (finger spread + recognition + 15 ms settle + retarget).
    val chordFirstKeyToRecognized = Stat("chordFirstKeyToRecognized_includesFingerSpread")
    val chordFirstKeyToApplyStart = Stat("chordFirstKeyToApplyStart_includesSettle")
    val chordFirstKeyToRetargetEnd = Stat("chordFirstKeyToRetargetDone_synchronousJniAndUsb")
    val chordRecognizedToApplyStart = Stat("chordRecognizedToApplyStart_settleAndMainQueue")
    // Plain counters (rates are computed at export time from `counterStartNs`).
    private val midiEventsIn = java.util.concurrent.atomic.AtomicLong()
    private val chordChanges = java.util.concurrent.atomic.AtomicLong()
    private val retargetNotes = java.util.concurrent.atomic.AtomicLong()
    private val retargetUnchanged = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var counterStartNs = 0L

    private val all = listOf(midiIngressLag, midiOnSendTotal, midiOnSendCpu, keyboardLockWait, rightLockToRouting, rightPreJniLogging, rightIngressToHandoff, jniKeyboardNote,
        sectionRequestToBoundary, sectionBoundaryToApplyStart, sectionApply, sectionApplyEndToFirstNote,
        sectionBoundaryToFirstNote, firstNoteLateness, schedulerWakeLateness, schedulerDispatchLateness,
        chordMidiToApplyStart, chordRetarget, chordMidiToRetargetEnd,
        chordFirstKeyToRecognized, chordRecognizedToApplyStart, chordFirstKeyToApplyStart, chordFirstKeyToRetargetEnd)

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

    fun reset() {
        all.forEach { it.reset() }
        Pb02.reset() // P-B0.2
        midiEventsIn.set(0); chordChanges.set(0); retargetNotes.set(0); retargetUnchanged.set(0); counterStartNs = System.nanoTime()
        ringN = 0; firstKeyNs = 0L; lastChordKeyNs = 0L; recognizedNs = 0L
    }

    // ---- Chord transition ring: fixed arrays, single writer per field, no allocation after init ----
    private const val RING = 64
    private val rFirst = LongArray(RING); private val rRecognized = LongArray(RING); private val rApply = LongArray(RING); private val rEnd = LongArray(RING)
    private val rNotes = IntArray(RING); private val rChangedNotes = IntArray(RING)
    @Volatile private var ringN = 0
    @Volatile private var firstKeyNs = 0L
    @Volatile private var lastChordKeyNs = 0L
    @Volatile private var recognizedNs = 0L
    private var applySlot = 0
    private var applyNotesBase = 0L

    /** Any parsed MIDI message on the keyboard channel (NOTE_ON/OFF, CC64). Counter only. */
    fun midiEvent() { if (enabled) { if (counterStartNs == 0L) counterStartNs = System.nanoTime(); midiEventsIn.incrementAndGet() } }
    /** A LEFT/ACMP key went down. A new chord burst starts when the previous chord key is older than 350 ms. */
    fun chordKeyDown() {
        if (!enabled) return
        val now = System.nanoTime()
        if (now - lastChordKeyNs > 350_000_000L) firstKeyNs = now
        lastChordKeyNs = now
    }
    /** The detector produced a chord that differs from the applied/pending one (MIDI thread, inside keyboardLock). */
    fun chordRecognized() {
        if (!enabled) return
        val now = System.nanoTime(); recognizedNs = now
        val f = firstKeyNs; if (f != 0L && now >= f) chordFirstKeyToRecognized.record(now - f)
    }
    /** One retarget decision that actually sent NOTE_OFF+NOTE_ON (changed pitch) or was unchanged. */
    fun retargetDecision(sent: Boolean) { if (enabled) { if (sent) retargetNotes.incrementAndGet() else retargetUnchanged.incrementAndGet() } }

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
        val f = firstKeyNs; val r = recognizedNs
        if (f != 0L && now >= f) chordFirstKeyToApplyStart.record(now - f)
        if (r != 0L && now >= r) chordRecognizedToApplyStart.record(now - r)
        applySlot = ringN % RING
        rFirst[applySlot] = f; rRecognized[applySlot] = r; rApply[applySlot] = now; rEnd[applySlot] = 0L
        applyNotesBase = retargetNotes.get()
        pbChordWindow?.invoke(true, 0L, 0L) // P-B0.1 (UI thread, before handleChordChange)
        return now
    }
    fun chordApplyEnd(startNs: Long) {
        if (!enabled || startNs == 0L) return
        val now = System.nanoTime(); chordRetarget.record(now - startNs)
        val t = chordArrivalNs; if (t != 0L) chordMidiToRetargetEnd.record(now - t)
        val f = firstKeyNs; if (f != 0L && now >= f) chordFirstKeyToRetargetEnd.record(now - f)
        rEnd[applySlot] = now; rNotes[applySlot] = (retargetNotes.get() - applyNotesBase).toInt()
        pbChordWindow?.invoke(false, now - startNs, retargetNotes.get() - applyNotesBase) // P-B0.1
        chordChanges.incrementAndGet(); ringN = ringN + 1
    }

    // ---- Sections ----
    fun sectionRequested() { if (enabled) sectionRequestNs = System.nanoTime() }
    /** The sequencer woke at the bar boundary and is switching sections. */
    fun sectionBoundary() {
        if (!enabled) return
        val now = System.nanoTime(); sectionBoundaryNs = now
        val r = sectionRequestNs; if (r != 0L) sectionRequestToBoundary.record(now - r)
    }
    /** P-B0.1: window hooks installed by AudioEngineManager (native per-window deltas). Null = not installed. */
    @Volatile var pbVoiceWindow: ((Boolean, Long) -> Unit)? = null
    @Volatile var pbChordWindow: ((Boolean, Long, Long) -> Unit)? = null
    fun voiceApplyStart() {
        if (!enabled) return
        pbVoiceWindow?.invoke(true, 0L) // P-B0.1 (same thread as the voice apply, before it starts)
        Pb02.voiceBegin() // P-B0.2
        val now = System.nanoTime(); voiceApplyStartNs = now
        val b = sectionBoundaryNs; if (b != 0L && now >= b) sectionBoundaryToApplyStart.record(now - b)
    }
    fun voiceApplyEnd() {
        if (!enabled) return
        val now = System.nanoTime(); voiceApplyEndNs = now
        val s = voiceApplyStartNs; if (s != 0L) sectionApply.record(now - s)
        if (s != 0L) pbVoiceWindow?.invoke(false, now - s) // P-B0.1
        Pb02.voiceEnd() // P-B0.2
        awaitingFirstNote = sectionBoundaryNs != 0L
    }
    fun schedulerWake(nowNs: Long, targetNs: Long) { Pb02.wake(nowNs, targetNs) /* P-B0.2, default OFF */; if (enabled && nowNs >= targetNs) schedulerWakeLateness.record(nowNs - targetNs) }
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
        append(Pb02.report()) // P-B0.2 (one line "PB02 enabled=false" when OFF)
        appendLine("LATENCY_KOTLIN clock=System.nanoTime(=CLOCK_MONOTONIC) enabled=$enabled dispatch_is_not_audible_time=1")
        all.forEach { appendLine(it.line()) }
        // P-A EXPERIMENT: late-event counts for the two Style scheduler stats (bucket sums, nothing new is recorded).
        for (st in listOf(schedulerWakeLateness, schedulerDispatchLateness))
            appendLine("LATENCY_KOTLIN_LATE stat=${st.name} count=${st.count()} meanUs=${if (st.count() > 0) st.meanUs() else 0} over32ms=${st.atLeast(7)} over64ms=${st.atLeast(8)} maxUs=${st.maxUs()}")
        val elapsedS = if (counterStartNs != 0L) (System.nanoTime() - counterStartNs) / 1e9 else 0.0
        val chords = chordChanges.get(); val notes = retargetNotes.get()
        appendLine("LATENCY_KOTLIN_COUNTERS sinceResetS=${"%.1f".format(java.util.Locale.US, elapsedS)} midiEventsIn=${midiEventsIn.get()} " +
            "midiEventsPerSec=${if (elapsedS > 0) "%.2f".format(java.util.Locale.US, midiEventsIn.get() / elapsedS) else "NOT_MEASURED"} chordChanges=$chords " +
            "chordChangesPerSec=${if (elapsedS > 0) "%.3f".format(java.util.Locale.US, chords / elapsedS) else "NOT_MEASURED"} " +
            "retargetNoteReplacements=$notes retargetUnchangedDecisions=${retargetUnchanged.get()} " +
            "replacementsPerChordChange=${if (chords > 0) "%.1f".format(java.util.Locale.US, notes.toDouble() / chords) else "NOT_MEASURED"} " +
            "midiQueueDepth=NOT_MEASURED(no_app_queue;android_midi_service_socket_is_not_observable;see_midiUsbTimestampToOnSend)")
        val n = ringN; val shown = minOf(n, 16)
        for (k in 0 until shown) {
            val idx = ((n - 1 - k) % RING + RING) % RING
            fun d(a: Long, b: Long) = if (a != 0L && b >= a) ((b - a) / 1000).toString() else "NA"
            appendLine("CHORD_TRANSITION back=$k firstKeyToRecognizedUs=${d(rFirst[idx], rRecognized[idx])} recognizedToApplyUs=${d(rRecognized[idx], rApply[idx])} " +
                "applyToRetargetDoneUs=${d(rApply[idx], rEnd[idx])} firstKeyToDoneUs=${d(rFirst[idx], rEnd[idx])} noteReplacements=${rNotes[idx]} applyAtNs=${rApply[idx]} doneAtNs=${rEnd[idx]}")
        }
    }
}

/**
 * A/B switch for the logging that runs on the keyboard NOTE_ON/OFF path (MIDI thread, before BASSMIDI sees the event).
 * Measured on device: ~0.6 ms per DebugLog.add, 3-4 calls per note, CPU-bound, and chord notes are handled one after
 * another on that thread. `lean` skips only those log lines; no engine call, controller or MIDI OUT message changes.
 * Process-local, never persisted: every launch is normal (lean = false).
 */
object NoteLogTuning {
    @Volatile var lean = false
    private val suppressedCount = java.util.concurrent.atomic.AtomicLong()
    val suppressed: Long get() = suppressedCount.get()
    /** true = skip this log line. */
    fun skip(): Boolean { if (!lean) return false; suppressedCount.incrementAndGet(); return true }
    fun resetCounter() = suppressedCount.set(0)
}

// ======================= P-B0.2 EXPERIMENT (observer only, default OFF) =======================
/**
 * P-B0.2 EXPERIMENT (observer only, default OFF): splits Style scheduler lateness (wake time - due time of each event) into
 *   WAKE   - the scheduler had no observed work left at the due time and woke up late (timer / thread scheduling). UPPER BOUND:
 *            it also contains the unobserved tail of the previous event (Kotlin after its last JNI/MIDI OUT call); the tail's own
 *            distribution is measured in backlog cases and reported (`tail`) so the bound can be judged.
 *   JNI    - the scheduler was inside a BASSMIDI JNI call (lock wait + native work), measured around the bridge call.
 *   MIDIOUT- the scheduler was inside a MIDI OUT send (router + USB write).
 *   VOICE  - the scheduler was inside a section voice apply (its own JNI calls are part of it, not counted twice).
 *   KOTLIN - the scheduler was busy but not in any of the above (CASM, ledger, Kotlin locks, GC, preemption while running).
 *   UNKNOWN- the part of a lateness interval older than the interval ring's coverage (never guessed).
 * BOUNDARY events: a section change contains waits this probe cannot see (the bar-boundary wait and P1's awaitMusicalTick live in
 * StyleSequencer, which is not instrumented). The first event after a voice apply and every event whose lateness interval reaches
 * back into that change are counted as BOUNDARY: their lateness is reported as a total, never split (no estimate).
 * Exactness: typed intervals are disjoint by construction (JNI/MIDI OUT calls are sequential on the scheduler; JNI inside a voice
 * apply is not recorded separately; WAKE is time asleep), so for every event wake+jni+midiOut+voice+kotlin+unknown == lateness.
 * Thread-time accounting assigns every nanosecond between two scheduler wakes to exactly one bucket (idle-on-time incl. tail,
 * wake overrun, jni, midiOut, voice, kotlin), so no time is counted twice across events either.
 * Single logical writer: the scheduler coroutine (sequential even when it changes worker thread; coroutine resumption is a
 * happens-before edge). Report readers only read atomics.
 */
class Pb02Core(private val ringSize: Int = 512) {
    companion object { const val JNI = 0; const val MIDIOUT = 1; const val VOICE = 2; const val WAKE = 3; const val TYPES = 4 }
    private val rs = LongArray(ringSize); private val re = LongArray(ringSize); private val rt = IntArray(ringSize)
    private var rn = 0L
    private var coverageStart = Long.MAX_VALUE   // complete interval coverage exists only from here on
    private var started = false; private var lastWake = 0L; private var lastActivity = 0L
    private val typedSinceWake = LongArray(TYPES)
    private var voiceStart = 0L
    private var boundaryPending = false; private var boundaryUntil = 0L
    @Volatile var inVoice = false; private set

    // ---- results (atomics: read by the report thread) ----
    val events = java.util.concurrent.atomic.AtomicLong(); val early = java.util.concurrent.atomic.AtomicLong(); val idleCase = java.util.concurrent.atomic.AtomicLong(); val backlogCase = java.util.concurrent.atomic.AtomicLong(); val mismatches = java.util.concurrent.atomic.AtomicLong()
    val boundaryCase = java.util.concurrent.atomic.AtomicLong(); val boundaryLateness = LatencyProbe.Stat("PB02_boundaryEvents_lateness_notSplit")
    val partSum = Array(6) { java.util.concurrent.atomic.AtomicLong() }           // ns per part over all events: wake, jni, midiOut, voice, kotlin, unknown
    val partSumLate32 = Array(6) { java.util.concurrent.atomic.AtomicLong() }     // same, events with lateness >= 32 ms
    val partSumLate64 = Array(6) { java.util.concurrent.atomic.AtomicLong() }     // same, events with lateness >= 64 ms
    val latenessSum = java.util.concurrent.atomic.AtomicLong(); val late32Sum = java.util.concurrent.atomic.AtomicLong(); val late64Sum = java.util.concurrent.atomic.AtomicLong(); val late32 = java.util.concurrent.atomic.AtomicLong(); val late64 = java.util.concurrent.atomic.AtomicLong()
    val timeline = Array(7) { java.util.concurrent.atomic.AtomicLong() }          // idleOnTime(incl tail), wakeOverrun, jni, midiOut, voice, kotlin, boundary(untyped incl. unhooked waits)
    val span = java.util.concurrent.atomic.AtomicLong()
    /** Per-part histograms of event lateness parts (Stat buckets, µs). */
    val lateness = LatencyProbe.Stat("PB02_lateness"); val parts = arrayOf(LatencyProbe.Stat("PB02_part_wake"), LatencyProbe.Stat("PB02_part_jni"),
        LatencyProbe.Stat("PB02_part_midiOut"), LatencyProbe.Stat("PB02_part_voice"), LatencyProbe.Stat("PB02_part_kotlin"), LatencyProbe.Stat("PB02_part_unknown"))
    val tail = LatencyProbe.Stat("PB02_tail_afterLastObservedActivity_backlogOnly")

    private fun add(s0: Long, e: Long, type: Int) {
        val s = if (type == WAKE) s0 else maxOf(s0, lastActivity)   // a nested/overlapping call keeps only its new part: disjoint by construction
        if (!started || e <= s) return
        val i = (rn % ringSize).toInt()
        if (rn >= ringSize) coverageStart = maxOf(coverageStart, re[i])   // evicting: time up to its end is no longer fully covered
        rs[i] = s; re[i] = e; rt[i] = type; rn++
        if (e > lastActivity) lastActivity = e
        if (type != WAKE) typedSinceWake[type] += e - s
    }
    fun jni(s: Long, e: Long) { if (!inVoice) add(s, e, JNI) }
    fun midiOut(s: Long, e: Long) { if (!inVoice) add(s, e, MIDIOUT) }
    fun voiceBegin(t: Long) { voiceStart = t; inVoice = true }
    fun voiceEnd(t: Long) { inVoice = false; if (voiceStart != 0L) add(voiceStart, t, VOICE); voiceStart = 0L; boundaryPending = true }

    /** One scheduler wake: [w] = time the event's wait returned, [d] = its due time. */
    fun wake(w: Long, d: Long) {
        if (!started) { started = true; lastWake = w; lastActivity = w; coverageStart = w; java.util.Arrays.fill(typedSinceWake, 0L); return }
        events.incrementAndGet()
        val gap = w - lastWake
        val typed = typedSinceWake.sum()
        if (boundaryPending || d < boundaryUntil) {        // section change in or overlapping this interval: total only
            boundaryCase.incrementAndGet()
            if (boundaryPending) { boundaryUntil = w; boundaryPending = false }
            if (w > d) boundaryLateness.record(w - d)
            accountGap(gap, typed, idleOnTime = 0L, overrun = 0L, boundary = true)
        } else if (w <= d) {                                      // on time (or dispatched early): nothing late
            early.incrementAndGet()
            accountGap(gap, typed, idleOnTime = maxOf(0L, w - lastActivity), overrun = 0L)
        } else if (d > lastActivity) {                     // IDLE at due: [d, w] is wake overrun (upper bound, see class doc)
            idleCase.incrementAndGet()
            val l = w - d
            record(l, longArrayOf(l, 0, 0, 0, 0, 0))
            accountGap(gap, typed, idleOnTime = d - lastActivity, overrun = l)
            add(d, w, WAKE)
        } else {                                           // BACKLOG: busy at due; [d, w] split by the typed intervals in the ring
            backlogCase.incrementAndGet()
            val l = w - d
            val p = LongArray(6)
            val unk = if (coverageStart > d) minOf(coverageStart, w) - d else 0L
            p[5] = unk
            var n = minOf(rn, ringSize.toLong())
            var k = rn - 1
            while (n-- > 0) {
                val i = (k % ringSize).toInt(); k--
                if (re[i] <= d) break                      // ring is in time order; older intervals end before d
                val s = maxOf(rs[i], maxOf(d, coverageStart)); val e = minOf(re[i], w)
                if (e > s) p[when (rt[i]) { WAKE -> 0; JNI -> 1; MIDIOUT -> 2; else -> 3 }] += e - s
            }
            p[4] = l - p[0] - p[1] - p[2] - p[3] - p[5]
            if (p[4] < 0) { mismatches.incrementAndGet(); p[4] = 0 }
            record(l, p)
            tail.record(w - lastActivity)
            accountGap(gap, typed, idleOnTime = 0L, overrun = 0L)
        }
        lastWake = w; lastActivity = w; java.util.Arrays.fill(typedSinceWake, 0L)
    }
    private fun record(l: Long, p: LongArray) {
        if (p.sum() != l) mismatches.incrementAndGet()
        lateness.record(l); latenessSum.addAndGet(l)
        for (i in 0 until 6) { partSum[i].addAndGet(p[i]); if (p[i] > 0) parts[i].record(p[i]) }
        if (l >= 32_000_000L) { late32.incrementAndGet(); late32Sum.addAndGet(l); for (i in 0 until 6) partSumLate32[i].addAndGet(p[i]) }
        if (l >= 64_000_000L) { late64.incrementAndGet(); late64Sum.addAndGet(l); for (i in 0 until 6) partSumLate64[i].addAndGet(p[i]) }
    }
    /** Thread-time accounting of one wake-to-wake gap; buckets sum to the gap exactly. */
    private fun accountGap(gap: Long, typed: Long, idleOnTime: Long, overrun: Long, boundary: Boolean = false) {
        span.addAndGet(gap)
        timeline[0].addAndGet(idleOnTime); timeline[1].addAndGet(overrun)
        for (t in 0 until 3) timeline[2 + t].addAndGet(typedSinceWake[t])
        val kotlin = gap - idleOnTime - overrun - typed
        if (kotlin < 0) mismatches.incrementAndGet()
        timeline[if (boundary) 6 else 5].addAndGet(maxOf(0L, kotlin))
    }

    fun report(): String = buildString {
        val names = listOf("wake", "jni", "midiOut", "voice", "kotlin", "unknown")
        fun share(a: Array<java.util.concurrent.atomic.AtomicLong>, total: Long) = names.indices.joinToString(" ") { "${names[it]}=${if (total > 0) "%.1f".format(java.util.Locale.US, 100.0 * a[it].get() / total) else "-"}%" }
        appendLine("PB02 events=${events.get()} onTimeOrEarly=${early.get()} idleCase=${idleCase.get()} backlogCase=${backlogCase.get()} boundaryCase=${boundaryCase.get()} late32=${late32.get()} late64=${late64.get()} mismatches=${mismatches.get()} (mismatches must be 0)")
        appendLine("PB02_SHARE_all ${share(partSum, latenessSum.get())} totalLateMs=${latenessSum.get() / 1_000_000}")
        appendLine("PB02_SHARE_late32ms ${share(partSumLate32, late32Sum.get())}")
        appendLine("PB02_SHARE_late64ms ${share(partSumLate64, late64Sum.get())}")
        val t = timeline.map { it.get() / 1_000_000 }
        appendLine("PB02_TIMELINE_ms span=${span.get() / 1_000_000} idleOnTimeInclTail=${t[0]} wakeOverrun=${t[1]} jni=${t[2]} midiOut=${t[3]} voice=${t[4]} kotlinBusy=${t[5]} boundaryUntyped=${t[6]} (buckets sum to span)")
        appendLine(lateness.line()); parts.forEach { appendLine(it.line()) }; appendLine(tail.line()); appendLine(boundaryLateness.line())
    }
}

/** Runtime switch + scheduler-thread filter around [Pb02Core]. Default OFF; every hook is one volatile read when OFF. */
object Pb02 {
    @Volatile var enabled = false; private set
    @Volatile private var core = Pb02Core()
    private val sched = ThreadLocal<Boolean>()
    private fun isSched(): Boolean {
        val c = sched.get(); if (c != null) return c
        val v = Thread.currentThread().name.startsWith("DefaultDispatcher-worker"); sched.set(v); return v
    }
    fun setEnabled(on: Boolean) { if (on && !enabled) core = Pb02Core(); enabled = on }
    fun reset() { if (enabled) core = Pb02Core() }
    /** Start stamp for a JNI / MIDI OUT call made by the scheduler; 0 = not recorded. */
    fun t0(): Long = if (enabled && isSched()) System.nanoTime() else 0L
    fun jni(t0: Long) { if (t0 != 0L) core.jni(t0, System.nanoTime()) }
    fun midiOut(t0: Long) { if (t0 != 0L) core.midiOut(t0, System.nanoTime()) }
    fun voiceBegin() { if (enabled && isSched()) core.voiceBegin(System.nanoTime()) }
    fun voiceEnd() { if (enabled && isSched() && core.inVoice) core.voiceEnd(System.nanoTime()) }
    fun wake(nowNs: Long, targetNs: Long) { if (enabled) core.wake(nowNs, targetNs) }
    internal fun coreForTest(): Pb02Core = core
    fun report(): String = "PB02 enabled=$enabled (P-B0.2 scheduler lateness split; counters since RESET LATENCY or since enabled)\n" + (if (enabled) core.report() else "")
}

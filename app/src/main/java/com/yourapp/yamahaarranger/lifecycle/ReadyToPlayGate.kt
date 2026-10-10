package com.yourapp.yamahaarranger.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** Admission only. It never starts playback, dispatches MIDI, or gates continuation. */
class ReadyToPlayGate {
    enum class Phase { NOT_READY, LOADING, READY, ERROR }
    data class Request(val generation: Long, val kind: String)
    data class Snapshot(val phase: Phase, val reason: String, val sections: Set<String> = emptySet()) {
        fun permits(section: String) = phase == Phase.READY && section in sections
    }
    // Physical resource commits and initial Start share this mutex. Start uses
    // tryLock, so it never waits on an SF2 load or queues an automatic start.
    val operations = Mutex()
    val fontRequests = Mutex()
    private val monitor = Any()
    private val mutable = MutableStateFlow(Snapshot(Phase.NOT_READY, "Load SF2 and style"))
    val state = mutable.asStateFlow()
    private var next = 0L
    private var fontRequest: Request? = null
    private var styleRequest: Request? = null
    private var fontPending = false
    private var stylePending = false
    private var fontError = false
    private var styleError = false
    private var outputError = false
    private var melody: String? = null
    private var drum: String? = null
    private var sections = emptySet<String>()
    private var styleIdentity: Any? = null
    private var outputEpoch = 0L
    private var outputWanted = false
    private var outputReady = false

    private fun publish() {
        mutable.value = when {
            fontPending -> Snapshot(Phase.LOADING, "Loading SF2...", sections)
            stylePending -> Snapshot(Phase.LOADING, "Loading style...", sections)
            fontError -> Snapshot(Phase.ERROR, "SF2 load failed; retry", sections)
            styleError -> Snapshot(Phase.ERROR, "Style load failed; retry", sections)
            outputError -> Snapshot(Phase.ERROR, "Audio output failed; restart audio", sections)
            melody == null || drum == null -> Snapshot(Phase.NOT_READY, "Load melody/drum SF2", sections)
            sections.isEmpty() -> Snapshot(Phase.NOT_READY, "Load a playable style", sections)
            !outputReady -> Snapshot(Phase.NOT_READY, "Preparing audio...", sections)
            else -> Snapshot(Phase.READY, "Ready", sections)
        }
    }
    fun beginFonts(): Request = synchronized(monitor) {
        Request(Math.addExact(next, 1L).also { next = it }, "fonts").also {
            fontRequest = it; fontPending = true; fontError = false; publish()
        }
    }
    fun beginStyle(): Request = synchronized(monitor) {
        Request(Math.addExact(next, 1L).also { next = it }, "style").also {
            styleRequest = it; stylePending = true; styleError = false; publish()
        }
    }
    fun current(request: Request): Boolean = synchronized(monitor) {
        if (request.kind == "fonts") request == fontRequest else request == styleRequest
    }
    // Called after the actual native result while operations is held. A false
    // result does not claim rollback of a loader that already freed old fonts.
    fun installedFonts(request: Request, ok: Boolean, melodyPath: String?, drumPath: String?) = synchronized(monitor) {
        if (!ok) { melody = null; drum = null }
        else { if (melodyPath != null) melody = melodyPath; if (drumPath != null) drum = drumPath }
        if (request == fontRequest && !ok) fontError = true
        publish()
    }
    fun clearFonts() = synchronized(monitor) { melody = null; drum = null; publish() }
    fun finishFonts(request: Request, ok: Boolean) = synchronized(monitor) {
        if (request == fontRequest) { fontPending = false; fontError = !ok; publish() }
    }
    fun finishStyle(request: Request, available: Set<String>?, identity: Any = request) = synchronized(monitor) {
        if (request == styleRequest) {
            stylePending = false; styleError = available.isNullOrEmpty()
            if (available != null) { sections = available.toSet(); styleIdentity = identity }
            publish()
        }
    }
    fun beginOutput(): Long = synchronized(monitor) {
        outputEpoch = Math.addExact(outputEpoch, 1L); outputWanted = true
        outputReady = false; outputError = false; publish(); outputEpoch
    }
    fun invalidateOutput(): Long = synchronized(monitor) {
        outputEpoch = Math.addExact(outputEpoch, 1L); outputWanted = false
        outputReady = false; outputError = false; publish(); outputEpoch
    }
    fun outputToken(): Long = synchronized(monitor) { outputEpoch }
    fun wantsOutput(epoch: Long): Boolean = synchronized(monitor) { outputEpoch == epoch && outputWanted }
    fun outputResult(epoch: Long, ok: Boolean) = synchronized(monitor) {
        if (outputEpoch == epoch && outputWanted) { outputReady = ok; outputError = !ok; publish() }
    }
    fun paused(epoch: Long) = synchronized(monitor) {
        if (epoch == outputEpoch) { outputReady = false; publish() }
    }
    fun admit(section: String, identity: Any? = null, start: () -> Unit): Boolean {
        if (!operations.tryLock()) return false
        try {
            if (!synchronized(monitor) { mutable.value.permits(section) && (identity == null || styleIdentity === identity) }) return false
            start()
            return true
        } finally { operations.unlock() }
    }
}

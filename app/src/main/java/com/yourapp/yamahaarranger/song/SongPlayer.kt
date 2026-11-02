package com.yourapp.yamahaarranger.song

import java.util.concurrent.locks.LockSupport

/** A destination for Song MIDI messages (internal Song BASSMIDI stream, MIDI OUT, or a test recorder). One message per call. */
interface SongOutput {
    val id: String
    fun send(bytes: ByteArray)
}

enum class SongState { EMPTY, STOPPED, PLAYING, PAUSED }

data class SongStatus(
    val state: SongState, val title: String, val positionUs: Long, val durationUs: Long, val tick: Long, val barBeat: BarBeat?,
    val bars: Int, val fileBpm: Double, val effectiveBpm: Double, val tempoPercent: Int, val timeSig: String, val muted: List<Boolean>,
    val channelsUsed: Set<Int>, val programs: List<Int>, val bankMsb: List<Int>, val lastNoteOnNs: List<Long>, val outputs: List<String>,
    val message: String?,
) {
    val progress get() = if (durationUs <= 0) 0f else (positionUs.toFloat() / durationUs).coerceIn(0f, 1f)
}

/**
 * MIDI Song player. All decisions happen in [step] under one lock with an injectable monotonic clock, so it is unit-testable
 * without threads; [start] runs a single scheduler thread that only calls [step] and parks until the next event.
 *
 * Contracts:
 *  - each event is sent ONCE to each active output, in merged file order (Bank/Program order preserved);
 *  - absolute scheduling (song time → wall time from one anchor): no cumulative drift;
 *  - the player owns a NOTE ledger and a sustain flag per channel: STOP / PAUSE / SEEK / MUTE / output removal send NOTE OFF
 *    (and CC64 0 where sustained) only for notes the SONG started — never All Notes Off, never other sources' notes;
 *  - a NOTE OFF whose NOTE ON was not sent (muted channel, or before a panic) is not sent (no orphan NOTE OFF);
 *  - SEEK and RESUME first send the channel state reached at that point (bank, program, controllers, pitch bend; no notes);
 *  - SysEx is never sent (not part of the timeline).
 */
class SongPlayer(private val clock: () -> Long = System::nanoTime) {
    private val lock = Object()
    private var timeline: SongTimeline? = null
    private var title = ""
    private var outputs: List<SongOutput> = emptyList()
    private var state = SongState.EMPTY
    private var next = 0                           // index of the next event to send
    private var anchorWallNs = 0L                  // wall time of anchorSongUs while playing
    private var anchorSongUs = 0L
    private var pausedUs = 0L                      // position while stopped/paused
    private var tempoPercent = 100
    private val muted = BooleanArray(16)
    private val active = IntArray(16 * 128)        // NOTE ON instances the song has sent and not yet ended
    private val sustain = BooleanArray(16)
    private val bent = BooleanArray(16)
    private val programs = IntArray(16) { -1 }
    private val bankMsb = IntArray(16) { -1 }
    private val lastNoteOn = LongArray(16)
    private var message: String? = null
    /** Called after STOP / end of song (e.g. to let the Style router resend its voices to the external module). */
    @Volatile var onStopped: (() -> Unit)? = null
    @Volatile private var thread: Thread? = null

    fun load(t: SongTimeline, name: String) = synchronized(lock) {
        stopLocked(); timeline = t; title = name; state = SongState.STOPPED; pausedUs = 0; next = 0
        muted.fill(false); programs.fill(-1); bankMsb.fill(-1); lastNoteOn.fill(0); message = null
        wake()
    }

    fun unload() = synchronized(lock) { stopLocked(); timeline = null; title = ""; state = SongState.EMPTY; wake() }

    /** Replace the outputs. Notes still sounding on a removed output are ended there (only those). */
    fun setOutputs(list: List<SongOutput>) = synchronized(lock) {
        val removed = outputs.filter { o -> list.none { it.id == o.id } }
        if (removed.isNotEmpty()) panicLocked(0 until 16, removed, clearState = false)
        val added = list.filter { o -> outputs.none { it.id == o.id } }
        outputs = list
        if (added.isNotEmpty() && (state == SongState.PLAYING || state == SongState.PAUSED)) chaseLocked(currentUsLocked(clock()), added)
    }

    fun play(): String? = synchronized(lock) {
        val t = timeline ?: return "No MIDI song loaded"
        if (state == SongState.PLAYING) return null
        if (pausedUs >= t.durationUs && t.durationUs > 0) { pausedUs = 0; next = 0 }
        chaseLocked(pausedUs, outputs)
        next = firstIndexAtOrAfterUs(t, pausedUs)
        anchorSongUs = pausedUs; anchorWallNs = clock(); state = SongState.PLAYING; message = null
        wake(); null
    }

    fun pause() = synchronized(lock) {
        if (state != SongState.PLAYING) return
        pausedUs = currentUsLocked(clock()); state = SongState.PAUSED
        panicLocked(0 until 16, outputs, clearState = false)
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /** Seek to an absolute song position (file time). Keeps playing if it was playing. */
    fun seekUs(us: Long) = synchronized(lock) {
        val t = timeline ?: return
        val target = us.coerceIn(0, t.durationUs)
        val wasPlaying = state == SongState.PLAYING
        panicLocked(0 until 16, outputs, clearState = false)
        pausedUs = target
        next = firstIndexAtOrAfterUs(t, target)
        if (wasPlaying) { chaseLocked(target, outputs); anchorSongUs = target; anchorWallNs = clock() }   // paused/stopped: PLAY sends the chase
        wake()
    }
    fun seekTick(tick: Long) { val t = synchronized(lock) { timeline } ?: return; seekUs(t.usAt(tick)) }
    fun seekBar(bar: Int) { val t = synchronized(lock) { timeline } ?: return; seekTick(t.tickOfBar(bar.coerceAtLeast(1))) }

    fun setMute(channel: Int, mute: Boolean) = synchronized(lock) {
        if (channel !in 0..15 || muted[channel] == mute) return
        muted[channel] = mute
        if (mute) panicLocked(channel..channel, outputs, clearState = false, keepSustainFlag = true)
        else if (sustain[channel]) sendLocked(byteArrayOf((0xB0 or channel).toByte(), 64, 127), outputs)
    }

    /** Tempo override in percent of the file's tempo map (25..300). Re-anchored at the current position: no jump. */
    fun setTempoPercent(p: Int) = synchronized(lock) {
        val v = p.coerceIn(25, 300)
        if (state == SongState.PLAYING) { val now = clock(); anchorSongUs = currentUsLocked(now); anchorWallNs = now }
        tempoPercent = v; wake()
    }

    /**
     * Send every event due at [nowNs]. Returns the wall time of the next event, or -1 when there is nothing to wait for.
     * Events with the same time go out together in merged order.
     */
    fun step(nowNs: Long = clock()): Long = synchronized(lock) {
        val t = timeline ?: return -1
        if (state != SongState.PLAYING) return -1
        val songUs = currentUsLocked(nowNs)
        while (next < t.events.size && t.events[next].us <= songUs) { dispatchLocked(t.events[next], nowNs); next++ }
        if (next >= t.events.size) {
            if (songUs >= t.durationUs) { stopLocked(); message = "End of song"; return -1 }
            return wallNsFor(t.durationUs)
        }
        return wallNsFor(t.events[next].us)
    }

    fun status(nowNs: Long = clock()): SongStatus = synchronized(lock) {
        val t = timeline
        val us = if (t == null) 0 else currentUsLocked(nowNs).coerceIn(0, t.durationUs)
        val tick = t?.tickAt(us) ?: 0
        val tempo = t?.tempoAt(tick)
        val ts = t?.timeSigAt(tick)
        SongStatus(state, title, us, t?.durationUs ?: 0, tick, t?.barBeat(tick), t?.bars ?: 0, tempo?.bpm ?: 0.0,
            (tempo?.bpm ?: 0.0) * tempoPercent / 100.0, tempoPercent, ts?.let { "${it.numerator}/${it.denominator}" } ?: "-", muted.toList(),
            t?.channelsUsed ?: emptySet(), programs.toList(), bankMsb.toList(), lastNoteOn.toList(), outputs.map { it.id }, message)
    }

    /** Song-owned notes still sounding (for tests and diagnostics). */
    fun activeNotes(): Int = synchronized(lock) { active.sum() }

    // ---- scheduler thread ----
    fun start() {
        if (thread != null) return
        thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val nextNs = try { step(clock()) } catch (e: Exception) { -1L }
                val now = clock()
                val wait = if (nextNs < 0) 50_000_000L else (nextNs - now).coerceIn(0, 50_000_000L)
                if (wait > 0) LockSupport.parkNanos(this, wait)
            }
        }, "SongPlayer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }
    fun shutdown() { stop(); thread?.interrupt(); thread = null }
    private fun wake() { thread?.let { LockSupport.unpark(it) } }

    // ---- internals (lock held) ----
    private fun factor() = tempoPercent / 100.0
    private fun currentUsLocked(nowNs: Long): Long =
        if (state == SongState.PLAYING) anchorSongUs + ((nowNs - anchorWallNs) / 1000.0 * factor()).toLong() else pausedUs
    private fun wallNsFor(songUs: Long): Long = anchorWallNs + ((songUs - anchorSongUs) * 1000.0 / factor()).toLong()
    private fun firstIndexAtOrAfterUs(t: SongTimeline, us: Long): Int {
        var lo = 0; var hi = t.events.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (t.events[m].us < us) lo = m + 1 else hi = m }
        return lo
    }

    private fun dispatchLocked(e: SongEvent, nowNs: Long) {
        val ch = e.channel
        when {
            e.isNoteOn -> {
                if (muted[ch]) return
                active[ch * 128 + e.data1]++; lastNoteOn[ch] = nowNs
                sendLocked(e.bytes, outputs)
            }
            e.isNoteOff -> {
                val k = ch * 128 + e.data1
                if (active[k] == 0) return                 // its NOTE ON was never sent (muted / ended by a panic)
                active[k]--
                sendLocked(e.bytes, outputs)
            }
            else -> {
                when (e.command) {
                    0xB0 -> when (e.data1) { 64 -> sustain[ch] = e.data2 >= 64; 0 -> bankMsb[ch] = e.data2 }
                    0xC0 -> programs[ch] = e.data1
                    0xE0 -> bent[ch] = !(e.data1 == 0 && e.data2 == 64)
                }
                sendLocked(e.bytes, outputs)
            }
        }
    }

    private fun sendLocked(bytes: ByteArray, to: List<SongOutput>) { for (o in to) o.send(bytes) }

    /** NOTE OFF for every song-owned note on [channels] (one per instance) and CC64 0 where the song held the sustain. */
    private fun panicLocked(channels: IntRange, to: List<SongOutput>, clearState: Boolean, keepSustainFlag: Boolean = false) {
        for (ch in channels) {
            for (n in 0 until 128) {
                val k = ch * 128 + n
                repeat(active[k]) { sendLocked(byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0), to) }
                if (to === outputs) active[k] = 0
            }
            if (sustain[ch]) { sendLocked(byteArrayOf((0xB0 or ch).toByte(), 64, 0), to); if (!keepSustainFlag && to === outputs) sustain[ch] = false }
            if (clearState && bent[ch]) { sendLocked(byteArrayOf((0xE0 or ch).toByte(), 0, 64), to); bent[ch] = false }
        }
    }

    private fun chaseLocked(us: Long, to: List<SongOutput>) {
        val t = timeline ?: return
        if (us <= 0 || to.isEmpty()) return
        val tick = t.tickAt(us)
        // events exactly at the target are played by the scheduler; the chase covers what happened strictly before it
        for (m in t.chaseBefore(firstTickAtOrAfterUs(t, us, tick))) {
            val st = m[0].toInt() and 0xFF; val ch = st and 0x0F
            when (st and 0xF0) {
                0xB0 -> when (m[1].toInt()) { 64 -> sustain[ch] = m[2] >= 64; 0 -> bankMsb[ch] = m[2].toInt() }
                0xC0 -> programs[ch] = m[1].toInt()
                0xE0 -> bent[ch] = !(m[1].toInt() == 0 && m[2].toInt() == 64)
            }
            if ((st and 0xF0) == 0xB0 && m[1].toInt() == 64 && muted[ch]) continue
            sendLocked(m, to)
        }
    }
    private fun firstTickAtOrAfterUs(t: SongTimeline, us: Long, fallback: Long): Long {
        val i = firstIndexAtOrAfterUs(t, us)
        return if (i < t.events.size) t.events[i].tick else fallback + 1
    }

    private fun stopLocked() {
        val wasActive = state == SongState.PLAYING || state == SongState.PAUSED
        panicLocked(0 until 16, outputs, clearState = true)
        if (timeline != null) state = SongState.STOPPED
        pausedUs = 0; next = 0
        if (wasActive) onStopped?.invoke()
    }
}

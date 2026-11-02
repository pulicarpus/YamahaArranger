package com.yourapp.yamahaarranger.song

import java.util.concurrent.locks.LockSupport

/** A destination for Song MIDI messages (internal Song BASSMIDI stream, MIDI OUT, or a test recorder). One message per call. */
interface SongOutput {
    val id: String
    fun send(bytes: ByteArray)
    /** Only the internal Song synth takes setup SysEx (F0 .. F7). MIDI OUT never does. */
    val acceptsSysEx: Boolean get() = false
}

enum class SongState { EMPTY, STOPPED, PLAYING, PAUSED }

data class SongStatus(
    val state: SongState, val title: String, val positionUs: Long, val durationUs: Long, val tick: Long, val barBeat: BarBeat?,
    val bars: Int, val fileBpm: Double, val effectiveBpm: Double, val tempoPercent: Int, val timeSig: String, val muted: List<Boolean>,
    val channelsUsed: Set<Int>, val programs: List<Int>, val bankMsb: List<Int>, val lastNoteOnNs: List<Long>, val outputs: List<String>,
    val message: String?,
    // ---- V1A.2 mixer (defaults keep older callers compiling) ----
    val overrides: List<ChannelOverride> = List(16) { ChannelOverride() },
    val solo: List<Boolean> = List(16) { false },
    /** muted, or another channel is soloed */
    val silent: List<Boolean> = List(16) { false },
    val bankLsb: List<Int> = List(16) { -1 },
    /** the file's own last CC7 / CC10 / CC64 per channel (-1 = not sent yet) */
    val fileVolume: List<Int> = List(16) { -1 },
    val filePan: List<Int> = List(16) { -1 },
    val filePedal: List<Int> = List(16) { -1 },
    val voiceBind: List<VoiceBind?> = List(16) { null },
    val drumChannels: Set<Int> = setOf(9),
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
 *  - V1A.2 mixer: per-channel user overrides ([ChannelOverride]: voice on the internal synth only, volume, pan, sustain mode) and solo.
 *    Every file message passes [SongMixerRules.transform] per output, so the file can never silently undo a user choice; the full
 *    override state is re-sent after a synth reset, a setup SysEx and every chase. The file's own values stay tracked for display
 *    and for RESET CHANNEL. Overrides are remembered per song title while the app runs (reopening the song restores them;
 *    a different song starts clean).
 *  - setup SysEx (XG / GS / GM, see [SongSysEx]) goes ONLY to outputs with [SongOutput.acceptsSysEx] (the internal synth), never to
 *    MIDI OUT. Before playing from the start or chasing, that synth is reset with GM System On and the setup SysEx before the
 *    position is re-sent, so a new song never inherits the previous song's XG / drum-part / vibrato-depth setup.
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
    private val ov = Array(16) { ChannelOverride() }
    private val solo = BooleanArray(16)
    private val voiceBind = arrayOfNulls<VoiceBind>(16)
    private val bankLsb = IntArray(16) { -1 }
    private val fileCc = Array(16) { IntArray(128) { -1 } }     // the file's own last controller values (before overrides)
    private val saved = HashMap<String, Array<ChannelOverride>>()
    /** Installs user voices in the internal Song synth (see [SongVoiceBinder]); null in pure tests = mapping assumed. */
    @Volatile var voiceBinder: SongVoiceBinder? = null
    /** Called after STOP / end of song (e.g. to let the Style router resend its voices to the external module). */
    @Volatile var onStopped: (() -> Unit)? = null
    @Volatile private var thread: Thread? = null

    fun load(t: SongTimeline, name: String) = synchronized(lock) {
        stopLocked()
        if (title.isNotEmpty()) { if (ov.all { it.isDefault }) saved.remove(title) else saved[title] = ov.copyOf() }
        val restore = saved[name]
        for (ch in 0 until 16) {
            val target = restore?.get(ch) ?: ChannelOverride()
            if (target.voice != ov[ch].voice) {
                val r = bindLocked(ch, target.voice)
                ov[ch] = if (r == VoiceBind.NOT_FOUND) target.copy(voice = null) else target
                voiceBind[ch] = if (target.voice == null) null else r
            } else ov[ch] = target
        }
        timeline = t; title = name; state = SongState.STOPPED; pausedUs = 0; next = 0
        muted.fill(false); solo.fill(false); programs.fill(-1); bankMsb.fill(-1); bankLsb.fill(-1); lastNoteOn.fill(0); message = null
        for (a in fileCc) a.fill(-1)
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
        if (pausedUs <= 0) { resetSynthLocked(outputs); for (o in outputs) applyOverridesLocked(o) }
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
        val before = silentSnapshot(); muted[channel] = mute; silenceChangedLocked(before)
    }

    /** SOLO: while any channel is soloed, every other channel is silent (its Song notes end; it keeps receiving controllers). */
    fun setSolo(channel: Int, on: Boolean) = synchronized(lock) {
        if (channel !in 0..15 || solo[channel] == on) return
        val before = silentSnapshot(); solo[channel] = on; silenceChangedLocked(before)
    }

    // ---------------- V1A.2 mixer ----------------
    /** Use [voice] (a loaded SF2 preset) on [channel] in the internal synth; null = back to the file's voice. MIDI OUT is unchanged. */
    fun setVoice(channel: Int, voice: SongVoice?): VoiceBind = synchronized(lock) {
        if (channel !in 0..15) return VoiceBind.NOT_FOUND
        val r = bindLocked(channel, voice)
        voiceBind[channel] = if (voice == null) null else r
        if (r == VoiceBind.NOT_FOUND) { message = "CH${channel + 1}: ${r.label}"; return r }
        ov[channel] = ov[channel].copy(voice = voice)
        val msgs = if (voice != null) SongMixerRules.voiceSelect(channel, voice) else fileVoiceLocked(channel)
        for (o in outputs) if (o.acceptsSysEx) msgs.forEach(o::send)
        r
    }

    /** CC7 0..127 for [channel] on every output; null = back to the file's volume. */
    fun setVolume(channel: Int, value: Int?) = synchronized(lock) {
        if (channel !in 0..15) return
        ov[channel] = ov[channel].copy(volume = value?.coerceIn(0, 127))
        sendLocked(SongMixerRules.cc(channel, 7, value ?: fileCc[channel][7].takeIf { it >= 0 } ?: 100), liveLocked())
    }

    /** CC10 0..127 (64 = centre) for [channel] on every output; null = back to the file's pan. */
    fun setPan(channel: Int, value: Int?) = synchronized(lock) {
        if (channel !in 0..15) return
        ov[channel] = ov[channel].copy(pan = value?.coerceIn(0, 127))
        sendLocked(SongMixerRules.cc(channel, 10, value ?: fileCc[channel][10].takeIf { it >= 0 } ?: 64), liveLocked())
    }

    /** Sustain pedal policy for [channel]. Leaving FOLLOW releases the pedal now; NOTE OFFs are always played normally. */
    fun setSustainMode(channel: Int, mode: SustainMode) = synchronized(lock) {
        if (channel !in 0..15) return
        ov[channel] = ov[channel].copy(sustain = mode)
        if (mode != SustainMode.FOLLOW) { sustain[channel] = false; sendLocked(SongMixerRules.cc(channel, 64, 0), liveLocked()) }
        else if (fileCc[channel][64] >= 64 && !silentLocked(channel) && (state == SongState.PLAYING || state == SongState.PAUSED)) {
            sustain[channel] = true; sendLocked(SongMixerRules.cc(channel, 64, 127), outputs)
        }
    }

    /** RESET CHANNEL: voice, volume, pan and sustain back to what the file says; mute and solo off. */
    fun resetChannel(channel: Int) = synchronized(lock) {
        if (channel !in 0..15) return
        val before = silentSnapshot(); muted[channel] = false; solo[channel] = false; silenceChangedLocked(before)
        if (ov[channel].voice != null) setVoice(channel, null)
        voiceBind[channel] = null
        if (ov[channel].volume != null) setVolume(channel, null)
        if (ov[channel].pan != null) setPan(channel, null)
        if (ov[channel].sustain != SustainMode.FOLLOW) setSustainMode(channel, SustainMode.FOLLOW)
        ov[channel] = ChannelOverride()
    }

    fun overrides(): List<ChannelOverride> = synchronized(lock) { ov.toList() }

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
            t?.channelsUsed ?: emptySet(), programs.toList(), bankMsb.toList(), lastNoteOn.toList(), outputs.map { it.id }, message,
            ov.toList(), solo.toList(), List(16) { silentLocked(it) }, bankLsb.toList(), List(16) { fileCc[it][7] }, List(16) { fileCc[it][10] },
            List(16) { fileCc[it][64] }, voiceBind.toList(), t?.drumChannels ?: setOf(9))
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
        if (e.isSysEx) {
            // a setup SysEx can reset the synth or change a part's voice / volume: re-assert the user's overrides right after it
            for (o in outputs) if (o.acceptsSysEx) { o.send(e.bytes); applyOverridesLocked(o) }
            return
        }
        val ch = e.channel
        when {
            e.isNoteOn -> {
                if (silentLocked(ch)) return
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
                noteFileStateLocked(e.bytes)
                for (o in outputs) for (m in SongMixerRules.transform(e.bytes, ov[ch], o.acceptsSysEx)) o.send(m)
            }
        }
    }

    /** Track the file's own channel state (display, RESET CHANNEL) and the pedal the SONG actually holds. */
    private fun noteFileStateLocked(m: ByteArray) {
        val st = m[0].toInt() and 0xFF; val ch = st and 0x0F
        when (st and 0xF0) {
            0xB0 -> {
                val n = m[1].toInt(); val v = m[2].toInt(); fileCc[ch][n] = v
                when (n) { 64 -> sustain[ch] = v >= 64 && ov[ch].sustain == SustainMode.FOLLOW; 0 -> bankMsb[ch] = v; 32 -> bankLsb[ch] = v }
            }
            0xC0 -> programs[ch] = m[1].toInt()
            0xE0 -> bent[ch] = !(m[1].toInt() == 0 && m[2].toInt() == 64)
        }
    }

    private fun sendLocked(bytes: ByteArray, to: List<SongOutput>) { for (o in to) o.send(bytes) }

    /**
     * Where a mixer change is sent right away: every output while the Song is playing or paused; only the internal Song synth
     * (its own stream) while stopped, so a stopped Song never touches the external module's channels. PLAY re-sends everything.
     */
    private fun liveLocked() = if (state == SongState.PLAYING || state == SongState.PAUSED) outputs else outputs.filter { it.acceptsSysEx }

    private fun silentLocked(ch: Int) = muted[ch] || (solo.any { it } && !solo[ch])
    private fun silentSnapshot() = BooleanArray(16) { silentLocked(it) }
    /** End the Song notes of channels that just became silent; give the pedal back to channels that just became audible. */
    private fun silenceChangedLocked(before: BooleanArray) {
        for (ch in 0 until 16) {
            val now = silentLocked(ch)
            if (now && !before[ch]) panicLocked(ch..ch, outputs, clearState = false, keepSustainFlag = true)
            else if (!now && before[ch] && sustain[ch]) sendLocked(SongMixerRules.cc(ch, 64, 127), outputs)
        }
    }

    private fun bindLocked(ch: Int, voice: SongVoice?): VoiceBind =
        voiceBinder?.bind(ch, voice) ?: if (voice == null) VoiceBind.CLEARED else VoiceBind.BOUND

    /** The file's own voice for [ch] as far as known (internal synth only). Bank messages only if the file sent them (XG drum mode). */
    private fun fileVoiceLocked(ch: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (bankMsb[ch] >= 0) out += SongMixerRules.cc(ch, 0, bankMsb[ch])
        if (bankLsb[ch] >= 0) out += SongMixerRules.cc(ch, 32, bankLsb[ch])
        out += SongMixerRules.pc(ch, programs[ch].coerceAtLeast(0))
        return out
    }

    /** Re-assert every channel override on one output (after a synth reset, a setup SysEx or a chase). */
    private fun applyOverridesLocked(o: SongOutput) {
        for (ch in 0 until 16) if (!ov[ch].isDefault) SongMixerRules.state(ch, ov[ch], o.acceptsSysEx).forEach(o::send)
    }

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
        val before = firstTickAtOrAfterUs(t, us, tick)
        val synths = to.filter { it.acceptsSysEx }
        if (synths.isNotEmpty()) { resetSynthLocked(synths); for (m in t.sysExBefore(before)) for (o in synths) o.send(m) }
        for (m in t.chaseBefore(before)) {
            val st = m[0].toInt() and 0xFF; val ch = st and 0x0F
            noteFileStateLocked(m)
            if ((st and 0xF0) == 0xB0 && m[1].toInt() == 64 && silentLocked(ch)) continue
            for (o in to) for (x in SongMixerRules.transform(m, ov[ch], o.acceptsSysEx)) o.send(x)
        }
        for (o in to) applyOverridesLocked(o)
    }
    private fun firstTickAtOrAfterUs(t: SongTimeline, us: Long, fallback: Long): Long {
        val i = firstIndexAtOrAfterUs(t, us)
        return if (i < t.events.size) t.events[i].tick else fallback + 1
    }

    /** GM System On to the internal synth only (all Song notes were already ended by the caller's panic, or none sound yet). */
    private fun resetSynthLocked(to: List<SongOutput>) { for (o in to) if (o.acceptsSysEx) o.send(SongSysEx.GM_SYSTEM_ON) }

    private fun stopLocked() {
        val wasActive = state == SongState.PLAYING || state == SongState.PAUSED
        panicLocked(0 until 16, outputs, clearState = true)
        if (timeline != null) state = SongState.STOPPED
        pausedUs = 0; next = 0
        if (wasActive) onStopped?.invoke()
    }
}

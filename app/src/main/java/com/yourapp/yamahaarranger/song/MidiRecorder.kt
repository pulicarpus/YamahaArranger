package com.yourapp.yamahaarranger.song

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A keyboard part's voice as the app plays it (SF2 bank / program of the loaded preset). */
data class RecVoice(val bank: Int, val program: Int, val name: String, val sf2: String = "")

data class RecorderStatus(
    val recording: Boolean, val elapsedUs: Long, val events: Int, val channels: Set<Int>, val hasTake: Boolean, val takeInfo: String,
)

/** A finished recording: a complete SMF Type 1 file plus a summary. */
class RecordedTake(val bytes: ByteArray, val durationUs: Long, val notes: Int, val channels: Set<Int>, val tempoChanges: Int, val title: String) {
    val info get() = "%s: %d:%02d, %d notes, tracks %s, %d tempo change(s), %d bytes".format(title, durationUs / 60_000_000, durationUs / 1_000_000 % 60,
        notes, channels.sorted().joinToString(",") { MidiRecorder.partName(it) }, tempoChanges, bytes.size)
}

/**
 * MIDI RECORDER V1B (KEYBOARD ONLY). Records what the keyboard parts really SOUND (the app's engine calls after split, transpose,
 * octave, touch response, sustain hold and part on/off), on a musical timeline: each event gets a tick from a tempo map
 * (BPM segments anchored on a monotonic clock), PPQ [ppq]. Output: SMF Type 1, track 0 = conductor (name, 4/4, tempo map),
 * one track per part that played notes (RIGHT1 ch1, RIGHT2 ch2, RIGHT3 ch3, LEFT ch4) with its voice (bank / program + a text
 * naming the SF2 preset), NOTE ON/OFF and CC64.
 *
 * Contracts (unit-tested): a NOTE OFF whose NOTE ON was not recorded is dropped (no orphans); STOP / part stop / snapshot close every
 * open note at that tick and release a pressed pedal; End of Track on every track at the stop tick; the file is always valid.
 * Only [channels] are recorded; everything else is ignored (V1C will add the Style channels on the same timeline).
 */
class MidiRecorder(private val clock: () -> Long = System::nanoTime, val ppq: Int = 960) {
    private class Seg(val ns: Long, val tick: Long, val bpm: Double)
    private val lock = Any()
    private var recording = false
    private var channels: Set<Int> = (0..3).toSet()
    private var title = "Take"
    private var startNs = 0L
    private val segs = ArrayList<Seg>()
    private val tempoEv = ArrayList<SmfWriter.Ev>()
    private val ev = Array(16) { ArrayList<SmfWriter.Ev>() }
    private val noteCount = IntArray(16)
    private val active = IntArray(16 * 128)
    private val voiceAtStart = arrayOfNulls<RecVoice>(16)
    private var sustainAtStart = false
    private var sustain = false
    private var count = 0
    private var overflow = false
    private var last: RecordedTake? = null

    companion object {
        const val MAX_EVENTS = 1_000_000
        fun partName(ch: Int) = when (ch) { 0 -> "RIGHT1"; 1 -> "RIGHT2"; 2 -> "RIGHT3"; 3 -> "LEFT"; else -> "CH${ch + 1}" }
        fun usPerQuarter(bpm: Double) = (60_000_000.0 / bpm.coerceIn(10.0, 400.0)).roundToInt()
    }

    val isRecording get() = synchronized(lock) { recording }
    fun lastTake(): RecordedTake? = synchronized(lock) { last }

    fun start(bpm: Double, voices: Map<Int, RecVoice>, sustainOn: Boolean, title: String, channels: Set<Int> = (0..3).toSet()) = synchronized(lock) {
        val now = clock()
        recording = true; this.channels = channels; this.title = title; startNs = now
        segs.clear(); segs += Seg(now, 0, bpm.coerceIn(10.0, 400.0)); tempoEv.clear(); tempoEv += SmfWriter.Ev(0, SmfWriter.tempo(usPerQuarter(bpm)))
        ev.forEach { it.clear() }; noteCount.fill(0); active.fill(0); voiceAtStart.fill(null)
        for ((ch, v) in voices) if (ch in 0..15) voiceAtStart[ch] = v
        sustainAtStart = sustainOn; sustain = sustainOn; count = 0; overflow = false
    }

    private fun tickAt(ns: Long): Long {
        val s = segs.last()
        return s.tick + ((ns - s.ns).coerceAtLeast(0) * s.bpm * ppq / 60_000_000_000.0).roundToLong()
    }
    private fun add(ch: Int, bytes: ByteArray) {
        if (count >= MAX_EVENTS) { overflow = true; return }
        ev[ch] += SmfWriter.Ev(tickAt(clock()), bytes); count++
    }

    fun noteOn(ch: Int, note: Int, velocity01: Float) = synchronized(lock) {
        if (!recording || ch !in channels || note !in 0..127) return
        val v = (velocity01 * 127f).roundToInt().coerceIn(1, 127)
        add(ch, byteArrayOf((0x90 or ch).toByte(), note.toByte(), v.toByte()))
        if (!overflow) { active[ch * 128 + note]++; noteCount[ch]++ }
    }

    fun noteOff(ch: Int, note: Int) = synchronized(lock) {
        if (!recording || ch !in channels || note !in 0..127) return
        val k = ch * 128 + note
        if (active[k] == 0) return                                     // started before RECORD, or already closed: no orphan NOTE OFF
        active[k]--; add(ch, byteArrayOf((0x80 or ch).toByte(), note.toByte(), 0))
    }

    /** A part was stopped without per-note NOTE OFFs (RIGHT part OFF, All Notes Off): close its recorded notes now. */
    fun channelStopped(ch: Int) = synchronized(lock) { if (recording && ch in channels) closeChannel(ch, ev[ch], active, tickAt(clock())) }
    fun allStopped() = synchronized(lock) { if (recording) for (ch in channels) closeChannel(ch, ev[ch], active, tickAt(clock())) }

    /** The keyboard sustain (pedal / SUSTAIN key) applies to every keyboard part. */
    fun sustain(on: Boolean) = synchronized(lock) {
        if (!recording || sustain == on) return
        sustain = on
        for (ch in channels) add(ch, byteArrayOf((0xB0 or ch).toByte(), 64, if (on) 127 else 0))
    }

    /** A voice change during the take: Bank MSB / LSB / Program at this tick, plus a text naming the SF2 preset. */
    fun voice(ch: Int, v: RecVoice) = synchronized(lock) {
        if (!recording || ch !in channels) return
        voiceMessages(ch, v).forEach { add(ch, it) }
    }

    fun tempo(bpm: Double) = synchronized(lock) {
        if (!recording) return
        val b = bpm.coerceIn(10.0, 400.0)
        if (b == segs.last().bpm) return
        val now = clock(); val t = tickAt(now)
        segs += Seg(now, t, b); tempoEv += SmfWriter.Ev(t, SmfWriter.tempo(usPerQuarter(b)))
    }

    /** STOP: close open notes and the pedal at this tick, build the file. */
    fun stop(): RecordedTake? = synchronized(lock) {
        if (!recording) return last
        val take = buildLocked(clock())
        recording = false; last = take; take
    }

    /** The take so far as a complete, valid file (open notes closed at "now"); the recording continues unchanged. */
    fun snapshot(): RecordedTake? = synchronized(lock) { if (recording) buildLocked(clock()) else null }

    fun status(): RecorderStatus = synchronized(lock) {
        RecorderStatus(recording, if (recording) (clock() - startNs) / 1000 else last?.durationUs ?: 0, count,
            (0..15).filter { noteCount[it] > 0 }.toSet(), last != null, last?.info ?: "")
    }

    // ---- internals (lock held) ----
    private fun voiceMessages(ch: Int, v: RecVoice): List<ByteArray> {
        val msb = if (v.bank >= 127) 127 else v.bank.coerceIn(0, 126)
        return listOf(SmfWriter.text(0x01, "YA voice ${partName(ch)}: ${v.name} (${v.sf2} ${v.bank}:${v.program})"),
            byteArrayOf((0xB0 or ch).toByte(), 0, msb.toByte()), byteArrayOf((0xB0 or ch).toByte(), 32, 0),
            byteArrayOf((0xC0 or ch).toByte(), v.program.coerceIn(0, 127).toByte()))
    }

    private fun closeChannel(ch: Int, into: MutableList<SmfWriter.Ev>, act: IntArray, tick: Long) {
        for (n in 0 until 128) { val k = ch * 128 + n; repeat(act[k]) { into += SmfWriter.Ev(tick, byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0)) }; act[k] = 0 }
    }

    private fun buildLocked(endNs: Long): RecordedTake {
        val end = maxOf(tickAt(endNs), (tempoEv.map { it.tick } + ev.flatMap { e -> e.map { it.tick } }).maxOrNull() ?: 0L)
        val conductor = ArrayList<SmfWriter.Ev>()
        conductor += SmfWriter.Ev(0, SmfWriter.text(0x03, title))
        conductor += SmfWriter.Ev(0, SmfWriter.text(0x01, "YamahaArranger MIDI Recorder V1B KEYBOARD ONLY" + if (overflow) " (event limit reached)" else ""))
        conductor += SmfWriter.Ev(0, SmfWriter.timeSig(4, 4))
        conductor += tempoEv
        val tracks = arrayListOf<List<SmfWriter.Ev>>(conductor); val ends = arrayListOf(end)
        val used = (0..15).filter { noteCount[it] > 0 }
        for (ch in used) {
            val t = ArrayList<SmfWriter.Ev>()
            t += SmfWriter.Ev(0, SmfWriter.text(0x03, partName(ch)))
            voiceAtStart[ch]?.let { v -> voiceMessages(ch, v).forEach { t += SmfWriter.Ev(0, it) } }
            if (sustainAtStart) t += SmfWriter.Ev(0, byteArrayOf((0xB0 or ch).toByte(), 64, 127))
            t += ev[ch]
            val act = active.copyOf(); closeChannel(ch, t, act, end)      // a snapshot must not change the live ledger
            if (sustain) t += SmfWriter.Ev(end, byteArrayOf((0xB0 or ch).toByte(), 64, 0))
            tracks += t; ends += end
        }
        val bytes = SmfWriter.write(1, ppq, tracks, ends)
        val s = segs; var us = 0L
        for (i in s.indices) {                                       // duration = sum of tempo segments up to the end tick
            val from = s[i].tick; val to = if (i + 1 < s.size) s[i + 1].tick else end
            us += ((to - from) * 60_000_000.0 / (s[i].bpm * ppq)).roundToLong()
        }
        return RecordedTake(bytes, us, used.sumOf { noteCount[it] }, used.toSet(), tempoEv.size - 1, title)
    }
}

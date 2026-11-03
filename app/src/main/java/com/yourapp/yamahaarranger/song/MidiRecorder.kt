package com.yourapp.yamahaarranger.song

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A part's voice as the app plays it. [bank] = SF2 bank of the loaded preset, or (when [bank14]) the Yamaha 14-bit bank
 * MSB*128+LSB that the Style / registration requested.
 */
data class RecVoice(val bank: Int, val program: Int, val name: String, val sf2: String = "", val bank14: Boolean = false,
                    /** the loaded SF2 preset the live engine really played for this voice (readback), written as a YAMAP route */
                    val src: SrcPreset? = null)

/** A loaded SF2 preset identity that survives a restart: SF2 file name + its bank / program as the app lists them. */
data class SrcPreset(val sf2: String, val bank: Int, val program: Int)

/**
 * YAMAP: in a YA NATIVE take, a text meta per voice change, "YAMAP ch=C dst=MSB/LSB/PC src=BANK/PROGRAM sf2=FILE", telling the SONG
 * player which loaded preset the live engine used for that Bank/Program on that channel (the live engine resolves Yamaha voices
 * across several SF2s; a plain Bank/Program lookup cannot reproduce it). Other players ignore text events.
 */
object YaVoiceMap {
    data class Route(val channel: Int, val msb: Int, val lsb: Int, val program: Int, val src: SrcPreset)
    fun text(ch: Int, msb: Int, lsb: Int, pc: Int, s: SrcPreset) = "YAMAP ch=$ch dst=$msb/$lsb/$pc src=${s.bank}/${s.program} sf2=${s.sf2}"
    private val re = Regex("""^YAMAP ch=(\d+) dst=(\d+)/(\d+)/(\d+) src=(\d+)/(\d+) sf2=(.+)$""")
    fun parse(text: String): Route? = re.matchEntire(text.trim())?.destructured?.let { (c, m, l, p, b, sp, f) ->
        Route(c.toInt(), m.toInt(), l.toInt(), p.toInt(), SrcPreset(f, b.toInt(), sp.toInt())).takeIf { it.channel in 0..15 && it.msb in 0..127 && it.lsb in 0..127 && it.program in 0..127 }
    }
    /** Every YAMAP route of a file (the last one wins for the same channel / Bank / Program). */
    fun parse(f: SmfFile): List<Route> {
        val out = LinkedHashMap<List<Int>, Route>()
        for (t in f.tracks) for (e in t.events) if (e.isMeta && e.metaType == 0x01) {
            val r = parse(String(e.payload ?: continue, Charsets.UTF_8)) ?: continue
            out[listOf(r.channel, r.msb, r.lsb, r.program)] = r
        }
        return out.values.toList()
    }
}

/** V1B = keyboard parts only; V1C = keyboard + every Style part on one timeline. */
enum class RecordMode(val label: String) { KEYBOARD("KEYBOARD ONLY"), FULL("FULL ARRANGER") }

/**
 * File flavour. NATIVE keeps the app's channels (Rhythm1 = ch9, Rhythm2 = ch10, both drums via XG System On + bank MSB 127) and
 * Yamaha banks. GM puts both Rhythm parts on channel 10 (GM drums), bank 0 + program for melodic parts, GM System On, no XG.
 */
enum class ExportFlavor(val label: String) { NATIVE("YA NATIVE (XG)"), GM("GM COMPATIBLE") }

data class RecorderStatus(
    val recording: Boolean, val elapsedUs: Long, val events: Int, val channels: Set<Int>, val hasTake: Boolean, val takeInfo: String,
    val mode: RecordMode = RecordMode.KEYBOARD,
)

/** A finished recording: a complete SMF Type 1 file ([bytes] = NATIVE, [gmBytes] = GM COMPATIBLE) plus a summary. */
class RecordedTake(val bytes: ByteArray, val durationUs: Long, val notes: Int, val channels: Set<Int>, val tempoChanges: Int, val title: String,
                   val mode: RecordMode = RecordMode.KEYBOARD, val gmBytes: ByteArray = bytes) {
    fun bytesFor(f: ExportFlavor) = if (f == ExportFlavor.GM) gmBytes else bytes
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
    private var mode = RecordMode.KEYBOARD
    private val ctlAtStart = Array(16) { IntArray(128) { -1 } }
    private val ctl = Array(16) { IntArray(128) { -1 } }
    private var overflow = false
    private var last: RecordedTake? = null

    companion object {
        const val MAX_EVENTS = 1_000_000
        /** App channel (0-based) -> part. Keyboard 0..3, Style destinations 8..15 (Yamaha order). */
        fun partName(ch: Int) = when (ch) {
            0 -> "RIGHT1"; 1 -> "RIGHT2"; 2 -> "RIGHT3"; 3 -> "LEFT"
            8 -> "Rhythm1"; 9 -> "Rhythm2"; 10 -> "Bass"; 11 -> "Chord1"; 12 -> "Chord2"; 13 -> "Pad"; 14 -> "Phrase1"; 15 -> "Phrase2"
            else -> "CH${ch + 1}"
        }
        fun isRhythm(ch: Int) = ch == 8 || ch == 9
        fun usPerQuarter(bpm: Double) = (60_000_000.0 / bpm.coerceIn(10.0, 400.0)).roundToInt()
    }

    val isRecording get() = synchronized(lock) { recording }
    fun lastTake(): RecordedTake? = synchronized(lock) { last }

    fun start(bpm: Double, voices: Map<Int, RecVoice>, sustainOn: Boolean, title: String, channels: Set<Int> = (0..3).toSet(),
              mode: RecordMode = RecordMode.KEYBOARD, controllers: Map<Int, IntArray> = emptyMap()) = synchronized(lock) {
        val now = clock()
        recording = true; this.channels = channels; this.title = title; startNs = now; this.mode = mode
        for (a in ctlAtStart) a.fill(-1); for (a in ctl) a.fill(-1)
        for ((ch, pairs) in controllers) if (ch in 0..15) { var i = 0; while (i + 1 < pairs.size) { ctlAtStart[ch][pairs[i] and 127] = pairs[i + 1]; ctl[ch][pairs[i] and 127] = pairs[i + 1]; i += 2 } }
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
    /** The engine's global All Notes Off (e.g. Style STOP silences all 16 channels): close every recorded note now. */
    fun allStopped() = synchronized(lock) { if (recording) for (ch in channels) closeChannel(ch, ev[ch], active, tickAt(clock())) }

    /** Mixer controllers of a channel as (number, value) pairs; only changes are recorded. */
    fun controllers(ch: Int, pairs: IntArray) = synchronized(lock) {
        if (!recording || ch !in channels) return
        var i = 0
        while (i + 1 < pairs.size) {
            val n = pairs[i] and 127; val v = pairs[i + 1].coerceIn(0, 127); i += 2
            if (ctl[ch][n] == v) continue
            ctl[ch][n] = v; add(ch, byteArrayOf((0xB0 or ch).toByte(), n.toByte(), v.toByte()))
        }
    }

    /** The keyboard sustain (pedal / SUSTAIN key) applies to every keyboard part. */
    fun sustain(on: Boolean) = synchronized(lock) {
        if (!recording || sustain == on) return
        sustain = on
        for (ch in channels) if (ch < 4) add(ch, byteArrayOf((0xB0 or ch).toByte(), 64, if (on) 127 else 0))   // keyboard parts only
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
        val take = takeLocked(clock())
        recording = false; last = take; take
    }

    /** The take so far as a complete, valid file (open notes closed at "now"); the recording continues unchanged. */
    fun snapshot(): RecordedTake? = synchronized(lock) { if (recording) takeLocked(clock()) else null }

    fun status(): RecorderStatus = synchronized(lock) {
        RecorderStatus(recording, if (recording) (clock() - startNs) / 1000 else last?.durationUs ?: 0, count,
            (0..15).filter { noteCount[it] > 0 }.toSet(), last != null, last?.info ?: "", if (recording) mode else last?.mode ?: mode)
    }

    // ---- internals (lock held) ----
    private fun voiceMessages(ch: Int, v: RecVoice): List<ByteArray> {
        val drum = mode == RecordMode.FULL && isRhythm(ch)
        val msb = when { drum -> 127; v.bank14 -> (v.bank / 128).coerceIn(0, 127); v.bank >= 127 -> 127; else -> v.bank.coerceIn(0, 126) }
        val lsb = if (v.bank14 && !drum) v.bank % 128 else 0
        val src = if (v.bank14) "${v.bank / 128}/${v.bank % 128}" else "${v.bank}"
        val pc = v.program.coerceIn(0, 127)
        return listOfNotNull(SmfWriter.text(0x01, "YA voice ${partName(ch)}: ${v.name} (${v.sf2.ifEmpty { "style" }} $src:${v.program})"),
            v.src?.let { SmfWriter.meta(0x01, YaVoiceMap.text(ch, msb, lsb, pc, it).toByteArray(Charsets.UTF_8)) },
            byteArrayOf((0xB0 or ch).toByte(), 0, msb.toByte()), byteArrayOf((0xB0 or ch).toByte(), 32, lsb.toByte()),
            byteArrayOf((0xC0 or ch).toByte(), pc.toByte()))
    }

    private fun closeChannel(ch: Int, into: MutableList<SmfWriter.Ev>, act: IntArray, tick: Long) {
        for (n in 0 until 128) { val k = ch * 128 + n; repeat(act[k]) { into += SmfWriter.Ev(tick, byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0)) }; act[k] = 0 }
    }

    private fun takeLocked(endNs: Long): RecordedTake {
        val native = buildLocked(endNs, ExportFlavor.NATIVE)
        if (mode == RecordMode.KEYBOARD) return native
        val gm = buildLocked(endNs, ExportFlavor.GM)
        return RecordedTake(native.bytes, native.durationUs, native.notes, native.channels, native.tempoChanges, native.title, mode, gm.bytes)
    }

    /** GM flavour: Rhythm1/Rhythm2 -> channel 10, melodic banks -> 0 (program kept), no XG. NATIVE: unchanged. */
    private fun flavor(m: ByteArray, ch: Int, f: ExportFlavor): ByteArray? {
        if (f == ExportFlavor.NATIVE || mode == RecordMode.KEYBOARD) return m
        val st = m[0].toInt() and 0xFF
        if (st == 0xFF && m[1].toInt() == 0x01 && String(m, Charsets.ISO_8859_1).contains("YAMAP ch=")) return null   // YA-only route
        if (st >= 0xF0) return m
        val out = m.copyOf()
        if (isRhythm(ch)) out[0] = ((st and 0xF0) or 9).toByte()
        if ((st and 0xF0) == 0xB0 && (m[1].toInt() == 0 || m[1].toInt() == 32)) out[2] = 0
        if (isRhythm(ch) && ch == 8 && (st and 0xF0) == 0xC0) return null   // one kit on GM ch10: Rhythm2's
        return out
    }

    private fun buildLocked(endNs: Long, f: ExportFlavor): RecordedTake {
        val end = maxOf(tickAt(endNs), (tempoEv.map { it.tick } + ev.flatMap { e -> e.map { it.tick } }).maxOrNull() ?: 0L)
        val conductor = ArrayList<SmfWriter.Ev>()
        conductor += SmfWriter.Ev(0, SmfWriter.text(0x03, title))
        conductor += SmfWriter.Ev(0, SmfWriter.text(0x01, "YamahaArranger MIDI Recorder " + (if (mode == RecordMode.FULL) "V1C FULL ARRANGER ${f.label}" else "V1B KEYBOARD ONLY") +
            if (overflow) " (event limit reached)" else ""))
        if (mode == RecordMode.FULL) {
            // standard setup messages only (no instrument-private SysEx): NATIVE = XG System On so bank MSB 127 makes Rhythm1 (ch9) a drum part
            conductor += SmfWriter.Ev(0, if (f == ExportFlavor.NATIVE) byteArrayOf(0xF0.toByte(), 0x08, 0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7.toByte())
                                         else byteArrayOf(0xF0.toByte(), 0x05, 0x7E, 0x7F, 0x09, 0x01, 0xF7.toByte()))
        }
        conductor += SmfWriter.Ev(0, SmfWriter.timeSig(4, 4))
        conductor += tempoEv
        val tracks = arrayListOf<List<SmfWriter.Ev>>(conductor); val ends = arrayListOf(end)
        val used = (0..15).filter { noteCount[it] > 0 }
        for (ch in used) {
            val t = ArrayList<SmfWriter.Ev>()
            t += SmfWriter.Ev(0, SmfWriter.text(0x03, partName(ch) + if (f == ExportFlavor.GM && mode == RecordMode.FULL && isRhythm(ch)) " (GM ch10)" else ""))
            voiceAtStart[ch]?.let { v -> voiceMessages(ch, v).forEach { t += SmfWriter.Ev(0, it) } }
            for (n in 0 until 128) if (ctlAtStart[ch][n] >= 0) t += SmfWriter.Ev(0, byteArrayOf((0xB0 or ch).toByte(), n.toByte(), ctlAtStart[ch][n].toByte()))
            if (sustainAtStart && ch < 4) t += SmfWriter.Ev(0, byteArrayOf((0xB0 or ch).toByte(), 64, 127))
            t += ev[ch]
            val act = active.copyOf(); closeChannel(ch, t, act, end)      // a snapshot must not change the live ledger
            if (sustain && ch < 4) t += SmfWriter.Ev(end, byteArrayOf((0xB0 or ch).toByte(), 64, 0))
            tracks += t.mapNotNull { e -> flavor(e.bytes, ch, f)?.let { SmfWriter.Ev(e.tick, it) } }; ends += end
        }
        val bytes = SmfWriter.write(1, ppq, tracks, ends)
        val s = segs; var us = 0L
        for (i in s.indices) {                                       // duration = sum of tempo segments up to the end tick
            val from = s[i].tick; val to = if (i + 1 < s.size) s[i + 1].tick else end
            us += ((to - from) * 60_000_000.0 / (s[i].bpm * ppq)).roundToLong()
        }
        return RecordedTake(bytes, us, used.sumOf { noteCount[it] }, used.toSet(), tempoEv.size - 1, title, mode)
    }
}

package com.yourapp.yamahaarranger.song

/**
 * One playable message at an absolute time: a channel message, or a complete setup SysEx ([isSysEx], bytes F0 .. F7) that
 * passed [SongSysEx.forInternal]. [us] = microseconds from the song start at the file's own tempo map.
 * [index] is the position in the merged, ordered list.
 */
class SongEvent(val index: Int, val tick: Long, val us: Long, val bytes: ByteArray) {
    val isSysEx get() = (bytes[0].toInt() and 0xFF) == 0xF0
    val status get() = bytes[0].toInt() and 0xFF
    val channel get() = status and 0x0F
    val command get() = status and 0xF0
    val data1 get() = bytes[1].toInt() and 0x7F
    val data2 get() = if (bytes.size > 2) bytes[2].toInt() and 0x7F else -1
    val isNoteOn get() = command == 0x90 && data2 > 0
    val isNoteOff get() = command == 0x80 || (command == 0x90 && data2 == 0)
}

/**
 * Which file SysEx may reach the INTERNAL Song synth (never MIDI OUT). Only complete single-packet messages of the standard setup
 * families that BASSMIDI interprets: Universal Non-Realtime GM System On/Off (7E dd 09 ..), Universal Realtime Device Control
 * (7F dd 04 .., master volume / balance), Roland GS parameter set (41 dd 42 12 ..) and Yamaha XG parameter change (43 1n 4C ..).
 * Everything else (manufacturer-private data, Yamaha style / panel messages 43 7E / 43 73, F7 escapes, split packets) is dropped.
 */
object SongSysEx {
    const val MAX_LENGTH = 256
    /** Wire bytes F0 .. F7 for an SMF SysEx event, or null if it must not be forwarded. */
    fun forInternal(e: SmfEvent): ByteArray? {
        val p = e.payload ?: return null
        if (e.status != 0xF0 || p.size < 4 || p.size + 1 > MAX_LENGTH || (p.last().toInt() and 0xFF) != 0xF7) return null
        for (i in 0 until p.size - 1) if ((p[i].toInt() and 0xFF) > 0x7F) return null
        val b0 = p[0].toInt(); val b2 = p[2].toInt(); val b3 = p[3].toInt()
        val ok = when (b0) {
            0x7E -> b2 == 0x09                                   // GM System On / Off
            0x7F -> b2 == 0x04                                   // device control (master volume / balance)
            0x41 -> b2 == 0x42 && b3 == 0x12                     // Roland GS DT1
            0x43 -> (p[1].toInt() and 0xF0) == 0x10 && b2 == 0x4C // Yamaha XG parameter change
            else -> false
        }
        return if (ok) byteArrayOf(0xF0.toByte()) + p else null
    }
    /** GM System On: puts the internal Song synth back to defaults (a new song, or before a chase). */
    val GM_SYSTEM_ON = byteArrayOf(0xF0.toByte(), 0x7E, 0x7F, 0x09, 0x01, 0xF7.toByte())
}

data class TempoPoint(val tick: Long, val usPerQuarter: Int, val us: Long) { val bpm get() = 60_000_000.0 / usPerQuarter }
data class TimeSigPoint(val tick: Long, val numerator: Int, val denominator: Int, val bar: Int)
data class BarBeat(val bar: Int, val beat: Int, val tickInBeat: Int) { override fun toString() = "%03d:%02d".format(bar, beat) }

/**
 * The merged, timed view of an [SmfFile] for playback.
 * Merge order: tick, then NOTE OFF before any other event at the same tick (a note ending and a new one starting on the
 * same key at the same tick must not cut the new one), then track index, then file order.
 * SysEx: all counted in [sysExCount]; only the setup messages accepted by [SongSysEx.forInternal] are part of [events]
 * ([sysExForwarded]), and the player sends those to the internal synth only. Meta events feed the tempo / time-signature maps and names.
 */
class SongTimeline private constructor(
    val ppq: Int, val format: Int, val events: List<SongEvent>, val tempos: List<TempoPoint>, val timeSigs: List<TimeSigPoint>,
    val lastTick: Long, val durationUs: Long, val sysExCount: Int, val trackNames: List<String>, val channelNames: Map<Int, String>,
    val channelsUsed: Set<Int>, val warnings: List<String>, val sysExForwarded: Int = 0,
    /** Channels the file plays as drums: channel 10, bank MSB 126/127 (XG), an XG PART MODE drum or a GS "use for rhythm part" message. */
    val drumChannels: Set<Int> = setOf(9),
) {
    /** Channel messages only (no SysEx). */
    val channelEventCount get() = events.size - sysExForwarded
    fun usAt(tick: Long): Long {
        val t = tempos.lastOrNull { it.tick <= tick } ?: tempos.first()
        return t.us + (tick - t.tick) * t.usPerQuarter / ppq
    }
    fun tickAt(us: Long): Long {
        val t = tempos.lastOrNull { it.us <= us } ?: tempos.first()
        return t.tick + (us - t.us) * ppq / t.usPerQuarter
    }
    fun tempoAt(tick: Long): TempoPoint = tempos.lastOrNull { it.tick <= tick } ?: tempos.first()
    fun timeSigAt(tick: Long): TimeSigPoint = timeSigs.lastOrNull { it.tick <= tick } ?: timeSigs.first()

    /** 1-based bar and beat (beat = time-signature denominator note). */
    fun barBeat(tick: Long): BarBeat {
        val ts = timeSigAt(tick)
        val tpb = ticksPerBeat(ts); val tpbar = tpb * ts.numerator
        val rel = (tick - ts.tick).coerceAtLeast(0)
        return BarBeat(ts.bar + (rel / tpbar).toInt(), (rel % tpbar / tpb).toInt() + 1, (rel % tpb).toInt())
    }
    /** Tick of the start of 1-based [bar]. */
    fun tickOfBar(bar: Int): Long {
        val ts = timeSigs.lastOrNull { it.bar <= bar } ?: timeSigs.first()
        return ts.tick + (bar - ts.bar).toLong() * ticksPerBeat(ts) * ts.numerator
    }
    val bars get() = barBeat(lastTick).bar
    private fun ticksPerBeat(ts: TimeSigPoint) = (ppq.toLong() * 4 / ts.denominator).coerceAtLeast(1)

    /** First index of [events] at or after [tick]. */
    fun indexAt(tick: Long): Int {
        var lo = 0; var hi = events.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (events[m].tick < tick) lo = m + 1 else hi = m }
        return lo
    }

    /**
     * Channel state reached just before [tick] (for SEEK): per channel, in send order: Bank MSB, Bank LSB, Program, then every other
     * controller's last value (sustain included), then the last pitch bend. Never a NOTE ON.
     */
    /** Forwardable setup SysEx strictly before [tick], in file order (re-sent to the internal synth before a chase). */
    fun sysExBefore(tick: Long): List<ByteArray> = events.asSequence().takeWhile { it.tick < tick }.filter { it.isSysEx }.map { it.bytes }.toList()

    fun chaseBefore(tick: Long): List<ByteArray> {
        val msb = IntArray(16) { -1 }; val lsb = IntArray(16) { -1 }; val prog = IntArray(16) { -1 }
        val cc = Array(16) { IntArray(128) { -1 } }; val bend = Array<ByteArray?>(16) { null }
        for (e in events) {
            if (e.tick >= tick) break
            if (e.isSysEx) continue
            val ch = e.channel
            when (e.command) {
                0xB0 -> when (e.data1) { 0 -> msb[ch] = e.data2; 32 -> lsb[ch] = e.data2; else -> if (e.data1 < 120) cc[ch][e.data1] = e.data2 }
                0xC0 -> prog[ch] = e.data1
                0xE0 -> bend[ch] = e.bytes
            }
        }
        val out = ArrayList<ByteArray>()
        for (ch in 0 until 16) {
            if (msb[ch] >= 0) out += byteArrayOf((0xB0 or ch).toByte(), 0, msb[ch].toByte())
            if (lsb[ch] >= 0) out += byteArrayOf((0xB0 or ch).toByte(), 32, lsb[ch].toByte())
            if (prog[ch] >= 0) out += byteArrayOf((0xC0 or ch).toByte(), prog[ch].toByte())
            for (n in 1 until 120) if (n != 32 && cc[ch][n] >= 0) out += byteArrayOf((0xB0 or ch).toByte(), n.toByte(), cc[ch][n].toByte())
            bend[ch]?.let { out += it }
        }
        return out
    }

    companion object {
        private fun drums(channel: List<SmfEvent>, sysEx: List<ByteArray>): Set<Int> {
            val d = HashSet<Int>(); d += 9
            for (e in channel) if (e.command == 0xB0 && e.data1 == 0 && (e.data2 == 127 || e.data2 == 126)) d += e.channel
            for (m in sysEx) {   // F0 43 1n 4C 08 pp 07 dd F7: XG multi part, PART MODE (0 = normal, else drum)
                if (m.size >= 9 && m[1].toInt() == 0x43 && m[3].toInt() == 0x4C && m[4].toInt() == 0x08 && m[6].toInt() == 0x07 && m[5] in 0..15) {
                    if (m[7].toInt() != 0) d += m[5].toInt()
                }
                // F0 41 dd 42 12 40 1x 15 vv ..: GS "use for rhythm part" (x: 0 = part 10, 1..9 = parts 1..9, A..F = parts 11..16)
                if (m.size >= 10 && m[1].toInt() == 0x41 && m[3].toInt() == 0x42 && m[4].toInt() == 0x12 && m[5].toInt() == 0x40 &&
                    (m[6].toInt() and 0xF0) == 0x10 && m[7].toInt() == 0x15 && m[8].toInt() != 0) {
                    val x = m[6].toInt() and 0x0F
                    d += when { x == 0 -> 9; x <= 9 -> x - 1; else -> x }
                }
            }
            return d
        }

        fun from(f: SmfFile): SongTimeline {
            val all = f.tracks.flatMap { it.events }
            // tempo map (any track; Type 1 normally track 0). Same tick: the last one wins.
            val tempoRaw = sortedMapOf<Long, Int>()
            for (e in all.sortedWith(compareBy({ it.tick }, { it.track }, { it.order })))
                if (e.isMeta && e.metaType == 0x51 && (e.payload?.size ?: 0) >= 3) {
                    val p = e.payload!!; val v = ((p[0].toInt() and 0xFF) shl 16) or ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
                    if (v > 0) tempoRaw[e.tick] = v
                }
            if (!tempoRaw.containsKey(0L)) tempoRaw[0L] = 500_000                     // SMF default: 120 BPM until the first tempo event
            val tempos = ArrayList<TempoPoint>()
            for ((tick, v) in tempoRaw) {
                val prev = tempos.lastOrNull()
                val us = if (prev == null) 0L else prev.us + (tick - prev.tick) * prev.usPerQuarter / f.ppq
                tempos += TempoPoint(tick, v, us)
            }
            // time signatures (bar index computed with the previous signature)
            val tsRaw = sortedMapOf<Long, Pair<Int, Int>>()
            for (e in all) if (e.isMeta && e.metaType == 0x58 && (e.payload?.size ?: 0) >= 2) {
                val n = e.payload!![0].toInt() and 0xFF; val dPow = e.payload[1].toInt() and 0xFF
                if (n in 1..64 && dPow in 0..6) tsRaw[e.tick] = n to (1 shl dPow)
            }
            if (!tsRaw.containsKey(0L)) tsRaw[0L] = 4 to 4                               // SMF default: 4/4
            val sigs = ArrayList<TimeSigPoint>()
            for ((tick, nd) in tsRaw) {
                val prev = sigs.lastOrNull()
                val bar = if (prev == null) 1 else {
                    val tpbar = (f.ppq.toLong() * 4 / prev.denominator) * prev.numerator
                    prev.bar + (((tick - prev.tick) + tpbar - 1) / tpbar).toInt()    // a signature change mid-bar starts the next bar
                }
                sigs += TimeSigPoint(tick, nd.first, nd.second, bar)
            }
            fun usAt(tick: Long): Long { val t = tempos.last { it.tick <= tick }; return t.us + (tick - t.tick) * t.usPerQuarter / f.ppq }
            val channelEvents = all.filter { it.isChannel }
            val sysEx = all.mapNotNull { e -> if (e.isSysEx) SongSysEx.forInternal(e)?.let { e to it } else null }
            val wire = HashMap<SmfEvent, ByteArray>(); for ((e, b) in sysEx) wire[e] = b
            val merged = (channelEvents + sysEx.map { it.first })
                .sortedWith(compareBy<SmfEvent>({ it.tick }, { if (it.isNoteOff) 0 else 1 }, { it.track }, { it.order }))
            val events = merged.mapIndexed { i, e -> SongEvent(i, e.tick, usAt(e.tick), wire[e] ?: e.channelBytes()) }
            val lastTick = all.maxOfOrNull { it.tick } ?: 0L
            val names = HashMap<Int, String>()
            for (t in f.tracks) if (t.name.isNotBlank()) {
                val chs = t.events.filter { it.isChannel }.map { it.channel }.toSet()
                if (chs.size == 1) names.putIfAbsent(chs.first(), t.name)
            }
            return SongTimeline(f.ppq, f.format, events, tempos, sigs, lastTick, usAt(lastTick), all.count { it.isSysEx },
                f.tracks.map { it.name }, names, channelEvents.map { it.channel }.toSet(), f.warnings, sysEx.size, drums(channelEvents, sysEx.map { it.second }))
        }
    }
}

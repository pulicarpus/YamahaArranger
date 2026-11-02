package com.yourapp.yamahaarranger.song

import java.io.ByteArrayOutputStream

/**
 * Standard MIDI File writer (pure Kotlin). Writes complete messages without running status (maximum compatibility),
 * one MTrk per track, delta times as variable-length quantities, and exactly one End of Track per track at or after its last event.
 */
object SmfWriter {
    /** One event of a track: absolute [tick] and its complete bytes (channel message, or meta FF type len data). */
    class Ev(val tick: Long, val bytes: ByteArray)

    fun meta(type: Int, data: ByteArray): ByteArray = byteArrayOf(0xFF.toByte(), type.toByte()) + vlq(data.size.toLong()) + data
    fun text(type: Int, s: String): ByteArray = meta(type, s.toByteArray(Charsets.ISO_8859_1).let { if (it.size > 120) it.copyOf(120) else it })
    fun tempo(usPerQuarter: Int): ByteArray = meta(0x51, byteArrayOf((usPerQuarter ushr 16).toByte(), (usPerQuarter ushr 8).toByte(), usPerQuarter.toByte()))
    /** Time signature [num]/[den] (den a power of two), 24 MIDI clocks per click, 8 32nds per quarter. */
    fun timeSig(num: Int, den: Int): ByteArray = meta(0x58, byteArrayOf(num.toByte(), (31 - Integer.numberOfLeadingZeros(den)).toByte(), 24, 8))

    fun vlq(v: Long): ByteArray {
        require(v in 0..0x0FFFFFFF) { "delta $v out of SMF range" }
        val o = ArrayList<Byte>(); var x = v
        o += (x and 0x7F).toByte(); x = x shr 7
        while (x > 0) { o += ((x and 0x7F) or 0x80).toByte(); x = x shr 7 }
        return o.reversed().toByteArray()
    }

    /**
     * SMF Type 1 (or 0 with one track). Events of a track must be sorted by tick (stable order is kept as given).
     * [endTicks] = End of Track tick per track (never before the track's last event).
     */
    fun write(format: Int, ppq: Int, tracks: List<List<Ev>>, endTicks: List<Long>): ByteArray {
        require(format == 0 && tracks.size == 1 || format == 1 && tracks.isNotEmpty()) { "format/tracks" }
        require(ppq in 1..0x7FFF)
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(be32(6)); out.write(be16(format)); out.write(be16(tracks.size)); out.write(be16(ppq))
        for ((i, t) in tracks.withIndex()) {
            val body = ByteArrayOutputStream(); var last = 0L
            for (e in t) { require(e.tick >= last) { "track $i not sorted" }; body.write(vlq(e.tick - last)); body.write(e.bytes); last = e.tick }
            val end = maxOf(last, endTicks.getOrElse(i) { last })
            body.write(vlq(end - last)); body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            val b = body.toByteArray()
            out.write("MTrk".toByteArray()); out.write(be32(b.size)); out.write(b)
        }
        return out.toByteArray()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}

package com.yourapp.yamahaarranger.song

/**
 * Standard MIDI File reader for the SONG player (pure Kotlin, no Android types; unit-tested on the JVM).
 * Type 0 and Type 1; Type 2 and SMPTE time division are rejected with a clear message. Variable-length quantities,
 * running status, meta and SysEx events, unknown chunks (skipped), missing End of Track (tolerated, flagged).
 * Any structural damage throws [SmfException] with the byte offset; it never crashes the caller.
 * The Style parser (app/src/main/cpp/smf_reader.cpp) is not used or changed by this.
 */
class SmfException(message: String, val offset: Int) : Exception("$message (byte $offset)")

/**
 * One event. Channel messages: [status] 0x80..0xEF with [data1]/[data2] (data2 = -1 for 1-data-byte messages).
 * Meta: [status] 0xFF, [metaType], [payload]. SysEx: [status] 0xF0 or 0xF7, [payload] = bytes after the length.
 * [track] and [order] keep the file order for a stable merge.
 */
class SmfEvent(val tick: Long, val track: Int, val order: Int, val status: Int, val data1: Int, val data2: Int,
               val metaType: Int = -1, val payload: ByteArray? = null) {
    val isChannel get() = status in 0x80..0xEF
    val channel get() = if (isChannel) status and 0x0F else -1
    val command get() = if (isChannel) status and 0xF0 else status
    val isNoteOn get() = command == 0x90 && data2 > 0
    val isNoteOff get() = command == 0x80 || (command == 0x90 && data2 == 0)
    val isMeta get() = status == 0xFF
    val isSysEx get() = status == 0xF0 || status == 0xF7
    /** Wire bytes of a channel message (status + 1 or 2 data bytes). */
    fun channelBytes(): ByteArray = if (data2 < 0) byteArrayOf(status.toByte(), data1.toByte()) else byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
    override fun toString() = "SmfEvent(tick=$tick trk=$track st=0x${status.toString(16)} d1=$data1 d2=$data2 meta=$metaType)"
}

class SmfTrack(val index: Int, val name: String, val events: List<SmfEvent>, val endOfTrack: Boolean)

class SmfFile(val format: Int, val ppq: Int, val tracks: List<SmfTrack>, val warnings: List<String>)

object Smf {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_EVENTS = 2_000_000

    fun parse(bytes: ByteArray): SmfFile {
        if (bytes.size > MAX_BYTES) throw SmfException("file larger than ${MAX_BYTES / 1024 / 1024} MB", 0)
        val r = Reader(bytes)
        if (bytes.size < 14 || r.ascii(0, 4) != "MThd") throw SmfException("not a Standard MIDI File (no MThd header)", 0)
        val hdrLen = r.u32(4)
        if (hdrLen < 6 || 8L + hdrLen > bytes.size) throw SmfException("bad header length $hdrLen", 4)
        val format = r.u16(8); val ntrks = r.u16(10); val division = r.u16(12)
        if (format == 2) throw SmfException("SMF Type 2 (independent sequences) is not supported in V1", 8)
        if (format !in 0..1) throw SmfException("unknown SMF format $format", 8)
        if (division and 0x8000 != 0) throw SmfException("SMPTE time division is not supported in V1", 12)
        if (division == 0) throw SmfException("time division (PPQ) is 0", 12)
        if (ntrks == 0) throw SmfException("the file declares 0 tracks", 10)
        if (format == 0 && ntrks != 1) throw SmfException("SMF Type 0 must have exactly 1 track (has $ntrks)", 10)
        val warnings = ArrayList<String>()
        val tracks = ArrayList<SmfTrack>()
        var pos = 8 + hdrLen.toInt()
        var total = 0
        while (pos < bytes.size && tracks.size < ntrks) {
            if (pos + 8 > bytes.size) throw SmfException("truncated chunk header", pos)
            val id = r.ascii(pos, 4); val len = r.u32(pos + 4)
            val start = pos + 8
            if (start + len > bytes.size) throw SmfException("chunk '$id' of $len bytes runs past the end of the file (truncated)", pos)
            if (id == "MTrk") {
                val t = parseTrack(r, tracks.size, start, start + len.toInt(), total)
                total += t.events.size
                if (!t.endOfTrack) warnings += "track ${t.index}: no End of Track event"
                tracks += t
            } else warnings += "skipped unknown chunk '$id' ($len bytes)"
            pos = start + len.toInt()
        }
        if (tracks.size < ntrks) throw SmfException("header declares $ntrks tracks, file contains ${tracks.size} (truncated)", pos)
        return SmfFile(format, division, tracks, warnings)
    }

    private fun parseTrack(r: Reader, index: Int, start: Int, end: Int, before: Int): SmfTrack {
        val events = ArrayList<SmfEvent>()
        var p = start; var tick = 0L; var running = -1; var name = ""; var eot = false; var order = 0
        while (p < end) {
            val (delta, np) = r.vlq(p, end); p = np
            tick += delta
            if (p >= end) throw SmfException("track $index: event missing after delta-time", p)
            var status = r.u8(p)
            if (status < 0x80) {
                if (running < 0) throw SmfException("track $index: data byte without status (running status not set)", p)
                status = running                          // running status: this byte is data1
            } else p++
            when {
                status in 0x80..0xEF -> {
                    running = status
                    val cmd = status and 0xF0
                    val d1 = r.data(p, end, index); p++
                    val d2 = if (cmd == 0xC0 || cmd == 0xD0) -1 else { val v = r.data(p, end, index); p++; v }
                    events += SmfEvent(tick, index, order++, status, d1, d2)
                }
                status == 0xFF -> {
                    running = -1                          // meta and SysEx cancel running status
                    if (p >= end) throw SmfException("track $index: truncated meta event", p)
                    val type = r.u8(p); p++
                    val (len, np2) = r.vlq(p, end); p = np2
                    if (p + len > end) throw SmfException("track $index: meta 0x${type.toString(16)} length $len runs past the track", p)
                    val payload = r.slice(p, len.toInt()); p += len.toInt()
                    if (type == 0x03 && name.isEmpty()) name = String(payload, Charsets.ISO_8859_1).trim()
                    events += SmfEvent(tick, index, order++, 0xFF, 0, 0, type, payload)
                    if (type == 0x2F) { eot = true; break }
                }
                status == 0xF0 || status == 0xF7 -> {
                    running = -1
                    val (len, np2) = r.vlq(p, end); p = np2
                    if (p + len > end) throw SmfException("track $index: SysEx length $len runs past the track", p)
                    events += SmfEvent(tick, index, order++, status, 0, 0, -1, r.slice(p, len.toInt())); p += len.toInt()
                }
                else -> throw SmfException("track $index: system message 0x${status.toString(16)} is not valid in a MIDI file", p - 1)
            }
            if (before + events.size > MAX_EVENTS) throw SmfException("more than $MAX_EVENTS events", p)
        }
        return SmfTrack(index, name, events, eot)
    }

    private class Reader(val b: ByteArray) {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int) = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
        fun ascii(i: Int, n: Int) = String(b, i, n, Charsets.ISO_8859_1)
        fun slice(i: Int, n: Int) = b.copyOfRange(i, i + n)
        fun data(i: Int, end: Int, track: Int): Int {
            if (i >= end) throw SmfException("track $track: truncated channel message", i)
            val v = u8(i); if (v > 0x7F) throw SmfException("track $track: data byte 0x${v.toString(16)} > 0x7F", i)
            return v
        }
        /** Variable-length quantity, at most 4 bytes. Returns (value, next position). */
        fun vlq(start: Int, end: Int): Pair<Long, Int> {
            var v = 0L; var i = start
            repeat(4) {
                if (i >= end) throw SmfException("truncated variable-length quantity", i)
                val c = u8(i); i++
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v to i
            }
            throw SmfException("variable-length quantity longer than 4 bytes", start)
        }
    }
}

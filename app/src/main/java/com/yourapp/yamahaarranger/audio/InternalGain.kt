package com.yourapp.yamahaarranger.audio

import java.io.File

/**
 * INTERNAL OUTPUT GAIN (pure Kotlin helpers). The gain itself lives in native `internal_gain.h` (BASSMIDI mix only, before the +/-1.0 clamp);
 * MIDI OUT, velocity, Style/CASM and SF2 envelopes are not involved. 0 dB is the legacy path and the default.
 */
object InternalGain {
    const val MAX_DB = 12
    val STEPS = listOf(0, 3, 6, 9, 12)

    fun clamp(db: Int): Int = db.coerceIn(0, MAX_DB)

    class Report(val db: Float, val peakPreDb: Float, val peakOutDb: Float, val rmsDb: Float, val samples: Long, val overSamples: Long, val limitedSamples: Long) {
        /** Without the knee these samples would have been hard-clipped (|x*gain| > 1.0). */
        val wouldClip: Boolean get() = overSamples > 0
        val limiting: Boolean get() = limitedSamples > 0
        fun summary(): String = "peak %.1f dBFS (pre-knee %.1f) · RMS %.1f dBFS · over=%d knee=%d".format(peakOutDb, peakPreDb, rmsDb, overSamples, limitedSamples)
    }

    /** Parses `IGAIN db=.. lin=.. peakPreDb=.. peakOutDb=.. rmsDb=.. samples=.. overSamples=.. limitedSamples=..`; null for anything else. */
    fun parse(line: String): Report? {
        if (!line.startsWith("IGAIN ")) return null
        val kv = line.trim().split(' ').drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return try {
            Report(kv.getValue("db").toFloat(), kv.getValue("peakPreDb").toFloat(), kv.getValue("peakOutDb").toFloat(), kv.getValue("rmsDb").toFloat(),
                kv.getValue("samples").toLong(), kv.getValue("overSamples").toLong(), kv.getValue("limitedSamples").toLong())
        } catch (_: Exception) { null }
    }
}

/** One-line text file (dB, integer). Missing/unreadable/garbage = 0 dB, i.e. the legacy gain. */
class InternalGainStore(private val file: File) {
    fun read(): Int = try { if (file.isFile) InternalGain.clamp(file.readText().trim().toInt()) else 0 } catch (_: Exception) { 0 }
    fun write(db: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(InternalGain.clamp(db).toString())
        if (!tmp.renameTo(file)) { file.writeText(InternalGain.clamp(db).toString()); tmp.delete() }
    }
}

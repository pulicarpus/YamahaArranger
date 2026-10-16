package com.yourapp.yamahaarranger.audio

/**
 * RIGHT voice A/B diagnostic (pure Kotlin, no Android types).
 *
 * The velocity test only changes the velocity handed to the internal synthesizer for RIGHT NOTE_ON.
 * The velocity sent to MIDI OUT and every non-RIGHT path is untouched. The mode is process-local:
 * never persisted, so it is ORIGINAL again after every app start.
 */
enum class RightVelocityMode(val label: String) {
    ORIGINAL("A: ORIGINAL"),
    FIXED_100("B: FIXED 100")
}

object RightVelocityTest {
    const val FIXED_VELOCITY = 100

    @Volatile
    var mode: RightVelocityMode = RightVelocityMode.ORIGINAL
        private set

    fun set(newMode: RightVelocityMode) { mode = newMode }
    fun reset() { mode = RightVelocityMode.ORIGINAL }

    /** Velocity (1..127) for the internal synth. Applies only to a NOTE_ON being started now. */
    fun internalVelocity127(original127: Int, current: RightVelocityMode = mode): Int =
        if (current == RightVelocityMode.FIXED_100) FIXED_VELOCITY else original127

    fun internalVelocity(original: Float, original127: Int, current: RightVelocityMode = mode): Float =
        if (current == RightVelocityMode.FIXED_100) FIXED_VELOCITY / 127f else original

    /** MIDI OUT velocity is, by contract, always the original PSR-E343 value. */
    @Suppress("UNUSED_PARAMETER")
    fun midiOutVelocity127(original127: Int, current: RightVelocityMode = mode): Int = original127
}

/** Histogram of NOTE_ON velocities as received from the keyboard, per RIGHT layer. */
class RightVelocityMonitor {
    class Stats(val count: Long, val last: Int, val min: Int, val median: Int, val max: Int)

    private val lock = Any()
    private val bins = Array(3) { IntArray(128) }
    private val counts = LongArray(3)
    private val lasts = IntArray(3)

    fun record(layer: Int, velocity127: Int) {
        if (layer !in 0..2) return
        val v = velocity127.coerceIn(0, 127)
        synchronized(lock) { bins[layer][v]++; counts[layer]++; lasts[layer] = v }
    }

    fun stats(layer: Int): Stats = synchronized(lock) {
        if (layer !in 0..2 || counts[layer] == 0L) return Stats(0, 0, -1, -1, -1)
        val h = bins[layer]
        val min = h.indexOfFirst { it > 0 }
        val max = h.indexOfLast { it > 0 }
        val target = (counts[layer] + 1) / 2
        var seen = 0L
        var median = -1
        for (i in 0..127) { seen += h[i]; if (seen >= target) { median = i; break } }
        Stats(counts[layer], lasts[layer], min, median, max)
    }

    fun reset() = synchronized(lock) {
        for (h in bins) h.fill(0)
        counts.fill(0); lasts.fill(0)
    }

    companion object { val shared = RightVelocityMonitor() }
}

/** One RIGHT part as reported by native `rightVoiceDiagnostic()` ("RVD key=value ..."). */
data class RightVoiceDiagRow(val fields: Map<String, String>) {
    operator fun get(key: String): String = fields[key].orEmpty()
    fun int(key: String): Int = fields[key]?.toIntOrNull() ?: -1
    val part: String get() = this["part"]
    val status: String get() = this["status"]
}

object RightVoiceDiagParser {
    /** Values are `key=value`, or `key='quoted value'` (quotes may contain spaces). */
    fun parseLine(line: String): RightVoiceDiagRow? {
        if (!line.startsWith("RVD ")) return null
        val out = LinkedHashMap<String, String>()
        var i = 4
        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            val eq = line.indexOf('=', i)
            if (eq < 0) break
            val key = line.substring(i, eq)
            i = eq + 1
            if (i < line.length && line[i] == '\'') {
                val end = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }
                out[key] = line.substring(i + 1, end)
                i = end + 1
            } else {
                val end = line.indexOf(' ', i).let { if (it < 0) line.length else it }
                out[key] = line.substring(i, end)
                i = end
            }
        }
        return RightVoiceDiagRow(out)
    }

    fun parse(text: String): List<RightVoiceDiagRow> = text.lineSequence().mapNotNull(::parseLine).toList()
}

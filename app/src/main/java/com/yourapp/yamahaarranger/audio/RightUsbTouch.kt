package com.yourapp.yamahaarranger.audio

import java.io.File
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * TOUCH RESPONSE for the physical keyboard (USB-MIDI) -> RIGHT 1/2/3 only.
 *
 * It maps the NOTE_ON velocity handed to the internal synthesizer. It never touches MIDI OUT, the Virtual Keyboard, LEFT,
 * ACMP/chord, Style, Rhythm or Bass velocity. ORIGINAL is the identity and the default. Every curve keeps 127 -> 127,
 * never produces 0 (velocity 0 is a NOTE_OFF and is decided before this runs) and is monotonic. The 128-entry tables are
 * precomputed, so the cost on the note path is one array read.
 */
enum class TouchResponse(val label: String, private val gamma: Double) {
    ORIGINAL("Original", 1.0),
    SOFT("Soft", 0.60),     // light touch reaches a louder velocity
    MEDIUM("Medium", 0.80), // mild lift
    HARD("Hard", 1.25);     // needs a firmer touch

    private val table: IntArray = IntArray(128) { v ->
        if (v <= 0) 0
        else if (gamma == 1.0) v
        else Math.round(127.0 * Math.pow(v / 127.0, gamma)).toInt().coerceIn(1, 127)
    }

    /** Velocity 1..127 for the internal synth; 0 stays 0 (callers never pass it). */
    fun map(velocity127: Int): Int = table[velocity127.coerceIn(0, 127)]

    companion object {
        val DEFAULT = ORIGINAL
        fun parse(text: String?): TouchResponse = values().firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

object RightUsbTouch {
    @Volatile var response: TouchResponse = TouchResponse.DEFAULT
}

/** Where a RIGHT NOTE_ON came from. Only [USB] is subject to Touch Response. */
enum class RightInputSource { USB, OTHER }

/** One-line text file; atomic replace. Unreadable or unknown content means the default (ORIGINAL). */
class TouchResponseStore(private val file: File) {
    fun read(): TouchResponse = try { if (file.isFile) TouchResponse.parse(file.readText()) else TouchResponse.DEFAULT } catch (_: Exception) { TouchResponse.DEFAULT }
    fun write(value: TouchResponse) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(value.name)
        if (!tmp.renameTo(file)) { file.writeText(value.name); tmp.delete() }
    }
}

/**
 * Source-tagged velocity and controller probe. Fixed arrays of atomics: no allocation, no locks, so it is safe to call from
 * the USB callback, the keyboard routing and the Virtual Keyboard without disturbing the audio thread.
 * Stages for the USB path: RAW (every NOTE_ON on any channel, as parsed from the bytes) -> IN (delivered to RIGHT routing)
 * -> FWD (velocity actually handed to the engine, after Touch Response). The Virtual Keyboard records IN and FWD.
 */
object RightInputProbe {
    private const val DUR_BUCKETS = 5 // <100 ms, <250, <500, <1000, >=1000
    private val usbRaw = AtomicIntegerArray(128)
    private val usbIn = AtomicIntegerArray(128)
    private val usbFwd = AtomicIntegerArray(128)
    private val vkIn = AtomicIntegerArray(128)
    private val vkFwd = AtomicIntegerArray(128)
    private val channelMsgs = AtomicIntegerArray(16)
    private val ccCount = AtomicIntegerArray(128)
    private val ccLast = AtomicIntegerArray(128).also { for (i in 0 until 128) it.set(i, -1) }
    private val misc = AtomicIntegerArray(8) // 0 noteOn,1 noteOff,2 noteOn vel0,3 pitchBend,4 chanPressure,5 polyAftertouch,6 programChange,7 other
    private val downAt = AtomicLongArray(128)
    private val durations = AtomicIntegerArray(DUR_BUCKETS)

    fun usbMessage(type: Int, channel: Int, d1: Int, d2: Int) {
        channelMsgs.incrementAndGet(channel and 15)
        when (type) {
            0x90 -> if (d2 > 0) { misc.incrementAndGet(0); usbRaw.incrementAndGet(d2 and 127) } else misc.incrementAndGet(2)
            0x80 -> misc.incrementAndGet(1)
            0xB0 -> { ccCount.incrementAndGet(d1 and 127); ccLast.set(d1 and 127, d2) }
            0xE0 -> misc.incrementAndGet(3)
            0xA0 -> misc.incrementAndGet(5)
            else -> misc.incrementAndGet(7)
        }
    }

    fun usbSingleData(type: Int, channel: Int) {
        channelMsgs.incrementAndGet(channel and 15)
        if (type == 0xC0) misc.incrementAndGet(6) else if (type == 0xD0) misc.incrementAndGet(4) else misc.incrementAndGet(7)
    }

    fun routed(source: RightInputSource, sourceNote: Int, velocity127: Int) {
        if (source == RightInputSource.USB) { usbIn.incrementAndGet(velocity127.coerceIn(0, 127)); downAt.set(sourceNote and 127, System.nanoTime()) }
        else vkIn.incrementAndGet(velocity127.coerceIn(0, 127))
    }

    fun forwarded(source: RightInputSource, velocity127: Int) {
        if (source == RightInputSource.USB) usbFwd.incrementAndGet(velocity127.coerceIn(0, 127)) else vkFwd.incrementAndGet(velocity127.coerceIn(0, 127))
    }

    /** Called from the NOTE_OFF of the physical keyboard: records how long the key was held. */
    fun usbNoteOff(sourceNote: Int) {
        val t = downAt.getAndSet(sourceNote and 127, 0L)
        if (t == 0L) return
        val ms = (System.nanoTime() - t) / 1_000_000
        durations.incrementAndGet(when { ms < 100 -> 0; ms < 250 -> 1; ms < 500 -> 2; ms < 1000 -> 3; else -> 4 })
    }

    class Hist(val n: Int, val last: Int, val min: Int, val median: Int, val max: Int, val mean: Double)

    private fun hist(a: AtomicIntegerArray): Hist {
        var n = 0; var min = -1; var max = -1; var sum = 0L
        for (i in 0 until 128) { val c = a.get(i); if (c > 0) { n += c; sum += c.toLong() * i; if (min < 0) min = i; max = i } }
        if (n == 0) return Hist(0, -1, -1, -1, -1, 0.0)
        var seen = 0; var med = -1
        for (i in 0 until 128) { seen += a.get(i); if (seen >= (n + 1) / 2) { med = i; break } }
        return Hist(n, -1, min, med, max, sum.toDouble() / n)
    }

    fun usbRawHist() = hist(usbRaw)
    fun usbInHist() = hist(usbIn)
    fun usbFwdHist() = hist(usbFwd)
    fun vkInHist() = hist(vkIn)
    fun vkFwdHist() = hist(vkFwd)

    fun report(): String {
        fun h(name: String, x: Hist) = "$name n=${x.n} min=${x.min} med=${x.median} max=${x.max} mean=${"%.1f".format(x.mean)}"
        val cc = (0 until 128).filter { ccCount.get(it) > 0 }.joinToString(" ") { "CC$it x${ccCount.get(it)} last=${ccLast.get(it)}" }.ifEmpty { "none received" }
        val ch = (0 until 16).filter { channelMsgs.get(it) > 0 }.joinToString(" ") { "ch${it + 1}:${channelMsgs.get(it)}" }.ifEmpty { "none" }
        val d = (0 until DUR_BUCKETS).joinToString(" ") { durations.get(it).toString() }
        return buildString {
            appendLine("RIGHT INPUT PROBE touch=${RightUsbTouch.response.label}")
            appendLine("USB " + h("RAW noteOn vel", usbRawHist()))
            appendLine("USB " + h("IN (to RIGHT routing)", usbInHist()))
            appendLine("USB " + h("FWD (to engine)", usbFwdHist()))
            appendLine("VK  " + h("IN", vkInHist()) + " | " + h("FWD", vkFwdHist()))
            appendLine("USB messages: noteOn=${misc.get(0)} noteOff=${misc.get(1)} noteOn(vel0)=${misc.get(2)} pitchBend=${misc.get(3)} chanPressure=${misc.get(4)} polyAT=${misc.get(5)} programChange=${misc.get(6)} other=${misc.get(7)}")
            appendLine("USB channels: $ch")
            appendLine("USB controllers: $cc")
            append("USB key hold <100ms/<250/<500/<1000/>=1000: $d")
        }
    }

    fun reset() {
        for (a in listOf(usbRaw, usbIn, usbFwd, vkIn, vkFwd, channelMsgs, ccCount, misc, durations)) for (i in 0 until a.length()) a.set(i, 0)
        for (i in 0 until 128) { ccLast.set(i, -1); downAt.set(i, 0L) }
    }
}

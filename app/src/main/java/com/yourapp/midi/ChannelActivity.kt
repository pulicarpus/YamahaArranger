package com.yourapp.midi

import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Per-part NOTE activity for the Mixer indicators. It is NOT an audio level: BASSMIDI mixes every part into one stream, so no per-part audio level exists.
 *  - [Source.NOTES]   : every NOTE_ON handed to the sound engine/router for that channel (the internal BASSMIDI path), with its velocity.
 *  - [Source.MIDI_OUT]: every NOTE_ON actually WRITTEN to the external module (only while MIDI OUT is enabled and connected).
 * The value shown is the last NOTE_ON velocity (0..1) decaying with time; nothing is derived from fader values, and no MIDI message is created or changed.
 * Fixed atomic arrays: no allocation or locks on the note path.
 */
object ChannelActivity {
    enum class Source { NOTES, MIDI_OUT }

    private val velocity = Array(2) { AtomicIntegerArray(16) }
    private val atNanos = Array(2) { AtomicLongArray(16) }

    fun noteOn(source: Source, channel: Int, velocity127: Int, nowNanos: Long = System.nanoTime()) {
        if (channel !in 0..15 || velocity127 <= 0) return
        velocity[source.ordinal].set(channel, velocity127.coerceIn(1, 127))
        atNanos[source.ordinal].set(channel, nowNanos)
    }

    /** 0..1: last velocity scaled, decaying exponentially (time constant [tauMs]); 0 when nothing was played yet. */
    fun level(source: Source, channel: Int, nowNanos: Long = System.nanoTime(), tauMs: Double = 450.0): Float {
        if (channel !in 0..15) return 0f
        val v = velocity[source.ordinal].get(channel)
        if (v == 0) return 0f
        val ageMs = (nowNanos - atNanos[source.ordinal].get(channel)) / 1_000_000.0
        if (ageMs < 0) return v / 127f
        val l = (v / 127.0) * Math.exp(-ageMs / tauMs)
        return if (l < 0.01) 0f else l.toFloat()
    }

    /** A part counts as "active" while its indicator is visibly above zero. */
    fun active(source: Source, channel: Int, nowNanos: Long = System.nanoTime()): Boolean = level(source, channel, nowNanos) > 0f

    fun reset() { for (s in 0..1) for (c in 0 until 16) { velocity[s].set(c, 0); atNanos[s].set(c, 0L) } }
}

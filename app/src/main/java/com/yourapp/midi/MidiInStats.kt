package com.yourapp.midi

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Passive MIDI IN diagnostics: what the keyboard actually sent, per MIDI channel, and whether it was played or ignored by the channel filter.
 * It answers "never received" vs "received but filtered" vs "received and played" without changing anything on the playing path.
 * Fixed atomic arrays, written from the MIDI thread.
 */
class MidiInStats {
    private val on = AtomicIntegerArray(16)
    private val off = AtomicIntegerArray(16)
    private val ignoredOn = AtomicIntegerArray(16)
    private val lowest = AtomicIntegerArray(16).also { for (i in 0 until 16) it.set(i, 128) }
    private val highest = AtomicIntegerArray(16).also { for (i in 0 until 16) it.set(i, -1) }
    private val last = AtomicIntegerArray(16).also { for (i in 0 until 16) it.set(i, -1) }

    fun noteOn(channel: Int, note: Int, played: Boolean) {
        if (channel !in 0..15) return
        on.incrementAndGet(channel); if (!played) ignoredOn.incrementAndGet(channel)
        last.set(channel, note)
        if (note < lowest.get(channel)) lowest.set(channel, note)
        if (note > highest.get(channel)) highest.set(channel, note)
    }
    fun noteOff(channel: Int) { if (channel in 0..15) off.incrementAndGet(channel) }
    fun reset() { for (c in 0 until 16) { on.set(c, 0); off.set(c, 0); ignoredOn.set(c, 0); lowest.set(c, 128); highest.set(c, -1); last.set(c, -1) } }

    fun noteOnCount(channel: Int) = on.get(channel)
    fun ignoredCount(channel: Int) = ignoredOn.get(channel)
    /** Channels (1-based) that sent at least one NOTE_ON, with their counts, note range and whether those notes were played. */
    fun lines(split: Int): List<String> = (0 until 16).filter { on.get(it) > 0 }.map { c ->
        val n = on.get(c); val ig = ignoredOn.get(c)
        val lo = lowest.get(c); val hi = highest.get(c)
        val zone = when { hi <= split -> "only LEFT-area keys"; lo > split -> "only RIGHT-area keys"; else -> "both areas" }
        "ch${c + 1}: $n NOTE_ON, notes $lo..$hi ($zone) — " + if (ig == 0) "played" else if (ig == n) "IGNORED by the channel filter" else "$ig of $n ignored"
    }
    fun summary(split: Int): String {
        val l = lines(split)
        return if (l.isEmpty()) "MIDI IN: no NOTE_ON received from the keyboard yet" else "MIDI IN received:\n" + l.joinToString("\n")
    }
}

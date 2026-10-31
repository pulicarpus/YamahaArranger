package com.yourapp.midi

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Which keys of the PHYSICAL keyboard (USB MIDI IN) are down, for the on-screen keyboard to mirror. Pure state, written from the MIDI thread, read by the UI.
 * It records the ORIGINAL note numbers exactly as received (before transpose, split routing, CASM or octave shifts) and never feeds anything back to MIDI OUT,
 * the engine or the Brain. NOTE_ON velocity 0 is a NOTE_OFF. Fixed arrays of atomics: no allocation, no locks, safe on the MIDI callback thread.
 * Touch input is a separate source (the Virtual Keyboard controller's own pressed mask); the screen shows the union, so releasing one source never clears the other.
 */
class LiveKeyState {
    private val velocity = AtomicIntegerArray(128)   // 0 = up
    private val changes = AtomicInteger()

    /** Bumped on every visible change; the UI polls it instead of redrawing per event. */
    val version: Int get() = changes.get()

    fun noteOn(note: Int, vel: Int) {
        if (note !in 0..127) return
        if (vel <= 0) { noteOff(note); return }
        val v = vel.coerceIn(1, 127)
        if (velocity.getAndSet(note, v) != v) changes.incrementAndGet()  // a repeated press at the same velocity changes nothing visible
    }

    fun noteOff(note: Int) {
        if (note !in 0..127) return
        if (velocity.getAndSet(note, 0) != 0) changes.incrementAndGet()
    }

    /** Device removed / connection closed / reset: nothing may stay lit. */
    fun clear() {
        var any = false
        for (n in 0 until 128) if (velocity.getAndSet(n, 0) != 0) any = true
        if (any) changes.incrementAndGet()
    }

    fun isDown(note: Int): Boolean = note in 0..127 && velocity.get(note) > 0
    fun velocityOf(note: Int): Int = if (note in 0..127) velocity.get(note) else 0
    fun downCount(): Int { var c = 0; for (n in 0 until 128) if (velocity.get(n) > 0) c++; return c }
    fun downNotes(): List<Int> = (0 until 128).filter { velocity.get(it) > 0 }

    /** Bit i is set when note [lowNote] + i is down, for i in 0 until [count] (count <= 63). Notes outside the window are simply not in the mask. */
    fun mask(lowNote: Int, count: Int): Long {
        var m = 0L
        for (i in 0 until count.coerceIn(0, 63)) if (isDown(lowNote + i)) m = m or (1L shl i)
        return m
    }

    /** Lowest and highest key that is down, or null: used to scroll the keyboard to the played area. */
    fun range(): IntRange? {
        var lo = -1; var hi = -1
        for (n in 0 until 128) if (velocity.get(n) > 0) { if (lo < 0) lo = n; hi = n }
        return if (lo < 0) null else lo..hi
    }

    companion object {
        /** Touch OR physical: one source going up never switches off a key the other one still holds. */
        fun combine(touchMask: Long, physicalMask: Long): Long = touchMask or physicalMask
    }
}

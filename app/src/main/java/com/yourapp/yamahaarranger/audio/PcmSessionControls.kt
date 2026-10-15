package com.yourapp.yamahaarranger.audio

/** Observational state only. Render never acquires this lock. Order: controls then native snapshot. */
object PcmSessionControls {
    data class Snapshot(val muteMask: Int, val knownMask: Int, val changes: Long, val lastChangeNs: Long)
    private var mask = 0
    private var changes = 0L
    private var lastChange = System.nanoTime()
    @Synchronized fun observeMute(channel: Int, muted: Boolean) {
        if (channel !in 0..15) return
        val next = if (muted) mask or (1 shl channel) else mask and (1 shl channel).inv()
        if (next != mask) { mask = next; changes++; lastChange = System.nanoTime() }
    }
    @Synchronized fun <T> snapshot(action: (Snapshot) -> T): T = action(Snapshot(mask,65535,changes,lastChange))
}

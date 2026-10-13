package com.yourapp.midi

/** MIDI transport boundary. Snapshots contain already-effective BASS mixer values.
 * MASTER is a scoped CC11 trim (100 = unity), never a universal CC7 or panic.
 * All writers, replay and note ownership are serialized outside the audio callback.
 */
class MidiMixerOutput(private val write: (ByteArray) -> Boolean) {
    private val values = Array(16) { intArrayOf(127, 64, 127, 40, 0) }
    private val controllers = intArrayOf(7, 10, 11, 91, 93)
    // Expression starts at MIDI/BASS unity. Other defaults are not transmitted
    // until a native mixer write has actually supplied their values.
    private val known = Array(16) { BooleanArray(5).also { it[2] = true } }
    private val sent = Array(16) { IntArray(128) { -1 } }
    private val notes = Array(16) { IntArray(128) }
    private val pendingOff = BooleanArray(16)
    private val programs = arrayOfNulls<IntArray>(16)
    private var channels = (0..3).toSet()
    private var mutedChannels = emptySet<Int>()
    private var enabled = false
    private var connected = false
    private var master = 100

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (!value) releaseOwnedNotes()
        enabled = value
        invalidateSent()
        if (value && connected) { releaseOwnedNotes(); replay() }
    }
    @Synchronized fun setConnected(value: Boolean) {
        if (!value) releaseOwnedNotes()
        connected = value
        if (!value) { notes.forEach { it.fill(0) }; pendingOff.fill(false) }
        invalidateSent()
        if (value && enabled) replay()
    }
    @Synchronized fun setChannels(styleChannels: Set<Int>) {
        require(styleChannels.all { it in 4..15 })
        val next = (0..3).toSet() + styleChannels
        (channels - next).forEach(::releaseNotes)
        val added = next - channels
        channels = next
        if (active()) added.sorted().forEach { replayChannel(it) }
    }
    @Synchronized fun setMutedChannels(muted: Set<Int>) {
        require(muted.all { it in 0..15 })
        val changed = (mutedChannels - muted) + (muted - mutedChannels)
        mutedChannels = muted
        if (active()) changed.intersect(channels).sorted().forEach { apply(it) }
    }
    @Synchronized fun update(channel: Int, pairs: IntArray) {
        require(channel in 0..15 && pairs.size % 2 == 0)
        for (i in pairs.indices step 2) {
            val index = controllers.indexOf(pairs[i])
            require(index >= 0)
            values[channel][index] = pairs[i + 1].coerceIn(0, 127)
            known[channel][index] = true
        }
        if (active() && channel in channels) apply(channel)
    }
    @Synchronized fun setMaster(value: Int) {
        master = value.coerceIn(0, 100) // Native MASTER also saturates above unity.
        if (active()) channels.sorted().forEach { apply(it) }
    }
    @Synchronized fun program(channel: Int, program: Int, msb: Int, lsb: Int) {
        require(channel in 0..15)
        programs[channel] = intArrayOf(program.coerceIn(0, 127), msb.coerceIn(0, 127), lsb.coerceIn(0, 127))
        if (active() && channel in channels) replayChannel(channel)
    }
    @Synchronized fun noteOn(channel: Int, note: Int, velocity: Int) {
        if (!active() || channel !in channels) return
        if (pendingOff[channel]) releaseNotes(channel)
        if (channel in mutedChannels || pendingOff[channel]) return
        val n = note.coerceIn(0, 127)
        if (write(byteArrayOf((0x90 or channel).toByte(), n.toByte(), velocity.coerceIn(1, 127).toByte()))) notes[channel][n]++
    }
    @Synchronized fun noteOff(channel: Int, note: Int) {
        if (!active() || channel !in channels) return
        val n = note.coerceIn(0, 127)
        // Never filter NOTE_OFF by the current mute/solo gain.
        if (off(channel, n)) {
            notes[channel][n] = (notes[channel][n] - 1).coerceAtLeast(0)
            if (notes[channel].all { it == 0 }) pendingOff[channel] = false
        }
    }
    private fun active() = enabled && connected
    private fun expression(channel: Int) =
        if (channel in 0..3 && channel in mutedChannels) 0 else (values[channel][2] * master + 50) / 100
    private fun volume(channel: Int) =
        if (channel >= 4 && channel in mutedChannels) 0 else values[channel][0]
    private fun off(ch: Int, note: Int) = write(byteArrayOf((0x80 or ch).toByte(), note.toByte(), 0))
    private fun releaseNotes(ch: Int) {
        if (!active()) return
        for (note in 0..127) repeat(notes[ch][note]) {
            if (off(ch, note)) notes[ch][note]--
        }
        pendingOff[ch] = notes[ch].any { it > 0 }
    }
    private fun releaseOwnedNotes() { channels.sorted().forEach(::releaseNotes) }
    private fun invalidateSent() { sent.forEach { it.fill(-1) } }
    private fun apply(ch: Int, force: Boolean = false) {
        if (ch in mutedChannels || pendingOff[ch]) releaseNotes(ch)
        if (pendingOff[ch] && ch !in mutedChannels) return // Retry offs before restoring audible gain.
        controllers.forEachIndexed { index, cc ->
            if (!known[ch][index] && !(cc == 7 && ch >= 4 && ch in mutedChannels)) return@forEachIndexed
            val value = when(cc) { 7 -> volume(ch); 11 -> expression(ch); else -> values[ch][index] }
            if (force || sent[ch][cc] != value) {
                if (write(byteArrayOf((0xB0 or ch).toByte(), cc.toByte(), value.toByte()))) sent[ch][cc] = value
            }
        }
    }
    private fun replayChannel(ch: Int) {
        programs[ch]?.let { p ->
            write(byteArrayOf((0xB0 or ch).toByte(), 0, p[1].toByte()))
            write(byteArrayOf((0xB0 or ch).toByte(), 32, p[2].toByte()))
            write(byteArrayOf((0xC0 or ch).toByte(), p[0].toByte()))
        }
        apply(ch, force = true)
    }
    private fun replay() { channels.sorted().forEach(::replayChannel) }
}

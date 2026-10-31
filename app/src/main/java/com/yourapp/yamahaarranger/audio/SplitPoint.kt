package com.yourapp.yamahaarranger.audio

import java.io.File

/**
 * Keyboard split point and MIDI IN channel settings (pure Kotlin).
 * Rule, identical to the baseline routing (`midiNote > splitNote` is RIGHT): a key AT the split point belongs to the LEFT area, every key above it to the RIGHT area.
 * Note names use the C4 = 60 convention, so the default 54 is F#3.
 */
object SplitPoint {
    /** E343 range C2..C7 = 36..96. At least one key must stay in each area, so the split lies on 36..95. */
    const val MIN = 36
    const val MAX = 95
    const val DEFAULT = 54

    enum class Zone { LEFT, RIGHT }

    fun clamp(note: Int): Int = note.coerceIn(MIN, MAX)
    fun zoneOf(note: Int, split: Int): Zone = if (note > split) Zone.RIGHT else Zone.LEFT
    fun step(split: Int, semitones: Int): Int = clamp(split + semitones)

    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun name(note: Int): String = NAMES[((note % 12) + 12) % 12] + (Math.floorDiv(note, 12) - 1)
    fun describe(split: Int): String = "LEFT ≤ ${name(split)}  ·  RIGHT ≥ ${name(split + 1)}"
}

/** Where a key press came from; SET BY KEY consumes the next press from either. */
enum class KeySource { USB_MIDI, TOUCH }

/**
 * SET BY KEY: armed, the NEXT note-on (USB MIDI or on-screen keyboard) becomes the split point and is NOT played; its note-off is swallowed too, so nothing
 * sounds or sticks. Notes pressed before arming keep their normal ownership and release normally.
 */
class SplitByKey {
    @Volatile var armed = false
        private set
    private val swallowed = Array(KeySource.values().size) { BooleanArray(128) }

    fun arm() { armed = true }
    fun cancel() { armed = false }

    /** Returns the new split point when this press set it (the caller must not play the note), otherwise null. */
    @Synchronized fun onPress(source: KeySource, note: Int): Int? {
        if (!armed || note !in 0..127) return null
        armed = false
        swallowed[source.ordinal][note] = true
        return SplitPoint.clamp(note)
    }

    /** True when this release belongs to a press that was swallowed (do not forward it). */
    @Synchronized fun onRelease(source: KeySource, note: Int): Boolean {
        if (note !in 0..127 || !swallowed[source.ordinal][note]) return false
        swallowed[source.ordinal][note] = false
        return true
    }
}

/**
 * Persisted keyboard input settings: `split=<note>` and `channels=<bitmask of MIDI IN channels played as keyboard>`.
 * Missing / unreadable / out-of-range values mean the baseline: split 54 (F#3) and MIDI channel 1 only.
 */
data class KeyboardInputSettings(val split: Int = SplitPoint.DEFAULT, val extraChannels: Int = 0) {
    companion object {
        /** Extra channels offered (2..8 = indices 1..7); channel 1 is the primary one, channels 9..16 are Style output of the keyboard and are never played. */
        const val EXTRA_MASK = 0xFE
        fun sanitize(s: KeyboardInputSettings) = KeyboardInputSettings(SplitPoint.clamp(s.split), s.extraChannels and EXTRA_MASK)
    }
}

class KeyboardInputSettingsStore(private val file: File) {
    fun read(): KeyboardInputSettings = try {
        if (!file.isFile) KeyboardInputSettings() else {
            val kv = file.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val split = kv["split"]?.toIntOrNull()?.takeIf { it in SplitPoint.MIN..SplitPoint.MAX } ?: SplitPoint.DEFAULT
            val channels = kv["channels"]?.toIntOrNull()?.let { it and KeyboardInputSettings.EXTRA_MASK } ?: 0
            KeyboardInputSettings(split, channels)
        }
    } catch (_: Exception) { KeyboardInputSettings() }

    fun write(settings: KeyboardInputSettings) {
        val s = KeyboardInputSettings.sanitize(settings)
        val text = "split=${s.split}\nchannels=${s.extraChannels}\n"
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }
}

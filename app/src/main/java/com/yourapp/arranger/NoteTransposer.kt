package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord

/**
 * Note Transposer — style pattern ikut chord user.
 *
 * Fitur:
 *  - Root transposition (normalized -6..+6)
 *  - Chord quality snap untuk melodic part
 *  - Bass snap ke ROOT
 *  - Range protection per channel type
 */
object NoteTransposer {

    private const val STYLE_ROOT_PITCH_CLASS = 0

    // ═══ Overload 1: simple ═══
    fun transpose(patternNote: Int, chord: DetectedChord): Int =
        transposeInternal(patternNote, chord, channel = 0, isBassPart = false)

    // ═══ Overload 2: isBassPart Boolean ═══
    fun transpose(patternNote: Int, chord: DetectedChord, isBassPart: Boolean): Int =
        transposeInternal(patternNote, chord, channel = 0, isBassPart = isBassPart)

    // ═══ Overload 3: channel Int ═══
    fun transpose(patternNote: Int, chord: DetectedChord, channel: Int): Int =
        transposeInternal(
            patternNote, chord, channel,
            isBassPart = (channel == 10 || channel == 11)
        )

    // ═══ Logika inti ═══
    private fun transposeInternal(
        patternNote: Int,
        chord: DetectedChord,
        channel: Int,
        isBassPart: Boolean
    ): Int {
        // 1) Root delta normalized
        var rootDelta = chord.rootNote - STYLE_ROOT_PITCH_CLASS
        while (rootDelta > 6) rootDelta -= 12
        while (rootDelta < -6) rootDelta += 12

        val shifted = patternNote + rootDelta

        // 2) Pitch class relative
        val pitchClass = ((shifted % 12) + 12) % 12
        val relativeToRoot = ((pitchClass - chord.rootNote) + 12) % 12

        // 3) Chord quality snap
        val corrected = if (isBassPart) {
            // Bass: snap ke ROOT
            shifted + (0 - relativeToRoot)
        } else {
            val templateIntervals = chord.quality.intervalsFromRoot.map { it % 12 }.toSet()
            if (relativeToRoot in templateIntervals) {
                shifted
            } else {
                val nearestInterval = templateIntervals.minByOrNull { interval ->
                    val diff = (interval - relativeToRoot + 12) % 12
                    minOf(diff, 12 - diff)
                } ?: relativeToRoot
                shifted + (nearestInterval - relativeToRoot)
            }
        }

        var result = corrected

        // 4) Range protection PER CHANNEL TYPE
        when {
            // Bass (ch10, ch11) → C2-G3 (36-55)
            channel == 10 || channel == 11 -> {
                while (result > 55) result -= 12
                while (result < 36) result += 12
            }
            // Chord (ch12, ch13) → C3-C5 (48-72)
            channel == 12 || channel == 13 -> {
                while (result > 72) result -= 12
                while (result < 48) result += 12
            }
            // Phrase (ch14, ch15) → C4-C6 (60-84)
            channel == 14 || channel == 15 -> {
                while (result > 84) result -= 12
                while (result < 60) result += 12
            }
            // Default (ch0-7) → C3-C5 (48-72)
            channel in 0..7 -> {
                while (result > 72) result -= 12
                while (result < 48) result += 12
            }
            // Ch8, ch9 (drum) → tidak di-transpose
            channel == 8 || channel == 9 -> {
                // skip
            }
        }

        // 5) Clamp MIDI
        while (result < 0) result += 12
        while (result > 127) result -= 12

        return result
    }
}
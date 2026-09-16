package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord

/**
 * Note Transposer — style pattern mengikuti chord user.
 *
 * Fitur:
 *  - Root transposition (geser sesuai root chord, normalized -6..+6)
 *  - Chord quality snap untuk melodic/chord part
 *  - Bass snap ke ROOT + range protection (C2..G3)
 *
 * Ada 3 overload biar kompatibel dengan berbagai caller.
 */
object NoteTransposer {

    private const val STYLE_ROOT_PITCH_CLASS = 0

    // ═════════════════════════════════════════════════════
    // Overload 1: tanpa parameter tambahan (backward compat)
    // ═════════════════════════════════════════════════════
    fun transpose(patternNote: Int, chord: DetectedChord): Int =
        transposeInternal(patternNote, chord, isBassPart = false)

    // ═════════════════════════════════════════════════════
    // Overload 2: dengan isBassPart Boolean
    // ═════════════════════════════════════════════════════
    fun transpose(
        patternNote: Int,
        chord: DetectedChord,
        isBassPart: Boolean
    ): Int =
        transposeInternal(patternNote, chord, isBassPart)

    // ═════════════════════════════════════════════════════
    // Overload 3: dengan channel Int (konversi ke isBassPart)
    // ═════════════════════════════════════════════════════
    fun transpose(
        patternNote: Int,
        chord: DetectedChord,
        channel: Int
    ): Int {
        val isBass = (channel == 10 || channel == 11)
        return transposeInternal(patternNote, chord, isBass)
    }

    // ═════════════════════════════════════════════════════
    // Logika inti
    // ═════════════════════════════════════════════════════
    private fun transposeInternal(
        patternNote: Int,
        chord: DetectedChord,
        isBassPart: Boolean
    ): Int {
        // 1) Root delta, normalize -6..+6
        var rootDelta = chord.rootNote - STYLE_ROOT_PITCH_CLASS
        while (rootDelta > 6) rootDelta -= 12
        while (rootDelta < -6) rootDelta += 12

        val shifted = patternNote + rootDelta

        // 2) Pitch class relative ke root
        val pitchClass = ((shifted % 12) + 12) % 12
        val relativeToRoot = ((pitchClass - chord.rootNote) + 12) % 12

        val corrected = if (isBassPart) {
            // Bass: snap ke ROOT (bukan 3rd/5th)
            shifted + (0 - relativeToRoot)
        } else {
            // Melodic: snap ke chord quality intervals
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

        // 3) Bass range protection: C2 (36) .. G3 (55)
        if (isBassPart) {
            while (result > 55) result -= 12
            while (result < 36) result += 12
        }

        // 4) Clamp MIDI range
        while (result < 0) result += 12
        while (result > 127) result -= 12

        return result
    }
}
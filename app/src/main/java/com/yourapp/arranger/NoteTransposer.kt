package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord

/**
 * Transposes a style part's notes (written against the style's own
 * reference chord, almost always C major/C major7) to match the chord the
 * user is currently playing.
 *
 * This is a deliberate simplification of Yamaha's real NTT (Note
 * Transposition Table) / NTR (Note Transposition Rule) system, which uses
 * per-note rules from the proprietary CASM chunk (root-fixed vs
 * root-transposed per part, guide-tone handling, etc.) — see
 * StyleParser's TODO. What's implemented here instead:
 *
 *   1. Shift by (targetRoot - sourceRoot) semitones.
 *   2. If the target chord quality differs from major (e.g. minor, 7th),
 *      re-map the shifted note onto the nearest scale tone of the target
 *      chord quality's interval set, so a Bass/Chord part still outlines
 *      the right chord instead of just a transposed C-major line.
 *
 * This covers the common Main-style case (bass + chord comping following
 * major/minor/7th changes) reasonably well; extended chords (9/11/13) and
 * rhythm/drum parts should bypass this entirely (see ArrangerBrain, which
 * only transposes Bass/Chord/Pad/Phrase parts, not Rhythm).
 */
object NoteTransposer {

    private const val SOURCE_ROOT_PITCH_CLASS = 0 // styles are authored in C

    fun transpose(midiNote: Int, targetChord: DetectedChord): Int {
        val semitoneShift = ((targetChord.rootNote - SOURCE_ROOT_PITCH_CLASS) + 12) % 12
        val shifted = midiNote + semitoneShift

        val templateIntervals = targetChord.quality.intervalsFromRoot.map { it % 12 }.toSet()
        val shiftedPitchClass = ((shifted % 12) + 12) % 12
        val relativeToRoot = ((shiftedPitchClass - targetChord.rootNote) + 12) % 12

        if (relativeToRoot in templateIntervals) return shifted

        // Snap to the nearest interval in the chord template so parts that
        // outline thirds/sevenths follow the chord quality (e.g. a major
        // third bends to a minor third when the user plays a minor chord).
        val nearest = templateIntervals.minByOrNull { interval ->
            val diff = (interval - relativeToRoot + 12) % 12
            minOf(diff, 12 - diff)
        } ?: relativeToRoot
        val correction = nearest - relativeToRoot
        return shifted + correction
    }
}

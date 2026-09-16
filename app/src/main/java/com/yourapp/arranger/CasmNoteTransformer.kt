package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.CasmPolicyModel

/**
 * Applies the musical part of Yamaha CASM to a style note before it is sent
 * to the destination MIDI channel.
 *
 * Ctb2/Ctab encodes the transform as NTR/NTT/HighKey/NoteLimit/RTR.  The
 * parser preserves those values; this class turns the common Yamaha modes
 * into deterministic note transforms. Unknown mode values deliberately fall
 * back to root-relative transposition instead of dropping a part.
 */
object CasmNoteTransformer {

    fun transform(note: Int, chord: DetectedChord, policy: CasmPolicyModel): Int? {
        if (note !in 0..127) return null

        val sourceRoot = policy.sourceChordRoot.coerceIn(0, 11)
        val rootDelta = floorSemitoneDelta(sourceRoot, chord.rootNote)
        val ntrNote = when (policy.ntr and 0x7f) {
            0 -> note + rootDelta
            1 -> fifthRelativeTranspose(note, sourceRoot, chord.rootNote)
            else -> note + rootDelta
        }

        val nttNote = when (policy.ntt and 0x7f) {
            0 -> ntrNote // bypass
            1 -> melodic(ntrNote, chord, rootDelta)
            2 -> chordal(ntrNote, chord)
            3 -> bass(ntrNote, chord, policy.bassOn)
            4 -> ntrNote // parallel/chord-voicing preserving mode
            else -> ntrNote
        }

        val highKeyAdjusted = applyHighKey(nttNote, policy.highKey, chord.rootNote)
        if (highKeyAdjusted !in policy.noteLimitLow..policy.noteLimitHigh) return null
        return highKeyAdjusted.coerceIn(0, 127)
    }

    private fun floorSemitoneDelta(sourceRoot: Int, targetRoot: Int): Int {
        var d = (targetRoot - sourceRoot) % 12
        if (d > 6) d -= 12
        if (d < -6) d += 12
        return d
    }

    private fun fifthRelativeTranspose(note: Int, sourceRoot: Int, targetRoot: Int): Int {
        // Fifth-transpose keeps the phrase near its original register while
        // moving the tonal center through the closest perfect-fifth path.
        val rootDelta = floorSemitoneDelta(sourceRoot, targetRoot)
        val fifthDelta = rootDelta * 7
        val candidate = note + fifthDelta
        return wrapNear(candidate, note, 12)
    }

    private fun melodic(note: Int, chord: DetectedChord, rootDelta: Int): Int {
        // Melodic NTT preserves the interval from the source root. Keep the
        // same octave whenever possible; the chord root supplies the target.
        return note + rootDelta
    }

    private fun chordal(note: Int, chord: DetectedChord): Int {
        val intervals = chord.quality.intervalsFromRoot
        if (intervals.isEmpty()) return note
        val pitchFromRoot = floorMod(note - chord.rootNote, 12)
        val nearest = intervals.minByOrNull { circularDistance(pitchFromRoot, it % 12) } ?: 0
        val octave = Math.floorDiv(note - chord.rootNote, 12)
        return chord.rootNote + octave * 12 + nearest
    }

    private fun bass(note: Int, chord: DetectedChord, bassOn: Boolean): Int {
        if (!bassOn) return note
        val target = chord.bassNote.coerceIn(0, 11)
        val octave = Math.floorDiv(note, 12)
        return target + octave * 12
    }

    private fun applyHighKey(note: Int, highKey: Int, targetRoot: Int): Int {
        val key = highKey.coerceIn(0, 11)
        if (key == 0) return note

        var result = note
        val guard = 12
        var count = 0
        while (result % 12 > key && count++ < guard) result -= 12
        while (result < targetRoot - 6 && count++ < guard) result += 12
        return result
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b) % 12
        return minOf(d, 12 - d)
    }

    private fun floorMod(value: Int, mod: Int): Int = ((value % mod) + mod) % mod

    private fun wrapNear(value: Int, reference: Int, octave: Int): Int {
        var v = value
        while (v - reference > octave / 2) v -= octave
        while (reference - v > octave / 2) v += octave
        return v
    }
}

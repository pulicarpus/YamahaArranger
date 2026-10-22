package com.yourapp.yamahaarranger.chord

/**
 * Master Transpose target chord. Root and bass move by [semitones] modulo 12 (never by an octave); the quality is kept.
 * A zero shift returns the same instance so untransposed chords stay bit-identical to the detector output.
 * The display name is dropped for a shifted chord because it would still name the played chord.
 */
fun DetectedChord.transposedBy(semitones: Int): DetectedChord {
    if (semitones == 0) return this
    return DetectedChord(
        rootNote = Math.floorMod(rootNote + semitones, 12),
        bassNote = Math.floorMod(bassNote + semitones, 12),
        quality = quality
    )
}

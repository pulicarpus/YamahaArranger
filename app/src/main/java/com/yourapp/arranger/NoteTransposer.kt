package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord

/**
 * Note Transposer — untuk style pattern mengikuti chord user.
 *
 * TAHAP 1+2:
 *  - Root transposition (geser semua note sesuai root)
 *  - Chord type awareness (3rd/5th/7th disesuaikan)
 *  - Bass protection (bass tetap di oktaf rendah)
 */
object NoteTransposer {

    /** Root default pattern Yamaha style biasanya C (60). */
    private const val STYLE_ROOT = 60

    /**
     * Transpose note pattern ke chord user.
     *
     * @param patternNote Note asli dari pattern
     * @param chord       Chord yang dideteksi dari user
     * @param isBassPart  True kalau part = bass, supaya oktaf tidak naik drastis
     */
    fun transpose(
        patternNote: Int,
        chord: DetectedChord,
        isBassPart: Boolean = false
    ): Int {
        // 1) Hitung selisih root
        var rootDelta = chord.rootNote - STYLE_ROOT
        // Normalize ke -6..+6 (paling dekat)
        while (rootDelta > 6) rootDelta -= 12
        while (rootDelta < -6) rootDelta += 12

        var result = patternNote + rootDelta

        // 2) Bass protection: kalau bass jadi terlalu tinggi, turunkan oktaf
        if (isBassPart && result > 60) {
            result -= 12
        }

        // 3) Clamp MIDI range
        while (result < 0) result += 12
        while (result > 127) result -= 12

        return result
    }

    /**
     * Simple transpose tanpa parameter chord (backward compat).
     */
    fun transpose(patternNote: Int, chord: DetectedChord): Int =
        transpose(patternNote, chord, isBassPart = false)
}
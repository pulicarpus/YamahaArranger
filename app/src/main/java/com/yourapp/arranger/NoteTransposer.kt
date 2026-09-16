package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord

/**
 * Simple root transposition — TAHAP 1.
 *
 * Semua note pattern di-transpose sejauh selisih root chord user
 * dengan root chord "asli" style (default C = 60).
 *
 * Chord type (major/minor/7th) BELUM di-handle di sini —
 * 3rd/5th masih asli dari pattern.
 */
object NoteTransposer {

    /** Root note default pattern — biasanya C (60) di style Yamaha. */
    private const val STYLE_ROOT = 60

    /**
     * Transpose 1 note pattern sesuai chord user.
     * @param patternNote Note asli dari pattern style
     * @param chord Chord yang dideteksi dari user
     * @return Note yang sudah di-transpose
     */
    fun transpose(patternNote: Int, chord: DetectedChord): Int {
        // Hitung selisih dari root style ke root chord user
        val rootDelta = chord.rootNote - STYLE_ROOT

        // Transpose note pattern
        var result = patternNote + rootDelta

        // Clamp ke range MIDI 0-127
        while (result < 0) result += 12
        while (result > 127) result -= 12

        return result
    }
}
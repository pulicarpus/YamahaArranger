package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord

object NoteTransposer {

    private const val STYLE_ROOT = 60

    fun transpose(
        patternNote: Int,
        chord: DetectedChord,
        channel: Int = 0
    ): Int {
        // 1) Root delta normalized -6..+6
        var rootDelta = chord.rootNote - STYLE_ROOT
        while (rootDelta > 6) rootDelta -= 12
        while (rootDelta < -6) rootDelta += 12

        var result = patternNote + rootDelta

        // 2) Bass channels (10, 11) — paksa oktaf audible
        if (channel == 10 || channel == 11) {
            // Bass harus di range C2 (36) sampai C4 (60)
            while (result < 36) result += 12
            while (result > 60) result -= 12
        }

        // 3) Chord channels (12, 13) — range C3 (48) sampai C6 (84)
        if (channel == 12 || channel == 13) {
            while (result < 48) result += 12
            while (result > 84) result -= 12
        }

        // 4) Clamp MIDI range
        if (result < 0) result = 0
        if (result > 127) result = 127

        return result
    }

    fun transpose(patternNote: Int, chord: DetectedChord): Int =
        transpose(patternNote, chord, channel = 0)
}
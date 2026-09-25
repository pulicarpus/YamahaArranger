package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.CasmPolicyModel

/** Yamaha SFF2 CASM note conversion. */
object CasmNoteTransformer {

    fun transform(note: Int, chord: DetectedChord, policy: CasmPolicyModel): Int? {
        if (note !in 0..127) return null
        if (policy.noteLimitLow > policy.noteLimitHigh) return null

        val sourceRoot = policy.sourceChordRoot.coerceIn(0, 11)
        val targetRoot = chord.rootNote.coerceIn(0, 11)
        val rootDelta = targetRoot - sourceRoot
        val ntr = policy.ntr and 0x7f
        val ntt = policy.ntt and 0x7f

        // Root Trans is a direct source-root -> play-root transposition.
        // Do not force the interval into +/-6 semitones: Yamaha applies the
        // octave decision afterwards through HIGH KEY.
        val base = when (ntr) {
            0 -> note + rootDelta
            1 -> note
            2 -> guitarFallback(note, chord, sourceRoot)
            3 -> note
            else -> note + rootDelta
        }

        val converted = when (ntt) {
            0 -> base
            1 -> melody(base, note, chord, sourceRoot, ntr)
            2 -> chordal(base, note, chord, sourceRoot, ntr)
            3 -> bass(base, note, chord, policy.bassOn, ntr)
            4 -> melodicMinor(base, chord, sourceRoot)
            5 -> harmonicMinor(base, chord, sourceRoot)
            else -> base
        }

        // Yamaha HIGH KEY changes the octave of the entire converted note when
        // the chord root crosses the configured upper root limit. It must not
        // be applied independently to each note's pitch class.
        val highKeyAdjusted = applyHighKey(converted, targetRoot, policy.highKey, ntr)

        // Yamaha NOTE LIMIT does not mute notes outside the range. It moves
        // them by octaves to the nearest octave that fits the configured range.
        return applyNoteLimit(highKeyAdjusted, policy.noteLimitLow, policy.noteLimitHigh)
    }

    private fun chordal(
        base: Int,
        original: Int,
        chord: DetectedChord,
        sourceRoot: Int,
        ntr: Int
    ): Int {
        val intervals = chord.quality.intervalsFromRoot
        if (intervals.isEmpty()) return base

        /*
         * Yamaha documents ROOT FIXED as keeping each chord note as close as
         * possible to its previous range. The canonical example is:
         *
         *   C3-E3-G3  ->  C3-F3-A3
         *
         * when C major changes to F major. That is not a fixed "root/third/
         * fifth" role table: it is a nearest-voicing operation.
         *
         * The old implementation hard-coded 3rd/5th/7th roles. That breaks
         * real source chords such as LoveSong's C-min7(11): source intervals
         * 5/10/3 could collapse onto the same target chord tone. For CHORD
         * NTT we therefore select the nearest target chord pitch-class for the
         * actual source pitch, preserving its octave whenever possible.
         *
         * For ROOT TRANS, base already contains the root transposition; using
         * the original pitch here would undo the root movement. For ROOT FIXED,
         * original is the correct reference because Yamaha keeps the previous
         * voicing range.
         */
        val reference = if (ntr == 1) original else base
        val targetPitches = intervals.map { floorMod(chord.rootNote + it, 12) }
        val targetPc = nearestPitchClass(reference, targetPitches)
        return nearestPitch(reference, targetPc)
    }

    private fun nearestPitchClass(reference: Int, pitchClasses: List<Int>): Int {
        var bestPc = pitchClasses.firstOrNull()?.coerceIn(0, 11) ?: 0
        var bestDistance = Int.MAX_VALUE

        for (pc in pitchClasses.distinct()) {
            val d = circularDistance(reference % 12, pc)
            if (d < bestDistance) {
                bestDistance = d
                bestPc = pc
            } else if (d == bestDistance) {
                // Stable tie-break: prefer the target pitch above the source
                // when both directions are equally close.
                val up = floorMod(pc - (reference % 12), 12)
                val bestUp = floorMod(bestPc - (reference % 12), 12)
                if (up < bestUp) bestPc = pc
            }
        }
        return bestPc
    }

    /**
     * Yamaha MELODY NTT is not BYPASS. In particular, LoveSong's real
     * strg48 table is NTR=ROOT FIXED + NTT=MELODY, so leaving NTT=1 as
     * "base" makes its C5/C6 source notes stay on C forever.
     *
     * MELODY preserves the source note's scale-degree relationship to the
     * source root, then ROOT FIXED chooses the nearest octave around the
     * previous pitch. For ROOT TRANS the root movement is already represented
     * by base, so we simply keep that result.
     */
    private fun melody(
        base: Int,
        original: Int,
        chord: DetectedChord,
        sourceRoot: Int,
        ntr: Int
    ): Int {
        if (ntr != 1) return base

        // Preserve the source note's interval from the source root, but move
        // that interval onto the played chord root. ROOT FIXED then keeps the
        // resulting melody note in the octave closest to its previous pitch.
        val sourceInterval = floorMod(original - sourceRoot, 12)
        val targetPc = floorMod(chord.rootNote + sourceInterval, 12)
        return nearestPitch(original, targetPc)
    }

    private fun bass(
        base: Int,
        original: Int,
        chord: DetectedChord,
        bassOn: Boolean,
        ntr: Int
    ): Int {
        if (!bassOn) return base
        val targetPc = chord.bassNote.coerceIn(0, 11)
        return nearestPitch(if (ntr == 1) original else base, targetPc)
    }

    private fun melodicMinor(note: Int, chord: DetectedChord, sourceRoot: Int): Int {
        val targetMinor = chord.quality == ChordQuality.MINOR ||
            chord.quality == ChordQuality.MIN6 || chord.quality == ChordQuality.MIN7
        if (!targetMinor) return note
        val sourceRelative = floorMod(note - sourceRoot, 12)
        return if (sourceRelative == 4) note - 1 else note
    }

    private fun harmonicMinor(note: Int, chord: DetectedChord, sourceRoot: Int): Int {
        val targetMinor = chord.quality == ChordQuality.MINOR ||
            chord.quality == ChordQuality.MIN6 || chord.quality == ChordQuality.MIN7
        if (!targetMinor) return note
        val sourceRelative = floorMod(note - sourceRoot, 12)
        return when (sourceRelative) {
            4, 9 -> note - 1
            else -> note
        }
    }

    private fun guitarFallback(note: Int, chord: DetectedChord, sourceRoot: Int): Int {
        val sourceInterval = floorMod(note - sourceRoot, 12)
        val intervals = chord.quality.intervalsFromRoot
        val targetInterval = intervals.minByOrNull {
            circularDistance(sourceInterval, it % 12)
        } ?: 0
        return nearestPitch(note, floorMod(chord.rootNote + targetInterval, 12))
    }

    private fun applyHighKey(note: Int, targetRoot: Int, highKey: Int, ntr: Int): Int {
        if (ntr != 0 || highKey !in 0..11) return note
        return if (targetRoot > highKey) note - 12 else note
    }

    private fun applyNoteLimit(note: Int, low: Int, high: Int): Int? {
        if (low > high || low !in 0..127 || high !in 0..127) return null
        var result = note
        var guard = 0
        while (result < low && result + 12 <= 127 && guard++ < 16) result += 12
        while (result > high && result - 12 >= 0 && guard++ < 32) result -= 12
        if (result < low || result > high) return null
        return result.coerceIn(0, 127)
    }

    private fun nearestPitch(reference: Int, pitchClass: Int): Int {
        var best = pitchClass
        var bestDistance = Int.MAX_VALUE
        for (octave in -1..10) {
            val candidate = pitchClass + octave * 12
            if (candidate !in 0..127) continue
            val distance = kotlin.math.abs(candidate - reference)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b) % 12
        return minOf(d, 12 - d)
    }

    private fun floorMod(value: Int, mod: Int): Int = ((value % mod) + mod) % mod
}

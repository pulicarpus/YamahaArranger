package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.CasmPolicyModel

/**
 * Yamaha SFF2 CASM note conversion.
 *
 * Numeric values are the SFF2 values used by Ctb2/Ctab:
 * NTR: 0 Root Trans, 1 Root Fixed, 2 Guitar, 3 Bypass.
 * NTT: 0 Bypass, 1 Melody, 2 Chord, 3 Bass, 4 Melodic Minor,
 *      5 Harmonic Minor.
 *
 * The implementation deliberately keeps Guitar as a conservative fallback;
 * true Yamaha guitar voicing needs the full guitar fingering table and is a
 * later step. The important correction here is that NTR=1 is Root Fixed,
 * not a fifth transposition.
 */
object CasmNoteTransformer {

    fun transform(note: Int, chord: DetectedChord, policy: CasmPolicyModel): Int? {
        if (note !in 0..127) return null
        if (policy.noteLimitLow > policy.noteLimitHigh) return null

        val sourceRoot = policy.sourceChordRoot.coerceIn(0, 11)
        val targetRoot = chord.rootNote.coerceIn(0, 11)
        val rootDelta = signedPitchClassDelta(sourceRoot, targetRoot)
        val ntr = policy.ntr and 0x7f
        val ntt = policy.ntt and 0x7f

        // NTR establishes the base relationship before the NTT is applied.
        val base = when (ntr) {
            0 -> note + rootDelta                    // ROOT TRANS
            1 -> note                                 // ROOT FIXED
            2 -> guitarFallback(note, chord, sourceRoot) // GUITAR
            3 -> note                                 // BYPASS
            else -> note + rootDelta
        }

        val converted = when (ntt) {
            0 -> { // BYPASS: Root Trans keeps interval; Root Fixed stays fixed.
                base
            }
            1 -> { // MELODY
                if (ntr == 1 || ntr == 3) note else base
            }
            2 -> { // CHORD
                chordal(base, note, chord, sourceRoot, ntr)
            }
            3 -> { // BASS
                bass(base, note, chord, policy.bassOn, sourceRoot, ntr)
            }
            4 -> { // MELODIC MINOR
                melodicMinor(base, chord, sourceRoot, policy)
            }
            5 -> { // HARMONIC MINOR
                harmonicMinor(base, chord, sourceRoot, policy)
            }
            else -> base
        }

        val highKeyAdjusted = applyHighKey(converted, policy.highKey, targetRoot, ntr)
        if (highKeyAdjusted !in policy.noteLimitLow..policy.noteLimitHigh) return null
        return highKeyAdjusted.coerceIn(0, 127)
    }

    private fun signedPitchClassDelta(sourceRoot: Int, targetRoot: Int): Int {
        var d = (targetRoot - sourceRoot) % 12
        if (d > 6) d -= 12
        if (d < -6) d += 12
        return d
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

        // Root Fixed is intended to keep chord notes near the source register.
        // Map the source note's interval from the source root into the target
        // chord, then choose the nearest octave to the original note.
        val sourceInterval = floorMod(original - sourceRoot, 12)
        val targetInterval = intervals.minByOrNull {
            circularDistance(sourceInterval, it % 12)
        } ?: 0
        val targetPc = floorMod(chord.rootNote + targetInterval, 12)
        return nearestPitch(original, targetPc)
    }

    private fun bass(
        base: Int,
        original: Int,
        chord: DetectedChord,
        bassOn: Boolean,
        sourceRoot: Int,
        ntr: Int
    ): Int {
        if (!bassOn) return base
        val targetPc = chord.bassNote.coerceIn(0, 11)
        return nearestPitch(if (ntr == 1) original else base, targetPc)
    }

    private fun melodicMinor(
        note: Int,
        chord: DetectedChord,
        sourceRoot: Int,
        policy: CasmPolicyModel
    ): Int {
        val relative = floorMod(note - chord.rootNote, 12)
        val targetMinor = chord.quality == ChordQuality.MINOR ||
            chord.quality == ChordQuality.MIN6 || chord.quality == ChordQuality.MIN7
        if (!targetMinor) return note

        // Lower the major third when the target chord is minor.
        val sourceRelative = floorMod(note - sourceRoot, 12)
        if (sourceRelative == 4) return note - 1
        return note
    }

    private fun harmonicMinor(
        note: Int,
        chord: DetectedChord,
        sourceRoot: Int,
        policy: CasmPolicyModel
    ): Int {
        val targetMinor = chord.quality == ChordQuality.MINOR ||
            chord.quality == ChordQuality.MIN6 || chord.quality == ChordQuality.MIN7
        if (!targetMinor) return note

        val sourceRelative = floorMod(note - sourceRoot, 12)
        return when (sourceRelative) {
            4, 9 -> note - 1 // 3rd and 6th
            else -> note
        }
    }

    private fun guitarFallback(note: Int, chord: DetectedChord, sourceRoot: Int): Int {
        // Preserve the source phrase register while placing the note on the
        // nearest useful chord tone. Full Yamaha guitar fingering is deferred.
        val sourceInterval = floorMod(note - sourceRoot, 12)
        val intervals = chord.quality.intervalsFromRoot
        val targetInterval = intervals.minByOrNull {
            circularDistance(sourceInterval, it % 12)
        } ?: 0
        return nearestPitch(note, floorMod(chord.rootNote + targetInterval, 12))
    }

    private fun applyHighKey(note: Int, highKey: Int, targetRoot: Int, ntr: Int): Int {
        // High Key is a pitch-class/octave boundary for converted notes. It is
        // meaningful for ROOT TRANS; Root Fixed/Bypass should not be moved just
        // because the played chord changed.
        if (ntr != 0 || highKey >= 127) return note
        val keyPc = floorMod(highKey, 12)
        var result = note
        var guard = 0
        while (floorMod(result, 12) > keyPc && guard++ < 12) result -= 12
        return result
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

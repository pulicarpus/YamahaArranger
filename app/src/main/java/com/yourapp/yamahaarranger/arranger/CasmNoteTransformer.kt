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
        val rootDelta = signedPitchClassDelta(sourceRoot, targetRoot)
        val ntr = policy.ntr and 0x7f
        val ntt = policy.ntt and 0x7f

        val base = when (ntr) {
            0 -> note + rootDelta
            1 -> note
            2 -> guitarFallback(note, chord, sourceRoot)
            3 -> note
            else -> note + rootDelta
        }

        val converted = when (ntt) {
            0 -> base
            1 -> if (ntr == 1 || ntr == 3) note else base
            2 -> chordal(base, note, chord, sourceRoot)
            3 -> bass(base, note, chord, sourceRoot, ntr)
            4 -> melodicMinor(base, chord, sourceRoot)
            5 -> harmonicMinor(base, chord, sourceRoot)
            else -> base
        }

        // In SFF2 the high bit of the NTT byte is the separate bass-on flag.
        // LoveSong3 uses NTT=Melody (1) + bass-on for its bass33 tables, so the
        // flag must be honored independently of the low 7-bit NTT value.
        val bassAdjusted = if (policy.bassOn) {
            bass(converted, note, chord, sourceRoot, ntr)
        } else {
            converted
        }

        val highKeyAdjusted = applyHighKey(bassAdjusted, policy.highKey, targetRoot, ntr)
        if (highKeyAdjusted !in policy.noteLimitLow..policy.noteLimitHigh) return null
        return highKeyAdjusted.coerceIn(0, 127)
    }

    private fun signedPitchClassDelta(sourceRoot: Int, targetRoot: Int): Int {
        var d = (targetRoot - sourceRoot) % 12
        if (d > 6) d -= 12
        if (d < -6) d += 12
        return d
    }

    private fun chordal(base: Int, original: Int, chord: DetectedChord, sourceRoot: Int): Int {
        val intervals = chord.quality.intervalsFromRoot
        if (intervals.isEmpty()) return base
        val sourceInterval = floorMod(original - sourceRoot, 12)
        val targetInterval = intervals.minByOrNull { circularDistance(sourceInterval, it % 12) } ?: 0
        return nearestPitch(original, floorMod(chord.rootNote + targetInterval, 12))
    }

    private fun bass(base: Int, original: Int, chord: DetectedChord, sourceRoot: Int, ntr: Int): Int {
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
        val targetInterval = intervals.minByOrNull { circularDistance(sourceInterval, it % 12) } ?: 0
        return nearestPitch(note, floorMod(chord.rootNote + targetInterval, 12))
    }

    private fun applyHighKey(note: Int, highKey: Int, targetRoot: Int, ntr: Int): Int {
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

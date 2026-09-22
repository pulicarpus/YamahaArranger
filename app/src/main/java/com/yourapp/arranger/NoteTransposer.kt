package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.YamahaCasmPolicy
import kotlin.math.abs

/**
 * Yamaha-style source-pattern transposition.
 *
 * The old implementation applied one generic "snap every note to the chord"
 * rule to every channel. That is not how Yamaha SFF works. Yamaha first uses
 * the per-channel CASM NTR/NTT policy, then applies High Key / Note Limits.
 *
 * This implementation covers the important SFF policies used by factory
 * styles and deliberately keeps unsupported Guitar/NoteGenerator behavior
 * conservative rather than inventing pitches.
 */
object NoteTransposer {
    private const val ROOT_TRANS = 0
    private const val ROOT_FIXED = 1
    private const val GUITAR = 2
    private const val BYPASS = 3

    private const val NTT_BYPASS = 0
    private const val NTT_MELODY = 1
    private const val NTT_CHORD = 2
    private const val NTT_BASS = 3
    private const val NTT_MELODIC_MINOR = 4
    private const val NTT_HARMONIC_MINOR = 5

    fun transpose(patternNote: Int, chord: DetectedChord): Int =
        fallback(patternNote, chord, channel = 0)

    fun transpose(patternNote: Int, chord: DetectedChord, isBassPart: Boolean): Int =
        if (isBassPart) bass(patternNote, chord, null) else fallback(patternNote, chord, 0)

    fun transpose(patternNote: Int, chord: DetectedChord, channel: Int): Int =
        fallback(patternNote, chord, channel)

    fun transpose(
        patternNote: Int,
        chord: DetectedChord,
        channel: Int,
        policy: YamahaCasmPolicy?
    ): Int {
        if (channel == 8 || channel == 9) return patternNote.coerceIn(0, 127)
        if (policy == null) return fallback(patternNote, chord, channel)

        val rootDelta = normalizeDelta(chord.rootNote - policy.sourceRoot)
        var result = when (policy.ntr) {
            BYPASS -> patternNote
            ROOT_FIXED -> {
                when (policy.ntt) {
                    NTT_BYPASS -> patternNote
                    NTT_BASS -> bass(patternNote, chord, policy)
                    else -> fitChord(patternNote, chord, policy, rootDelta = 0)
                }
            }
            GUITAR -> {
                // Full guitar fingering is a separate algorithm. Root-shift +
                // chord fitting is the safe fallback until that table exists.
                fitChord(patternNote, chord, policy, rootDelta)
            }
            else -> { // ROOT_TRANS
                when (policy.ntt) {
                    NTT_BYPASS -> patternNote + rootDelta
                    NTT_BASS -> bass(patternNote, chord, policy)
                    NTT_CHORD -> fitChord(patternNote, chord, policy, rootDelta)
                    NTT_MELODY,
                    NTT_MELODIC_MINOR,
                    NTT_HARMONIC_MINOR -> fitScale(patternNote + rootDelta, chord, policy)
                    else -> patternNote + rootDelta
                }
            }
        }

        result = applyLimits(result, policy, useHighKey = policy.ntr == ROOT_TRANS)
        return result.coerceIn(0, 127)
    }

    private fun fallback(patternNote: Int, chord: DetectedChord, channel: Int): Int {
        // Standard Yamaha accompaniment channels are MIDI 9-16 (1-based):
        // 9/10 rhythm, 11 bass, 12/13 chord, 14 pad, 15/16 phrase.
        return when (channel) {
            8, 9 -> patternNote
            10 -> bass(patternNote, chord, null)
            11, 12, 13 -> fitChord(patternNote, chord, null, normalizeDelta(chord.rootNote))
            14, 15 -> fitScale(patternNote + normalizeDelta(chord.rootNote), chord, null)
            else -> patternNote + normalizeDelta(chord.rootNote)
        }.coerceIn(0, 127)
    }

    private fun bass(
        patternNote: Int,
        chord: DetectedChord,
        policy: YamahaCasmPolicy?
    ): Int {
        val targetRoot = if (policy?.bassOn == true) chord.bassNote else chord.rootNote
        val sourcePitchClass = ((patternNote - (policy?.sourceRoot ?: 0)) % 12 + 12) % 12

        // Bass NTT treats the source root as the bass anchor. Other source
        // notes retain their interval where practical, but the lowest/root
        // note is explicitly anchored to the played bass/root.
        var result = if (sourcePitchClass == 0) {
            patternNote + normalizeDelta(targetRoot - (policy?.sourceRoot ?: 0))
        } else {
            patternNote + normalizeDelta(targetRoot - (policy?.sourceRoot ?: 0))
        }

        result = applyLimits(result, policy, useHighKey = true, defaultLow = 28, defaultHigh = 60)
        return result
    }

    private fun fitChord(
        patternNote: Int,
        chord: DetectedChord,
        policy: YamahaCasmPolicy?,
        rootDelta: Int
    ): Int {
        val shifted = patternNote + rootDelta
        val intervals = chord.quality.intervalsFromRoot.map { ((it % 12) + 12) % 12 }
            .distinct()
            .ifEmpty { listOf(0, 4, 7) }
        return snapPitchClass(shifted, chord.rootNote, intervals)
    }

    private fun fitScale(
        shifted: Int,
        chord: DetectedChord,
        policy: YamahaCasmPolicy?
    ): Int {
        val q = chord.quality.intervalsFromRoot.map { ((it % 12) + 12) % 12 }
        val isMinor = q.contains(3) && !q.contains(4)
        val scale = if (isMinor) intArrayOf(0, 2, 3, 5, 7, 8, 10)
                   else intArrayOf(0, 2, 4, 5, 7, 9, 11)

        var relative = ((shifted - chord.rootNote) % 12 + 12) % 12

        // Yamaha's Melodic Minor table changes the 3rd according to chord
        // quality; this is the important audible distinction for phrase parts.
        if (policy?.ntt == NTT_MELODIC_MINOR) {
            val minorChord = q.contains(3) && !q.contains(4)
            if (minorChord && relative == 4) relative = 3
            if (!minorChord && relative == 3) relative = 4
        }

        val target = scale.minByOrNull { intervalDistance(it, relative) } ?: 0
        return shifted + (target - relative)
    }

    private fun snapPitchClass(note: Int, root: Int, intervals: List<Int>): Int {
        val rel = ((note - root) % 12 + 12) % 12
        val target = intervals.minByOrNull { intervalDistance(it, rel) } ?: 0
        return note + (target - rel)
    }

    private fun intervalDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % 12
        return minOf(d, 12 - d)
    }

    private fun applyLimits(
        note: Int,
        policy: YamahaCasmPolicy?,
        useHighKey: Boolean,
        defaultLow: Int = 0,
        defaultHigh: Int = 127
    ): Int {
        var n = note
        val low = policy?.noteLow ?: defaultLow
        val high = policy?.noteHigh ?: defaultHigh

        while (n < low) n += 12
        while (n > high) n -= 12

        if (useHighKey && policy != null && policy.highKey in 1..127) {
            val hk = policy.highKey
            while (n > hk) n -= 12
        }
        return n
    }

    private fun normalizeDelta(delta: Int): Int {
        var d = delta
        while (d > 6) d -= 12
        while (d < -6) d += 12
        return d
    }
}

package com.yourapp.yamahaarranger.chord

/**
 * ACMP chord analyzer.
 *
 * This is intentionally separate from the fingering-mode detector: ACMP needs
 * one stable chord interpretation that can be fed to the arranger/CASM layer.
 * Pitch classes are compared, so inversions and octave doubling are handled
 * without treating duplicated notes as extra chord tones.
 */
object AcmpChordAnalyzer {
    private val noteNames = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    data class Analysis(
        val rootNote: Int,
        val bassNote: Int,
        val quality: ChordQuality,
        val confidence: Float,
        val displayName: String,
        val pitchClasses: Set<Int>
    )

    fun analyze(notes: Collection<Int>): Analysis? {
        if (notes.isEmpty()) return null
        val normalized = notes.map { ((it % 12) + 12) % 12 }
        val pitchClasses = normalized.toSet()
        val bass = notes.minOrNull()!!.mod(12)

        var best: Candidate? = null
        for (root in pitchClasses) {
            for (quality in ChordQuality.entries) {
                val template = quality.pitchClassIntervals
                    .map { (root + it) % 12 }
                    .toSet()
                val matched = template.intersect(pitchClasses).size
                val extras = pitchClasses.size - matched
                val missing = template.size - matched
                if (missing != 0 || extras != 0) continue

                val inversionPenalty = if (quality.allowsInversion && bass != root) 0 else if (bass == root) 0 else -4
                val rootBassBonus = if (bass == root) 3 else 0
                val score = quality.priority * 100 + rootBassBonus + inversionPenalty
                val candidate = Candidate(root, quality, score)
                if (best == null || candidate.score > best!!.score) best = candidate
            }
        }

        // E343-style two-note/easy-chord fallback. This is deliberately lower
        // confidence than a complete chord so the UI can distinguish it.
        if (best == null && pitchClasses.size == 2) {
            val root = bass
            val interval = ((pitchClasses.first { it != root } - root) + 12) % 12
            val quality = when (interval) {
                4 -> ChordQuality.MAJOR
                3 -> ChordQuality.MINOR
                5 -> ChordQuality.SUS4
                7 -> ChordQuality.POWER5
                else -> null
            }
            if (quality != null) best = Candidate(root, quality, quality.priority * 100 - 20)
        }

        val result = best ?: return null
        val slash = if (result.root != bass && result.quality.allowsSlashDisplay) "/${noteNames[bass]}" else ""
        val display = noteNames[result.root] + result.quality.symbol + slash
        val confidence = when {
            pitchClasses.size >= result.quality.pitchClassIntervals.size && result.quality.pitchClassIntervals.size >= 3 -> 1.0f
            pitchClasses.size == 2 -> 0.65f
            else -> 0.5f
        }
        return Analysis(result.root, bass, result.quality, confidence, display, pitchClasses)
    }

    private data class Candidate(val root: Int, val quality: ChordQuality, val score: Int)
}

private val ChordQuality.pitchClassIntervals: List<Int>
    get() = intervalsFromRoot.map { it % 12 }.distinct()

private val ChordQuality.priority: Int
    get() = when (this) {
        ChordQuality.MAJOR -> 100
        ChordQuality.MINOR -> 99
        ChordQuality.SUS4 -> 98
        ChordQuality.SUS2 -> 97
        ChordQuality.DOM7 -> 96
        ChordQuality.MIN7 -> 95
        ChordQuality.MAJ7 -> 94
        ChordQuality.SIX -> 93
        ChordQuality.MIN6 -> 92
        ChordQuality.DIM -> 91
        ChordQuality.DIM7 -> 90
        ChordQuality.AUG -> 89
        ChordQuality.MIN7_FLAT5 -> 88
        ChordQuality.DOM7_FLAT5 -> 87
        ChordQuality.SIX9 -> 86
        ChordQuality.ADD9 -> 85
        ChordQuality.DOM7_SUS4 -> 84
        ChordQuality.MIN7_11 -> 83
        ChordQuality.POWER5 -> 10
    }

private val ChordQuality.symbol: String
    get() = when (this) {
        ChordQuality.MAJOR -> ""
        ChordQuality.MINOR -> "m"
        ChordQuality.SUS4 -> "sus4"
        ChordQuality.SUS2 -> "sus2"
        ChordQuality.DOM7 -> "7"
        ChordQuality.MIN7 -> "m7"
        ChordQuality.MAJ7 -> "M7"
        ChordQuality.SIX -> "6"
        ChordQuality.MIN6 -> "m6"
        ChordQuality.DIM -> "dim"
        ChordQuality.DIM7 -> "dim7"
        ChordQuality.AUG -> "aug"
        ChordQuality.MIN7_FLAT5 -> "m7b5"
        ChordQuality.DOM7_FLAT5 -> "7b5"
        ChordQuality.SIX9 -> "6(9)"
        ChordQuality.ADD9 -> "add9"
        ChordQuality.DOM7_SUS4 -> "7sus4"
        ChordQuality.MIN7_11 -> "m7(11)"
        ChordQuality.POWER5 -> "5"
    }

private val ChordQuality.allowsInversion: Boolean
    get() = this !in setOf(
        ChordQuality.MIN7,
        ChordQuality.MIN7_FLAT5,
        ChordQuality.SIX,
        ChordQuality.MIN6,
        ChordQuality.SUS4,
        ChordQuality.AUG,
        ChordQuality.DIM7,
        ChordQuality.DOM7_FLAT5,
        ChordQuality.SIX9,
        ChordQuality.SUS2
    )

private val ChordQuality.allowsSlashDisplay: Boolean
    get() = this !in setOf(ChordQuality.SUS2)

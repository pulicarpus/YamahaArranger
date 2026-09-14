package com.yourapp.yamahaarranger.chord

/** Detection mode selectable in the UI, mirrors classic arranger keyboards. */
enum class ChordMode { SingleFinger, Fingered, FullKeyboard, AiFingered }

enum class ChordQuality(val intervalsFromRoot: List<Int>) {
    MAJOR(listOf(0, 4, 7)),
    MINOR(listOf(0, 3, 7)),
    DIM(listOf(0, 3, 6)),
    AUG(listOf(0, 4, 8)),
    SUS2(listOf(0, 2, 7)),
    SUS4(listOf(0, 5, 7)),
    DOM7(listOf(0, 4, 7, 10)),
    MAJ7(listOf(0, 4, 7, 11)),
    MIN7(listOf(0, 3, 7, 10)),
    DIM7(listOf(0, 3, 6, 9)),
    SIX(listOf(0, 4, 7, 9)),
    MIN6(listOf(0, 3, 7, 9)),
    ADD9(listOf(0, 4, 7, 14)),
    NINE(listOf(0, 4, 7, 10, 14)),
    ELEVEN(listOf(0, 4, 7, 10, 14, 17)),
    THIRTEEN(listOf(0, 4, 7, 10, 14, 21));
}

data class DetectedChord(
    val rootNote: Int,      // 0-11 pitch class
    val bassNote: Int,      // pitch class actually sounding lowest (for slash chords)
    val quality: ChordQuality
)

/**
 * Note-history-buffer chord matcher. Runs on the UI/input thread — this is
 * NOT in the audio callback, so allocation here is fine; target is
 * "< 10ms perceived latency" from key-down to chord recognized, which is
 * comfortably met by a plain pattern match over <= 10 held notes.
 */
class ChordDetector(private val mode: ChordMode = ChordMode.Fingered) {

    private val heldNotes = LinkedHashSet<Int>() // insertion order == play order
    private val historyLimit = 10

    fun noteOn(midiNote: Int): DetectedChord? {
        heldNotes.add(midiNote)
        if (heldNotes.size > historyLimit) {
            heldNotes.remove(heldNotes.first())
        }
        return detect()
    }

    fun noteOff(midiNote: Int): DetectedChord? {
        heldNotes.remove(midiNote)
        return detect()
    }

    fun reset() = heldNotes.clear()

    private fun detect(): DetectedChord? {
        if (heldNotes.isEmpty()) return null

        return when (mode) {
            ChordMode.SingleFinger -> detectSingleFinger()
            ChordMode.Fingered, ChordMode.FullKeyboard, ChordMode.AiFingered -> detectFingered()
        }
    }

    /** Single Finger: 1 key = major, +white key to the left = minor/7th/dim
     * per the classic Yamaha convention. Simplified here to major/minor/7th. */
    private fun detectSingleFinger(): DetectedChord {
        val sorted = heldNotes.sorted()
        val root = sorted.first() % 12
        val quality = when (sorted.size) {
            1 -> ChordQuality.MAJOR
            2 -> if ((sorted[1] - sorted[0]) % 12 == 3) ChordQuality.MINOR else ChordQuality.DOM7
            else -> ChordQuality.DOM7
        }
        return DetectedChord(root, root, quality)
    }

    /** Fingered/Full Keyboard/AI Fingered: match the held pitch-class set
     * against every quality's interval template, rooted at every held note,
     * and keep the best (most-intervals-matched, fewest-extra-notes) fit. */
    private fun detectFingered(): DetectedChord {
        val pitchClasses = heldNotes.map { it % 12 }.toSet()
        var best: DetectedChord? = null
        var bestScore = -1

        for (rootCandidate in pitchClasses) {
            for (quality in ChordQuality.entries) {
                val template = quality.intervalsFromRoot.map { (rootCandidate + it) % 12 }.toSet()
                val matched = template.intersect(pitchClasses).size
                val extras = pitchClasses.size - pitchClasses.intersect(template).size
                val score = matched * 10 - extras // favor full template matches, penalize stray notes
                if (matched == template.size && score > bestScore) {
                    bestScore = score
                    val bass = heldNotes.min() % 12
                    best = DetectedChord(rootCandidate, bass, quality)
                }
            }
        }
        // Fall back to a bare root+fifth "power chord" reading if nothing
        // matched cleanly (e.g. user is mid-transition between chords).
        return best ?: DetectedChord(pitchClasses.min(), pitchClasses.min(), ChordQuality.MAJOR)
    }
}

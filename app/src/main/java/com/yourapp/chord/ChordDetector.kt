package com.yourapp.yamahaarranger.chord

/** Chord-detection modes modeled on Yamaha arranger fingering behavior. */
enum class ChordMode { SingleFinger, MultiFinger, Fingered, AiFingered }

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
    val rootNote: Int,
    val bassNote: Int,
    val quality: ChordQuality
)

/**
 * Chord detector for arranger-style input.
 *
 * Important behavior: Note-off never promotes the remaining held notes into
 * a new chord. The last recognized chord remains active until a new chord is
 * recognized by a subsequent note-on. This prevents finger-lift transitions
 * from producing accidental chords.
 */
class ChordDetector(private val mode: ChordMode = ChordMode.MultiFinger) {

    private val heldNotes = LinkedHashSet<Int>()
    private val historyLimit = 10
    private var lastChord: DetectedChord? = null

    fun noteOn(midiNote: Int): DetectedChord? {
        heldNotes.add(midiNote)
        if (heldNotes.size > historyLimit) heldNotes.remove(heldNotes.first())

        val detected = detectOn()
        if (detected != null) lastChord = detected
        return detected
    }

    fun noteOff(midiNote: Int): DetectedChord? {
        heldNotes.remove(midiNote)
        // Deliberately return the last recognized chord. Releasing one finger
        // must not cause the remaining transition notes to become a new chord.
        return lastChord
    }

    fun reset() {
        heldNotes.clear()
        lastChord = null
    }

    private fun detectOn(): DetectedChord? = when (mode) {
        ChordMode.SingleFinger -> detectSingleFinger()
        ChordMode.MultiFinger -> detectMultiFinger()
        ChordMode.Fingered -> detectFingered()
        ChordMode.AiFingered -> detectAiFingered()
    }

    /** Yamaha-style Single Finger: root, root+white-left=7th,
     * root+black-left=minor, root+both=minor-7th. */
    private fun detectSingleFinger(): DetectedChord {
        val sorted = heldNotes.sorted()
        val root = sorted.minOrNull() ?: return DetectedChord(0, 0, ChordQuality.MAJOR)
        val rootPc = root % 12
        val relative = sorted.drop(1).map { (it - root) % 12 }.toSet()
        val quality = when {
            relative.containsAll(setOf(3, 10)) -> ChordQuality.MIN7
            relative.contains(3) -> ChordQuality.MINOR
            relative.contains(10) -> ChordQuality.DOM7
            else -> ChordQuality.MAJOR
        }
        return DetectedChord(rootPc, rootPc, quality)
    }

    /** Multi Finger accepts either the Single Finger shorthand or full
     * Fingered voicings, as documented by Yamaha. */
    private fun detectMultiFinger(): DetectedChord {
        val single = detectSingleFinger()
        if (heldNotes.size <= 3) {
            val fingered = detectFingeredOrNull()
            if (fingered != null && heldNotes.size >= 3) return fingered
            return single
        }
        return detectFingeredOrNull() ?: single
    }

    private fun detectFingered(): DetectedChord {
        return detectFingeredOrNull()
            ?: fallbackRootChord()
    }

    /** AI Fingered follows Fingered when 3+ notes are present. With fewer
     * notes, it uses the previous chord as context instead of allowing the
     * finger-lift transition to create a random chord. */
    private fun detectAiFingered(): DetectedChord {
        if (heldNotes.size >= 3) return detectFingeredOrNull() ?: fallbackRootChord()

        val previous = lastChord
        val notes = heldNotes.sorted()
        if (notes.isEmpty()) return previous ?: fallbackRootChord()

        if (notes.size == 1) {
            val notePc = notes[0] % 12
            // A single note is treated as a new root while retaining the
            // previous quality, which gives AI Fingered useful continuity.
            return DetectedChord(notePc, notePc, previous?.quality ?: ChordQuality.MAJOR)
        }

        // Two-note input: first try a subset of the previous chord; otherwise
        // infer the closest common triad/7th relationship.
        val fromPrevious = previous?.let { chord ->
            val pcs = notes.map { it % 12 }.toSet()
            val template = chord.quality.intervalsFromRoot
                .map { (chord.rootNote + it) % 12 }.toSet()
            if (pcs.all { it in template }) {
                DetectedChord(chord.rootNote, notes.min() % 12, chord.quality)
            } else null
        }
        return fromPrevious ?: detectFingeredOrNull() ?: fallbackRootChord()
    }

    private fun detectFingeredOrNull(): DetectedChord? {
        val pitchClasses = heldNotes.map { it % 12 }.toSet()
        if (pitchClasses.isEmpty()) return null

        var best: DetectedChord? = null
        var bestScore = Int.MIN_VALUE

        for (rootCandidate in pitchClasses) {
            for (quality in ChordQuality.entries) {
                val template = quality.intervalsFromRoot
                    .map { (rootCandidate + it) % 12 }.toSet()
                val matched = template.intersect(pitchClasses).size
                val extras = pitchClasses.size - matched
                val score = matched * 10 - extras
                if (matched == template.size && score > bestScore) {
                    bestScore = score
                    best = DetectedChord(
                        rootCandidate,
                        heldNotes.min() % 12,
                        quality
                    )
                }
            }
        }
        return best
    }

    private fun fallbackRootChord(): DetectedChord {
        val root = heldNotes.minOrNull()?.rem(12) ?: lastChord?.rootNote ?: 0
        return DetectedChord(root, root, ChordQuality.MAJOR)
    }
}

package com.yourapp.yamahaarranger.audio

/**
 * Which SF2 files of the managed folder are attached, and why the others are not.
 *
 * Colombo (primary) and Tyros (fallback) keep their role and drive the automatic resolver. Every other .sf2 found in the
 * folder - SX Prime Melody/Drum and any custom file, no rebuild needed - may join as MANUAL-ONLY: selectable by hand
 * (Voice Selector, Style Voice Editor), never considered by the automatic resolver. Attaching costs time and memory
 * (the native side reads and normalises each file), so the number of manual files and their total size are capped.
 * Nothing is dropped silently: skipped files are returned with the reason and shown in the editor.
 */
object ManagedSf2Policy {
    /** Native pool is Tyros + at most three manual-only fonts. */
    const val MAX_MANUAL = 3
    /** Total size of manual-only files attached together. SX Prime Melody (343 MB) + Prime Drum (153 MB) + one ~500 MB custom fit. */
    const val MANUAL_BUDGET_BYTES = 1_000_000_000L

    data class Candidate<T>(val item: T, val name: String, val sizeBytes: Long)
    data class Skipped<T>(val candidate: Candidate<T>, val reason: String)
    data class Plan<T>(val primary: Candidate<T>?, val fallback: Candidate<T>?, val manual: List<Candidate<T>>, val skipped: List<Skipped<T>>) {
        val manualBytes: Long get() = manual.sumOf { it.sizeBytes }
    }

    /** Lower is attached first: SX Prime Melody, SX Prime Drum, then everything else by name. */
    private fun manualRank(name: String) = when (SoundFontRolePlanner.kind(name)) {
        SoundFontRolePlanner.Kind.PRIME_MELODY -> 0
        SoundFontRolePlanner.Kind.PRIME_DRUM -> 1
        else -> 2
    }

    fun <T> plan(files: List<Candidate<T>>, maxManual: Int = MAX_MANUAL, budgetBytes: Long = MANUAL_BUDGET_BYTES): Plan<T> {
        val roles = SoundFontRolePlanner.plan(files) { it.name }
        val rest = files.filter { it != roles.primary && it != roles.fallback }
            .sortedWith(compareBy({ manualRank(it.name) }, { it.name.lowercase() }))
        val manual = ArrayList<Candidate<T>>(); val skipped = ArrayList<Skipped<T>>(); var used = 0L
        for (c in rest) {
            when {
                c.sizeBytes <= 0 -> skipped += Skipped(c, "EMPTY_OR_UNREADABLE")
                manual.size >= maxManual -> skipped += Skipped(c, "LIMIT_$maxManual" + "_MANUAL_FILES")
                used + c.sizeBytes > budgetBytes -> skipped += Skipped(c, "OVER_BUDGET_${budgetBytes / 1_000_000}MB")
                else -> { manual += c; used += c.sizeBytes }
            }
        }
        return Plan(roles.primary, roles.fallback, manual, skipped)
    }
}

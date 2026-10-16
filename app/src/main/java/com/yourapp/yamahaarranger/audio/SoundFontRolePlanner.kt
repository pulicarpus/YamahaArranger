package com.yourapp.yamahaarranger.audio

/**
 * CPV font roles: Colombo GM/GS2 is the primary SF2 for every instrument and
 * Tyros 4 the only fallback. Prime Melodi / Prime Drum and other files are
 * never loaded automatically.
 */
object SoundFontRolePlanner {
    enum class Kind { COLOMBO, TYROS, PRIME_MELODY, PRIME_DRUM, OTHER }

    data class Plan<T>(val primary: T?, val fallback: T?, val excluded: List<T>) {
        val ready: Boolean get() = primary != null
    }

    fun kind(fileName: String): Kind {
        val n = fileName.lowercase()
        return when {
            n.contains("colombo") -> Kind.COLOMBO
            n.contains("tyros") && !n.contains("drum") -> Kind.TYROS
            (n.contains("drumkit") || n.contains("drum")) && (n.contains("yamaha") || n.contains("prime") || n.contains("psr")) -> Kind.PRIME_DRUM
            (n.contains("melodi") || n.contains("melody")) && (n.contains("yamaha") || n.contains("prime") || n.contains("psr")) -> Kind.PRIME_MELODY
            else -> Kind.OTHER
        }
    }

    fun <T> plan(files: List<T>, name: (T) -> String): Plan<T> {
        // Prefer the BASSMIDI edition of Colombo when several copies exist.
        val colombo = files.filter { kind(name(it)) == Kind.COLOMBO }
            .sortedWith(compareByDescending<T> { name(it).lowercase().let { n -> n.contains("bm") || n.contains("bassmidi") } }
                .thenBy { name(it) })
        val tyros = files.filter { kind(name(it)) == Kind.TYROS }.sortedBy { name(it) }
        val primary = colombo.firstOrNull() ?: tyros.firstOrNull()
        val fallback = if (colombo.isNotEmpty()) tyros.firstOrNull() else null
        return Plan(primary, fallback, files.filter { it != primary && it != fallback })
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.ParsedStyle

/** Read-only MainD source histogram; never invokes CASM or the scheduler. */
internal object DrumStyleAuditProfile {
    data class Profile(val histogram: IntArray, val header: String)

    fun from(style: ParsedStyle, sectionName: String = "MainD"): Profile {
        val section = style.sections[sectionName]
            ?: return Profile(intArrayOf(), "DRUM STYLE PROFILE unavailable: $sectionName absent\n")
        val bins = linkedMapOf<Triple<Int, Int, Int>, Int>()
        val requestedKits = linkedSetOf<String>()
        for (part in section.parts) {
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            for (event in part.events) {
                if (!event.isNoteOn || event.velocity <= 0) continue
                val destinations = policies.filter { it.sourceChannel == event.channel }
                    .map { it.destinationChannel }.distinct()
                val destination = if (destinations.isEmpty()) event.channel
                    else destinations.singleOrNull()
                if (destination == null && destinations.any { it == 8 || it == 9 })
                    return Profile(intArrayOf(), "DRUM STYLE PROFILE unavailable: ambiguous rhythm destination\n")
                val rhythmChannel = destination ?: continue
                if (rhythmChannel != 8 && rhythmChannel != 9) continue
                val key = Triple(rhythmChannel, event.note, event.velocity)
                bins[key] = (bins[key] ?: 0) + 1
                requestedKits += "ch=$destination bank=${part.bankMsb}:${part.bankLsb} PC=${part.program} part='${part.name}'"
            }
        }
        val sorted = bins.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
        val histogram = sorted.flatMap { (key, count) -> listOf(key.first, key.second, key.third, count) }.toIntArray()
        val header = buildString {
            appendLine("Style='${style.fileName}' section='${section.name}' ticks=${section.lengthTicks} PPQ=${style.ppq}")
            appendLine("Profile=one_complete_raw_section notes_unremapped excludes_NOTE_OFF_and_velocity_zero")
            requestedKits.forEach { appendLine("REQUESTED KIT $it") }
            for (ch in 8..9) {
                val partBins = sorted.filter { it.first.first == ch }
                appendLine("RHYTHM PROFILE ch=$ch hits=${partBins.sumOf { it.second }} uniqueKeys=${partBins.map { it.first.second }.distinct().size}")
            }
        }
        return Profile(histogram, header)
    }
}


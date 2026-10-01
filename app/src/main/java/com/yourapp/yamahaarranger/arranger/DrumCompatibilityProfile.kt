package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.ParsedStyle

/** On-demand provenance only. Reuses #759's raw histogram; never calls the sequencer. */
internal object DrumCompatibilityProfile {
    data class Profile(val histogram: IntArray, val header: String, val complete: Boolean)
    private fun label(s: String) = s.take(96).map { if (it < ' ' || it == '\'') '?' else it }.joinToString("")

    fun from(style: ParsedStyle): Profile {
        val rows = mutableListOf<String>()
        val values = mutableListOf<Int>()
        var complete = true
        style.sections.keys.sorted().forEachIndexed { id, sectionName ->
            val section = style.sections.getValue(sectionName)
            val profile = DrumStyleAuditProfile.from(style, sectionName)
            if (profile.header.contains("unavailable:")) complete = false
            rows += "SECTION id=$id name='${label(sectionName)}' ticks=${section.lengthTicks} sourceHits=${profile.histogram.toList().chunked(4).sumOf { it[3].toLong() }}"
            if (profile.header.contains("unavailable:")) rows += "UNKNOWN section=$id reason=${label(profile.header)}"
            profile.histogram.toList().chunked(4).forEach { bin -> values += listOf(id) + bin }
            section.parts.forEach { part ->
                val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
                val sources = part.events.filter { it.isNoteOn && it.velocity > 0 }.map { it.channel }.distinct().sorted()
                for (src in sources) {
                    val routes = policies.filter { it.sourceChannel == src }.map { it.destinationChannel }.distinct().sorted().ifEmpty { listOf(src) }
                    if (routes.none { it == 8 || it == 9 }) continue
                    val dynamic = part.events.any { it.channel == src && it.tick > 0 &&
                        (it.isProgramChange || (it.isControlChange && (it.note == 0 || it.note == 32))) }
                    rows += "REQUEST section=$id part='${label(part.name)}' src=$src dst=${routes.joinToString(",")} headerBank=${part.bankMsb}:${part.bankLsb} headerPC=${part.program} PCknown=${part.program >= 0} dynamicBankPC=$dynamic identity=UNKNOWN scope=parsed_header_not_live_per_note"
                }
            }
        }
        // No partial union may masquerade as complete coverage if routing or JNI payload is unknown.
        if (values.size > 327680) { complete = false; rows += "UNKNOWN reason=demand_payload_limit" }
        val header = "YAMAHA ARRANGER DRUM COMPATIBILITY (compact v1) maxFileBytes=49152\nStyle='${label(style.fileName)}' PPQ=${style.ppq} sections=${style.sections.size} demandComplete=$complete\nHeader Yamaha MSB/LSB/PC vs native request vs live source bank/PC are separate identities.\nHeader PC=-1 means unavailable; dynamicBankPC means header is not a per-note binding.\nSource histogram excludes NOTE_OFF/velocity-zero; each section once, no remap. Masks/mutes/overrides and actual sent/sample voices are UNKNOWN.\n"
        val bounded = ChordReportBounds.lines(header, rows, 8 * 1024)
            .replace("captureDropped is separate.", "routingUnknown is separate.")
        return Profile(if (complete) values.toIntArray() else intArrayOf(), bounded, complete)
    }
}

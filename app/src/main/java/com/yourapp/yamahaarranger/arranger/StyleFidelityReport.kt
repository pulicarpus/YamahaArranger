package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.ParsedStyle

/** Explicit export only. No CASM transform, defaults, mixer reset or synth/MIDI write. */
internal object StyleFidelityReport {
    fun export(style: ParsedStyle, sectionAtExport: String, nativeSnapshot: String): String = buildString {
        appendLine("=== STYLE FIDELITY U0 v1 ===")
        appendLine("mode=OBSERVATION_ONLY resolver=POLICY_C catalogUse=OFF sectionAtExport=${label(sectionAtExport)}")
        appendLine("scope=parsed_requests_plus_current_binding_snapshot not_section_history not_SInt_or_effectiveSetup")
        val nativeRows = nativeSnapshot.lineSequence().filter { it.startsWith("NATIVE ch=") }
            .associateBy { Regex("^NATIVE ch=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        for ((sectionName, section) in style.sections.toSortedMap()) {
            val observedDestinations = mutableSetOf<Int>()
            for ((partId, part) in section.parts.withIndex()) {
                val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
                val sources = (part.events.filter { it.isChannelVoice }.map { it.channel } + policies.map { it.sourceChannel }).distinct().sorted()
                for (source in sources) {
                    val destinations = policies.filter { it.sourceChannel == source }.map { it.destinationChannel }.distinct()
                    val destination = if (destinations.isEmpty()) source else destinations.singleOrNull()
                    if (destination != null) observedDestinations.add(destination)
                    val hits = part.events.filter { it.channel == source && it.isNoteOn && it.velocity > 0 }
                    val keys = hits.map { it.note }.distinct().sorted()
                    appendLine("FIDELITY section=${label(sectionName)} part=$partId sourceCh=$source destinationCh=${destination ?: "AMBIGUOUS"} " +
                        "bankMsb=${part.bankMsb} bankLsb=${part.bankLsb} rawPC=${part.program} programEvidence=${if(part.program<0) "UNSPECIFIED_NOT_GUESSED_BY_AUDIT" else "PARSED_PART"} " +
                        "label=${label(part.name)} rawKeys=${keys.joinToString(",")} keyEvidence=SOURCE_NOT_CASM_TRANSFORMED " +
                        "declaredCC7=${part.volume} declaredCC10=${part.pan} declaredCC11=${part.expression} declaredCC91=${part.reverbSend} declaredCC93=${part.chorusSend}")
                    if (sectionName == sectionAtExport && destination != null) {
                        appendLine("BINDING_AT_EXPORT ${nativeRows[destination] ?: "UNOBSERVED"}")
                        val nativeRow = nativeRows[destination]
                        val present = nativeRow?.let { Regex("keysPresent=([0-9,]+|NONE|UNAVAILABLE)").find(it)?.groupValues?.get(1) }
                        if (destination in 8..9 && present != null && present != "UNAVAILABLE") {
                            val covered = present.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                            appendLine("rawDrumKeysMissing=${keys.filterNot { it in covered }.joinToString(",")} coverageScope=zone_union_not_all_velocities_or_PCM")
                        } else appendLine("coverage=UNVERIFIED melodic_CASM_transformed_range_not_measured")
                    } else appendLine("activeBinding=UNOBSERVED effectiveControllers=UNOBSERVED coverage=UNOBSERVED")
                }
            }
            for (channel in 8..15) if (channel !in observedDestinations) appendLine(
                "FIDELITY section=${label(sectionName)} destinationCh=$channel request=UNOBSERVED activeBinding=UNOBSERVED coverage=UNOBSERVED effectiveControllers=UNOBSERVED")
        }
    }

    private fun label(value: String): String = value.take(160).replace(Regex("[\\r\\n\\t]"), " ")
}

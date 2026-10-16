package com.yourapp.yamahaarranger.style

/**
 * Read-only view of the voices a style asks for, per section and part.
 * Values come straight from the style events (SInt/tick-0 setup and later
 * Program Changes); nothing here drives playback.
 */
data class StyleProgramChange(val tick: Int, val msb: Int?, val lsb: Int?, val program: Int)

data class StyleVoiceRequest(
    val section: String,
    val partIndex: Int,
    val partName: String,
    val sourceChannel: Int,
    /** CASM destination channel, or the source channel when the part has no CASM policy. */
    val destinationChannel: Int,
    val hasCasm: Boolean,
    val casmVoiceName: String,
    /** Null means the style sent no CC0/CC32/Program Change at tick 0. */
    val msb: Int?,
    val lsb: Int?,
    val program: Int?,
    val drum: Boolean,
    /** True for the part whose setup the sequencer applies to this destination in this section. */
    val appliedToDestination: Boolean,
    val dynamicChanges: List<StyleProgramChange>
) {
    val bankText: String get() = "${msb?.toString() ?: "-"}:${lsb?.toString() ?: "-"}"
    val programText: String get() = program?.toString() ?: "-"
}

object StyleVoiceRequests {
    fun extract(style: ParsedStyle): List<StyleVoiceRequest> {
        val rows = ArrayList<StyleVoiceRequest>()
        for ((sectionName, section) in style.sections) {
            // Mirror StyleSequencer.applyVoicesFromCasm: the first part with a
            // tick-0 program in 0..127 owns the destination's setup.
            val owner = HashMap<Int, Int>()
            section.parts.forEachIndexed { index, part ->
                val policy = part.casmPolicies.firstOrNull() ?: part.casm ?: return@forEachIndexed
                if (part.program in 0..127 && !owner.containsKey(policy.destinationChannel))
                    owner[policy.destinationChannel] = index
            }
            section.parts.forEachIndexed { index, part ->
                val policy = part.casmPolicies.firstOrNull() ?: part.casm
                val source = policy?.sourceChannel ?: part.events.firstOrNull { it.isChannelVoice }?.channel ?: -1
                val destination = policy?.destinationChannel ?: source
                val channelEvents = part.events.filter { it.isChannelVoice && (source < 0 || it.channel == source) }
                    .sortedBy { it.tick }
                val tick0 = channelEvents.filter { it.tick == 0 }
                val msb = tick0.lastOrNull { it.isControlChange && it.note == 0 }?.velocity
                val lsb = tick0.lastOrNull { it.isControlChange && it.note == 32 }?.velocity
                val program = tick0.lastOrNull { it.isProgramChange }?.note
                val voiceName = policy?.voiceName.orEmpty()
                rows += StyleVoiceRequest(
                    section = sectionName,
                    partIndex = index,
                    partName = part.name,
                    sourceChannel = source,
                    destinationChannel = destination,
                    hasCasm = policy != null,
                    casmVoiceName = voiceName,
                    msb = msb,
                    lsb = lsb,
                    program = program,
                    drum = isDrumDestination(destination, voiceName),
                    appliedToDestination = owner[destination] == index ||
                        (policy != null && !owner.containsKey(destination) && section.parts.indexOfFirst {
                            (it.casmPolicies.firstOrNull() ?: it.casm)?.destinationChannel == destination
                        } == index),
                    dynamicChanges = dynamicChanges(channelEvents, msb, lsb)
                )
            }
        }
        return rows
    }

    private fun dynamicChanges(events: List<StyleNoteEvent>, msb0: Int?, lsb0: Int?): List<StyleProgramChange> {
        var msb = msb0; var lsb = lsb0
        val out = ArrayList<StyleProgramChange>()
        for (e in events) {
            if (e.tick == 0) continue
            when {
                e.isControlChange && e.note == 0 -> msb = e.velocity
                e.isControlChange && e.note == 32 -> lsb = e.velocity
                e.isProgramChange -> out += StyleProgramChange(e.tick, msb, lsb, e.note)
            }
        }
        return out
    }

    /** Same role rule as StyleSequencer: Rhythm 1/2 are 8/9; 10..15 are melodic. */
    fun isDrumDestination(destination: Int, voiceName: String): Boolean =
        destination == 8 || destination == 9 || (destination !in 10..15 && isDrumVoice(voiceName))

    private fun isDrumVoice(name: String) = name.lowercase().trim().let {
        it.contains("crash") || it.contains("cymbal") || it.contains("perc") ||
            it.contains("add-dr") || it.contains("drum") || it.contains("kit") ||
            it == "dr" || it.startsWith("dr.") || it.startsWith("dr_") ||
            it.startsWith("dr ") || it.startsWith("dr-")
    }

    fun report(rows: List<StyleVoiceRequest>): String = buildString {
        for (r in rows) {
            append("STYLE_VOICE_REQUEST section='").append(r.section).append("' part=").append(r.partIndex)
                .append(" name='").append(r.partName).append("' srcCh=").append(r.sourceChannel)
                .append(" dstCh=").append(r.destinationChannel).append(" casm=").append(if (r.hasCasm) 1 else 0)
                .append(" voice='").append(r.casmVoiceName).append("' msb:lsb=").append(r.bankText)
                .append(" pc=").append(r.programText).append(" role=").append(if (r.drum) "DRUM" else "MELODY")
                .append(" applied=").append(if (r.appliedToDestination) 1 else 0)
                .append(" dynamicPC=").append(r.dynamicChanges.joinToString(",") {
                    "${it.tick}@${it.msb ?: "-"}:${it.lsb ?: "-"}:${it.program}"
                }.ifEmpty { "-" }).append('\n')
        }
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.ParsedStyle
import java.util.concurrent.atomic.AtomicLongArray

/** Fixed counters only on the scheduler path. Formatting/inventory traversal is explicit export. */
internal class StylePartPresence {
    private val counts = AtomicLongArray(16 * WIDTH)
    fun reset() { for (i in 0 until counts.length()) counts.set(i, 0) }
    fun seen(ch: Int) = add(ch, SEEN)
    fun beforeTransform(ch: Int) = add(ch, BEFORE)
    fun missingPolicy(ch: Int) = add(ch, MISSING_POLICY)
    fun decision(ch: Int, stage: String) {
        val column = when (stage) {
            "FORWARD" -> FORWARD
            "DROP_NO_POLICY_WITH_CHORD" -> POLICY
            "DROP_UNSUPPORTED_ARTICULATION" -> ARTICULATION
            "DROP_RESERVED_KEYBOARD" -> RESERVED
            "DROP_LOCKED" -> LOCKED
            "DROP_MUTED" -> MUTED
            "DROP_TRANSFORM_NULL" -> TRANSFORM
            else -> return // NOTE_OFF/retarget are not scheduled NOTE_ON losses.
        }
        add(ch, column)
    }
    private fun add(ch: Int, column: Int) { if (ch in 0..15) counts.incrementAndGet(ch * WIDTH + column) }
    fun report(style: ParsedStyle): String = buildString {
        appendLine("=== ACCOMPANIMENT PART PRESENCE / SCHEDULER ===")
        appendLine("style='${style.fileName}' counters=since_style_load scheduled_ON_only; raw=one_pass_all_parsed_sections; runtime_includes_repeats")
        appendLine("afterTransform/bridgeCalls are scheduler forwards, not native acceptance or audible PCM. channels=zero_based; midiChannel=one_based")
        val raw = LongArray(16)
        val sources = Array(16) { sortedSetOf<Int>() }
        val voices = Array(16) { sortedSetOf<String>() }
        var ambiguous = 0L
        style.sections.values.forEach { section -> section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            part.events.filter { it.isNoteOn && it.velocity > 0 }.forEach { event ->
                val declared = policies.map { it.destinationChannel }.distinct()
                val destination = if (declared.isEmpty()) event.channel else declared.singleOrNull()
                if (destination == null) ambiguous++
                else if (destination in 0..15) { raw[destination]++; sources[destination] += event.channel }
            }
            policies.forEach { if (it.destinationChannel in 0..15) voices[it.destinationChannel] += it.voiceName }
        } }
        for (ch in 8..15) {
            fun n(column: Int) = counts.get(ch * WIDTH + column)
            appendLine("PART role=${ROLES[ch-8]} ch=$ch midiChannel=${ch+1} parsedRaw=${raw[ch]} src=${sources[ch]} voices=${voices[ch].joinToString().take(160)} seen=${n(SEEN)} beforeTransform=${n(BEFORE)} afterTransform=${n(FORWARD)} bridgeCalls=${n(FORWARD)} policyRejected=${n(POLICY)} missingCASMRejected=${n(MISSING_POLICY)} maskOrRangeRejected=${n(POLICY)-n(MISSING_POLICY)} articulationRejected=${n(ARTICULATION)} reserved=${n(RESERVED)} locked=${n(LOCKED)} muted=${n(MUTED)} transformRejected=${n(TRANSFORM)}")
        }
        appendLine("parsedAmbiguousDestination=$ambiguous; rejected policy counters retain CASM masks/ranges unchanged")
        style.sections.values.forEach { section -> section.parts.forEachIndexed { index, part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            appendLine("SOURCE section=${section.name} part=$index src=${part.events.filter { it.isNoteOn }.map { it.channel }.distinct()} destinations=${policies.map { it.destinationChannel }.distinct()} rawON=${part.events.count { it.isNoteOn }} bank=${part.bankMsb}:${part.bankLsb} rawPC=${part.program} policies=${policies.size} missingCASM=${policies.isEmpty()}")
        } }
    }.let { text -> ChordReportBounds.lines("", text.lines(), 16 * 1024) }
    companion object {
        private const val WIDTH = 10
        private const val SEEN = 0
        private const val BEFORE = 1
        private const val FORWARD = 2
        private const val POLICY = 3
        private const val ARTICULATION = 4
        private const val RESERVED = 5
        private const val LOCKED = 6
        private const val MUTED = 7
        private const val TRANSFORM = 8
        private const val MISSING_POLICY = 9
        private val ROLES = arrayOf("Rhythm1", "Rhythm2", "Bass", "Chord1", "Chord2", "Pad", "Phrase1", "Phrase2")
    }
}

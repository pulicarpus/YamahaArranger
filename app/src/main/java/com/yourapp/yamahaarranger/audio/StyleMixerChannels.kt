package com.yourapp.yamahaarranger.audio

import com.yourapp.yamahaarranger.style.StylePartModel

/** Display inventory only: it never modifies CASM or schedules events. */
object StyleMixerChannels {
    fun destinations(part: StylePartModel): Set<Int> {
        val notes = part.events.filter { (it.status and 0xF0) == 0x90 && it.velocity > 0 }
        val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
        val channels = if (policies.isEmpty()) notes.map { it.channel } else
            policies.filter { policy -> notes.any { it.channel == policy.sourceChannel && it.note in policy.sourceNoteLow..policy.sourceNoteHigh } }
                .map { it.destinationChannel }
        return channels.filter { it in 4..15 }.toSortedSet()
    }
}

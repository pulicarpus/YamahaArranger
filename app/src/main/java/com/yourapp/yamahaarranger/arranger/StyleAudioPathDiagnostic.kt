package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import java.util.concurrent.atomic.AtomicLong

/** Observer only. Does not select policy, transform notes, change MIDI or control time. */
internal class StyleAudioPathDiagnostic(private val section: String, private val presence: StylePartPresence? = null) {
    private val seen = IntArray(16)
    private val forwarded = IntArray(16)
    private val drops = Array(16) { linkedMapOf<String, Int>() }

    fun observe(ch: Int): Long {
        presence?.seen(ch)
        if (ch in 0..15) seen[ch]++
        return nextId()
    }
    fun event(id: Long, ch: Int, src: Int, original: Int, output: Int, velocity: Int,
              bank: Int, tick: Long, voice: String, stage: String, detail: String = ""): Boolean {
        presence?.decision(ch, stage)
        if (ch in 0..15) {
            if (stage == "FORWARD") forwarded[ch]++
            else drops[ch][stage] = (drops[ch][stage] ?: 0) + 1
        }
        val sample = allow(ch, (ch == 8 || ch == 9) && (original == 38 || original == 40 || output == 38 || output == 40))
        if (sample) DebugLog.add("STYLE PATH id=$id section='$section' ch=$ch src=$src original=$original output=$output vel=$velocity styleBank=${bank / 128}:${bank % 128} tick=$tick voice='$voice' stage=$stage $detail")
        return sample
    }
    fun finish() {
        for (ch in 0..15) if (seen[ch] > 0 || forwarded[ch] > 0 || drops[ch].isNotEmpty())
            DebugLog.add("STYLE SUMMARY section='$section' ch=$ch seen=${seen[ch]} forwarded=${forwarded[ch]} decisions=${drops[ch]} channelNumbers=zero_based")
    }
    companion object {
        private val ids = AtomicLong()
        fun nextId(): Long = ids.incrementAndGet()
        private val windows = LongArray(16)
        private val normal = IntArray(16)
        private val snares = IntArray(16)
        @Synchronized private fun allow(ch: Int, special: Boolean): Boolean {
            if (ch !in 0..15) return false
            val now = System.nanoTime() / 1_000_000L
            if (now - windows[ch] >= 2000) { windows[ch] = now; normal[ch] = 0; snares[ch] = 0 }
            val counts = if (special) snares else normal
            if (counts[ch] >= 4) return false
            counts[ch]++
            return true
        }
        private val inspectedSections = linkedSetOf<Int>()
        @Synchronized fun inventory(section: StyleSectionModel) {
            // Emit once per section object; do not repeat inventory every bar/Fill.
            if (!inspectedSections.add(System.identityHashCode(section))) return
            if (inspectedSections.size > 32) inspectedSections.remove(inspectedSections.first())
            section.parts.take(24).forEachIndexed { index, part ->
                val on = part.events.filter { it.isNoteOn }
                val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
                DebugLog.add("STYLE NOTE MAP section='${section.name}' part=$index src=${on.map { it.channel }.distinct()} dst=${policies.map { it.destinationChannel }.distinct()} keys=${on.groupingBy { it.note }.eachCount().toSortedMap()} velocities=${on.groupingBy { it.velocity }.eachCount().toSortedMap()} evidence=raw_style_not_remapped")
                DebugLog.add("STYLE INVENTORY section='${section.name}' part=$index src=${on.map { it.channel }.distinct()} dst=${policies.map { it.destinationChannel }.distinct()} voices='${policies.map { it.voiceName }.distinct().joinToString()}' headerBank=${part.bankMsb}:${part.bankLsb} headerPC=${part.program} noteOns=${on.size} noteOffs=${part.events.count { ((it.status and 0xF0) == 0x80 || ((it.status and 0xF0) == 0x90 && !it.isNoteOn)) }} raw38=${on.count { it.note == 38 }} raw40=${on.count { it.note == 40 }} velocityMin=${on.minOfOrNull { it.velocity }} velocityMax=${on.maxOfOrNull { it.velocity }}")
            }
        }
    }
}


package com.yourapp.yamahaarranger.e343out

import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleVoiceRequests
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Style part roles of the destination channels (0-based MIDI channel; display = +1). */
object E343Roles {
    val names = mapOf(8 to "Rhythm 1", 9 to "Rhythm 2", 10 to "Bass", 11 to "Chord 1", 12 to "Chord 2", 13 to "Pad", 14 to "Phrase 1", 15 to "Phrase 2")
    fun of(channel: Int) = names[channel] ?: "ch${channel + 1}"
}

/** Authored level of one part, read from the Style data (Main sections): NOTE ON count, median velocity, CC7 and CC11 at the part setup. */
data class E343PartLevel(val channel: Int, val noteOns: Int, val velocityMedian: Int, val cc7: Int, val cc11: Int) {
    /** Estimated relative level in dB: 40·log10(x/127) for CC7, CC11 and velocity (the GM2 recommended gain laws; the E343 curve itself is not published). */
    val levelDb: Double get() = 40 * log10(cc7.coerceAtLeast(1) / 127.0) + 40 * log10(cc11.coerceAtLeast(1) / 127.0) + 40 * log10(velocityMedian.coerceAtLeast(1) / 127.0)
}

data class E343BalanceRow(val channel: Int, val level: E343PartLevel?, val autoTrim: Int, val cutDb: Double, val reason: String)

/**
 * AUTO BALANCE for MIDI OUT to the PSR-E343. One CC7 trim per part for the whole Style (127 = unchanged), so a section change never moves a
 * part's level by itself: the Style's own CC7/CC11 changes between sections still pass through, scaled by the same constant. Velocity is never
 * changed. Only parts that are louder than their role ceiling (relative to the Style Bass) are lowered, never raised; Rhythm and Bass are never trimmed.
 *
 * Ceilings were calibrated on ONE fixture: Love Song.T547.prs on the PSR-E343 balanced by ear by the user with slider positions
 * Chord1 63 / Pad 99 / Phrase 97 (Rhythm, Bass, Chord2 127). They are a starting point, not a measurement; the result must be checked by ear.
 */
object E343BalancePlanner {
    /** Highest allowed level of a role, in dB relative to the Bass part. */
    val ceilingDb = mapOf(11 to -2.5, 12 to -2.5, 13 to -17.5, 14 to -17.5, 15 to -17.5)
    const val MAX_CUT_DB = 16.0

    fun trimForCut(cutDb: Double): Int = (127 * 10.0.pow(-cutDb / 40)).roundToInt().coerceIn(1, 127)
    fun cutForTrim(trim: Int): Double = -40 * log10(trim.coerceIn(1, 127) / 127.0)

    fun plan(levels: Map<Int, E343PartLevel>): Map<Int, E343BalanceRow> {
        val out = HashMap<Int, E343BalanceRow>()
        val bass = levels[10]?.takeIf { it.noteOns > 0 }
        for (ch in 8..15) {
            val l = levels[ch]?.takeIf { it.noteOns > 0 }
            val ceil = ceilingDb[ch]
            out[ch] = when {
                l == null -> E343BalanceRow(ch, null, 127, 0.0, "no notes in the Main sections")
                ceil == null -> E343BalanceRow(ch, l, 127, 0.0, if (ch == 10) "Bass is the reference: not trimmed" else "Rhythm: not trimmed")
                bass == null -> E343BalanceRow(ch, l, 127, 0.0, "no Bass part to compare with: not trimmed")
                else -> {
                    val over = l.levelDb - (bass.levelDb + ceil)
                    if (over <= 0) E343BalanceRow(ch, l, 127, 0.0, "%.1f dB vs Bass, within the %s ceiling %.1f dB: not trimmed".format(l.levelDb - bass.levelDb, E343Roles.of(ch), ceil))
                    else {
                        val cut = over.coerceAtMost(MAX_CUT_DB)
                        val trim = trimForCut(cut)
                        E343BalanceRow(ch, l, trim, cutForTrim(trim), "%.1f dB vs Bass, %.1f dB over the %s ceiling %.1f dB: CC7 x %d/127 (-%.1f dB)%s".format(
                            l.levelDb - bass.levelDb, over, E343Roles.of(ch), ceil, trim, cutForTrim(trim), if (over > MAX_CUT_DB) " (limited)" else ""))
                    }
                }
            }
        }
        return out
    }
}

/** Everything AUTO E343 needs about one Style: voice decisions per requested Bank/Program, part usage, authored levels and the balance trims. */
class E343StylePlan(
    val styleName: String,
    val decisions: List<E343VoiceDecision>,
    val names: Map<Int, String>,
    val usage: Map<Int, E343PartUsage>,
    val levels: Map<Int, E343PartLevel>,
    val balance: Map<Int, E343BalanceRow>,
) {
    private val byKey = decisions.associateBy { key(it.channel, it.msb, it.lsb, it.program) }
    fun decision(channel: Int, msb: Int, lsb: Int, program: Int): E343VoiceDecision? = byKey[key(channel, msb, lsb, program)]
    fun forChannel(channel: Int) = decisions.filter { it.channel == channel }
    companion object {
        fun key(ch: Int, msb: Int, lsb: Int, pc: Int) = (ch shl 21) or (msb shl 14) or (lsb shl 7) or pc
        val EMPTY = E343StylePlan("", emptyList(), emptyMap(), emptyMap(), emptyMap(), E343BalancePlanner.plan(emptyMap()))
    }
}

object E343StylePlanner {
    private fun isMain(section: String) = section.contains("main", ignoreCase = true)

    fun plan(style: ParsedStyle, mapper: E343VoiceMapper): E343StylePlan {
        val requests = StyleVoiceRequests.extract(style).filter { it.destinationChannel in 8..15 }
        // Usage of every part, all sections (velocity zones and drum notes are a property of the whole Style).
        val usage = HashMap<Int, E343PartUsage>()
        val mainVel = HashMap<Int, E343PartUsage>()
        val cc7w = HashMap<Int, MutableMap<Int, Int>>(); val cc11w = HashMap<Int, MutableMap<Int, Int>>()
        val hasMain = style.sections.keys.any(::isMain)
        for ((sectionName, section) in style.sections) for (part in section.parts) {
            val policy = part.casmPolicies.firstOrNull() ?: part.casm
            val source = policy?.sourceChannel ?: part.events.firstOrNull { it.isChannelVoice }?.channel ?: continue
            val dst = policy?.destinationChannel ?: source
            if (dst !in 8..15) continue
            val ons = part.events.filter { it.isChannelVoice && it.channel == source && it.isNoteOn && it.velocity > 0 && (it.status and 0xF0) == 0x90 }
            val u = usage.getOrPut(dst) { E343PartUsage() }
            for (e in ons) { u.velocity[e.velocity.coerceIn(0, 127)]++; u.drumNotes.merge(e.note, 1, Int::plus) }
            if (!hasMain || isMain(sectionName)) {
                val m = mainVel.getOrPut(dst) { E343PartUsage() }
                for (e in ons) m.velocity[e.velocity.coerceIn(0, 127)]++
                val tick0 = part.events.filter { it.tick == 0 && it.isControlChange && it.channel == source }
                val cc7 = part.volume.takeIf { it in 0..127 } ?: tick0.lastOrNull { it.note == 7 }?.velocity ?: 100
                val cc11 = part.expression.takeIf { it in 0..127 } ?: tick0.lastOrNull { it.note == 11 }?.velocity ?: 127
                if (ons.isNotEmpty()) { cc7w.getOrPut(dst) { HashMap() }.merge(cc7, ons.size, Int::plus); cc11w.getOrPut(dst) { HashMap() }.merge(cc11, ons.size, Int::plus) }
            }
        }
        fun wMedian(m: Map<Int, Int>?): Int? { if (m.isNullOrEmpty()) return null; val n = m.values.sum(); var acc = 0; for ((v, c) in m.toSortedMap()) { acc += c; if (acc * 2 >= n) return v }; return null }
        val levels = mainVel.mapNotNull { (ch, u) -> val c7 = wMedian(cc7w[ch]) ?: return@mapNotNull null
            ch to E343PartLevel(ch, u.noteOns, u.median(), c7, wMedian(cc11w[ch]) ?: 127) }.toMap()

        val names = requests.filter { it.casmVoiceName.isNotBlank() }.groupBy { it.destinationChannel }
            .mapValues { (_, l) -> l.groupingBy { it.casmVoiceName }.eachCount().maxByOrNull { it.value }!!.key }
        val seen = LinkedHashMap<Int, E343VoiceDecision>()
        for (r in requests) {
            val candidates = ArrayList<Triple<Int, Int, Int>>()
            if (r.program != null) candidates += Triple(r.msb ?: 0, r.lsb ?: 0, r.program)
            for (d in r.dynamicChanges) candidates += Triple(d.msb ?: r.msb ?: 0, d.lsb ?: r.lsb ?: 0, d.program)
            for ((m, l, p) in candidates) {
                val k = E343StylePlan.key(r.destinationChannel, m, l, p)
                if (k in seen) continue
                val drum = r.drum || m >= 126
                seen[k] = mapper.map(r.destinationChannel, m, l, p, r.casmVoiceName.ifBlank { names[r.destinationChannel].orEmpty() }, drum, usage[r.destinationChannel])
            }
        }
        return E343StylePlan(style.fileName, seen.values.toList(), names, usage, levels, E343BalancePlanner.plan(levels))
    }
}

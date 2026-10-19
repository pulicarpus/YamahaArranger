package com.yourapp.yamahaarranger.ui

import com.yourapp.yamahaarranger.style.ParsedStyle

/** LIVE V3 presentation model. Pure Kotlin: no Compose, Android, engine or MIDI dependency. */
enum class SectionPresence { PRESENT, ABSENT, UNKNOWN }

/** AVAILABLE blue, ACTIVE amber, ABSENT dark gray (disabled), UNKNOWN neutral (enabled, never claimed absent). */
enum class SectionLed { AVAILABLE, ACTIVE, ABSENT, UNKNOWN }

/** [label] is the existing ViewModel button label (`onSectionSelected`), [styleName] the parser section key. */
data class LiveSectionButton(val group: String, val caption: String, val label: String, val styleName: String)

object LiveSectionGrid {
    val intro = listOf("A", "B", "C").mapIndexed { i, l -> LiveSectionButton("INTRO", "${i + 1}", "Intro ${i + 1}", "Intro$l") }
    val main = listOf("A", "B", "C", "D").map { LiveSectionButton("MAIN", it, "Main $it", "Main$it") }
    val fill = listOf("A", "B", "C", "D").map { LiveSectionButton("FILL", it, "Fill $it", "Fill$it$it") }
    val ending = listOf("A", "B", "C").mapIndexed { i, l -> LiveSectionButton("ENDING", "${i + 1}", "Ending ${i + 1}", "Ending$l") }
    val rows = listOf(intro, main, fill, ending)
}

/**
 * Which sections the loaded style really contains. Built only from a successfully parsed style; no style, or a parse
 * that produced no sections at all, is UNKNOWN. A section is ABSENT only when the parsed table is non-empty and lacks it.
 */
class LiveSectionInventory private constructor(private val names: Set<String>?) {
    val known: Boolean get() = names != null
    fun presence(styleName: String): SectionPresence = when {
        names == null -> SectionPresence.UNKNOWN
        styleName in names -> SectionPresence.PRESENT
        else -> SectionPresence.ABSENT
    }
    companion object {
        val UNKNOWN = LiveSectionInventory(null)
        fun from(style: ParsedStyle?): LiveSectionInventory =
            if (style == null || style.sections.isEmpty()) UNKNOWN else LiveSectionInventory(style.sections.keys.toSet())
    }
}

fun liveSectionLed(inventory: LiveSectionInventory, button: LiveSectionButton, activeLabel: String): SectionLed =
    when (inventory.presence(button.styleName)) {
        SectionPresence.ABSENT -> SectionLed.ABSENT
        SectionPresence.PRESENT -> if (button.label == activeLabel) SectionLed.ACTIVE else SectionLed.AVAILABLE
        SectionPresence.UNKNOWN -> if (button.label == activeLabel) SectionLed.ACTIVE else SectionLed.UNKNOWN
    }

/** Only a confirmed-absent section is locked. */
fun liveSectionEnabled(led: SectionLed): Boolean = led != SectionLed.ABSENT

/** The four independent LIVE faders: label and the existing ViewModel volume action each one drives. */
enum class LiveFaderId(val label: String) { RIGHT1("R1"), RIGHT2("R2"), RIGHT3("R3"), STYLE("STYLE") }

/** Style-control layout plan (dp). One row of four groups when every key stays comfortably wide, otherwise two rows. */
object LiveStyleLayout {
    const val MAIN_WEIGHT = 1.25f
    const val ONE_ROW_MIN_KEY = 52f
    const val MIN_TOUCH = 48f
    private const val KEY_GAP = 4f
    private const val GROUP_GAP = 8f
    private const val GROUP_PAD = 8f
    /** Narrowest key (INTRO/FILL/ENDING caption keys) for an area of [areaWidthDp]. */
    fun minKeyWidth(areaWidthDp: Float, oneRow: Boolean): Float =
        if (oneRow) (areaWidthDp - 3 * GROUP_GAP - 8 * GROUP_PAD - 10 * KEY_GAP) / (3f + 4 * MAIN_WEIGHT + 4f + 3f)
        else minOf((areaWidthDp - GROUP_GAP - 4 * GROUP_PAD - 5 * KEY_GAP) / (3f + 4 * MAIN_WEIGHT),
                   (areaWidthDp - GROUP_GAP - 4 * GROUP_PAD - 5 * KEY_GAP) / 7f)
    fun oneRow(areaWidthDp: Float): Boolean = minKeyWidth(areaWidthDp, true) >= ONE_ROW_MIN_KEY
}

/** Registration key appearance: exactly one of these per slot. */
enum class RegKeyLook { ACTIVE, FILLED, EMPTY, SAVE_TARGET }
fun liveRegKeyLook(hasSlot: Boolean, selected: Boolean, saveArmed: Boolean): RegKeyLook = when {
    saveArmed -> RegKeyLook.SAVE_TARGET
    selected && hasSlot -> RegKeyLook.ACTIVE
    hasSlot -> RegKeyLook.FILLED
    else -> RegKeyLook.EMPTY
}

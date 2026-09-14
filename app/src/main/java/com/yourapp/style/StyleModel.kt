package com.yourapp.yamahaarranger.style

/** A single note on/off event, tick-relative to its section's start. */
data class StyleNoteEvent(
    val tick: Int,
    val isNoteOn: Boolean,
    val note: Int,
    val velocity: Int
)

data class StylePartModel(
    val name: String,
    val events: List<StyleNoteEvent>
)

data class StyleSectionModel(
    val name: String,        // matches native styleSectionToString, e.g. "MainA"
    val lengthTicks: Int,
    val parts: List<StylePartModel>
)

data class ParsedStyle(
    val fileName: String,
    val ppq: Int,
    val sections: Map<String, StyleSectionModel>
) {
    /** Common Yamaha default; real BPM comes from the style's own tempo
     * meta-event once MODUL 2's tempo-track reading lands (Phase 2b). */
    val defaultTempoBpm: Int = 120
}

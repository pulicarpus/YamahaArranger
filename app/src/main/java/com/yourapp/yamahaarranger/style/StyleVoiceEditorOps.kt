package com.yourapp.yamahaarranger.style

/** Where an edit lands. THIS_SECTION = one part in one section; ALL_SECTIONS = the same part in every section of the Style. */
enum class EditScope { THIS_SECTION, ALL_SECTIONS }

/** What the user chose to play instead of the Style's request. */
sealed interface Replacement {
    /** Internal preset of a loaded SoundFont (identity = file name + SF2 content fingerprint + bank + program). */
    data class Sf2(val preset: CatalogPreset) : Replacement
    /** Bank/Program sent to the external module. Numbers are raw MIDI values (Program 0-based). */
    data class External(val voice: ExternalVoice) : Replacement
}

data class EditTarget(val section: String, val channel: Int, val original: OriginalRef?)

/** One level that differs between two profiles; [sf2]/[external] null means "removed". */
data class OverrideChange(
    val external: Boolean, val channel: Int, val scope: OverrideScope, val section: String?,
    val sf2: StyleVoiceOverride?, val externalEntry: StyleExternalOverride?
)

/** Pure editing rules for the Style Voice Editor; the view model only executes what this returns. */
object StyleVoiceEditorOps {
    /** Melodic style parts only. Rhythm 1/2 (8,9) carry the drum note map and stay with the Style. */
    fun editable(channel: Int) = channel in 10..15

    fun apply(record: StyleOverrideRecord, target: EditTarget, scope: EditScope, replacement: Replacement): StyleOverrideRecord {
        require(editable(target.channel)) { "Channel ${target.channel} is not editable" }
        val level = if (scope == EditScope.ALL_SECTIONS) OverrideScope.ALL else OverrideScope.SECTION
        val section = if (level == OverrideScope.SECTION) target.section else null
        return when (replacement) {
            is Replacement.Sf2 -> {
                val p = replacement.preset
                require(!p.drum) { "A kit cannot replace a melodic part" }
                record.withSf2(StyleVoiceOverride(target.channel, level, section,
                    StyleVoicePin(p.soundFontName, p.bank, p.program, p.name, false), p.pdta, target.original))
            }
            is Replacement.External -> {
                val v = replacement.voice
                require(v.msb in 0..127 && v.lsb in 0..127 && v.program in 0..127) { "MIDI numbers out of range" }
                record.withExternal(StyleExternalOverride(target.channel, level, section, v, target.original))
            }
        }
    }

    /** Everything that must be re-sent to the sequencer to turn [old] into [new]. */
    fun diff(old: StyleOverrideRecord?, new: StyleOverrideRecord?): List<OverrideChange> {
        val out = ArrayList<OverrideChange>()
        fun key(c: Int, s: OverrideScope, sec: String?) = Triple(c, s, if (s == OverrideScope.ALL) null else sec)
        val oldSf = (old?.overrides ?: emptyList()).associateBy { key(it.channel, it.scope, it.section) }
        val newSf = (new?.overrides ?: emptyList()).associateBy { key(it.channel, it.scope, it.section) }
        for (k in oldSf.keys + newSf.keys) if (oldSf[k] != newSf[k]) out += OverrideChange(false, k.first, k.second, k.third, newSf[k], null)
        val oldEx = (old?.externalOverrides ?: emptyList()).associateBy { key(it.channel, it.scope, it.section) }
        val newEx = (new?.externalOverrides ?: emptyList()).associateBy { key(it.channel, it.scope, it.section) }
        for (k in oldEx.keys + newEx.keys) if (oldEx[k] != newEx[k]) out += OverrideChange(true, k.first, k.second, k.third, null, newEx[k])
        return out
    }

    /** Melodic presets of every loaded SoundFont, not only GM. Kits (banks 127/128) are excluded for melodic parts. */
    fun sf2Candidates(catalog: List<CatalogPreset>, query: String): List<CatalogPreset> {
        val q = query.trim().lowercase()
        return catalog.filter { !it.drum && it.bank != 127 && it.bank != 128 }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.soundFontName.lowercase().contains(q) || "${it.bank}:${it.program}".contains(q) }
            .sortedWith(compareBy({ it.soundFontName.lowercase() }, { it.bank }, { it.program }))
    }

    /** Typed external voice. Program is entered 1-based, as on the instrument's data list. Null + reason on bad input. */
    fun parseExternal(msb: String, lsb: String, program1Based: String, label: String): Pair<ExternalVoice?, String?> {
        val m = msb.trim().toIntOrNull(); val l = lsb.trim().toIntOrNull(); val p = program1Based.trim().toIntOrNull()
        if (m == null || l == null || p == null) return null to "MSB, LSB and Program must be numbers."
        if (m !in 0..127 || l !in 0..127) return null to "MSB and LSB must be 0-127."
        if (p !in 1..128) return null to "Program must be 1-128 (sent as 0-127)."
        return ExternalVoice(m, l, p - 1, label.trim().take(24).ifBlank { "$m/$l/$p" }) to null
    }

    /** SF2 override states for display: ACTIVE, STALE_* (from [StyleOverridePins]) or UNVERIFIED. */
    fun describePin(pin: StyleVoicePin) = "${pin.soundFontName} ${pin.bank}:${pin.program} '${pin.presetName}'"

    fun describeExternal(v: ExternalVoice) = "MIDI OUT ${v.msb}:${v.lsb}:${v.program} (display PC ${v.program + 1}) '${v.label}'"

    /**
     * Row-level override summary per (section, destination channel) using the documented priority:
     * section-specific, then part-wide, then the Style. [sf2State]/[externalState] give ACTIVE/STALE/UNVERIFIED.
     */
    fun rowOverrides(
        record: StyleOverrideRecord?, sections: Collection<String>, midiOutActive: Boolean,
        sf2State: (StyleVoiceOverride) -> String, externalState: (StyleExternalOverride) -> String
    ): Map<Pair<String, Int>, RowOverride> {
        if (record == null || record.isEmpty) return emptyMap()
        val channels = (record.overrides.map { it.channel } + record.externalOverrides.map { it.channel }).distinct()
        val out = HashMap<Pair<String, Int>, RowOverride>()
        for (section in sections) for (ch in channels) {
            val sf = StyleOverrideResolver.sf2(record, section, ch)
            val ex = StyleOverrideResolver.external(record, section, ch)
            if (sf == null && ex == null) continue
            val parts = ArrayList<String>(); val states = ArrayList<String>()
            sf?.let { parts += "INTERNAL ${describePin(it.pin)}"; states += sf2State(it) }
            ex?.let {
                parts += describeExternal(it.voice) + if (midiOutActive) "" else " — sent when MIDI OUT is on"
                states += externalState(it)
            }
            val sectionLevel = sf?.scope == OverrideScope.SECTION || ex?.scope == OverrideScope.SECTION
            val state = states.firstOrNull { it != "ACTIVE" } ?: "ACTIVE"
            out[section to ch] = RowOverride(
                if (sf != null && ex != null) "INTERNAL + MIDI OUT" else if (sf != null) "INTERNAL" else "MIDI OUT",
                if (sectionLevel) OverrideScope.SECTION else OverrideScope.ALL, parts.joinToString(" · "), state)
        }
        return out
    }

    /** Test notes for AUDITION by role; fixed, short, in the part's natural register. */
    fun auditionNotes(channel: Int): List<Int> = when (channel) {
        10 -> listOf(36, 43, 48, 43)      // Bass
        else -> listOf(60, 64, 67, 72)    // Chord / Pad / Phrase
    }
}

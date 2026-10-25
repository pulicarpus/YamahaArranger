package com.yourapp.yamahaarranger.style

/** Where an edit lands. THIS_SECTION = one part in one section; ALL_SECTIONS = the same part in every section of the Style. */
enum class EditScope { THIS_SECTION, ALL_SECTIONS }

/** What the user chose to play instead of the Style's request. */
sealed interface Replacement {
    /** Internal preset of a loaded SoundFont (identity = file name + SF2 content fingerprint + bank + program). */
    data class Sf2(val preset: CatalogPreset) : Replacement
    /** Bank/Program sent to the external module. Numbers are raw MIDI values (Program 0-based). */
    data class External(val voice: ExternalVoice) : Replacement
    /** Rhythm 1/2 kit from any loaded SoundFont (raw drum bank 127/128). Whole Style only; drum note mapping is not touched. */
    data class DrumKit(val preset: CatalogPreset) : Replacement
}

data class EditTarget(val section: String, val channel: Int, val original: OriginalRef?)

/** One level that differs between two profiles; [sf2]/[external] null means "removed". */
data class OverrideChange(
    val external: Boolean, val channel: Int, val scope: OverrideScope, val section: String?,
    val sf2: StyleVoiceOverride?, val externalEntry: StyleExternalOverride?
)

/** Pure editing rules for the Style Voice Editor; the view model only executes what this returns. */
object StyleVoiceEditorOps {
    /** Melodic style parts. */
    fun editable(channel: Int) = channel in 10..15
    /** Rhythm 1/2: the KIT can be chosen; the drum note map stays exactly as the Style authored it. */
    fun editableKit(channel: Int) = channel in 8..9

    fun apply(record: StyleOverrideRecord, target: EditTarget, scope: EditScope, replacement: Replacement): StyleOverrideRecord {
        val level = if (scope == EditScope.ALL_SECTIONS) OverrideScope.ALL else OverrideScope.SECTION
        val section = if (level == OverrideScope.SECTION) target.section else null
        return when (replacement) {
            is Replacement.DrumKit -> {
                require(editableKit(target.channel)) { "A drum kit can only replace Rhythm 1 or Rhythm 2" }
                require(scope == EditScope.ALL_SECTIONS) { "Drum kits apply to the whole Style in this version" }
                val p = replacement.preset
                require(p.drum && (p.bank == 127 || p.bank == 128)) { "Not a drum kit preset" }
                record.withSf2(StyleVoiceOverride(target.channel, OverrideScope.ALL, null,
                    StyleVoicePin(p.soundFontName, p.bank, p.program, p.name, true), p.pdta, target.original))
            }
            is Replacement.Sf2 -> {
                require(editable(target.channel)) { "Channel ${target.channel} is not editable" }
                val p = replacement.preset
                require(!p.drum) { "A kit cannot replace a melodic part" }
                record.withSf2(StyleVoiceOverride(target.channel, level, section,
                    StyleVoicePin(p.soundFontName, p.bank, p.program, p.name, false), p.pdta, target.original))
            }
            is Replacement.External -> {
                require(editable(target.channel)) { "Channel ${target.channel} is not editable" }
                val v = replacement.voice
                require(v.msb in 0..127 && v.lsb in 0..127 && v.program in 0..127) { "MIDI numbers out of range" }
                record.withExternal(StyleExternalOverride(target.channel, level, section, v, target.original))
            }
        }
    }

    // ---- V2: octave shift per melodic part and drum note maps (profile-only; the Style file is never touched) ----
    const val OCTAVE_MIN = -2
    const val OCTAVE_MAX = 2

    fun applyOctave(record: StyleOverrideRecord, target: EditTarget, scope: EditScope, octaves: Int): StyleOverrideRecord {
        require(editable(target.channel)) { "Octave shift is for the melodic parts (Bass, Chord, Pad, Phrase)" }
        require(octaves in OCTAVE_MIN..OCTAVE_MAX) { "Octave shift must be -2 to +2" }
        val level = if (scope == EditScope.ALL_SECTIONS) OverrideScope.ALL else OverrideScope.SECTION
        return record.withOctave(StyleOctaveShift(target.channel, level, if (level == OverrideScope.SECTION) target.section else null, octaves))
    }

    /** Removes the octave entry stored at exactly this level (RESET). */
    fun clearOctave(record: StyleOverrideRecord, target: EditTarget, scope: EditScope): StyleOverrideRecord {
        require(editable(target.channel)) { "Octave shift is for the melodic parts (Bass, Chord, Pad, Phrase)" }
        val level = if (scope == EditScope.ALL_SECTIONS) OverrideScope.ALL else OverrideScope.SECTION
        return record.withoutOctave(target.channel, level, if (level == OverrideScope.SECTION) target.section else null)
    }

    /** [notes] maps source note to target note; identical pairs are dropped (no change), an empty result removes the map. */
    fun applyDrumMap(record: StyleOverrideRecord, target: EditTarget, scope: EditScope, output: DrumOutput, notes: Map<Int, Int>): StyleOverrideRecord {
        require(editableKit(target.channel)) { "A note map is for Rhythm 1 or Rhythm 2" }
        require(notes.all { (k, v) -> k in 0..127 && v in 0..127 }) { "MIDI notes must be 0-127" }
        val level = if (scope == EditScope.ALL_SECTIONS) OverrideScope.ALL else OverrideScope.SECTION
        return record.withDrumMap(StyleDrumMap(target.channel, output, level, if (level == OverrideScope.SECTION) target.section else null,
            notes.filter { (k, v) -> k != v }))
    }

    /** One octave or drum-map level that differs between two profiles; [octaves]/[notes] null means "removed". */
    data class ExtraChange(val drum: Boolean, val channel: Int, val scope: OverrideScope, val section: String?,
        val output: DrumOutput?, val octaves: Int?, val notes: Map<Int, Int>?)

    fun diffExtras(old: StyleOverrideRecord?, new: StyleOverrideRecord?): List<ExtraChange> {
        val out = ArrayList<ExtraChange>()
        fun lv(s: OverrideScope, sec: String?) = if (s == OverrideScope.ALL) null else sec
        val oo = (old?.octaveShifts ?: emptyList()).associateBy { Triple(it.channel, it.scope, lv(it.scope, it.section)) }
        val no = (new?.octaveShifts ?: emptyList()).associateBy { Triple(it.channel, it.scope, lv(it.scope, it.section)) }
        for (k in oo.keys + no.keys) if (oo[k] != no[k]) out += ExtraChange(false, k.first, k.second, k.third, null, no[k]?.octaves, null)
        val od = (old?.drumMaps ?: emptyList()).associateBy { listOf(it.channel, it.output, it.scope, lv(it.scope, it.section)) }
        val nd = (new?.drumMaps ?: emptyList()).associateBy { listOf(it.channel, it.output, it.scope, lv(it.scope, it.section)) }
        for (k in od.keys + nd.keys) if (od[k] != nd[k]) {
            val ref = nd[k] ?: od[k]!!
            out += ExtraChange(true, ref.channel, ref.scope, ref.section, ref.output, null, nd[k]?.notes)
        }
        return out
    }

    /**
     * Drum notes the Style really plays on Rhythm 1 (destination 8) or Rhythm 2 (9), with how often: NOTE_ON events whose source channel the
     * CASM routes to [destination]. [section] null = the whole Style. Pure metadata; nothing is sounded.
     */
    fun drumNoteUsage(sections: Map<String, StyleSectionModel>, section: String?, destination: Int): Map<Int, Int> {
        val counts = java.util.TreeMap<Int, Int>()
        val selected = if (section == null) sections.values else listOfNotNull(sections[section])
        for (sec in selected) for (part in sec.parts) {
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            for (e in part.events) {
                if (!e.isNoteOn) continue
                val dst = policies.firstOrNull { it.sourceChannel == e.channel }?.destinationChannel ?: policies.firstOrNull()?.destinationChannel ?: e.channel
                if (dst == destination) counts.merge(e.note, 1, Int::plus)
            }
        }
        return counts
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

    /** Drum kits of every loaded SoundFont (raw drum banks 127/128 only); never mixed with melodic presets. */
    fun drumKitCandidates(catalog: List<CatalogPreset>, query: String): List<CatalogPreset> {
        val q = query.trim().lowercase()
        return catalog.filter { it.drum && (it.bank == 127 || it.bank == 128) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.soundFontName.lowercase().contains(q) || "${it.bank}:${it.program}".contains(q) }
            .sortedWith(compareBy({ it.soundFontName.lowercase() }, { it.bank }, { it.program }))
    }

    /**
     * Named PSR-E343 voices from a file the USER supplies (copied from the Yamaha PSR-E343 Data List): one voice per line,
     * `name <TAB> MSB <TAB> LSB <TAB> program(1-128)`; `,` or `;` also separate fields; `#` lines and a header line are ignored.
     * The app ships NO built-in list: nothing here is invented, and every line that is not valid is skipped.
     */
    fun parseDeviceCatalog(text: String): List<ExternalVoice> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('\t', ';', ',').map { it.trim() }
        if (f.size < 4) return@mapNotNull null
        val msb = f[1].toIntOrNull(); val lsb = f[2].toIntOrNull(); val pc = f[3].toIntOrNull()
        if (msb == null || lsb == null || pc == null || msb !in 0..127 || lsb !in 0..127 || pc !in 1..128 || f[0].isBlank()) null
        else ExternalVoice(msb, lsb, pc - 1, f[0].take(24))
    }.distinct().toList()

    /** Typed external voice. Program is entered 1-based, as on the instrument's data list. Null + reason on bad input. */
    fun parseExternal(msb: String, lsb: String, program1Based: String, label: String): Pair<ExternalVoice?, String?> {
        val m = msb.trim().toIntOrNull(); val l = lsb.trim().toIntOrNull(); val p = program1Based.trim().toIntOrNull()
        if (m == null || l == null || p == null) return null to "MSB, LSB and Program must be numbers."
        if (m !in 0..127 || l !in 0..127) return null to "MSB and LSB must be 0-127."
        if (p !in 1..128) return null to "Program must be 1-128 (sent as 0-127)."
        return ExternalVoice(m, l, p - 1, label.trim().take(24).ifBlank { "$m/$l/$p" }) to null
    }

    /** SF2 override states for display: ACTIVE, STALE_* (from [StyleOverridePins]) or UNVERIFIED. */
    fun describePin(pin: StyleVoicePin) = (if (pin.drum) "KIT " else "") + "${pin.soundFontName} ${pin.bank}:${pin.program} '${pin.presetName}'"

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
        8, 9 -> listOf(36, 38, 42, 46)    // Rhythm: kick, snare, closed/open hat (GM positions; the kit's own map is not changed)
        10 -> listOf(36, 43, 48, 43)      // Bass
        else -> listOf(60, 64, 67, 72)    // Chord / Pad / Phrase
    }
}

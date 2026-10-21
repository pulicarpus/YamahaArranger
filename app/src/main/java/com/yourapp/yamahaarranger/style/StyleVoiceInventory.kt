package com.yourapp.yamahaarranger.style

/**
 * Style Voice Inspector model (read-only). Joins what the style file requests
 * (StyleVoiceRequests), how CASM routes it, and what the engine reports it is
 * bound to. It never drives playback and never invents Voice names: a name is
 * shown only when the style's own CASM table carries one.
 */
enum class VoiceStatus { EXACT, FALLBACK, MISSING, AMBIGUOUS, UNKNOWN, OVERRIDDEN }

/** Where a fact comes from. Shown next to every resolved value. */
enum class Provenance {
    /** Bytes written in the style file (CC0/CC32/Program Change). */
    AUTHORED,
    /** Derived by this app from the file (CASM routing, role from destination channel). */
    INTERPRETED,
    /** Computed by comparing the request with the loaded preset catalog or another observed section. */
    RESOLVED,
    /** Read back from the audio engine for the section that is playing now. */
    RUNTIME_OBSERVED
}

/** Engine readback for one channel; mirrors audio.ChannelBinding without the Android dependency. */
data class ObservedBinding(
    val channel: Int,
    /** BOUND, UNRESOLVED, UNVERIFIED or UNINITIALIZED. */
    val status: String,
    /** PRIMARY, SECONDARY, DRUM_R23, DRUM_LEGACY, KEYBOARD, PINNED or NONE (as reported). */
    val source: String,
    val soundFontName: String,
    val pdta: String,
    val bank: Int,
    val program: Int,
    val presetName: String,
    val reason: String
)

/** Why a row has the status it has, when that is more specific than the status itself. */
object ReasonCode {
    /** The engine has not reported a binding for this channel (yet). Says nothing about whether the part is audible. */
    const val NO_BINDING_REPORTED = "NO_BINDING_REPORTED"
    /** The engine explicitly reported that no preset can serve the request. */
    const val PRESET_MISSING = "PRESET_MISSING"
    /** The engine reported a binding but could not verify it by readback. */
    const val BINDING_NOT_VERIFIED = "BINDING_NOT_VERIFIED"
    /** The runtime reading belongs to another section. */
    const val NOT_MEASURED_THIS_SECTION = "NOT_MEASURED_THIS_SECTION"
    /** The Inspector never measures audio output, so AUDIO_SILENT is never produced; listed so tests can assert that. */
    const val AUDIO_SILENT = "AUDIO_SILENT"
}

/** Engine reasons that are explicit evidence a preset is unavailable. Nothing else is read as MISSING. */
private val EXPLICIT_MISSING_REASONS = setOf("no_compatible_candidate", "keyboard_preset_missing")

/**
 * What the Inspector knows about the playback session when the snapshot was taken.
 * The engine binds channels when a section starts, so before START there is nothing to read.
 */
data class InspectorSession(val playing: Boolean = false)

/** One preset of a loaded SoundFont. [pdta] is the SF2 preset-data fingerprint when known. */
data class CatalogPreset(
    val soundFontName: String,
    val pdta: String,
    val bank: Int,
    val program: Int,
    val name: String,
    val drum: Boolean
)

/** Saved override that is in force for a row (internal SF2 pin or external MIDI OUT voice). */
data class RowOverride(
    val kind: String,
    val scope: OverrideScope,
    val description: String,
    /** ACTIVE, STALE (saved preset not available) or UNVERIFIED. */
    val state: String
)

/** One Voice request of one part in one section, with its resolution and provenance. */
data class StyleVoiceRow(
    val section: String,
    val sectionLabel: String,
    val partIndex: Int,
    val partName: String,
    val partRole: String,
    val sourceChannel: Int,
    val destinationChannel: Int,
    val hasCasm: Boolean,
    val casmVoiceName: String,
    val drum: Boolean,
    val appliedToDestination: Boolean,
    val msb: Int?,
    val lsb: Int?,
    val program: Int?,
    val dynamicChanges: List<StyleProgramChange>,
    val status: VoiceStatus,
    val statusReason: String,
    val actualDescription: String,
    val actualBank: Int?,
    val actualProgram: Int?,
    val actualSoundFont: String,
    val actualPresetName: String,
    val requestProvenance: Provenance,
    val routingProvenance: Provenance,
    val resolutionProvenance: Provenance?,
    /** See [ReasonCode]; empty when the status needs no extra code. */
    val reasonCode: String,
    val override: RowOverride?,
    /** Edit is refused with this reason (drums, no Program Change, ...). Null when editable. */
    val readOnlyReason: String?
) {
    /** Raw bytes as stored, e.g. "0:112:33". "-" marks a message the style does not send. */
    val rawRequest: String get() = "${msb ?: "-"}:${lsb ?: "-"}:${program ?: "-"}"

    /** 1-based display as shown on Yamaha hardware lists: Program 33 is shown as 34. */
    val displayRequest: String get() = if (program == null) "—"
        else "MSB ${msb ?: "-"} LSB ${lsb ?: "-"} PC ${program + 1}"

    /** Stable key for the part inside a section (used by overrides). */
    val partKey: String get() = "$destinationChannel"
}

data class StyleVoiceInventory(
    val styleName: String,
    val dialect: StyleDialect,
    val dialectEvidence: StyleDialectEvidence,
    val rows: List<StyleVoiceRequestRowGroup>,
    val activeSection: String?,
    val runtimeObserved: Boolean,
    val session: InspectorSession = InspectorSession()
) {
    val sections: List<String> get() = rows.map { it.section }
    val flat: List<StyleVoiceRow> get() = rows.flatMap { it.rows }
    fun forSection(section: String): List<StyleVoiceRow> = rows.firstOrNull { it.section == section }?.rows.orEmpty()
    val partCount: Int get() = flat.map { it.destinationChannel }.distinct().size
    val voiceCount: Int get() = flat.count { it.program != null }
    val dialectText: String get() = when (dialect) {
        StyleDialect.SFF1 -> "SFF1"
        StyleDialect.SFF2 -> "SFF2"
        StyleDialect.UNKNOWN -> "SFF format not identified"
    }
}

data class StyleVoiceRequestRowGroup(val section: String, val label: String, val rows: List<StyleVoiceRow>)

object StyleVoiceInventoryBuilder {
    private val partRoles = mapOf(
        8 to "Rhythm 1", 9 to "Rhythm 2", 10 to "Bass", 11 to "Chord 1",
        12 to "Chord 2", 13 to "Pad", 14 to "Phrase 1", 15 to "Phrase 2"
    )

    /** Yamaha role of a destination channel; null when the channel is outside the accompaniment range. */
    fun roleOf(destination: Int): String? = partRoles[destination]

    private val sectionOrder = listOf("Intro", "Main", "Fill", "Ending", "Break")

    /** "MainA" -> "MAIN A", "FillAA" -> "FILL A", "IntroB" -> "INTRO 2", "EndingC" -> "ENDING 3". */
    fun sectionLabel(key: String): String {
        val k = key.replace(" ", "")
        val tail = k.lastOrNull()?.uppercaseChar()
        val letterIndex = if (tail != null && tail in 'A'..'D') tail - 'A' + 1 else null
        return when {
            k.startsWith("Intro", true) && letterIndex != null -> "INTRO $letterIndex"
            k.startsWith("Ending", true) && letterIndex != null -> "ENDING $letterIndex"
            k.startsWith("Main", true) && tail != null -> "MAIN $tail"
            k.startsWith("Fill", true) && tail != null -> "FILL $tail"
            else -> key.uppercase()
        }
    }

    private fun sectionRank(key: String): Int {
        val k = key.replace(" ", "")
        val family = sectionOrder.indexOfFirst { k.startsWith(it, true) }.let { if (it < 0) sectionOrder.size else it }
        return family * 100 + (k.lastOrNull()?.uppercaseChar()?.code ?: 0)
    }

    /**
     * @param catalog presets of the SoundFonts currently loaded (empty when nothing is loaded).
     * @param observed engine readback keyed by channel; valid only for [activeSection].
     * @param overrides per (section, destination channel) overrides in force.
     */
    fun build(
        style: ParsedStyle,
        catalog: List<CatalogPreset>,
        observed: Map<Int, ObservedBinding>,
        activeSection: String?,
        overrides: Map<Pair<String, Int>, RowOverride> = emptyMap(),
        requests: List<StyleVoiceRequest> = StyleVoiceRequests.extract(style),
        session: InspectorSession = InspectorSession()
    ): StyleVoiceInventory {
        val activeKey = activeSection?.let { a -> style.sections.keys.firstOrNull { it.equals(a, true) } }

        // Requests that were observed live in the active section, keyed by what the resolver is asked.
        val observedByRequest = HashMap<RequestKey, ObservedBinding>()
        if (activeKey != null) requests.filter { it.section == activeKey && it.appliedToDestination }.forEach { r ->
            // Only a real binding is carried to other sections; "nothing reported" says nothing about them.
            observed[r.destinationChannel]?.takeIf { it.status == "BOUND" }?.let { observedByRequest[RequestKey.of(r)] = it }
        }

        val grouped = requests.groupBy { it.section }
        val ordered = grouped.keys.sortedWith(compareBy({ sectionRank(it) }, { it }))
        val groups = ordered.map { sectionName ->
            val rows = grouped.getValue(sectionName).map { r ->
                row(r, sectionName == activeKey, catalog, observed, observedByRequest, session, overrides[sectionName to r.destinationChannel]
                    ?: overrides[ALL_SECTIONS to r.destinationChannel])
            }.sortedWith(compareBy({ it.destinationChannel }, { it.partIndex }))
            StyleVoiceRequestRowGroup(sectionName, sectionLabel(sectionName), rows)
        }
        return StyleVoiceInventory(
            styleName = style.fileName,
            dialect = style.dialectIdentity.dialect,
            dialectEvidence = style.dialectIdentity.evidence,
            rows = groups,
            activeSection = activeKey,
            runtimeObserved = observed.values.any { it.status == "BOUND" } && activeKey != null,
            session = session
        )
    }

    const val ALL_SECTIONS = "*"

    private data class RequestKey(val destination: Int, val msb: Int?, val lsb: Int?, val program: Int?, val voice: String) {
        companion object {
            fun of(r: StyleVoiceRequest) = RequestKey(r.destinationChannel, r.msb, r.lsb, r.program, r.casmVoiceName)
        }
    }

    private fun row(
        r: StyleVoiceRequest,
        isActiveSection: Boolean,
        catalog: List<CatalogPreset>,
        observed: Map<Int, ObservedBinding>,
        byRequest: Map<RequestKey, ObservedBinding>,
        session: InspectorSession,
        override: RowOverride?
    ): StyleVoiceRow {
        val role = roleOf(r.destinationChannel) ?: "Channel ${r.destinationChannel + 1}"
        val readOnly = when {
            // Rhythm 1/2: the KIT is selectable (whole Style); the drum note map is never edited here.
            r.drum && r.destinationChannel in 8..9 && r.appliedToDestination -> null
            r.drum -> "Drum kits can only replace Rhythm 1 or Rhythm 2 where the Style defines them."
            r.program == null -> "This part sends no Program Change at tick 0, so there is no Voice request to replace."
            !r.appliedToDestination -> "Another part owns this channel's Voice in this section."
            else -> null
        }
        val key = RequestKey.of(r)
        val live = if (isActiveSection && r.appliedToDestination) observed[r.destinationChannel] else null
        val inherited = if (live == null) byRequest[key] else null
        val binding = live ?: inherited
        val bindingProv = if (live != null) Provenance.RUNTIME_OBSERVED else Provenance.RESOLVED

        val c = classify(r, binding, catalog)
        val status = when {
            override != null -> VoiceStatus.OVERRIDDEN
            else -> c.status
        }
        val noBinding = c.reasonCode == ReasonCode.NO_BINDING_REPORTED ||
            (c.status == VoiceStatus.UNKNOWN && binding == null && isActiveSection && r.appliedToDestination && r.program != null)
        val code = if (c.reasonCode.isNotEmpty()) c.reasonCode else if (noBinding) ReasonCode.NO_BINDING_REPORTED
            else if (c.status == VoiceStatus.UNKNOWN && binding == null && !isActiveSection && r.program != null) ReasonCode.NOT_MEASURED_THIS_SECTION else ""
        val sessionHint = if (!noBinding) "" else if (!session.playing)
            " Style is stopped: the engine binds Voices when a section starts, so there is nothing to read yet. Start the Style, then REFRESH. This is not evidence that the part is silent."
        else " Style is running but the engine has reported no binding for this channel. This is not evidence that the part is silent."
        val reason = when {
            override != null -> "Override ${override.state.lowercase()}: ${override.description}. Original request: ${c.reason}"
            inherited != null && c.status != VoiceStatus.UNKNOWN ->
                "${c.reason} (same request was observed in the active section; not measured for this section)"
            else -> (if (code.isNotEmpty() && c.status == VoiceStatus.UNKNOWN) "$code: " else "") + c.reason + sessionHint
        }
        val prov = when {
            c.status == VoiceStatus.UNKNOWN && binding == null && c.catalogMatch == null -> null
            binding != null -> bindingProv
            else -> Provenance.RESOLVED
        }
        return StyleVoiceRow(
            section = r.section,
            sectionLabel = sectionLabel(r.section),
            partIndex = r.partIndex,
            partName = r.partName,
            partRole = role,
            sourceChannel = r.sourceChannel,
            destinationChannel = r.destinationChannel,
            hasCasm = r.hasCasm,
            casmVoiceName = r.casmVoiceName,
            drum = r.drum,
            appliedToDestination = r.appliedToDestination,
            msb = r.msb, lsb = r.lsb, program = r.program,
            dynamicChanges = r.dynamicChanges,
            status = status,
            statusReason = reason,
            actualDescription = c.actualDescription,
            actualBank = c.actualBank, actualProgram = c.actualProgram,
            actualSoundFont = c.actualSoundFont, actualPresetName = c.actualPresetName,
            requestProvenance = Provenance.AUTHORED,
            routingProvenance = if (r.hasCasm) Provenance.AUTHORED else Provenance.INTERPRETED,
            resolutionProvenance = prov,
            reasonCode = code,
            override = override,
            readOnlyReason = readOnly
        )
    }

    private class Classified(
        val status: VoiceStatus, val reason: String, val actualDescription: String,
        val actualBank: Int? = null, val actualProgram: Int? = null,
        val actualSoundFont: String = "", val actualPresetName: String = "",
        val catalogMatch: CatalogPreset? = null,
        val reasonCode: String = ""
    )

    /** SF2 bank number the engine derives from a Yamaha MSB/LSB pair (bank = MSB*128 + LSB). */
    fun requestedBank(msb: Int?, lsb: Int?): Int = (msb ?: 0) * 128 + (lsb ?: 0)

    private fun classify(r: StyleVoiceRequest, b: ObservedBinding?, catalog: List<CatalogPreset>): Classified {
        val program = r.program
            ?: return Classified(VoiceStatus.UNKNOWN,
                "The style sends no Program Change at tick 0; the Voice is whatever the engine already had on this channel.",
                "—")
        val wantBank = requestedBank(r.msb, r.lsb)
        if (b != null) return classifyObserved(r, b, wantBank, program)

        // No readback for this exact request: compare with the loaded catalog by number only.
        if (catalog.isEmpty()) return Classified(VoiceStatus.UNKNOWN,
            "No SoundFont presets are loaded, and this section is not the one playing.", "not resolved")
        val numeric = catalog.filter { it.bank == wantBank && it.program == program && it.drum == r.drum }
        return when {
            numeric.size == 1 -> numeric[0].let {
                Classified(VoiceStatus.EXACT,
                    "Loaded catalog has Bank $wantBank / Program $program; matched by number, not by name. " +
                        "The engine's final choice is confirmed only while this section plays.",
                    "${it.soundFontName} ${it.bank}:${it.program} '${it.name}'", it.bank, it.program, it.soundFontName, it.name, it)
            }
            numeric.size > 1 -> Classified(VoiceStatus.AMBIGUOUS,
                "${numeric.size} loaded SoundFonts carry Bank $wantBank / Program $program: " +
                    numeric.joinToString { it.soundFontName } + ". The engine's source order decides.",
                "${numeric.size} candidates", wantBank, program, catalogMatch = numeric[0])
            else -> Classified(VoiceStatus.UNKNOWN,
                "No loaded preset has Bank $wantBank / Program $program. The engine may substitute a same-family preset; " +
                    "that choice is observable only while this section plays.",
                "not resolved")
        }
    }

    private fun classifyObserved(r: StyleVoiceRequest, b: ObservedBinding, wantBank: Int, program: Int): Classified {
        if (b.status != "BOUND") {
            val engine = "Engine reports ${b.status}${if (b.reason.isNotBlank()) " (${b.reason})" else ""}."
            return when {
                // Explicit evidence that no preset can serve the request.
                b.status == "UNRESOLVED" && b.reason in EXPLICIT_MISSING_REASONS ->
                    Classified(VoiceStatus.MISSING, "$engine No loaded preset can serve this request.", "no preset available",
                        reasonCode = ReasonCode.PRESET_MISSING)
                // Nothing bound yet (drum binding not made, channel never initialised, unknown reason): not evidence of anything.
                b.status == "UNINITIALIZED" || b.status == "UNRESOLVED" && (b.reason.isBlank() || b.reason == "no_verified_drum_binding") ->
                    Classified(VoiceStatus.UNKNOWN, "The engine has not bound this channel. $engine", "no binding reported",
                        reasonCode = ReasonCode.NO_BINDING_REPORTED)
                else -> Classified(VoiceStatus.UNKNOWN, "$engine The binding is not verified, so nothing is claimed about the sound.",
                    "binding not verified", reasonCode = ReasonCode.BINDING_NOT_VERIFIED)
            }
        }
        val bound = "${b.soundFontName} ${b.bank}:${b.program} '${b.presetName}'"
        val numberMatch = b.bank == wantBank && b.program == program
        val drumExact = r.drum && b.source == "DRUM_R23" && b.reason == "r23_exact_fingerprint_namespace_coverage"
        val ambiguousLegacy = r.drum && b.reason == "legacy_same_pc_first_matching_cache_entry"
        return when {
            drumExact -> Classified(VoiceStatus.EXACT, "Drum kit bound by exact fingerprint coverage.", bound,
                b.bank, b.program, b.soundFontName, b.presetName)
            ambiguousLegacy -> Classified(VoiceStatus.AMBIGUOUS,
                "Drum kit chosen as the first cached entry with the same Program number.", bound,
                b.bank, b.program, b.soundFontName, b.presetName)
            r.drum -> Classified(VoiceStatus.FALLBACK, "Drum kit substituted by the engine (${b.reason}).", bound,
                b.bank, b.program, b.soundFontName, b.presetName)
            numberMatch -> Classified(VoiceStatus.EXACT,
                "Bound preset has the requested Bank $wantBank / Program $program (matched by number, not by name).", bound,
                b.bank, b.program, b.soundFontName, b.presetName)
            else -> Classified(VoiceStatus.FALLBACK,
                "Requested Bank $wantBank / Program $program is not what plays; the engine chose a compatible preset (${b.reason}).", bound,
                b.bank, b.program, b.soundFontName, b.presetName)
        }
    }

    /** Plain-text dump used by the report and logs. */
    fun report(inv: StyleVoiceInventory): String = buildString {
        appendLine("style='${inv.styleName}' format=${inv.dialectText} active=${inv.activeSection ?: "-"} runtime=${inv.runtimeObserved} playing=${inv.session.playing}")
        for (g in inv.rows) for (row in g.rows) {
            appendLine("${g.label} | ${row.partRole} (src ${row.sourceChannel + 1} -> dst ${row.destinationChannel + 1}) | " +
                "${row.rawRequest} | ${row.actualDescription} | ${row.status}${if (row.reasonCode.isNotEmpty()) " [${row.reasonCode}]" else ""}")
        }
    }
}

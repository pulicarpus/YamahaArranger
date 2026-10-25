package com.yourapp.yamahaarranger.style

import com.yourapp.yamahaarranger.registration.FutureSchemaException
import com.yourapp.yamahaarranger.registration.RegistrationCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** ALL applies to every section of the style (part-wide). SECTION applies to one section only. */
enum class OverrideScope { ALL, SECTION }

/** The Voice request the Style itself carried when the override was made (raw bytes; null = not sent). */
data class OriginalRef(val msb: Int?, val lsb: Int?, val program: Int?)

/** Internal (BASSMIDI) replacement. [pdta] is the SF2 preset-data fingerprint when it was known, "" otherwise. */
data class StyleVoiceOverride(
    val channel: Int,
    val scope: OverrideScope,
    val section: String?,
    val pin: StyleVoicePin,
    val pdta: String = "",
    val original: OriginalRef? = null
)

data class StyleExternalOverride(
    val channel: Int,
    val scope: OverrideScope,
    val section: String?,
    val voice: ExternalVoice,
    val original: OriginalRef? = null
)

/** Octave shift of one melodic part (destination channel 10..15), -2..+2 octaves. Applied to the output pitch only, after the CASM/NTT transform. */
data class StyleOctaveShift(val channel: Int, val scope: OverrideScope, val section: String?, val octaves: Int)

/** Which output a drum note map applies to: the internal SF2 kit or the MIDI OUT module (kit numbering can differ). */
enum class DrumOutput { INTERNAL, MIDI_OUT }

/**
 * Source note (as authored in the Style) -> target note, for Rhythm 1 / Rhythm 2 (destination channel 8 / 9) on one output.
 * Notes not listed play unchanged. Velocity, timing, duration and event order are never touched.
 */
data class StyleDrumMap(val channel: Int, val output: DrumOutput, val scope: OverrideScope, val section: String?, val notes: Map<Int, Int>)

/**
 * Voice Override Profile of one Style (kept apart from the Style file, keyed by its content hash).
 * Effective mapping = Style request, then [overrides]/[externalOverrides] by priority:
 * section-specific, then part-wide. SF2 and external entries target different outputs and are independent.
 */
data class StyleOverrideRecord(
    val styleSha256: String,
    val styleFileName: String,
    val overrides: List<StyleVoiceOverride>,
    val savedAt: Long = 0,
    val externalOverrides: List<StyleExternalOverride> = emptyList(),
    val revision: Long = 0,
    val octaveShifts: List<StyleOctaveShift> = emptyList(),
    val drumMaps: List<StyleDrumMap> = emptyList()
) {
    val isEmpty: Boolean get() = overrides.isEmpty() && externalOverrides.isEmpty() && octaveShifts.isEmpty() && drumMaps.isEmpty()

    private fun sameLevel(c1: Int, s1: OverrideScope, sec1: String?, c2: Int, s2: OverrideScope, sec2: String?) =
        c1 == c2 && s1 == s2 && (if (s1 == OverrideScope.ALL) true else sec1 == sec2)

    /** Octave 0 at part-wide scope removes the entry. */
    fun withOctave(entry: StyleOctaveShift): StyleOverrideRecord {
        val kept = octaveShifts.filterNot { sameLevel(entry.channel, entry.scope, entry.section, it.channel, it.scope, it.section) }
        // A part-wide 0 is "no shift" (removed); a section-level 0 stays: it overrides a part-wide shift for that section.
        return copy(octaveShifts = if (entry.octaves == 0 && entry.scope == OverrideScope.ALL) kept else kept + entry, revision = revision + 1)
    }
    fun withoutOctave(channel: Int, scope: OverrideScope, section: String?): StyleOverrideRecord = copy(
        octaveShifts = octaveShifts.filterNot { sameLevel(channel, scope, section, it.channel, it.scope, it.section) }, revision = revision + 1)

    /** An empty map removes the entry. */
    fun withDrumMap(entry: StyleDrumMap): StyleOverrideRecord {
        val kept = drumMaps.filterNot { it.output == entry.output && sameLevel(entry.channel, entry.scope, entry.section, it.channel, it.scope, it.section) }
        return copy(drumMaps = if (entry.notes.isEmpty()) kept else kept + entry, revision = revision + 1)
    }
    fun withoutDrumMap(channel: Int, output: DrumOutput, scope: OverrideScope, section: String?): StyleOverrideRecord = copy(
        drumMaps = drumMaps.filterNot { it.output == output && sameLevel(channel, scope, section, it.channel, it.scope, it.section) }, revision = revision + 1)

    /** The whole-style pin for a channel. */
    fun allSectionPin(channel: Int): StyleVoicePin? =
        overrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.ALL }?.pin

    fun withAllSectionPin(channel: Int, pin: StyleVoicePin?): StyleOverrideRecord {
        val kept = overrides.filterNot { it.channel == channel && it.scope == OverrideScope.ALL }
        return copy(overrides = if (pin == null) kept else kept + StyleVoiceOverride(channel, OverrideScope.ALL, null, pin))
    }

    private fun sameKey(channel: Int, scope: OverrideScope, section: String?, c: Int, s: OverrideScope, sec: String?) =
        c == channel && s == scope && (if (scope == OverrideScope.ALL) true else sec == section)

    fun withSf2(entry: StyleVoiceOverride): StyleOverrideRecord = copy(
        overrides = overrides.filterNot { sameKey(entry.channel, entry.scope, entry.section, it.channel, it.scope, it.section) } + entry,
        revision = revision + 1)

    fun withExternal(entry: StyleExternalOverride): StyleOverrideRecord = copy(
        externalOverrides = externalOverrides.filterNot { sameKey(entry.channel, entry.scope, entry.section, it.channel, it.scope, it.section) } + entry,
        revision = revision + 1)

    /** Remove the SF2 and external entries stored at exactly this level. */
    fun without(channel: Int, scope: OverrideScope, section: String?): StyleOverrideRecord = copy(
        overrides = overrides.filterNot { sameKey(channel, scope, section, it.channel, it.scope, it.section) },
        externalOverrides = externalOverrides.filterNot { sameKey(channel, scope, section, it.channel, it.scope, it.section) },
        revision = revision + 1)

    /** RESET PART: every level (part-wide and all sections) for one channel. */
    fun withoutPart(channel: Int): StyleOverrideRecord = copy(
        overrides = overrides.filterNot { it.channel == channel },
        externalOverrides = externalOverrides.filterNot { it.channel == channel },
        octaveShifts = octaveShifts.filterNot { it.channel == channel },
        drumMaps = drumMaps.filterNot { it.channel == channel }, revision = revision + 1)

    /** RESET SECTION: the section-specific entries of one section; part-wide entries stay. */
    fun withoutSection(section: String): StyleOverrideRecord = copy(
        overrides = overrides.filterNot { it.scope == OverrideScope.SECTION && it.section == section },
        externalOverrides = externalOverrides.filterNot { it.scope == OverrideScope.SECTION && it.section == section },
        octaveShifts = octaveShifts.filterNot { it.scope == OverrideScope.SECTION && it.section == section },
        drumMaps = drumMaps.filterNot { it.scope == OverrideScope.SECTION && it.section == section }, revision = revision + 1)

    /** RESET ALL. */
    fun cleared(): StyleOverrideRecord = copy(overrides = emptyList(), externalOverrides = emptyList(), octaveShifts = emptyList(), drumMaps = emptyList(), revision = revision + 1)
}

/** Priority: section-specific override, then part-wide override, then the Style/resolver (null). */
object StyleOverrideResolver {
    fun sf2(record: StyleOverrideRecord?, section: String, channel: Int): StyleVoiceOverride? {
        if (record == null) return null
        return record.overrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.SECTION && it.section == section }
            ?: record.overrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.ALL }
    }

    /** Octaves for a part: section-specific, then part-wide, then 0. */
    fun octave(record: StyleOverrideRecord?, section: String, channel: Int): Int {
        if (record == null) return 0
        return (record.octaveShifts.firstOrNull { it.channel == channel && it.scope == OverrideScope.SECTION && it.section == section }
            ?: record.octaveShifts.firstOrNull { it.channel == channel && it.scope == OverrideScope.ALL })?.octaves ?: 0
    }

    fun external(record: StyleOverrideRecord?, section: String, channel: Int): StyleExternalOverride? {
        if (record == null) return null
        return record.externalOverrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.SECTION && it.section == section }
            ?: record.externalOverrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.ALL }
    }
}

/** Schema-3 JSON with a CRC over the sorted-key payload (3 adds octave shifts and drum note maps). Schemas 1 and 2 are still read. */
object StyleOverrideCodec {
    const val SCHEMA = 3L
    private val json = RegistrationCodec.Json

    private fun crc(payload: Any?): String =
        "%08x".format(CRC32().apply { update(json.write(payload).toByteArray(Charsets.UTF_8)) }.value)

    private fun originalMap(o: OriginalRef?): Map<String, Any?> =
        mapOf("origMsb" to o?.msb, "origLsb" to o?.lsb, "origProgram" to o?.program, "hasOrig" to (o != null))

    fun encode(record: StyleOverrideRecord): String {
        require(record.styleSha256.matches(Regex("[0-9a-f]{64}")))
        val payload = mapOf("styleFileName" to record.styleFileName, "revision" to record.revision,
            "overrides" to record.overrides.map { o ->
                require(o.channel in 0..15)
                require((o.scope == OverrideScope.SECTION) == (o.section != null))
                mapOf("channel" to o.channel, "scope" to o.scope.name, "section" to o.section,
                    "sf2FileName" to o.pin.soundFontName, "bank" to o.pin.bank, "program" to o.pin.program,
                    "presetName" to o.pin.presetName, "drum" to o.pin.drum, "pdta" to o.pdta) + originalMap(o.original)
            },
            "externalOverrides" to record.externalOverrides.map { o ->
                require(o.channel in 0..15)
                require((o.scope == OverrideScope.SECTION) == (o.section != null))
                require(o.voice.msb in 0..127 && o.voice.lsb in 0..127 && o.voice.program in 0..127)
                mapOf("channel" to o.channel, "scope" to o.scope.name, "section" to o.section,
                    "msb" to o.voice.msb, "lsb" to o.voice.lsb, "program" to o.voice.program, "label" to o.voice.label) + originalMap(o.original)
            },
            "octaveShifts" to record.octaveShifts.map { o ->
                require(o.channel in 10..15 && o.octaves in -2..2)
                require((o.scope == OverrideScope.SECTION) == (o.section != null))
                mapOf("channel" to o.channel, "scope" to o.scope.name, "section" to o.section, "octaves" to o.octaves)
            },
            "drumMaps" to record.drumMaps.map { o ->
                require(o.channel in 8..9)
                require((o.scope == OverrideScope.SECTION) == (o.section != null))
                require(o.notes.isNotEmpty() && o.notes.all { (k, v) -> k in 0..127 && v in 0..127 })
                mapOf("channel" to o.channel, "output" to o.output.name, "scope" to o.scope.name, "section" to o.section,
                    "notes" to o.notes.toSortedMap().map { (k, v) -> mapOf("source" to k, "target" to v) })
            })
        return json.write(mapOf("schema" to SCHEMA, "kind" to "styleVoiceOverrides", "styleSha256" to record.styleSha256,
            "savedAt" to record.savedAt, "payload" to payload, "crc32" to crc(payload)))
    }

    private fun int(o: Map<String, Any?>, key: String) = (o[key] as? Long ?: error(key)).toInt()
    private fun optInt(o: Map<String, Any?>, key: String) = (o[key] as? Long)?.toInt()

    private fun original(o: Map<String, Any?>): OriginalRef? =
        if (o["hasOrig"] == true) OriginalRef(optInt(o, "origMsb"), optInt(o, "origLsb"), optInt(o, "origProgram")) else null

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): StyleOverrideRecord {
        val e = json.parse(text) as? Map<String, Any?> ?: error("Object expected")
        val schema = e["schema"] as? Long ?: error("schema")
        if (schema > SCHEMA) throw FutureSchemaException(schema)
        require((schema in 1L..SCHEMA) && e["kind"] == "styleVoiceOverrides")
        require(e.keys == setOf("schema", "kind", "styleSha256", "savedAt", "payload", "crc32")) { "Unexpected fields" }
        require(e["crc32"] == crc(e["payload"])) { "CRC mismatch" }
        val sha = e["styleSha256"] as? String ?: error("styleSha256")
        require(sha.matches(Regex("[0-9a-f]{64}")))
        val p = e["payload"] as? Map<String, Any?> ?: error("payload")
        val list = p["overrides"] as? List<Any?> ?: error("overrides")
        val overrides = list.map { item ->
            val o = item as? Map<String, Any?> ?: error("override")
            val channel = int(o, "channel")
            require(channel in 0..15)
            val scope = OverrideScope.valueOf(o["scope"] as? String ?: error("scope"))
            val section = o["section"] as String?
            require((scope == OverrideScope.SECTION) == (section != null))
            StyleVoiceOverride(channel, scope, section, StyleVoicePin(
                o["sf2FileName"] as? String ?: error("sf2FileName"), int(o, "bank"), int(o, "program"),
                o["presetName"] as? String ?: error("presetName"), o["drum"] as? Boolean ?: error("drum")),
                pdta = if (schema >= 2L) o["pdta"] as? String ?: error("pdta") else "",
                original = if (schema >= 2L) original(o) else null)
        }
        val external = if (schema >= 2L) (p["externalOverrides"] as? List<Any?> ?: error("externalOverrides")).map { item ->
            val o = item as? Map<String, Any?> ?: error("external")
            val channel = int(o, "channel")
            require(channel in 0..15)
            val scope = OverrideScope.valueOf(o["scope"] as? String ?: error("scope"))
            val section = o["section"] as String?
            require((scope == OverrideScope.SECTION) == (section != null))
            val voice = ExternalVoice(int(o, "msb"), int(o, "lsb"), int(o, "program"), o["label"] as? String ?: error("label"))
            require(voice.msb in 0..127 && voice.lsb in 0..127 && voice.program in 0..127)
            StyleExternalOverride(channel, scope, section, voice, original(o))
        } else emptyList()
        val octaves = if (schema >= 3L) (p["octaveShifts"] as? List<Any?> ?: error("octaveShifts")).map { item ->
            val o = item as? Map<String, Any?> ?: error("octaveShift")
            val channel = int(o, "channel"); val octaves = int(o, "octaves")
            require(channel in 10..15 && octaves in -2..2)
            val scope = OverrideScope.valueOf(o["scope"] as? String ?: error("scope"))
            val section = o["section"] as String?
            require((scope == OverrideScope.SECTION) == (section != null))
            StyleOctaveShift(channel, scope, section, octaves)
        } else emptyList()
        val drum = if (schema >= 3L) (p["drumMaps"] as? List<Any?> ?: error("drumMaps")).map { item ->
            val o = item as? Map<String, Any?> ?: error("drumMap")
            val channel = int(o, "channel")
            require(channel in 8..9)
            val scope = OverrideScope.valueOf(o["scope"] as? String ?: error("scope"))
            val section = o["section"] as String?
            require((scope == OverrideScope.SECTION) == (section != null))
            val notes = (o["notes"] as? List<Any?> ?: error("notes")).associate { n ->
                val m = n as? Map<String, Any?> ?: error("note")
                val src = int(m, "source"); val dst = int(m, "target")
                require(src in 0..127 && dst in 0..127)
                src to dst
            }
            require(notes.isNotEmpty())
            StyleDrumMap(channel, DrumOutput.valueOf(o["output"] as? String ?: error("output")), scope, section, notes)
        } else emptyList()
        return StyleOverrideRecord(sha, p["styleFileName"] as? String ?: error("styleFileName"), overrides,
            e["savedAt"] as? Long ?: error("savedAt"), external, if (schema >= 2L) p["revision"] as? Long ?: error("revision") else 0L, octaves, drum)
    }
}

enum class OverrideReadStatus { VALID, RECOVERED_BACKUP, MISSING, DAMAGED, NEWER_SCHEMA }
data class OverrideReadResult(val status: OverrideReadStatus, val record: StyleOverrideRecord?) {
    /** Never overwrite data written by a newer app version or a file we could not read. */
    val writable: Boolean get() = status != OverrideReadStatus.NEWER_SCHEMA && status != OverrideReadStatus.DAMAGED
}

/**
 * One JSON file per style content hash under filesDir/style_overrides.
 * Writes go to .tmp, the previous file becomes .bak, then an atomic move.
 * Unreadable files are moved to quarantine/ instead of being deleted.
 * Callers run it off the main and audio threads.
 */
class StyleOverrideStore(directory: Path) {
    private val root = directory.toAbsolutePath().normalize()
    private val lock = Any()

    private fun file(sha: String): Path {
        require(sha.matches(Regex("[0-9a-f]{64}")))
        return root.resolve("style-$sha.json")
    }
    private fun bak(p: Path) = p.resolveSibling(p.fileName.toString() + ".bak")
    private fun tmp(p: Path) = p.resolveSibling(p.fileName.toString() + ".tmp")
    private fun text(p: Path): String {
        require(Files.size(p) <= 1_000_000) { "File too large" }
        return String(Files.readAllBytes(p), Charsets.UTF_8)
    }

    fun read(sha: String): OverrideReadResult = synchronized(lock) {
        val p = file(sha)
        Files.deleteIfExists(tmp(p))
        if (Files.exists(p)) {
            try {
                val r = StyleOverrideCodec.decode(text(p))
                require(r.styleSha256 == sha)
                return OverrideReadResult(OverrideReadStatus.VALID, r)
            } catch (_: FutureSchemaException) { return OverrideReadResult(OverrideReadStatus.NEWER_SCHEMA, null)
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            } catch (_: ClassCastException) { }
        }
        val b = bak(p)
        if (Files.exists(b)) {
            try {
                val r = StyleOverrideCodec.decode(text(b))
                require(r.styleSha256 == sha)
                return OverrideReadResult(OverrideReadStatus.RECOVERED_BACKUP, r)
            } catch (_: FutureSchemaException) { return OverrideReadResult(OverrideReadStatus.NEWER_SCHEMA, null)
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            } catch (_: ClassCastException) { }
        }
        if (Files.exists(p) || Files.exists(b)) return OverrideReadResult(OverrideReadStatus.DAMAGED, null)
        return OverrideReadResult(OverrideReadStatus.MISSING, null)
    }

    fun write(record: StyleOverrideRecord) = synchronized(lock) {
        Files.createDirectories(root)
        val p = file(record.styleSha256)
        val bytes = StyleOverrideCodec.encode(record).toByteArray(Charsets.UTF_8)
        val t = tmp(p)
        Files.write(t, bytes)
        // Verify before replacing anything.
        require(StyleOverrideCodec.decode(String(Files.readAllBytes(t), Charsets.UTF_8)) == record)
        if (Files.exists(p)) {
            if (!isReadable(p)) quarantine(p)
            else Files.copy(p, bak(p), REPLACE_EXISTING)
        }
        Files.move(t, p, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    /** Remove the style's record (RESET ALL); the backup is kept for recovery. */
    fun delete(sha: String) = synchronized(lock) {
        val p = file(sha)
        if (Files.exists(p)) Files.move(p, bak(p), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun isReadable(p: Path): Boolean = try { StyleOverrideCodec.decode(text(p)); true }
        catch (_: Exception) { false }

    private fun quarantine(p: Path) {
        val q = Files.createDirectories(root.resolve("quarantine")).resolve(p.fileName.toString() + "." + UUID.randomUUID())
        Files.move(p, q, ATOMIC_MOVE)
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** A saved pin checked against the presets that are loaded right now. */
data class PinCheck(
    val channel: Int, val pin: StyleVoicePin, val valid: Boolean, val reason: String,
    val scope: OverrideScope = OverrideScope.ALL, val section: String? = null
)

object StyleOverridePins {
    /**
     * [lookup] returns the loaded preset name for (sf2 file name, bank, program), or null.
     * [pdtaOf] returns the preset-data fingerprint of a loaded SF2 file, or null when unknown.
     * A missing file/preset, a different preset name or a different SF2 content marks the pin STALE;
     * it is then shown but not applied, and it is kept on disk. Nothing is substituted.
     */
    fun check(record: StyleOverrideRecord, pdtaOf: (String) -> String? = { null }, lookup: (String, Int, Int) -> String?): List<PinCheck> =
        record.overrides.map { o ->
            val loaded = lookup(o.pin.soundFontName, o.pin.bank, o.pin.program)
            val pdta = pdtaOf(o.pin.soundFontName)
            val reason = when {
                loaded == null -> "STALE_PRESET_NOT_LOADED"
                loaded != o.pin.presetName -> "STALE_PRESET_NAME_CHANGED"
                o.pdta.isNotEmpty() && pdta != null && pdta.isNotEmpty() && pdta != o.pdta -> "STALE_SOUNDFONT_CONTENT_CHANGED"
                else -> "VALID"
            }
            PinCheck(o.channel, o.pin, reason == "VALID", reason, o.scope, o.section)
        }
}

/** What re-applying one saved entry did: ACTIVE, UNVERIFIED, or the STALE_* reason (shown, not applied, never deleted). */
data class RestoreResult(
    val external: Boolean, val channel: Int, val scope: OverrideScope, val section: String?, val state: String,
    val pin: StyleVoicePin? = null, val voice: ExternalVoice? = null, val reason: String = state,
    /** SF2, EXTERNAL, OCTAVE or DRUM_MAP. [output] is set for DRUM_MAP only. */
    val kind: String = if (external) "EXTERNAL" else "SF2", val output: DrumOutput? = null
)

/**
 * Re-applies a saved profile after a Style load. The Style load calls this while it HOLDS the engine operation lock, and that lock is a
 * coroutine Mutex (not reentrant): [pdtaByFile] and [loadedPresetName] must therefore read the cached native snapshot and never take it.
 * (The first version called the locking preset listing from here and blocked the main thread forever whenever a profile was saved.)
 */
object StyleOverrideRestorer {
    fun restore(
        record: StyleOverrideRecord,
        pdtaByFile: () -> Map<String, String>,
        loadedPresetName: (StyleVoicePin) -> String?,
        setSf2Pin: (channel: Int, section: String?, pin: StyleVoicePin?) -> Boolean,
        setExternal: (channel: Int, section: String?, voice: ExternalVoice) -> Boolean,
        log: (String) -> Unit = {},
        setOctave: (channel: Int, section: String?, octaves: Int) -> Boolean = { _, _, _ -> true },
        setDrumMap: (channel: Int, section: String?, output: DrumOutput, notes: Map<Int, Int>) -> Boolean = { _, _, _, _ -> true }
    ): List<RestoreResult> {
        val out = ArrayList<RestoreResult>()
        val pdta = pdtaByFile()
        val checks = StyleOverridePins.check(record, { pdta[it] }) { sf2, bank, pc -> loadedPresetName(StyleVoicePin(sf2, bank, pc, "", false)) }
        checks.forEach { c ->
            val section = if (c.scope == OverrideScope.ALL) null else c.section
            val state = if (!c.valid) { setSf2Pin(c.channel, section, null); c.reason }
                        else if (setSf2Pin(c.channel, section, c.pin)) "ACTIVE" else "UNVERIFIED"
            out += RestoreResult(false, c.channel, c.scope, c.section, state, pin = c.pin, reason = c.reason)
            log("CPV restore ch=${c.channel} ${c.scope} ${c.section ?: "*"} ${c.pin.soundFontName} ${c.pin.bank}:${c.pin.program} '${c.pin.presetName}' ${c.reason}")
        }
        record.externalOverrides.forEach { e ->
            val ok = setExternal(e.channel, e.section, e.voice)
            out += RestoreResult(true, e.channel, e.scope, e.section, if (ok) "ACTIVE" else "UNVERIFIED", voice = e.voice)
            log("VOICE OVERRIDE restore ch=${e.channel} ${e.scope} ${e.section ?: "*"} ${e.voice.msb}:${e.voice.lsb}:${e.voice.program} ${if (ok) "ACTIVE" else "UNVERIFIED"}")
        }
        record.octaveShifts.forEach { o ->
            val ok = setOctave(o.channel, if (o.scope == OverrideScope.ALL) null else o.section, o.octaves)
            out += RestoreResult(false, o.channel, o.scope, o.section, if (ok) "ACTIVE" else "UNVERIFIED", kind = "OCTAVE")
            log("OCTAVE restore ch=${o.channel} ${o.scope} ${o.section ?: "*"} ${o.octaves} ${if (ok) "ACTIVE" else "UNVERIFIED"}")
        }
        record.drumMaps.forEach { d ->
            val ok = setDrumMap(d.channel, if (d.scope == OverrideScope.ALL) null else d.section, d.output, d.notes)
            out += RestoreResult(false, d.channel, d.scope, d.section, if (ok) "ACTIVE" else "UNVERIFIED", kind = "DRUM_MAP", output = d.output)
            log("DRUM MAP restore ch=${d.channel} ${d.output} ${d.scope} ${d.section ?: "*"} notes=${d.notes.size} ${if (ok) "ACTIVE" else "UNVERIFIED"}")
        }
        return out
    }
}

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

/** ALL applies to every section. SECTION is stored and preserved for a later per-section feature. */
enum class OverrideScope { ALL, SECTION }

data class StyleVoiceOverride(
    val channel: Int,
    val scope: OverrideScope,
    val section: String?,
    val pin: StyleVoicePin
)

data class StyleOverrideRecord(
    val styleSha256: String,
    val styleFileName: String,
    val overrides: List<StyleVoiceOverride>,
    val savedAt: Long = 0
) {
    /** The whole-style pin for a channel; SECTION entries are not applied yet. */
    fun allSectionPin(channel: Int): StyleVoicePin? =
        overrides.firstOrNull { it.channel == channel && it.scope == OverrideScope.ALL }?.pin

    fun withAllSectionPin(channel: Int, pin: StyleVoicePin?): StyleOverrideRecord {
        val kept = overrides.filterNot { it.channel == channel && it.scope == OverrideScope.ALL }
        return copy(overrides = if (pin == null) kept else kept + StyleVoiceOverride(channel, OverrideScope.ALL, null, pin))
    }
}

/** Schema-1 JSON with a CRC over the sorted-key payload. Separate from REG MEMORY schema 2. */
object StyleOverrideCodec {
    const val SCHEMA = 1L
    private val json = RegistrationCodec.Json

    private fun crc(payload: Any?): String =
        "%08x".format(CRC32().apply { update(json.write(payload).toByteArray(Charsets.UTF_8)) }.value)

    fun encode(record: StyleOverrideRecord): String {
        require(record.styleSha256.matches(Regex("[0-9a-f]{64}")))
        val payload = mapOf("styleFileName" to record.styleFileName, "overrides" to record.overrides.map { o ->
            require(o.channel in 0..15)
            require((o.scope == OverrideScope.SECTION) == (o.section != null))
            mapOf("channel" to o.channel, "scope" to o.scope.name, "section" to o.section,
                "sf2FileName" to o.pin.soundFontName, "bank" to o.pin.bank, "program" to o.pin.program,
                "presetName" to o.pin.presetName, "drum" to o.pin.drum)
        })
        return json.write(mapOf("schema" to SCHEMA, "kind" to "styleVoiceOverrides", "styleSha256" to record.styleSha256,
            "savedAt" to record.savedAt, "payload" to payload, "crc32" to crc(payload)))
    }

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): StyleOverrideRecord {
        val e = json.parse(text) as? Map<String, Any?> ?: error("Object expected")
        val schema = e["schema"] as? Long ?: error("schema")
        if (schema > SCHEMA) throw FutureSchemaException(schema)
        require(schema == SCHEMA && e["kind"] == "styleVoiceOverrides")
        require(e.keys == setOf("schema", "kind", "styleSha256", "savedAt", "payload", "crc32")) { "Unexpected fields" }
        require(e["crc32"] == crc(e["payload"])) { "CRC mismatch" }
        val sha = e["styleSha256"] as? String ?: error("styleSha256")
        require(sha.matches(Regex("[0-9a-f]{64}")))
        val p = e["payload"] as? Map<String, Any?> ?: error("payload")
        val list = p["overrides"] as? List<Any?> ?: error("overrides")
        val overrides = list.map { item ->
            val o = item as? Map<String, Any?> ?: error("override")
            val channel = (o["channel"] as? Long ?: error("channel")).toInt()
            require(channel in 0..15)
            val scope = OverrideScope.valueOf(o["scope"] as? String ?: error("scope"))
            val section = o["section"] as String?
            require((scope == OverrideScope.SECTION) == (section != null))
            StyleVoiceOverride(channel, scope, section, StyleVoicePin(
                o["sf2FileName"] as? String ?: error("sf2FileName"),
                (o["bank"] as? Long ?: error("bank")).toInt(),
                (o["program"] as? Long ?: error("program")).toInt(),
                o["presetName"] as? String ?: error("presetName"),
                o["drum"] as? Boolean ?: error("drum")))
        }
        return StyleOverrideRecord(sha, p["styleFileName"] as? String ?: error("styleFileName"), overrides,
            e["savedAt"] as? Long ?: error("savedAt"))
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
data class PinCheck(val channel: Int, val pin: StyleVoicePin, val valid: Boolean, val reason: String)

object StyleOverridePins {
    /**
     * [lookup] returns the loaded preset name for (sf2 file name, bank, program), or null.
     * A missing file/preset or a different preset name marks the pin STALE; it is then
     * shown but not applied, and it is kept on disk.
     */
    fun check(record: StyleOverrideRecord, lookup: (String, Int, Int) -> String?): List<PinCheck> =
        record.overrides.filter { it.scope == OverrideScope.ALL }.map { o ->
            val loaded = lookup(o.pin.soundFontName, o.pin.bank, o.pin.program)
            when {
                loaded == null -> PinCheck(o.channel, o.pin, false, "STALE_PRESET_NOT_LOADED")
                loaded != o.pin.presetName -> PinCheck(o.channel, o.pin, false, "STALE_PRESET_NAME_CHANGED")
                else -> PinCheck(o.channel, o.pin, true, "VALID")
            }
        }
}

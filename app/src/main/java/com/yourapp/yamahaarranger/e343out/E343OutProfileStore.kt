package com.yourapp.yamahaarranger.e343out

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Manual MIDI OUT trims (PSR-E343 balance) per Style, keyed by the Style content hash, stored next to the Style Voice Editor profiles
 * (`<sha>.e343out`, a small text file). Separate from the voice profile on purpose: the Style Voice Editor profile format is not changed.
 * Format: first line `E343OUT 1`, then `trim <channel 8..15> <0..127>`. Unknown lines are ignored; an unreadable file means "no manual trims".
 */
class E343OutProfileStore(private val dir: Path) {
    private fun file(sha: String): Path { require(sha.matches(Regex("[0-9a-f]{16,128}"))) { "bad style hash" }; return dir.resolve("$sha.e343out") }

    fun read(sha: String): Map<Int, Int> = try {
        val f = file(sha)
        if (!Files.isRegularFile(f) || Files.size(f) > 64_000) emptyMap() else parse(String(Files.readAllBytes(f), Charsets.UTF_8))
    } catch (_: Exception) { emptyMap() }

    fun write(sha: String, trims: Map<Int, Int>) {
        val f = file(sha)
        if (trims.isEmpty()) { Files.deleteIfExists(f); return }
        Files.createDirectories(dir)
        val tmp = dir.resolve("$sha.e343out.tmp")
        Files.write(tmp, format(trims).toByteArray(Charsets.UTF_8))
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun format(trims: Map<Int, Int>) = "E343OUT 1\n" + trims.toSortedMap().entries.joinToString("") { "trim ${it.key} ${it.value}\n" }
        fun parse(text: String): Map<Int, Int> {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "E343OUT 1") return emptyMap()
            val out = sortedMapOf<Int, Int>()
            for (l in lines.drop(1)) {
                val p = l.trim().split(Regex("\\s+"))
                if (p.size == 3 && p[0] == "trim") {
                    val ch = p[1].toIntOrNull(); val v = p[2].toIntOrNull()
                    if (ch != null && v != null && ch in 8..15 && v in 0..127) out[ch] = v
                }
            }
            return out
        }
    }
}

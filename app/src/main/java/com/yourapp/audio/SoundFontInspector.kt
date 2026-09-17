package com.yourapp.audio

import java.io.InputStream
import java.nio.charset.Charset

/** Reads SoundFont 2 RIFF metadata without loading the sample data into memory. */
object SoundFontInspector {
    data class Preset(val name: String, val program: Int, val bank: Int)

    data class Report(
        val fileName: String,
        val fileSize: Long,
        val validSf2: Boolean,
        val soundFontName: String?,
        val engine: String?,
        val comment: String?,
        val presets: List<Preset>,
        val instrumentCount: Int,
        val sampleCount: Int
    ) {
        fun asText(): String = buildString {
            appendLine("YamahaArranger SF2 Inspector")
            appendLine("============================")
            appendLine("File: $fileName")
            appendLine("Size: ${fileSize / 1024.0 / 1024.0} MB")
            appendLine("Valid SF2: $validSf2")
            soundFontName?.takeIf { it.isNotBlank() }?.let { appendLine("Name: $it") }
            engine?.takeIf { it.isNotBlank() }?.let { appendLine("Engine: $it") }
            comment?.takeIf { it.isNotBlank() }?.let { appendLine("Comment: $it") }
            appendLine("Presets: ${presets.size}")
            appendLine("Instruments: $instrumentCount")
            appendLine("Samples: $sampleCount")
            appendLine()
            appendLine("Relevant presets")
            appendLine("-----------------")
            val relevant = presets.filter { p ->
                val n = p.name.lowercase()
                n.contains("bass") || n.contains("piano") || n.contains("guitar") ||
                    n.contains("string") || n.contains("pad") || n.contains("kit") ||
                    n.contains("drum") || p.bank == 128
            }
            if (relevant.isEmpty()) appendLine("(none found)")
            relevant.forEach { appendLine("bank=${it.bank.toString().padStart(3, '0')} program=${it.program.toString().padStart(3, '0')}  ${it.name}") }
            appendLine()
            appendLine("All presets (first ${minOf(presets.size, 400)})")
            appendLine("-----------------")
            presets.take(400).forEach { appendLine("bank=${it.bank.toString().padStart(3, '0')} program=${it.program.toString().padStart(3, '0')}  ${it.name}") }
            if (presets.size > 400) appendLine("... ${presets.size - 400} more")
        }
    }

    fun inspect(input: InputStream, fileName: String, fileSize: Long = -1L): Report {
        val data = input.readBytes()
        val actualSize = if (fileSize >= 0) fileSize else data.size.toLong()
        if (data.size < 12 || ascii(data, 0, 4) != "RIFF" || ascii(data, 8, 4) != "sfbk") {
            return Report(fileName, actualSize, false, null, null, null, emptyList(), 0, 0)
        }

        val presets = mutableListOf<Preset>()
        var instrumentCount = 0
        var sampleCount = 0
        var soundFontName: String? = null
        var engine: String? = null
        var comment: String? = null

        walkChunks(data, 12, data.size) { id, start, end ->
            when (id) {
                "phdr" -> {
                    var p = start
                    while (p + 38 <= end) {
                        val name = readString(data, p, 20)
                        val program = u16(data, p + 20)
                        val bank = u16(data, p + 22)
                        if (name.isNotBlank() && name != "EOP") presets += Preset(name, program, bank)
                        p += 38
                    }
                }
                "inst " -> {
                    instrumentCount = maxOf(0, (end - start) / 22 - 1)
                }
                "shdr" -> {
                    sampleCount = maxOf(0, (end - start) / 46 - 1)
                }
                "INAM" -> soundFontName = readString(data, start, end - start)
                "ISFT" -> engine = readString(data, start, end - start)
                "ICMT" -> comment = readString(data, start, end - start)
            }
            false
        }

        return Report(fileName, actualSize, true, soundFontName, engine, comment, presets, instrumentCount, sampleCount)
    }

    private fun walkChunks(data: ByteArray, start: Int, limit: Int, visitor: (String, Int, Int) -> Boolean) {
        var pos = start
        while (pos + 8 <= limit) {
            val id = ascii(data, pos, 4)
            val size = u32(data, pos + 4).toLong()
            val payload = pos + 8
            val endLong = payload.toLong() + size
            if (endLong > limit || endLong > data.size) break
            val end = endLong.toInt()
            if (id == "LIST" && payload + 4 <= end) {
                walkChunks(data, payload + 4, end, visitor)
            } else {
                visitor(id, payload, end)
            }
            pos = end + (size.toInt() and 1)
        }
    }

    private fun u16(data: ByteArray, p: Int): Int =
        (data[p].toInt() and 0xff) or ((data[p + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, p: Int): Long =
        (data[p].toLong() and 0xff) or
            ((data[p + 1].toLong() and 0xff) shl 8) or
            ((data[p + 2].toLong() and 0xff) shl 16) or
            ((data[p + 3].toLong() and 0xff) shl 24)

    private fun ascii(data: ByteArray, p: Int, length: Int): String =
        String(data, p, length, Charsets.US_ASCII)

    private fun readString(data: ByteArray, p: Int, length: Int): String {
        val n = minOf(length, data.size - p)
        if (n <= 0) return ""
        val bytes = data.copyOfRange(p, p + n)
        val zero = bytes.indexOfFirst { it.toInt() == 0 }
        return String(if (zero >= 0) bytes.copyOf(zero) else bytes, Charset.forName("windows-1252"))
            .trim()
    }
}

package com.yourapp.audio

import java.io.BufferedInputStream
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

    /** Inspect SF2 metadata without loading the sample-data chunk into RAM. */
    fun inspect(input: InputStream, fileName: String, fileSize: Long = -1L): Report {
        BufferedInputStream(input, 64 * 1024).use { stream ->
            val header = ByteArray(12)
            if (!readFully(stream, header, 0, header.size) ||
                ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "sfbk"
            ) {
                return Report(fileName, fileSize.coerceAtLeast(0L), false, null, null, null, emptyList(), 0, 0)
            }

            val declaredRiffSize = u32(header, 4)
            val presets = mutableListOf<Preset>()
            var instrumentCount = 0
            var sampleCount = 0
            var soundFontName: String? = null
            var engine: String? = null
            var comment: String? = null
            var validStructure = true
            val remaining = (declaredRiffSize - 4L).coerceAtLeast(0L)

            fun visitChunk(id: String, size: Long) {
                when (id) {
                    "phdr" -> {
                        val recordSize = 38L
                        val recordCount = size / recordSize
                        repeat(recordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
                            val record = ByteArray(38)
                            if (!readFully(stream, record, 0, record.size)) {
                                validStructure = false
                                return
                            }
                            val name = readString(record, 0, 20)
                            if (name.isNotBlank() && name != "EOP") {
                                presets += Preset(name, u16(record, 20), u16(record, 22))
                            }
                        }
                        if (!skipFully(stream, size - recordCount * recordSize)) validStructure = false
                    }
                    "inst " -> {
                        instrumentCount = maxOf(0L, size / 22L - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        if (!skipFully(stream, size)) validStructure = false
                    }
                    "shdr" -> {
                        sampleCount = maxOf(0L, size / 46L - 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        if (!skipFully(stream, size)) validStructure = false
                    }
                    "INAM", "ISFT", "ICMT" -> {
                        val maxRead = minOf(size, 64L * 1024L).toInt()
                        val bytes = ByteArray(maxRead)
                        if (!readFully(stream, bytes, 0, maxRead)) {
                            validStructure = false
                            return
                        }
                        val text = readString(bytes, 0, bytes.size)
                        when (id) {
                            "INAM" -> soundFontName = text
                            "ISFT" -> engine = text
                            "ICMT" -> comment = text
                        }
                        if (!skipFully(stream, size - maxRead)) validStructure = false
                    }
                    else -> if (!skipFully(stream, size)) validStructure = false
                }
            }

            fun parseChunks(limit: Long) {
                var left = limit
                while (left >= 8L) {
                    val idBytes = ByteArray(4)
                    val sizeBytes = ByteArray(4)
                    if (!readFully(stream, idBytes, 0, 4) || !readFully(stream, sizeBytes, 0, 4)) {
                        validStructure = false
                        return
                    }
                    val id = ascii(idBytes, 0, 4)
                    val size = u32(sizeBytes, 0)
                    left -= 8L
                    if (size > left) {
                        validStructure = false
                        return
                    }
                    if (id == "LIST") {
                        if (size < 4L) {
                            validStructure = false
                            return
                        }
                        val type = ByteArray(4)
                        if (!readFully(stream, type, 0, 4)) {
                            validStructure = false
                            return
                        }
                        parseChunks(size - 4L)
                    } else {
                        visitChunk(id, size)
                    }
                    if (!validStructure) return
                    left -= size
                    if ((size and 1L) != 0L) {
                        if (!skipFully(stream, 1L)) {
                            validStructure = false
                            return
                        }
                        left--
                    }
                }
                if (left > 0L && !skipFully(stream, left)) validStructure = false
            }

            parseChunks(remaining)
            return Report(fileName, fileSize, validStructure, soundFontName, engine, comment, presets, instrumentCount, sampleCount)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) return false
            if (n == 0) continue
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, length: Long): Boolean {
        var left = length
        while (left > 0L) {
            val skipped = input.skip(left)
            if (skipped > 0L) left -= skipped
            else {
                if (input.read() < 0) return false
                left--
            }
        }
        return true
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

package com.yourapp.yamahaarranger.soundfont

import java.io.EOFException
import java.io.InputStream

/**
 * Read-only SoundFont 2 metadata parser.
 *
 * The parser deliberately skips the sdta/smpl PCM payload, so opening a large
 * SF2 does not copy hundreds of megabytes of audio samples into the heap.
 */
class Sf2Parser {

    fun parse(input: InputStream): Sf2File {
        val riff = ByteReader(input)
        require(riff.ascii(4) == "RIFF") { "Missing RIFF header" }
        riff.u32() // RIFF file size
        require(riff.ascii(4) == "sfbk") { "RIFF is not an SF2 SoundFont (sfbk)" }

        val chunks = HashMap<String, ByteArray>()
        val info = HashMap<String, String>()

        while (true) {
            val id = riff.tryAscii(4) ?: break
            val size = riff.u32()
            require(size <= Int.MAX_VALUE) { "SF2 chunk is too large: $id" }

            if (id == "LIST") {
                parseList(riff, size.toInt(), chunks, info)
            } else {
                riff.skipFully(size)
            }

            if ((size and 1L) != 0L) riff.skipFully(1)
        }

        val phdr = chunks["phdr"] ?: error("SF2 missing phdr")
        val pbag = chunks["pbag"] ?: error("SF2 missing pbag")
        val pgen = chunks["pgen"] ?: error("SF2 missing pgen")
        val inst = chunks["inst "] ?: error("SF2 missing inst")
        val ibag = chunks["ibag"] ?: error("SF2 missing ibag")
        val igen = chunks["igen"] ?: error("SF2 missing igen")
        val shdr = chunks["shdr"] ?: error("SF2 missing shdr")

        return Sf2File(
            info = info,
            presets = parsePresets(phdr, pbag, pgen),
            instruments = parseInstruments(inst, ibag, igen),
            samples = parseSamples(shdr)
        )
    }

    private fun parseList(
        reader: ByteReader,
        size: Int,
        chunks: MutableMap<String, ByteArray>,
        info: MutableMap<String, String>
    ) {
        require(size >= 4) { "Malformed LIST chunk" }
        val listType = reader.ascii(4)
        val payloadSize = size - 4

        // The sdta LIST contains raw PCM in its smpl child. Never read it.
        if (listType == "sdta") {
            reader.skipFully(payloadSize.toLong())
            return
        }

        // INFO and pdta are metadata and should remain small. Refuse absurd
        // allocations instead of allowing a malformed file to exhaust memory.
        require(payloadSize <= MAX_METADATA_BYTES) {
            "Metadata LIST is unexpectedly large: $payloadSize bytes"
        }

        val payload = reader.readFully(payloadSize)
        parseChildChunks(payload, listType, chunks, info)
    }

    private fun parseChildChunks(
        data: ByteArray,
        listType: String,
        chunks: MutableMap<String, ByteArray>,
        info: MutableMap<String, String>
    ) {
        var p = 0
        while (p + 8 <= data.size) {
            val id = ascii(data, p, 4)
            val size = u32(data, p + 4)
            require(size <= Int.MAX_VALUE) { "Invalid child chunk size: $id" }
            val start = p + 8
            val endLong = start.toLong() + size
            require(endLong <= data.size) { "Truncated child chunk: $id" }
            val end = endLong.toInt()

            when (listType) {
                "pdta" -> chunks[id] = data.copyOfRange(start, end)
                "INFO" -> info[id] = decodeText(data, start, end - start)
            }

            p = end + (size.toInt() and 1)
        }
    }

    private fun parsePresets(
        phdr: ByteArray,
        pbag: ByteArray,
        pgen: ByteArray
    ): List<Sf2Preset> {
        require(phdr.size % 38 == 0) { "Malformed phdr table" }
        val headers = (0 until phdr.size / 38).map { i ->
            val o = i * 38
            PresetHeader(
                name = text(phdr, o, 20),
                program = u16(phdr, o + 20),
                bank = u16(phdr, o + 22),
                bagIndex = u16(phdr, o + 24)
            )
        }
        if (headers.size < 2) return emptyList()

        val bags = readBags(pbag)
        val gens = readGenerators(pgen)
        return headers.dropLast(1).mapIndexed { index, header ->
            val next = headers[index + 1]
            val zones = zoneRange(header.bagIndex, next.bagIndex, bags).map { (start, end) ->
                val map = generatorMap(gens, start, end)
                Sf2PresetZone(
                    keyRange = range(map[Sf2Generator.KEY_RANGE]),
                    velocityRange = range(map[Sf2Generator.VEL_RANGE]),
                    instrumentIndex = map[Sf2Generator.INSTRUMENT],
                    generators = map
                )
            }
            Sf2Preset(header.name, header.program, header.bank, zones)
        }
    }

    private fun parseInstruments(
        inst: ByteArray,
        ibag: ByteArray,
        igen: ByteArray
    ): List<Sf2Instrument> {
        require(inst.size % 22 == 0) { "Malformed inst table" }
        val headers = (0 until inst.size / 22).map { i ->
            val o = i * 22
            InstrumentHeader(text(inst, o, 20), u16(inst, o + 20))
        }
        if (headers.size < 2) return emptyList()

        val bags = readBags(ibag)
        val gens = readGenerators(igen)
        return headers.dropLast(1).mapIndexed { index, header ->
            val next = headers[index + 1]
            val zones = zoneRange(header.bagIndex, next.bagIndex, bags).map { (start, end) ->
                val map = generatorMap(gens, start, end)
                Sf2InstrumentZone(
                    keyRange = range(map[Sf2Generator.KEY_RANGE]),
                    velocityRange = range(map[Sf2Generator.VEL_RANGE]),
                    sampleIndex = map[Sf2Generator.SAMPLE_ID],
                    rootKey = map[Sf2Generator.OVERRIDING_ROOT_KEY],
                    coarseTune = signed16(map[Sf2Generator.COARSE_TUNE]),
                    fineTune = signed16(map[Sf2Generator.FINE_TUNE]),
                    pan = signed16(map[Sf2Generator.PAN]),
                    attenuation = signed16(map[Sf2Generator.ATTENUATION]),
                    sampleModes = map[Sf2Generator.SAMPLE_MODES],
                    exclusiveClass = map[Sf2Generator.EXCLUSIVE_CLASS],
                    generators = map
                )
            }
            Sf2Instrument(header.name, zones)
        }
    }

    private fun parseSamples(shdr: ByteArray): List<Sf2Sample> {
        require(shdr.size % 46 == 0) { "Malformed shdr table" }
        val count = shdr.size / 46
        if (count < 2) return emptyList()

        return (0 until count - 1).map { i ->
            val o = i * 46
            Sf2Sample(
                name = text(shdr, o, 20),
                start = u32(shdr, o + 20),
                end = u32(shdr, o + 24),
                loopStart = u32(shdr, o + 28),
                loopEnd = u32(shdr, o + 32),
                sampleRate = u32(shdr, o + 36),
                originalPitch = u8(shdr, o + 40),
                pitchCorrection = shdr[o + 41].toInt(),
                sampleLink = u16(shdr, o + 42),
                sampleType = u16(shdr, o + 44)
            )
        }
    }

    private fun generatorMap(
        generators: List<Pair<Int, Int>>,
        start: Int,
        end: Int
    ): Map<Int, Int> {
        require(start in 0..generators.size && end in start..generators.size) {
            "Generator range out of bounds: $start..$end"
        }
        return generators.subList(start, end).associate { it.first to it.second }
    }

    private fun zoneRange(
        start: Int,
        end: Int,
        bags: List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        require(start >= 0 && start < bags.size && end > start && end < bags.size) {
            "Bag range out of bounds: $start..$end"
        }
        return (start until end).map { i ->
            val generatorStart = bags[i].second
            val generatorEnd = bags[i + 1].second
            generatorStart to generatorEnd
        }
    }

    private fun readBags(data: ByteArray): List<Pair<Int, Int>> {
        require(data.size % 4 == 0) { "Malformed bag table" }
        return (0 until data.size / 4).map { i ->
            u16(data, i * 4) to u16(data, i * 4 + 2)
        }
    }

    private fun readGenerators(data: ByteArray): List<Pair<Int, Int>> {
        require(data.size % 4 == 0) { "Malformed generator table" }
        return (0 until data.size / 4).map { i ->
            u16(data, i * 4) to u16(data, i * 4 + 2)
        }
    }

    private fun range(raw: Int?): IntRange? {
        if (raw == null) return null
        val low = raw and 0xFF
        val high = (raw ushr 8) and 0xFF
        return if (low <= high) low..high else null
    }

    private fun signed16(raw: Int?): Int? =
        raw?.let { if ((it and 0x8000) != 0) it - 0x10000 else it }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun ascii(data: ByteArray, offset: Int, length: Int): String =
        data.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun text(data: ByteArray, offset: Int, length: Int): String =
        decodeText(data, offset, length)

    private fun decodeText(data: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { data[it].toInt() == 0 } ?: offset + length
        return data.copyOfRange(offset, end).toString(Charsets.ISO_8859_1).trim()
    }

    private data class PresetHeader(
        val name: String,
        val program: Int,
        val bank: Int,
        val bagIndex: Int
    )

    private data class InstrumentHeader(val name: String, val bagIndex: Int)

    private class ByteReader(private val input: InputStream) {
        fun ascii(length: Int): String =
            readFully(length).toString(Charsets.US_ASCII)

        fun tryAscii(length: Int): String? {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(bytes, offset, length - offset)
                if (n < 0) return if (offset == 0) null else throw EOFException()
                offset += n
            }
            return bytes.toString(Charsets.US_ASCII)
        }

        fun u32(): Long {
            val b = readFully(4)
            return (b[0].toLong() and 0xFF) or
                ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or
                ((b[3].toLong() and 0xFF) shl 24)
        }

        fun readFully(length: Int): ByteArray {
            require(length >= 0) { "Negative read length" }
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(result, offset, length - offset)
                if (n < 0) throw EOFException("Unexpected end of SF2")
                offset += n
            }
            return result
        }

        fun skipFully(length: Long) {
            var remaining = length
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw EOFException("Unexpected end of SF2")
                    remaining--
                }
            }
        }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 16 * 1024 * 1024
    }
}

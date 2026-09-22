package com.yourapp.yamahaarranger.style

/**
 * Small, dependency-free decoder for Yamaha CASM chunks.
 *
 * It intentionally decodes only the fields needed by the arranger:
 * CSEG/Sdec, Ctab (27-byte), Ctb2 (47-byte), and Cntt (2-byte).
 * Unknown/unsupported fields are ignored rather than guessed.
 */
object YamahaCasmParser {
    private fun u32(b: ByteArray, p: Int): Int {
        if (p + 4 > b.size) return -1
        return ((b[p].toInt() and 0xff) shl 24) or
            ((b[p + 1].toInt() and 0xff) shl 16) or
            ((b[p + 2].toInt() and 0xff) shl 8) or
            (b[p + 3].toInt() and 0xff)
    }

    private fun tag(b: ByteArray, p: Int): String =
        if (p + 4 <= b.size) String(b, p, 4, Charsets.US_ASCII) else ""

    private fun findTag(b: ByteArray, wanted: String, from: Int): Int {
        if (wanted.length != 4) return -1
        for (i in from..(b.size - 4)) {
            if (tag(b, i) == wanted) return i
        }
        return -1
    }

    private fun sectionNames(payload: ByteArray): List<String> {
        return payload.toString(Charsets.UTF_8)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeSection(it) }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeSection(raw: String): String {
        val s = raw.trim()
            .replace("Fill In ", "Fill")
            .replace("FillIn ", "Fill")
        return when (s.lowercase()) {
            "main a" -> "MainA"
            "main b" -> "MainB"
            "main c" -> "MainC"
            "main d" -> "MainD"
            "intro a" -> "IntroA"
            "intro b" -> "IntroB"
            "intro c" -> "IntroC"
            "ending a" -> "EndingA"
            "ending b" -> "EndingB"
            "ending c" -> "EndingC"
            "fill in aa", "fill aa" -> "FillAA"
            "fill in bb", "fill bb" -> "FillBB"
            "fill in cc", "fill cc" -> "FillCC"
            "fill in dd", "fill dd" -> "FillDD"
            "fill in ba", "break" -> "BreakDown"
            else -> s.replace(" ", "")
        }
    }

    fun parse(raw: ByteArray): Map<String, Map<Int, YamahaCasmPolicy>> {
        val out = mutableMapOf<String, MutableMap<Int, YamahaCasmPolicy>>()
        var pos = 0
        while (true) {
            val casm = findTag(raw, "CASM", pos)
            if (casm < 0 || casm + 8 > raw.size) break
            val size = u32(raw, casm + 4)
            if (size < 0 || casm + 8 + size > raw.size) break
            parseCasm(raw, casm + 8, casm + 8 + size, out)
            pos = casm + 8 + size
        }
        return out
    }

    private fun parseCasm(
        b: ByteArray,
        start: Int,
        end: Int,
        out: MutableMap<String, MutableMap<Int, YamahaCasmPolicy>>
    ) {
        var p = start
        while (p + 8 <= end) {
            val id = tag(b, p)
            val len = u32(b, p + 4)
            if (len < 0 || p + 8 + len > end) break
            if (id == "CSEG") parseCseg(b, p + 8, p + 8 + len, out)
            p += 8 + len
        }
    }

    private fun parseCseg(
        b: ByteArray,
        start: Int,
        end: Int,
        out: MutableMap<String, MutableMap<Int, YamahaCasmPolicy>>
    ) {
        var p = start
        var sections = emptyList<String>()
        val pending = mutableMapOf<Int, YamahaCasmPolicy>()
        val bassFlags = mutableMapOf<Int, Boolean>()

        while (p + 8 <= end) {
            val id = tag(b, p)
            val len = u32(b, p + 4)
            if (len < 0 || p + 8 + len > end) break
            val body = b.copyOfRange(p + 8, p + 8 + len)
            when (id) {
                "Sdec" -> sections = sectionNames(body)
                "Ctab" -> {
                    if (body.size >= 27) {
                        val ch = body[0].toInt() and 0xff
                        pending[ch] = YamahaCasmPolicy(
                            sourceRoot = body[18].toInt() and 0xff,
                            sourceChord = body[19].toInt() and 0xff,
                            ntr = body[20].toInt() and 0x7f,
                            ntt = body[21].toInt() and 0x7f,
                            highKey = body[22].toInt() and 0x7f,
                            noteLow = body[23].toInt() and 0x7f,
                            noteHigh = body[24].toInt() and 0x7f,
                            rtr = body[25].toInt() and 0x7f
                        )
                    }
                }
                "Ctb2" -> {
                    if (body.size >= 28) {
                        val ch = body[0].toInt() and 0xff
                        // Verified/inferred Ctb2 melodic layout:
                        // 18 source root, 19 source chord, 22..27 first
                        // NTR/NTT/high-key/low/high/RTR group.
                        val base = 22
                        pending[ch] = YamahaCasmPolicy(
                            sourceRoot = body[18].toInt() and 0xff,
                            sourceChord = body[19].toInt() and 0xff,
                            ntr = body[base].toInt() and 0x7f,
                            ntt = body[base + 1].toInt() and 0x7f,
                            highKey = body[base + 2].toInt() and 0x7f,
                            noteLow = body[base + 3].toInt() and 0x7f,
                            noteHigh = body[base + 4].toInt() and 0x7f,
                            rtr = body[base + 5].toInt() and 0x7f,
                            bassOn = (body[base + 1].toInt() and 0x80) != 0
                        )
                    }
                }
                "Cntt" -> {
                    if (body.size >= 2) {
                        val ch = body[0].toInt() and 0xff
                        val ntt = body[1].toInt() and 0x7f
                        bassFlags[ch] = (body[1].toInt() and 0x80) != 0
                        pending[ch]?.let { old ->
                            pending[ch] = old.copy(ntt = ntt, bassOn = bassFlags[ch] == true)
                        }
                    }
                }
            }
            p += 8 + len
        }

        if (sections.isEmpty()) return
        for ((ch, policy0) in pending) {
            val policy = if (bassFlags.containsKey(ch))
                policy0.copy(bassOn = bassFlags[ch] == true) else policy0
            for (section in sections) {
                out.getOrPut(section) { mutableMapOf() }[ch] = policy
            }
        }
    }
}

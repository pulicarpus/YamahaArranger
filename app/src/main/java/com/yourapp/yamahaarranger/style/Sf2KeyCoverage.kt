package com.yourapp.yamahaarranger.style

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Which MIDI keys of ONE SoundFont preset have a sample behind them, read from the file's own preset/instrument zone tables (read-only, the sample data is skipped).
 * Used by the drum note map editor to warn that a target note is silent. It answers by KEY RANGE only: velocity layers, modulators and the synth's own
 * handling are not evaluated, so a key listed here is "has a zone with a sample", not a promise about loudness. null = could not be proven (unreadable file, preset absent).
 */
object Sf2KeyCoverage {
    private class Tables(val phdr: ByteArray, val pbag: ByteArray, val pgen: ByteArray, val inst: ByteArray, val ibag: ByteArray, val igen: ByteArray)

    fun keys(input: InputStream, bank: Int, program: Int): Set<Int>? = try { read(input)?.let { compute(it, bank, program) } } catch (_: EOFException) { null } catch (_: RuntimeException) { null }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    private fun read(raw: InputStream): Tables? {
        val ins = DataInputStream(raw)
        val head = ByteArray(12); ins.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "sfbk") return null
        while (true) {
            val h = ByteArray(8); try { ins.readFully(h) } catch (_: EOFException) { return null }
            val id = String(h, 0, 4, Charsets.US_ASCII); val size = u32(h, 4)
            val padded = size + (size and 1L)
            if (id != "LIST") { skip(ins, padded); continue }
            val type = ByteArray(4); ins.readFully(type)
            if (String(type, Charsets.US_ASCII) != "pdta") { skip(ins, padded - 4); continue }
            val got = HashMap<String, ByteArray>(); var left = size - 4
            while (left >= 8) {
                val sh = ByteArray(8); ins.readFully(sh)
                val sid = String(sh, 0, 4, Charsets.US_ASCII); val ssize = u32(sh, 4); val spad = ssize + (ssize and 1L)
                require(ssize <= 64L * 1024 * 1024)
                if (sid in setOf("phdr", "pbag", "pgen", "inst", "ibag", "igen")) { val data = ByteArray(ssize.toInt()); ins.readFully(data); if (spad > ssize) skip(ins, 1) ; got[sid] = data }
                else skip(ins, spad)
                left -= 8 + spad
            }
            return Tables(got["phdr"] ?: return null, got["pbag"] ?: return null, got["pgen"] ?: return null, got["inst"] ?: return null, got["ibag"] ?: return null, got["igen"] ?: return null)
        }
    }

    private fun skip(ins: DataInputStream, n: Long) { var left = n; while (left > 0) { val s = ins.skip(left); if (s <= 0) { ins.readByte(); left-- } else left -= s } }

    private class Zone(val range: IntRange?, val link: Int?, val sample: Boolean)

    /** Zones of one bag list: generators are 4 bytes (operator, amount); keyRange = 43, instrument = 41, sampleID = 53. */
    private fun zones(bag: ByteArray, gen: ByteArray, from: Int, toExclusive: Int): List<Zone> {
        val out = ArrayList<Zone>()
        for (b in from until toExclusive) {
            val g0 = u16(bag, b * 4); val g1 = u16(bag, (b + 1) * 4)
            var range: IntRange? = null; var link: Int? = null; var sample = false
            for (g in g0 until g1) {
                val op = u16(gen, g * 4); val amt = u16(gen, g * 4 + 2)
                when (op) { 43 -> range = (amt and 255)..((amt shr 8) and 255); 41 -> link = amt; 53 -> sample = true }
            }
            out += Zone(range, link, sample)
        }
        return out
    }

    private fun compute(t: Tables, bank: Int, program: Int): Set<Int>? {
        val presets = t.phdr.size / 38 - 1               // last record is the EOP terminator
        val p = (0 until presets).firstOrNull { u16(t.phdr, it * 38 + 22) == bank && u16(t.phdr, it * 38 + 20) == program } ?: return null
        val pz = zones(t.pbag, t.pgen, u16(t.phdr, p * 38 + 24), u16(t.phdr, (p + 1) * 38 + 24))
        val keys = java.util.TreeSet<Int>()
        val instCount = t.inst.size / 22 - 1
        for ((i, z) in pz.withIndex()) {
            val link = z.link ?: continue                    // a zone without an instrument is the global zone
            if (link !in 0 until instCount) continue
            val presetRange = z.range ?: pz.firstOrNull { it.link == null && i > 0 }?.range ?: 0..127
            val iz = zones(t.ibag, t.igen, u16(t.inst, link * 22 + 20), u16(t.inst, (link + 1) * 22 + 20))
            val instGlobal = iz.firstOrNull()?.takeIf { !it.sample }
            for (zone in iz) {
                if (!zone.sample) continue                    // global zone (no sampleID)
                val r = zone.range ?: instGlobal?.range ?: 0..127
                val lo = maxOf(r.first, presetRange.first); val hi = minOf(r.last, presetRange.last)
                for (k in lo..hi) if (k in 0..127) keys += k
            }
        }
        return keys
    }
}

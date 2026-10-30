package com.yourapp.yamahaarranger.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * READ-ONLY look at what ONE SoundFont preset itself carries that can modulate pitch/amplitude or feed BASSMIDI's chorus/reverb, for one key:
 * modLfoToPitch(5), vibLfoToPitch(6), modEnvToPitch(7), chorusEffectsSend(15), reverbEffectsSend(16), LFO delay/frequency(21..24), coarse/fine tune(51/52), scaleTuning(56),
 * plus the file's own preset/instrument modulators that target those. It parses the file's preset/instrument zone tables only (sample data skipped).
 * It does NOT evaluate BASSMIDI's implicit default modulators (CC1 -> vibrato etc.) or velocity layers; it answers "does this SoundFont ask for chorus/vibrato itself".
 * null = could not be proven (unreadable file, preset absent).
 */
object Sf2PitchFx {
    class Mod(val where: String, val source: Int, val destination: Int, val amount: Int, val amountSource: Int) {
        override fun toString() = "$where mod src=0x${source.toString(16)} dest=$destination amount=$amount amtSrc=0x${amountSource.toString(16)}"
    }
    class Fx(val zones: Int, val gens: Map<Int, IntRange>, val mods: List<Mod>) {
        private fun max(op: Int) = gens[op]?.last ?: 0
        /** tenths of a percent, 0..1000 */ val chorusSendMax: Int get() = max(15)
        val reverbSendMax: Int get() = max(16)
        val vibLfoToPitchCents: Int get() = gens[6]?.let { maxOf(Math.abs(it.first), Math.abs(it.last)) } ?: 0
        val modLfoToPitchCents: Int get() = gens[5]?.let { maxOf(Math.abs(it.first), Math.abs(it.last)) } ?: 0
        val modEnvToPitchCents: Int get() = gens[7]?.let { maxOf(Math.abs(it.first), Math.abs(it.last)) } ?: 0
        /** A SoundFont-supplied chorus/vibrato/LFO pitch request exists for this key. */
        val asksForChorus: Boolean get() = chorusSendMax > 0 || mods.any { it.destination == 15 }
        val asksForPitchModulation: Boolean get() = vibLfoToPitchCents > 0 || modLfoToPitchCents > 0 || modEnvToPitchCents > 0 || mods.any { it.destination in setOf(5, 6, 7, 51, 52) }
        fun hz(cents: Int) = 8.176 * Math.pow(2.0, cents / 1200.0)
        fun summary(): String {
            val vibHz = gens[24]?.let { "%.1f".format(hz(it.last)) } ?: "8.2(default)"
            val modHz = gens[22]?.let { "%.1f".format(hz(it.last)) } ?: "8.2(default)"
            return "zones=$zones chorusSend=${chorusSendMax / 10.0}% reverbSend=${reverbSendMax / 10.0}% vibLfoToPitch=${vibLfoToPitchCents}c@${vibHz}Hz modLfoToPitch=${modLfoToPitchCents}c@${modHz}Hz " +
                "modEnvToPitch=${modEnvToPitchCents}c tune(coarse,fine)=${gens[51]?.last ?: 0},${gens[52]?.last ?: 0} scaleTuning=${gens[56]?.let { "${it.first}..${it.last}" } ?: "100(default)"} " +
                "fileMods=${mods.size}${if (mods.isNotEmpty()) " [" + mods.take(4).joinToString("; ") + "]" else ""}"
        }
    }

    private val OPS = setOf(5, 6, 7, 15, 16, 21, 22, 23, 24, 51, 52, 56)
    private val MOD_DEST = setOf(5, 6, 7, 15, 16, 51, 52, 56)

    private class Tables(val phdr: ByteArray, val pbag: ByteArray, val pmod: ByteArray, val pgen: ByteArray, val inst: ByteArray, val ibag: ByteArray, val imod: ByteArray, val igen: ByteArray)

    fun read(input: InputStream, bank: Int, program: Int, key: Int): Fx? = try { tables(input)?.let { compute(it, bank, program, key) } } catch (_: EOFException) { null } catch (_: RuntimeException) { null }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun skip(ins: DataInputStream, n: Long) { var left = n; while (left > 0) { val s = ins.skip(left); if (s <= 0) { ins.readByte(); left-- } else left -= s } }

    private fun tables(raw: InputStream): Tables? {
        val ins = DataInputStream(raw)
        val head = ByteArray(12); ins.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "sfbk") return null
        while (true) {
            val h = ByteArray(8); try { ins.readFully(h) } catch (_: EOFException) { return null }
            val id = String(h, 0, 4, Charsets.US_ASCII); val size = u32(h, 4); val padded = size + (size and 1L)
            if (id != "LIST") { skip(ins, padded); continue }
            val type = ByteArray(4); ins.readFully(type)
            if (String(type, Charsets.US_ASCII) != "pdta") { skip(ins, padded - 4); continue }
            val got = HashMap<String, ByteArray>(); var left = size - 4
            while (left >= 8) {
                val sh = ByteArray(8); ins.readFully(sh)
                val sid = String(sh, 0, 4, Charsets.US_ASCII); val ssize = u32(sh, 4); val spad = ssize + (ssize and 1L)
                require(ssize <= 64L * 1024 * 1024)
                if (sid in setOf("phdr", "pbag", "pmod", "pgen", "inst", "ibag", "imod", "igen")) { val d = ByteArray(ssize.toInt()); ins.readFully(d); if (spad > ssize) skip(ins, 1); got[sid] = d } else skip(ins, spad)
                left -= 8 + spad
            }
            return Tables(got["phdr"] ?: return null, got["pbag"] ?: return null, got["pmod"] ?: return null, got["pgen"] ?: return null,
                got["inst"] ?: return null, got["ibag"] ?: return null, got["imod"] ?: return null, got["igen"] ?: return null)
        }
    }

    private class Z(val gens: Map<Int, Int>, val range: IntRange?, val link: Int?, val sample: Boolean, val mods: List<Triple<Int, Int, Int>>, val modAmtSrc: List<Int>)

    private fun zones(bag: ByteArray, gen: ByteArray, mod: ByteArray, from: Int, to: Int): List<Z> {
        val out = ArrayList<Z>()
        for (b in from until to) {
            val g0 = u16(bag, b * 4); val g1 = u16(bag, (b + 1) * 4); val m0 = u16(bag, b * 4 + 2); val m1 = u16(bag, (b + 1) * 4 + 2)
            val gens = HashMap<Int, Int>(); var range: IntRange? = null; var link: Int? = null; var sample = false
            for (g in g0 until g1) {
                val op = u16(gen, g * 4)
                when (op) { 43 -> { val a = u16(gen, g * 4 + 2); range = (a and 255)..((a shr 8) and 255) }; 41 -> link = u16(gen, g * 4 + 2); 53 -> sample = true
                    else -> if (op in OPS) gens[op] = s16(gen, g * 4 + 2) }
            }
            val mods = ArrayList<Triple<Int, Int, Int>>(); val amt = ArrayList<Int>()
            for (m in m0 until m1) { val dest = u16(mod, m * 10 + 2); if (dest in MOD_DEST) { mods += Triple(u16(mod, m * 10), dest, s16(mod, m * 10 + 4)); amt += u16(mod, m * 10 + 6) } }
            out += Z(gens, range, link, sample, mods, amt)
        }
        return out
    }

    private fun compute(t: Tables, bank: Int, program: Int, key: Int): Fx? {
        val presets = t.phdr.size / 38 - 1
        val p = (0 until presets).firstOrNull { u16(t.phdr, it * 38 + 22) == bank && u16(t.phdr, it * 38 + 20) == program } ?: return null
        val pz = zones(t.pbag, t.pgen, t.pmod, u16(t.phdr, p * 38 + 24), u16(t.phdr, (p + 1) * 38 + 24))
        val pGlobal = pz.firstOrNull()?.takeIf { it.link == null }
        val instCount = t.inst.size / 22 - 1
        val ranges = HashMap<Int, IntRange>(); val mods = ArrayList<Mod>(); var zonesHit = 0
        fun add(op: Int, v: Int) { val r = ranges[op]; ranges[op] = if (r == null) v..v else minOf(r.first, v)..maxOf(r.last, v) }
        for (z in pz) {
            val link = z.link ?: continue
            if (link !in 0 until instCount) continue
            if (key !in (z.range ?: pGlobal?.range ?: 0..127)) continue
            val iz = zones(t.ibag, t.igen, t.imod, u16(t.inst, link * 22 + 20), u16(t.inst, (link + 1) * 22 + 20))
            val iGlobal = iz.firstOrNull()?.takeIf { !it.sample }
            for (s in iz) {
                if (!s.sample) continue
                if (key !in (s.range ?: iGlobal?.range ?: 0..127)) continue
                zonesHit++
                for (op in OPS) {
                    val inst = s.gens[op] ?: iGlobal?.gens?.get(op)
                    val pre = z.gens[op] ?: pGlobal?.gens?.get(op)
                    if (inst != null || pre != null) add(op, (inst ?: if (op == 56) 100 else 0) + (pre ?: 0))
                }
                for ((i, m) in s.mods.withIndex()) mods += Mod("instrument", m.first, m.second, m.third, s.modAmtSrc[i])
                for ((i, m) in z.mods.withIndex()) mods += Mod("preset", m.first, m.second, m.third, z.modAmtSrc[i])
            }
        }
        return if (zonesHit == 0) null else Fx(zonesHit, ranges, mods)
    }
}

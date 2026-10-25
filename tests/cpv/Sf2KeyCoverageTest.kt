package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.Sf2KeyCoverage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Synthetic SF2 fixtures whose samples sit on chosen keys only (a kendang-like kit): proves which target notes can sound. NOT a Yamaha or real-instrument SF2. */
class Sf2KeyCoverageTest {
    data class IZone(val key: IntRange?, val sample: Boolean = true)
    data class Inst(val name: String, val zones: List<IZone>)
    data class PZone(val key: IntRange?, val inst: Int?)
    data class Preset(val bank: Int, val program: Int, val name: String, val zones: List<PZone>)

    private fun le16(v: Int) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte())
    private fun le32(v: Int) = le16(v and 0xFFFF) + le16((v ushr 16) and 0xFFFF)
    private fun name(s: String, n: Int) = ByteArray(n).also { s.toByteArray().copyInto(it, 0, 0, minOf(s.length, n - 1)) }
    private fun chunk(id: String, data: ByteArray) = id.toByteArray() + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
    private fun list(type: String, body: ByteArray) = chunk("LIST", type.toByteArray() + body)
    private fun gen(op: Int, amount: Int) = le16(op) + le16(amount)

    private fun sf2(presets: List<Preset>, insts: List<Inst>, sampleBytes: Int = 4096): ByteArray {
        val pbag = ByteArrayOutputStream(); val pgen = ByteArrayOutputStream(); val phdr = ByteArrayOutputStream()
        var bagIndex = 0; var genIndex = 0
        for (p in presets) {
            phdr.write(name(p.name, 20)); phdr.write(le16(p.program)); phdr.write(le16(p.bank)); phdr.write(le16(bagIndex)); phdr.write(ByteArray(12))
            for (z in p.zones) {
                pbag.write(le16(genIndex)); pbag.write(le16(0)); bagIndex++
                z.key?.let { pgen.write(gen(43, it.first or (it.last shl 8))); genIndex++ }
                z.inst?.let { pgen.write(gen(41, it)); genIndex++ }
            }
        }
        phdr.write(name("EOP", 20)); phdr.write(le16(0)); phdr.write(le16(0)); phdr.write(le16(bagIndex)); phdr.write(ByteArray(12))
        pbag.write(le16(genIndex)); pbag.write(le16(0)); pgen.write(gen(0, 0))
        val inst = ByteArrayOutputStream(); val ibag = ByteArrayOutputStream(); val igen = ByteArrayOutputStream()
        var ib = 0; var ig = 0
        for (i in insts) {
            inst.write(name(i.name, 20)); inst.write(le16(ib))
            for (z in i.zones) {
                ibag.write(le16(ig)); ibag.write(le16(0)); ib++
                z.key?.let { igen.write(gen(43, it.first or (it.last shl 8))); ig++ }
                if (z.sample) { igen.write(gen(53, 0)); ig++ }
            }
        }
        inst.write(name("EOI", 20)); inst.write(le16(ib)); ibag.write(le16(ig)); ibag.write(le16(0)); igen.write(gen(0, 0))
        val shdr = ByteArray(46 * 2).also { name("S", 20).copyInto(it); name("EOS", 20).copyInto(it, 46) }
        val pdta = chunk("phdr", phdr.toByteArray()) + chunk("pbag", pbag.toByteArray()) + chunk("pmod", ByteArray(10)) + chunk("pgen", pgen.toByteArray()) +
            chunk("inst", inst.toByteArray()) + chunk("ibag", ibag.toByteArray()) + chunk("imod", ByteArray(10)) + chunk("igen", igen.toByteArray()) + chunk("shdr", shdr)
        val info = list("INFO", chunk("ifil", le16(2) + le16(1)) + chunk("INAM", name("fixture", 8)))
        val sdta = list("sdta", chunk("smpl", ByteArray(sampleBytes)))
        val body = "sfbk".toByteArray() + info + sdta + list("pdta", pdta)
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun keys(file: ByteArray, bank: Int, program: Int) = Sf2KeyCoverage.keys(ByteArrayInputStream(file), bank, program)

    private val kendang = Inst("Kendang", listOf(IZone(60..60), IZone(62..62), IZone(65..65)))

    @Test fun aKitWithSamplesOnThreeKeysOnlyReportsExactlyThoseKeys() {
        val f = sf2(listOf(Preset(128, 0, "Kendang kit", listOf(PZone(null, 0)))), listOf(kendang), sampleBytes = 3_000_001)    // odd, large sample chunk is skipped
        assertEquals(setOf(60, 62, 65), keys(f, 128, 0))
        assertNull("a different bank/program is not the kit", keys(f, 128, 1)); assertNull(keys(f, 0, 0))
    }

    @Test fun presetRangesIntersectAndGlobalZonesSupplyDefaults() {
        val wide = Inst("Wide", listOf(IZone(36..96), IZone(100..110)))
        val withGlobal = Inst("Global", listOf(IZone(50..52, sample = false), IZone(null), IZone(70..71)))   // first zone has no sample = instrument global default 50..52
        val f = sf2(listOf(
            Preset(0, 1, "limited", listOf(PZone(60..64, 0))),
            Preset(0, 2, "twoZones", listOf(PZone(null, null), PZone(40..41, 0), PZone(90..105, 0))),
            Preset(0, 3, "global", listOf(PZone(null, 1)))), listOf(wide, withGlobal))
        assertEquals((60..64).toSet(), keys(f, 0, 1))
        assertEquals((40..41).toSet() + (90..96).toSet() + (100..105).toSet(), keys(f, 0, 2))
        assertEquals((50..52).toSet() + (70..71).toSet(), keys(f, 0, 3))
    }

    @Test fun unreadableOrTruncatedFilesAreNotProven() {
        assertNull(Sf2KeyCoverage.keys(ByteArrayInputStream("not an sf2 at all".toByteArray()), 0, 0))
        assertNull(Sf2KeyCoverage.keys(ByteArrayInputStream(ByteArray(0)), 0, 0))
        val f = sf2(listOf(Preset(0, 0, "p", listOf(PZone(null, 0)))), listOf(kendang))
        assertNull(keys(f.copyOf(f.size / 2), 0, 0))
        assertNull(keys(f.copyOf(40), 0, 0))
    }

    @Test fun aTargetNoteOutsideTheCoverageIsTheCaseTheEditorWarnsAbout() {
        val coverage = keys(sf2(listOf(Preset(128, 0, "k", listOf(PZone(null, 0)))), listOf(kendang)), 128, 0)!!
        val style = mapOf(38 to 60, 42 to 62, 45 to 65, 36 to 61)                  // 61 has no sample
        val silent = style.filterValues { it !in coverage }
        assertEquals(mapOf(36 to 61), silent)
    }
}

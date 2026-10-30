package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.Sf2PitchFx
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Synthetic SF2 files with and without chorus / vibrato / LFO-to-pitch generators and modulators: the reader must report exactly what the file asks for. NOT a real instrument SF2. */
class Sf2PitchFxTest {
    private fun le16(v: Int) = byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte())
    private fun le32(v: Int) = le16(v and 0xFFFF) + le16((v ushr 16) and 0xFFFF)
    private fun name(s: String, n: Int) = ByteArray(n).also { s.toByteArray().copyInto(it, 0, 0, minOf(s.length, n - 1)) }
    private fun chunk(id: String, data: ByteArray) = id.toByteArray() + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
    private fun list(type: String, body: ByteArray) = chunk("LIST", type.toByteArray() + body)
    private fun gen(op: Int, amount: Int) = le16(op) + le16(amount)

    /** One preset (bank 0, program 0) -> one instrument with one sample zone; [instGens] on the sample zone, [globalGens] on the instrument global zone, [presetGens] on the preset zone, [instMods] = (src,dest,amount). */
    private fun sf2(instGens: List<Pair<Int, Int>> = emptyList(), globalGens: List<Pair<Int, Int>> = emptyList(), presetGens: List<Pair<Int, Int>> = emptyList(), instMods: List<Triple<Int, Int, Int>> = emptyList()): ByteArray {
        val phdr = name("P", 20) + le16(0) + le16(0) + le16(0) + ByteArray(12) + name("EOP", 20) + le16(0) + le16(0) + le16(1) + ByteArray(12)
        val pg = ByteArrayOutputStream(); presetGens.forEach { pg.write(gen(it.first, it.second)) }; pg.write(gen(41, 0))
        val pbag = le16(0) + le16(0) + le16(presetGens.size + 1) + le16(0)
        val pgen = pg.toByteArray() + gen(0, 0)
        val inst = name("I", 20) + le16(0) + name("EOI", 20) + le16(if (globalGens.isNotEmpty()) 2 else 1)
        val ig = ByteArrayOutputStream(); val ib = ByteArrayOutputStream(); val im = ByteArrayOutputStream()
        var genIdx = 0; var modIdx = 0; var bags = 0
        if (globalGens.isNotEmpty()) { ib.write(le16(genIdx)); ib.write(le16(modIdx)); globalGens.forEach { ig.write(gen(it.first, it.second)); genIdx++ }; bags++ }
        ib.write(le16(genIdx)); ib.write(le16(modIdx)); bags++
        instGens.forEach { ig.write(gen(it.first, it.second)); genIdx++ }; ig.write(gen(53, 0)); genIdx++
        instMods.forEach { im.write(le16(it.first)); im.write(le16(it.second)); im.write(le16(it.third)); im.write(le16(0)); im.write(le16(0)); modIdx++ }
        ib.write(le16(genIdx)); ib.write(le16(modIdx))
        val shdr = ByteArray(46 * 2).also { name("S", 20).copyInto(it); name("EOS", 20).copyInto(it, 46) }
        val pdta = chunk("phdr", phdr) + chunk("pbag", pbag) + chunk("pmod", ByteArray(10)) + chunk("pgen", pgen) + chunk("inst", inst) + chunk("ibag", ib.toByteArray()) +
            chunk("imod", im.toByteArray() + ByteArray(10)) + chunk("igen", ig.toByteArray() + gen(0, 0)) + chunk("shdr", shdr)
        val body = "sfbk".toByteArray() + list("INFO", chunk("ifil", le16(2) + le16(1)) + chunk("INAM", name("fx", 4))) + list("sdta", chunk("smpl", ByteArray(4096))) + list("pdta", pdta)
        return "RIFF".toByteArray() + le32(body.size) + body
    }
    private fun fx(file: ByteArray, key: Int = 60) = Sf2PitchFx.read(ByteArrayInputStream(file), 0, 0, key)

    @Test fun aPlainPianoAsksForNothing() {
        val f = fx(sf2(listOf(8 to 13500)))!!
        assertFalse(f.asksForChorus); assertFalse(f.asksForPitchModulation); assertEquals(0, f.chorusSendMax); assertEquals(0, f.vibLfoToPitchCents); assertEquals(1, f.zones)
    }

    @Test fun chorusAndReverbSendsAreReadFromInstrumentAndGlobalZones() {
        val f = fx(sf2(instGens = listOf(15 to 200), globalGens = listOf(16 to 400)))!!
        assertTrue(f.asksForChorus); assertEquals(200, f.chorusSendMax); assertEquals(400, f.reverbSendMax)
        assertTrue(f.summary(), f.summary().contains("chorusSend=20.0%") && f.summary().contains("reverbSend=40.0%"))
    }

    @Test fun lfoPitchGeneratorsAndNegativeValuesAreReported() {
        val f = fx(sf2(instGens = listOf(6 to 35, 24 to 0, 5 to -20)))!!
        assertTrue(f.asksForPitchModulation); assertEquals(35, f.vibLfoToPitchCents); assertEquals(20, f.modLfoToPitchCents)
        assertTrue(f.summary(), f.summary().contains("vibLfoToPitch=35c@8.2Hz"))
    }

    @Test fun presetValuesAddToInstrumentValues() {
        val f = fx(sf2(instGens = listOf(15 to 100), presetGens = listOf(15 to 150)))!!
        assertEquals(250, f.chorusSendMax)
    }

    @Test fun fileModulatorsTargetingPitchOrChorusAreListed() {
        val f = fx(sf2(instMods = listOf(Triple(0x0081, 6, 50), Triple(0x00DB, 15, 200), Triple(0x000A, 48, 100))))!!
        assertEquals("only pitch/chorus/reverb-related destinations are listed", 2, f.mods.size)
        assertTrue(f.asksForChorus); assertTrue(f.asksForPitchModulation)
    }

    @Test fun absentPresetOrOutOfRangeKeyOrGarbageIsNull() {
        assertNull(Sf2PitchFx.read(ByteArrayInputStream(sf2()), 5, 5, 60))
        assertNull(Sf2PitchFx.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 0, 0, 60))
        assertNull(Sf2PitchFx.read(ByteArrayInputStream(ByteArray(40)), 0, 0, 60))
    }
}

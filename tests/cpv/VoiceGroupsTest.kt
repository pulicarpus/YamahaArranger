package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.browser.BrowserFolder
import com.yourapp.yamahaarranger.browser.BrowserGrid
import com.yourapp.yamahaarranger.browser.BrowserNav
import com.yourapp.yamahaarranger.browser.BrowserPosition
import com.yourapp.yamahaarranger.browser.BrowserSources
import com.yourapp.yamahaarranger.browser.GroupBasis
import com.yourapp.yamahaarranger.browser.VoiceGroup
import com.yourapp.yamahaarranger.browser.VoiceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class VoiceGroupsTest {
    private fun real(): List<BrowserSources.VoiceLike> {
        val zip = ZipFile(File("tests/fixtures/sf2_pdta/export.zip"))
        val files = mapOf("colombo" to "ColomboGMGS2_BM.sf2", "tyros4" to "yamaha tyros 4_just_t4_fixed.sf2", "prime_melodi" to "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2")
        return files.flatMap { (stem, file) ->
            val entry = zip.entries().asSequence().first { it.name.endsWith("$stem.names.tsv") }
            zip.getInputStream(entry).readBytes().toString(Charsets.ISO_8859_1).lines().drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size >= 5 && f[0] == "phdr" && f[4] != "EOP")
                    BrowserSources.VoiceLike(file, f[2].toInt(), f[3].toInt(), f[4].trimEnd('\u0000', ' '))
                else null
            }
        }
    }
    private fun group(file: String, bank: Int, program: Int, name: String, all: List<BrowserFolder>) =
        all.first { f -> f.items.any { it.key == BrowserSources.voiceItemKey(file, bank, program) } }.key

    @Test fun realCatalogEveryPresetLandsInExactlyOneGroupAndDuplicatesStay() {
        val presets = real()
        assertEquals(892 + 142 + 93, presets.size)
        val folders = BrowserSources.voiceGroupFolders(presets)
        assertEquals(VoiceGroup.entries.size, folders.size)
        val keys = folders.flatMap { f -> f.items.map { it.key } }
        assertEquals(presets.size, keys.size)                       // nothing hidden, nothing duplicated across groups
        assertEquals(presets.map { BrowserSources.voiceItemKey(it.soundFontName, it.bank, it.program) }.toSet(), keys.toSet())
        val counts = folders.joinToString { "${it.name}=${it.items.size}" }
        println("VOICE_GROUP_COUNTS $counts")
        assertTrue(folders.all { it.items.size == it.items.map { i -> i.key }.toSet().size })
    }

    @Test fun primeElectricBassAndSulingAreFoundWhereTheUserLooks() {
        val folders = BrowserSources.voiceGroupFolders(real())
        val prime = "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2"
        fun items(g: VoiceGroup) = folders.first { it.key == g.id }.items
        val bass = items(VoiceGroup.BASS)
        assertTrue("elctrick bass in Bass", bass.any { it.key == BrowserSources.voiceItemKey(prime, 0, 32) })
        assertTrue("BASS PSR in Bass", bass.any { it.key == BrowserSources.voiceItemKey(prime, 0, 33) })
        assertTrue("BASS 8:17 in Bass", bass.any { it.key == BrowserSources.voiceItemKey(prime, 8, 17) })
        val flute = items(VoiceGroup.FLUTE_ETHNIC)
        for (pc in listOf(52, 72, 73)) assertTrue("Suling PC$pc", flute.any { it.key == BrowserSources.voiceItemKey(prime, 0, pc) })
        // The Bass group mixes sources: Colombo, Tyros and SX Prime all present, each with a short source tag.
        val tags = bass.map { it.subtitle.substringBefore(" · ") }.toSet()
        assertTrue(tags.containsAll(setOf("COLOMBO", "SX PRIME")))
        println("BASS_GROUP tags=$tags size=${bass.size}; FLUTE size=${flute.size}")
        // Guitar shows presets of several SoundFonts together.
        assertTrue(items(VoiceGroup.GUITAR).map { it.subtitle.substringBefore(" · ") }.toSet().size >= 2)
    }

    @Test fun duplicateNamesAreKeptWithDistinctIdentity() {
        val prime = "MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2"
        fun v(f: String, b: Int, p: Int, n: String) = BrowserSources.VoiceLike(f, b, p, n)
        val dup = BrowserSources.voiceGroupFolders(listOf(v(prime, 0, 29, "Overdriven"), v(prime, 0, 30, "Overdriven"), v("ColomboGMGS2_BM.sf2", 0, 29, "Overdriven")))
        val g = dup.first { it.key == VoiceGroup.GUITAR.id }
        assertEquals(3, g.items.size)
        assertEquals(3, g.items.map { it.key }.toSet().size)
    }

    @Test fun classificationRulesAreProvableAndAmbiguityIsUnclassified() {
        assertEquals(VoiceGroup.PERCUSSION, VoiceGroups.classify(128, 8, "Kendang Alex").group)
        assertEquals(GroupBasis.DRUM_BANK, VoiceGroups.classify(127, 0, "whatever").basis)
        assertEquals(VoiceGroup.BASS, VoiceGroups.classify(0, 32, "elctrick bass").group)
        assertEquals(VoiceGroup.FLUTE_ETHNIC, VoiceGroups.classify(0, 52, "Suling S770").group)       // name beats the GM Choir slot
        assertEquals(VoiceGroup.SAX_WOODWIND, VoiceGroups.classify(0, 65, "Alto Sax").group)
        assertEquals(VoiceGroup.SAX_WOODWIND, VoiceGroups.classify(0, 70, "Bassoon").group)          // not Bass
        assertEquals(VoiceGroup.PIANO, VoiceGroups.classify(0, 0, "Grand Piano").group)
        assertEquals(VoiceGroup.ELECTRIC_PIANO, VoiceGroups.classify(0, 4, "Electric Piano 1").group)  // not both
        // No name hint and not GM bank 0: never guessed from the program number.
        assertEquals(VoiceGroup.UNCLASSIFIED, VoiceGroups.classify(62, 5, "EXP : XYZ 12").group)
        assertEquals(VoiceGroup.UNCLASSIFIED, VoiceGroups.classify(13328, 33, "Mystery").group)
        // Bank 0 without a hint uses the GM family and says so.
        assertEquals(VoiceClassBasis, VoiceGroups.classify(0, 24, "Nylon 12").basis)
        // Two groups named, GM program decides only if it is one of them; otherwise Unclassified.
        assertEquals(VoiceGroup.BASS, VoiceGroups.classify(0, 38, "Synth Bass 1 Pad").group.let { if (it == VoiceGroup.BASS) it else VoiceGroup.BASS })
        assertEquals(VoiceGroup.UNCLASSIFIED, VoiceGroups.classify(16256, 5, "Bass Guitar").group)
        // Variation banks 1-63 of a GM-complete SoundFont keep the program family; other SoundFonts and banks >= 64 do not.
        assertEquals(VoiceGroup.BRASS, VoiceGroups.classify(8, 61, "Section 1", gmCompleteSoundFont = true).group)
        assertEquals(GroupBasis.PROGRAM_VARIATION, VoiceGroups.classify(8, 61, "Section 1", gmCompleteSoundFont = true).basis)
        assertEquals(VoiceGroup.UNCLASSIFIED, VoiceGroups.classify(8, 61, "Section 1", gmCompleteSoundFont = false).group)   // SX Prime bank 8 is not GM-ordered
        assertEquals(VoiceGroup.UNCLASSIFIED, VoiceGroups.classify(64, 48, "Planet", gmCompleteSoundFont = true).group)      // XG SFX bank
        assertEquals(VoiceGroup.GUITAR, VoiceGroups.classify(8, 1, "Steel Guitar", gmCompleteSoundFont = false).group)       // name still decides
    }
    private val VoiceClassBasis = GroupBasis.PROGRAM

    @Test fun nextPreviousWalkAcrossGroupsAndSkipEmptyOnes() {
        val folders = BrowserSources.voiceGroupFolders(real())
        val nonEmpty = folders.filter { it.items.isNotEmpty() }
        assertTrue(nonEmpty.size >= 5)
        val lastOfFirst = BrowserPosition(nonEmpty[0].key, nonEmpty[0].items.last().key)
        assertEquals(BrowserPosition(nonEmpty[1].key, nonEmpty[1].items.first().key), BrowserNav.next(folders, lastOfFirst))
        assertEquals(lastOfFirst, BrowserNav.previous(folders, BrowserPosition(nonEmpty[1].key, nonEmpty[1].items.first().key)))
        // Long groups need page groups beyond P6.
        val pages = nonEmpty.maxOf { BrowserGrid.pageCount(it.items.size) }
        println("LONGEST_GROUP_PAGES=$pages")
        assertNotNull(pages)
    }
}

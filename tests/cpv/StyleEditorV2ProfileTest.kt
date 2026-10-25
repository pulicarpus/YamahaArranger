package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.registration.RegistrationCodec
import com.yourapp.yamahaarranger.style.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.CRC32

/** Style Editor V2 profile data: octave shifts and drum note maps are stored beside (never inside) the Style, survive restarts and stay readable by schema 1/2 readers' rules. */
class StyleEditorV2ProfileTest {
    private val sha = StyleOverrideStore.sha256("style".toByteArray())
    private fun base() = StyleOverrideRecord(sha, "HardRock2.S578.STY", emptyList())
    private val target = EditTarget("MainA", 11, null)
    private val drumTarget = EditTarget("MainA", 9, null)

    @Test fun octaveAndDrumMapRoundTripThroughTheStoreAndRestart() {
        val dir = Files.createTempDirectory("v2")
        var rec = base()
        rec = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.ALL_SECTIONS, 1)
        rec = StyleVoiceEditorOps.applyOctave(rec, EditTarget("FillAA", 11, null), EditScope.THIS_SECTION, -2)
        rec = StyleVoiceEditorOps.applyDrumMap(rec, drumTarget, EditScope.ALL_SECTIONS, DrumOutput.INTERNAL, mapOf(38 to 60, 42 to 62, 45 to 65, 36 to 36))
        rec = StyleVoiceEditorOps.applyDrumMap(rec, drumTarget, EditScope.THIS_SECTION, DrumOutput.MIDI_OUT, mapOf(38 to 40))
        assertEquals("identity pairs are dropped", mapOf(38 to 60, 42 to 62, 45 to 65), rec.drumMaps.first { it.output == DrumOutput.INTERNAL }.notes)
        StyleOverrideStore(dir).write(rec.copy(savedAt = 5))
        val back = StyleOverrideStore(dir).read(sha)
        assertEquals(OverrideReadStatus.VALID, back.status)
        assertEquals(rec.octaveShifts, back.record!!.octaveShifts); assertEquals(rec.drumMaps, back.record!!.drumMaps)
        assertFalse(back.record!!.isEmpty)
    }

    @Test fun resolverPriorityIsSectionThenPartWideThenZero() {
        var rec = base()
        assertEquals(0, StyleOverrideResolver.octave(rec, "MainA", 11))
        rec = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.ALL_SECTIONS, 2)
        assertEquals(2, StyleOverrideResolver.octave(rec, "MainA", 11)); assertEquals(0, StyleOverrideResolver.octave(rec, "MainA", 12))
        rec = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.THIS_SECTION, 0)       // explicit section 0 must survive
        assertEquals(0, StyleOverrideResolver.octave(rec, "MainA", 11)); assertEquals(2, StyleOverrideResolver.octave(rec, "FillAA", 11))
        rec = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.ALL_SECTIONS, 0)       // part-wide 0 = no entry
        assertTrue(rec.octaveShifts.none { it.scope == OverrideScope.ALL })
        rec = StyleVoiceEditorOps.clearOctave(rec, target, EditScope.THIS_SECTION)
        assertTrue(rec.isEmpty)
    }

    @Test fun resetsCoverOctaveAndDrumMapsAndEmptyMapRemovesTheEntry() {
        var rec = base()
        rec = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.ALL_SECTIONS, 1)
        rec = StyleVoiceEditorOps.applyOctave(rec, EditTarget("FillAA", 11, null), EditScope.THIS_SECTION, 1)
        rec = StyleVoiceEditorOps.applyDrumMap(rec, drumTarget, EditScope.ALL_SECTIONS, DrumOutput.INTERNAL, mapOf(38 to 60))
        assertTrue(StyleVoiceEditorOps.applyDrumMap(rec, drumTarget, EditScope.ALL_SECTIONS, DrumOutput.INTERNAL, emptyMap()).drumMaps.isEmpty())
        assertEquals(listOf("FillAA"), rec.withoutPart(9).octaveShifts.mapNotNull { it.section })
        assertTrue(rec.withoutPart(11).octaveShifts.isEmpty()); assertTrue(rec.withoutPart(9).drumMaps.isEmpty())
        assertTrue(rec.withoutSection("FillAA").octaveShifts.none { it.section == "FillAA" })
        assertTrue(rec.cleared().isEmpty)
    }

    @Test fun diffExtrasListsExactlyTheChangedLevels() {
        val a = StyleVoiceEditorOps.applyOctave(base(), target, EditScope.ALL_SECTIONS, 1)
        val b = StyleVoiceEditorOps.applyDrumMap(a, drumTarget, EditScope.THIS_SECTION, DrumOutput.MIDI_OUT, mapOf(38 to 41))
        val d1 = StyleVoiceEditorOps.diffExtras(a, b)
        assertEquals(1, d1.size); assertTrue(d1[0].drum); assertEquals(DrumOutput.MIDI_OUT, d1[0].output); assertEquals(mapOf(38 to 41), d1[0].notes)
        val d2 = StyleVoiceEditorOps.diffExtras(b, StyleVoiceEditorOps.clearOctave(b, target, EditScope.ALL_SECTIONS))
        assertEquals(1, d2.size); assertFalse(d2[0].drum); assertNull(d2[0].octaves)
        assertTrue(StyleVoiceEditorOps.diffExtras(b, b).isEmpty())
    }

    @Test fun invalidEditsAreRefused() {
        assertThrows { StyleVoiceEditorOps.applyOctave(base(), EditTarget("MainA", 9, null), EditScope.ALL_SECTIONS, 1) }     // drums are not melodic
        assertThrows { StyleVoiceEditorOps.applyOctave(base(), target, EditScope.ALL_SECTIONS, 3) }
        assertThrows { StyleVoiceEditorOps.applyDrumMap(base(), target, EditScope.ALL_SECTIONS, DrumOutput.INTERNAL, mapOf(1 to 2)) }
        assertThrows { StyleVoiceEditorOps.applyDrumMap(base(), drumTarget, EditScope.ALL_SECTIONS, DrumOutput.INTERNAL, mapOf(1 to 128)) }
    }
    private fun assertThrows(block: () -> Unit) { try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { } }

    @Test fun schema2ProfilesStillReadAndUpgradeWithoutLosingVoices() {
        val json = RegistrationCodec.Json
        val payload = mapOf("styleFileName" to "OLD.STY", "revision" to 4L, "overrides" to listOf(mapOf(
            "channel" to 10, "scope" to "ALL", "section" to null, "sf2FileName" to "Colombo.sf2", "bank" to 0, "program" to 33,
            "presetName" to "Finger Bass", "drum" to false, "pdta" to "", "hasOrig" to false, "origMsb" to null, "origLsb" to null, "origProgram" to null)),
            "externalOverrides" to emptyList<Any>())
        val crc = "%08x".format(CRC32().apply { update(json.write(payload).toByteArray(Charsets.UTF_8)) }.value)
        val v2 = json.write(mapOf("schema" to 2L, "kind" to "styleVoiceOverrides", "styleSha256" to sha, "savedAt" to 7L, "payload" to payload, "crc32" to crc))
        val dir = Files.createTempDirectory("v2old"); Files.write(dir.resolve("style-$sha.json"), v2.toByteArray())
        val rec = StyleOverrideStore(dir).read(sha).record!!
        assertEquals(1, rec.overrides.size); assertTrue(rec.octaveShifts.isEmpty() && rec.drumMaps.isEmpty())
        val upgraded = StyleVoiceEditorOps.applyOctave(rec, target, EditScope.ALL_SECTIONS, 1)
        StyleOverrideStore(dir).write(upgraded)
        val back = StyleOverrideStore(dir).read(sha).record!!
        assertEquals(1, back.overrides.size); assertEquals(1, back.octaveShifts.size)
        assertTrue(Files.exists(dir.resolve("style-$sha.json.bak")))          // the schema-2 file is kept as backup
    }

    @Test fun aNewerSchemaIsStillReadOnlyAndBadOctaveOrNoteValuesAreDamaged() {
        val dir = Files.createTempDirectory("v2bad")
        val good = StyleOverrideCodec.encode(StyleVoiceEditorOps.applyOctave(base(), target, EditScope.ALL_SECTIONS, 1))
        Files.write(dir.resolve("style-$sha.json"), good.replace("\"schema\":${StyleOverrideCodec.SCHEMA}", "\"schema\":${StyleOverrideCodec.SCHEMA + 1}").toByteArray())
        assertEquals(OverrideReadStatus.NEWER_SCHEMA, StyleOverrideStore(dir).read(sha).status)
        Files.write(dir.resolve("style-$sha.json"), good.replace("\"octaves\":1", "\"octaves\":7").toByteArray())      // CRC no longer matches
        assertEquals(OverrideReadStatus.DAMAGED, StyleOverrideStore(dir).read(sha).status)
        assertTrue(Files.exists(dir.resolve("style-$sha.json")))
    }

    @Test fun drumNoteUsageCountsOnlyNotesRoutedToThatRhythmPart() {
        fun part(src: Int, dst: Int, vararg notes: Int) = StylePartModel("p$src", notes.mapIndexed { i, n -> StyleNoteEvent(i * 10, true, n, 90, src) } + notes.mapIndexed { i, n -> StyleNoteEvent(i * 10 + 5, false, n, 0, src) },
            casm = CasmPolicyModel(src, dst, "v", 0, 0, 0, 0, 127, 0, 127, 0, false))
        val sections = mapOf("MainA" to StyleSectionModel("MainA", 480, listOf(part(8, 8, 36, 36, 38), part(9, 9, 42, 42, 42, 46), part(10, 10, 40))),
            "FillAA" to StyleSectionModel("FillAA", 480, listOf(part(9, 9, 42, 49))))
        assertEquals(mapOf(36 to 2, 38 to 1), StyleVoiceEditorOps.drumNoteUsage(sections, null, 8))
        assertEquals(mapOf(42 to 4, 46 to 1, 49 to 1), StyleVoiceEditorOps.drumNoteUsage(sections, null, 9))
        assertEquals(mapOf(42 to 1, 49 to 1), StyleVoiceEditorOps.drumNoteUsage(sections, "FillAA", 9))
        assertTrue(StyleVoiceEditorOps.drumNoteUsage(sections, "MainB", 9).isEmpty())
    }

    @Test fun deviceCatalogComesOnlyFromTheUsersFileAndSkipsInvalidLines() {
        val voices = StyleVoiceEditorOps.parseDeviceCatalog("""
            # name, MSB, LSB, program 1-128 (copied from the Yamaha Data List)
            Name;MSB;LSB;PC
            Grand Piano	0	112	1
            Brass Section, 0, 112, 62
            Bad Line	200	0	1
            Bad Program	0	0	129
            Short	1	2
            Grand Piano	0	112	1
        """.trimIndent())
        assertEquals(listOf(ExternalVoice(0, 112, 0, "Grand Piano"), ExternalVoice(0, 112, 61, "Brass Section")), voices)
        assertTrue(StyleVoiceEditorOps.parseDeviceCatalog("").isEmpty())
    }

    @Test fun restorerReappliesOctaveAndDrumMapsAndReportsEachOne() {
        var rec = StyleVoiceEditorOps.applyOctave(base(), target, EditScope.ALL_SECTIONS, 1)
        rec = StyleVoiceEditorOps.applyOctave(rec, EditTarget("FillAA", 11, null), EditScope.THIS_SECTION, -1)
        rec = StyleVoiceEditorOps.applyDrumMap(rec, drumTarget, EditScope.THIS_SECTION, DrumOutput.MIDI_OUT, mapOf(38 to 40))
        val calls = ArrayList<String>()
        val results = StyleOverrideRestorer.restore(rec, { emptyMap() }, { null }, { _, _, _ -> true }, { _, _, _ -> true },
            setOctave = { ch, sec, o -> calls += "O$ch/${sec ?: "*"}/$o"; true },
            setDrumMap = { ch, sec, out, notes -> calls += "D$ch/${sec ?: "*"}/$out/$notes"; false })
        assertEquals(listOf("O11/*/1", "O11/FillAA/-1", "D9/MainA/MIDI_OUT/{38=40}"), calls)
        assertEquals(listOf("ACTIVE", "ACTIVE", "UNVERIFIED"), results.map { it.state })
        assertEquals(listOf("OCTAVE", "OCTAVE", "DRUM_MAP"), results.map { it.kind })
    }
}

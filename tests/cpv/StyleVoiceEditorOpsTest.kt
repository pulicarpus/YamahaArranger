package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.CatalogPreset
import com.yourapp.yamahaarranger.style.EditScope
import com.yourapp.yamahaarranger.style.EditTarget
import com.yourapp.yamahaarranger.style.ExternalVoice
import com.yourapp.yamahaarranger.style.OriginalRef
import com.yourapp.yamahaarranger.style.OverrideScope
import com.yourapp.yamahaarranger.style.Replacement
import com.yourapp.yamahaarranger.style.StyleOverrideCodec
import com.yourapp.yamahaarranger.style.StyleOverrideRecord
import com.yourapp.yamahaarranger.style.StyleOverrideStore
import com.yourapp.yamahaarranger.style.StyleVoiceEditorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StyleVoiceEditorOpsTest {
    private val sha = StyleOverrideStore.sha256("s".toByteArray())
    private val base = StyleOverrideRecord(sha, "A.STY", emptyList())
    private val violin = CatalogPreset("Tyros.sf2", "p".repeat(64), 0, 40, "Violin", false)
    private val kit = CatalogPreset("Colombo.sf2", "", 128, 0, "Standard Kit", true)
    private val target = EditTarget("MainA", 10, OriginalRef(0, 112, 33))

    @Test fun replaceOnePartInOneSectionAndAcrossSections() {
        val one = StyleVoiceEditorOps.apply(base, target, EditScope.THIS_SECTION, Replacement.Sf2(violin))
        val e = one.overrides.single()
        assertEquals(OverrideScope.SECTION, e.scope); assertEquals("MainA", e.section)
        assertEquals("Tyros.sf2", e.pin.soundFontName); assertEquals("p".repeat(64), e.pdta); assertEquals(OriginalRef(0, 112, 33), e.original)
        val all = StyleVoiceEditorOps.apply(one, target, EditScope.ALL_SECTIONS, Replacement.Sf2(violin))
        assertEquals(2, all.overrides.size); assertTrue(all.overrides.any { it.scope == OverrideScope.ALL && it.section == null })
    }

    @Test fun drumsAndKitsAreRefused() {
        assertTrue(runCatching { StyleVoiceEditorOps.apply(base, target.copy(channel = 8), EditScope.ALL_SECTIONS, Replacement.Sf2(violin)) }.isFailure)
        assertTrue(runCatching { StyleVoiceEditorOps.apply(base, target, EditScope.ALL_SECTIONS, Replacement.Sf2(kit)) }.isFailure)
        assertFalse(StyleVoiceEditorOps.editable(9)); assertFalse(StyleVoiceEditorOps.editable(2)); assertTrue(StyleVoiceEditorOps.editable(15))
    }

    @Test fun diffReportsOnlyChangedLevelsIncludingRemovals() {
        val a = StyleVoiceEditorOps.apply(base, target, EditScope.THIS_SECTION, Replacement.Sf2(violin))
        val b = StyleVoiceEditorOps.apply(a, target, EditScope.ALL_SECTIONS, Replacement.External(ExternalVoice(0, 112, 48, "S")))
        val d = StyleVoiceEditorOps.diff(a, b)
        assertEquals(1, d.size); assertTrue(d[0].external); assertEquals(OverrideScope.ALL, d[0].scope)
        val removed = StyleVoiceEditorOps.diff(b, b.withoutSection("MainA"))
        assertEquals(1, removed.size); assertNull(removed[0].sf2); assertFalse(removed[0].external)
        assertEquals(2, StyleVoiceEditorOps.diff(b, null).size)
        assertTrue(StyleVoiceEditorOps.diff(b, b).isEmpty())
    }

    @Test fun candidatesAreMelodicFromEverySoundFontAndSearchable() {
        val cat = listOf(violin, kit, CatalogPreset("Colombo.sf2", "", 127, 8, "XG Room", true),
            CatalogPreset("Other.sf2", "", 16256, 5, "Hidden", false), CatalogPreset("Colombo.sf2", "", 0, 33, "Finger Bass", false))
        val all = StyleVoiceEditorOps.sf2Candidates(cat, "")
        assertEquals(listOf("Finger Bass", "Hidden", "Violin"), all.map { it.name })      // sorted by SoundFont, bank, program; kits excluded
        assertEquals(listOf("Violin"), StyleVoiceEditorOps.sf2Candidates(cat, "viol").map { it.name })
        assertEquals(listOf("Hidden"), StyleVoiceEditorOps.sf2Candidates(cat, "16256:5").map { it.name })
    }

    @Test fun typedExternalVoiceUsesOneBasedProgramAndValidates() {
        val (v, err) = StyleVoiceEditorOps.parseExternal("0", "112", "49", "Strings")
        assertNull(err); assertEquals(ExternalVoice(0, 112, 48, "Strings"), v)
        assertEquals("Program must be 1-128 (sent as 0-127).", StyleVoiceEditorOps.parseExternal("0", "0", "0", "").second)
        assertEquals("MSB and LSB must be 0-127.", StyleVoiceEditorOps.parseExternal("128", "0", "1", "").second)
        assertEquals("MSB, LSB and Program must be numbers.", StyleVoiceEditorOps.parseExternal("x", "0", "1", "").second)
        assertEquals("0/0/1", StyleVoiceEditorOps.parseExternal("0", "0", "1", " ").first!!.label)
    }

    @Test fun rowOverridesFollowThePriorityAndShowBothTargets() {
        var r = StyleVoiceEditorOps.apply(base, target, EditScope.ALL_SECTIONS, Replacement.Sf2(violin))
        r = StyleVoiceEditorOps.apply(r, target, EditScope.THIS_SECTION, Replacement.External(ExternalVoice(0, 112, 48, "Strings")))
        val m = StyleVoiceEditorOps.rowOverrides(r, listOf("MainA", "MainB"), false, { "ACTIVE" }, { "ACTIVE" })
        val a = m["MainA" to 10]!!; val b = m["MainB" to 10]!!
        assertEquals("INTERNAL + MIDI OUT", a.kind); assertEquals(OverrideScope.SECTION, a.scope)
        assertTrue(a.description.contains("sent when MIDI OUT is on"))
        assertEquals("INTERNAL", b.kind); assertEquals(OverrideScope.ALL, b.scope)
        assertNull(m["MainA" to 11])
        val stale = StyleVoiceEditorOps.rowOverrides(r, listOf("MainB"), true, { "STALE_PRESET_NOT_LOADED" }, { "ACTIVE" })
        assertEquals("STALE_PRESET_NOT_LOADED", stale["MainB" to 10]!!.state)
        assertTrue(StyleVoiceEditorOps.rowOverrides(base, listOf("MainA"), true, { "ACTIVE" }, { "ACTIVE" }).isEmpty())
    }

    @Test fun savedProfileReloadsIdenticallyAfterRestart() {
        var r = StyleVoiceEditorOps.apply(base, target, EditScope.THIS_SECTION, Replacement.Sf2(violin))
        r = StyleVoiceEditorOps.apply(r, target.copy(channel = 12), EditScope.ALL_SECTIONS, Replacement.External(ExternalVoice(0, 112, 48, "Strings")))
        r = r.copy(savedAt = 1234)
        val dir = Files.createTempDirectory("ops")
        StyleOverrideStore(dir).write(r)
        val back = StyleOverrideStore(dir).read(sha).record!!
        assertEquals(r, back)
        assertEquals(StyleOverrideCodec.encode(r), StyleOverrideCodec.encode(back))
        assertTrue(StyleVoiceEditorOps.diff(r, back).isEmpty())
    }

    @Test fun auditionNotesAreFixedAndRoleBased() {
        assertEquals(listOf(36, 43, 48, 43), StyleVoiceEditorOps.auditionNotes(10))
        assertEquals(listOf(60, 64, 67, 72), StyleVoiceEditorOps.auditionNotes(13))
    }
}

package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.registration.RegistrationCodec
import com.yourapp.yamahaarranger.style.ExternalVoice
import com.yourapp.yamahaarranger.style.OriginalRef
import com.yourapp.yamahaarranger.style.OverrideReadStatus
import com.yourapp.yamahaarranger.style.OverrideScope
import com.yourapp.yamahaarranger.style.StyleExternalOverride
import com.yourapp.yamahaarranger.style.StyleOverrideCodec
import com.yourapp.yamahaarranger.style.StyleOverridePins
import com.yourapp.yamahaarranger.style.StyleOverrideRecord
import com.yourapp.yamahaarranger.style.StyleOverrideResolver
import com.yourapp.yamahaarranger.style.StyleOverrideStore
import com.yourapp.yamahaarranger.style.StyleVoiceOverride
import com.yourapp.yamahaarranger.style.StyleVoicePin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.CRC32

class StyleVoiceOverrideProfileTest {
    private val sha = StyleOverrideStore.sha256("style".toByteArray())
    private val pinA = StyleVoicePin("Colombo.sf2", 0, 40, "Violin", false)
    private val pinB = StyleVoicePin("Tyros.sf2", 0, 33, "Finger Bass", false)
    private val orig = OriginalRef(0, 112, 33)
    private val ext = ExternalVoice(0, 112, 48, "E343 Strings")
    private fun base() = StyleOverrideRecord(sha, "ROSITA.STY", emptyList())

    @Test fun priorityIsSectionThenPartWideThenStyle() {
        val r = base()
            .withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinB))
            .withSf2(StyleVoiceOverride(10, OverrideScope.SECTION, "MainA", pinA))
        assertEquals(pinA, StyleOverrideResolver.sf2(r, "MainA", 10)!!.pin)       // section-specific wins
        assertEquals(pinB, StyleOverrideResolver.sf2(r, "MainB", 10)!!.pin)       // part-wide elsewhere
        assertNull(StyleOverrideResolver.sf2(r, "MainA", 11))                      // other part: original Style
        assertNull(StyleOverrideResolver.sf2(null, "MainA", 10))
    }

    @Test fun sf2AndExternalAreIndependentTargets() {
        val r = base().withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA))
            .withExternal(StyleExternalOverride(10, OverrideScope.SECTION, "MainA", ext, orig))
        assertEquals(pinA, StyleOverrideResolver.sf2(r, "MainA", 10)!!.pin)
        assertEquals(ext, StyleOverrideResolver.external(r, "MainA", 10)!!.voice)
        assertNull(StyleOverrideResolver.external(r, "MainB", 10))
    }

    @Test fun replacingAtTheSameLevelKeepsOneEntry() {
        val r = base().withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA)).withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinB))
        assertEquals(1, r.overrides.size); assertEquals(pinB, r.overrides[0].pin)
        val s = r.withSf2(StyleVoiceOverride(10, OverrideScope.SECTION, "MainA", pinA)).withSf2(StyleVoiceOverride(10, OverrideScope.SECTION, "MainB", pinA))
        assertEquals(3, s.overrides.size)
    }

    @Test fun resetPartSectionAllRemoveExactlyTheirLevel() {
        val r = base()
            .withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA))
            .withSf2(StyleVoiceOverride(10, OverrideScope.SECTION, "MainA", pinB))
            .withSf2(StyleVoiceOverride(11, OverrideScope.SECTION, "MainA", pinB))
            .withExternal(StyleExternalOverride(11, OverrideScope.ALL, null, ext))
        assertEquals(2, r.withoutSection("MainA").let { it.overrides.size + it.externalOverrides.size })
        assertTrue(r.withoutSection("MainA").overrides.single().scope == OverrideScope.ALL)
        val part = r.withoutPart(10)
        assertTrue(part.overrides.none { it.channel == 10 }); assertEquals(1, part.overrides.size); assertEquals(1, part.externalOverrides.size)
        assertEquals(2, r.without(10, OverrideScope.SECTION, "MainA").let { it.overrides.size + it.externalOverrides.size } - 1)
        assertTrue(r.cleared().isEmpty)
        assertTrue(r.cleared().revision > r.revision)
    }

    @Test fun schema2RoundTripKeepsEveryField() {
        val r = base().copy(savedAt = 99)
            .withSf2(StyleVoiceOverride(10, OverrideScope.SECTION, "MainA", pinA, pdta = "ab".repeat(32), original = orig))
            .withSf2(StyleVoiceOverride(12, OverrideScope.ALL, null, pinB))
            .withExternal(StyleExternalOverride(11, OverrideScope.ALL, null, ext, OriginalRef(null, null, 4)))
        val back = StyleOverrideCodec.decode(StyleOverrideCodec.encode(r))
        assertEquals(r, back)
        assertTrue(StyleOverrideCodec.encode(r).contains("\"schema\":2"))
        assertEquals("ab".repeat(32), back.overrides[0].pdta); assertEquals(orig, back.overrides[0].original)
        assertNull(back.overrides[1].original)
        assertEquals(OriginalRef(null, null, 4), back.externalOverrides[0].original)
    }

    @Test fun schema1FilesAreReadAndUpgradedOnNextWrite() {
        val json = RegistrationCodec.Json
        val payload = mapOf("styleFileName" to "OLD.STY", "overrides" to listOf(mapOf(
            "channel" to 10, "scope" to "ALL", "section" to null, "sf2FileName" to "Colombo.sf2", "bank" to 0, "program" to 33,
            "presetName" to "Finger Bass", "drum" to false)))
        val crc = "%08x".format(CRC32().apply { update(json.write(payload).toByteArray(Charsets.UTF_8)) }.value)
        val v1 = json.write(mapOf("schema" to 1L, "kind" to "styleVoiceOverrides", "styleSha256" to sha, "savedAt" to 5L,
            "payload" to payload, "crc32" to crc))
        val dir = Files.createTempDirectory("svo")
        Files.write(dir.resolve("style-$sha.json"), v1.toByteArray())
        val store = StyleOverrideStore(dir)
        val read = store.read(sha)
        assertEquals(OverrideReadStatus.VALID, read.status)
        val rec = read.record!!
        assertEquals("", rec.overrides[0].pdta); assertNull(rec.overrides[0].original); assertTrue(rec.externalOverrides.isEmpty())
        store.write(rec.withExternal(StyleExternalOverride(11, OverrideScope.ALL, null, ext)))
        assertTrue(String(Files.readAllBytes(dir.resolve("style-$sha.json"))).contains("\"schema\":2"))
        assertEquals(1, store.read(sha).record!!.externalOverrides.size)
    }

    @Test fun profileSurvivesAppRestartAndIsKeyedByContentNotName() {
        val dir = Files.createTempDirectory("svo")
        val a = StyleOverrideStore(dir)
        val rec = base().withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA))
        a.write(rec)
        val reopened = StyleOverrideStore(dir).read(sha)           // new store instance = new process
        assertEquals(rec, reopened.record)
        // Same file name, different content => different hash => no profile.
        val other = StyleOverrideStore.sha256("style with other bytes".toByteArray())
        assertEquals(OverrideReadStatus.MISSING, StyleOverrideStore(dir).read(other).status)
    }

    @Test fun damagedAndNewerFilesAreNeverOverwritten() {
        val dir = Files.createTempDirectory("svo")
        Files.write(dir.resolve("style-$sha.json"), "garbage".toByteArray())
        val r = StyleOverrideStore(dir).read(sha)
        assertEquals(OverrideReadStatus.DAMAGED, r.status); assertFalse(r.writable)
    }

    @Test fun invalidExternalNumbersAreRejected() {
        val bad = base().copy(externalOverrides = listOf(StyleExternalOverride(10, OverrideScope.ALL, null, ExternalVoice(0, 128, 0, "x"))))
        assertTrue(runCatching { StyleOverrideCodec.encode(bad) }.isFailure)
        val sectionless = base().copy(externalOverrides = listOf(StyleExternalOverride(10, OverrideScope.SECTION, null, ext)))
        assertTrue(runCatching { StyleOverrideCodec.encode(sectionless) }.isFailure)
    }

    @Test fun staleReasonsAreReportedNeverSubstituted() {
        val r = base()
            .withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA, pdta = "aa"))
            .withSf2(StyleVoiceOverride(11, OverrideScope.SECTION, "MainA", pinB, pdta = "bb"))
            .withSf2(StyleVoiceOverride(12, OverrideScope.ALL, null, StyleVoicePin("Gone.sf2", 0, 1, "X", false)))
        val loaded = mapOf(Triple("Colombo.sf2", 0, 40) to "Violin", Triple("Tyros.sf2", 0, 33) to "Finger Bass")
        val pdta = mapOf("Colombo.sf2" to "aa", "Tyros.sf2" to "CHANGED")
        val checks = StyleOverridePins.check(r, { pdta[it] }) { f, b, p -> loaded[Triple(f, b, p)] }
        assertEquals(listOf("VALID", "STALE_SOUNDFONT_CONTENT_CHANGED", "STALE_PRESET_NOT_LOADED"), checks.map { it.reason })
        assertEquals(OverrideScope.SECTION, checks[1].scope); assertEquals("MainA", checks[1].section)
        // Unknown pdta (legacy entry or fingerprint unavailable) is not treated as a change.
        val legacy = base().withSf2(StyleVoiceOverride(10, OverrideScope.ALL, null, pinA))
        assertEquals("VALID", StyleOverridePins.check(legacy, { "zz" }) { f, b, p -> loaded[Triple(f, b, p)] }.single().reason)
    }
}

package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.SoundFontRolePlanner
import com.yourapp.yamahaarranger.style.OverrideReadStatus
import com.yourapp.yamahaarranger.style.OverrideScope
import com.yourapp.yamahaarranger.style.StyleOverrideCodec
import com.yourapp.yamahaarranger.style.StyleOverrideRecord
import com.yourapp.yamahaarranger.style.StyleOverridePins
import com.yourapp.yamahaarranger.style.StyleOverrideStore
import com.yourapp.yamahaarranger.style.StyleVoiceOverride
import com.yourapp.yamahaarranger.style.StyleVoicePin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CpvOverrideStoreTest {
    private val shaA = StyleOverrideStore.sha256("style A".toByteArray())
    private val shaB = StyleOverrideStore.sha256("style B".toByteArray())
    private val bass = StyleVoicePin("ColomboGMGS2_BM.sf2", 0, 33, "Finger Bass", false)
    private val kit = StyleVoicePin("ColomboGMGS2_BM.sf2", 127, 8, "XG Room", true)

    @Test fun roundTripKeepsSectionEntriesForLater() {
        val record = StyleOverrideRecord(shaA, "ROSITA.STY", listOf(
            StyleVoiceOverride(10, OverrideScope.ALL, null, bass),
            StyleVoiceOverride(9, OverrideScope.SECTION, "Main A", kit)), savedAt = 42)
        assertEquals(record, StyleOverrideCodec.decode(StyleOverrideCodec.encode(record)))
        // Changing the whole-style pin leaves the per-section entry untouched.
        val next = record.withAllSectionPin(10, null).withAllSectionPin(9, bass)
        assertEquals(bass, next.allSectionPin(9))
        assertNull(next.allSectionPin(10))
        assertTrue(next.overrides.any { it.scope == OverrideScope.SECTION && it.section == "Main A" && it.pin == kit })
    }

    @Test fun storeIsPerStyleAtomicAndRecoversFromBackup() {
        val dir = Files.createTempDirectory("cpv-store")
        val store = StyleOverrideStore(dir)
        assertEquals(OverrideReadStatus.MISSING, store.read(shaA).status)
        val first = StyleOverrideRecord(shaA, "A.STY", listOf(StyleVoiceOverride(10, OverrideScope.ALL, null, bass)), 1)
        store.write(first)
        assertEquals(first, store.read(shaA).record)
        assertEquals(OverrideReadStatus.MISSING, store.read(shaB).status) // no leak to another style
        val second = first.withAllSectionPin(9, kit).copy(savedAt = 2)
        store.write(second)
        // Corrupt the primary: the previous version is recovered from .bak, not deleted.
        Files.write(dir.resolve("style-$shaA.json"), "{broken".toByteArray())
        val recovered = store.read(shaA)
        assertEquals(OverrideReadStatus.RECOVERED_BACKUP, recovered.status)
        assertEquals(first, recovered.record)
        // Writing again moves the unreadable primary to quarantine.
        store.write(second)
        assertEquals(second, store.read(shaA).record)
        assertTrue(Files.list(dir.resolve("quarantine")).use { it.count() } == 1L)
        store.delete(shaA)
        assertEquals(OverrideReadStatus.RECOVERED_BACKUP, store.read(shaA).status)
    }

    @Test fun newerSchemaAndDamageAreReadOnly() {
        val dir = Files.createTempDirectory("cpv-store")
        val store = StyleOverrideStore(dir)
        val text = StyleOverrideCodec.encode(StyleOverrideRecord(shaA, "A", emptyList())).replace("\"schema\":2", "\"schema\":3")
        Files.write(dir.resolve("style-$shaA.json"), text.toByteArray())
        val newer = store.read(shaA)
        assertEquals(OverrideReadStatus.NEWER_SCHEMA, newer.status); assertFalse(newer.writable)
        Files.write(dir.resolve("style-$shaB.json"), "not json".toByteArray())
        val damaged = store.read(shaB)
        assertEquals(OverrideReadStatus.DAMAGED, damaged.status); assertFalse(damaged.writable)
        val tampered = StyleOverrideCodec.encode(StyleOverrideRecord(shaA, "A", listOf(StyleVoiceOverride(10, OverrideScope.ALL, null, bass))))
            .replace("Finger Bass", "Other Bass")
        assertTrue(runCatching { StyleOverrideCodec.decode(tampered) }.isFailure) // CRC mismatch
    }

    @Test fun stalePinsAreReportedNotSubstituted() {
        val record = StyleOverrideRecord(shaA, "A", listOf(
            StyleVoiceOverride(10, OverrideScope.ALL, null, bass),
            StyleVoiceOverride(11, OverrideScope.ALL, null, StyleVoicePin("PRIME.sf2", 0, 4, "EP", false)),
            StyleVoiceOverride(12, OverrideScope.ALL, null, StyleVoicePin("ColomboGMGS2_BM.sf2", 0, 0, "Old Name", false)),
            StyleVoiceOverride(13, OverrideScope.SECTION, "Main A", bass)))
        val loaded = mapOf(Triple("ColomboGMGS2_BM.sf2", 0, 33) to "Finger Bass", Triple("ColomboGMGS2_BM.sf2", 0, 0) to "Grand Piano")
        val checks = StyleOverridePins.check(record) { f, b, p -> loaded[Triple(f, b, p)] }
        assertEquals(listOf(10, 11, 12, 13), checks.map { it.channel }) // SECTION entries are checked too
        assertEquals(listOf("VALID", "STALE_PRESET_NOT_LOADED", "STALE_PRESET_NAME_CHANGED", "VALID"), checks.map { it.reason })
    }

    @Test fun plannerUsesColomboThenTyrosAndNeverPrime() {
        val files = listOf("MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", "DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2",
            "yamaha tyros 4_just_t4_fixed.sf2", "ColomboGMGS2_BM.sf2", "merlin_GMpro.sf2")
        val plan = SoundFontRolePlanner.plan(files) { it }
        assertEquals("ColomboGMGS2_BM.sf2", plan.primary)
        assertEquals("yamaha tyros 4_just_t4_fixed.sf2", plan.fallback)
        assertEquals(3, plan.excluded.size)
        val onlyPrime = SoundFontRolePlanner.plan(files.take(2)) { it }
        assertNull(onlyPrime.primary); assertFalse(onlyPrime.ready)
        assertEquals(SoundFontRolePlanner.Kind.PRIME_DRUM, SoundFontRolePlanner.kind(files[1]))
        assertEquals(SoundFontRolePlanner.Kind.PRIME_MELODY, SoundFontRolePlanner.kind(files[0]))
    }
}

package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.CatalogPreset
import com.yourapp.yamahaarranger.style.ObservedBinding
import com.yourapp.yamahaarranger.style.OverrideScope
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.Provenance
import com.yourapp.yamahaarranger.style.RowOverride
import com.yourapp.yamahaarranger.style.StyleDialect
import com.yourapp.yamahaarranger.style.StyleDialectEvidence
import com.yourapp.yamahaarranger.style.StyleDialectIdentity
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder
import com.yourapp.yamahaarranger.style.VoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleVoiceInventoryTest {
    private fun cc(tick: Int, ch: Int, n: Int, v: Int) = StyleNoteEvent(tick, false, n, v, ch, 0xB0 or ch)
    private fun pc(tick: Int, ch: Int, p: Int) = StyleNoteEvent(tick, false, p, 0, ch, 0xC0 or ch)
    private fun on(tick: Int, ch: Int) = StyleNoteEvent(tick, true, 60, 100, ch)
    private fun casm(src: Int, dst: Int, name: String) = CasmPolicyModel(src, dst, name, 0, 0, 0, 0, 0, 0, 127, 0, false)
    private fun part(name: String, src: Int, dst: Int, voice: String, events: List<StyleNoteEvent>): StylePartModel {
        val policy = casm(src, dst, voice)
        return StylePartModel(name, events, policy, listOf(policy),
            events.lastOrNull { it.tick == 0 && it.isProgramChange }?.note ?: -1,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 0 }?.velocity ?: 0,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 32 }?.velocity ?: 0)
    }
    private fun bass(program: Int, lsb: Int = 112) = part("Bass", 10, 10, "Fingered Bass",
        listOf(cc(0, 10, 0, 0), cc(0, 10, 32, lsb), pc(0, 10, program), on(0, 10)))

    private fun style(dialect: StyleDialectIdentity = StyleDialectIdentity(StyleDialect.SFF1, StyleDialectEvidence.SMF_TICK0_MARKER_SFF1)): ParsedStyle {
        val rhy = part("Rhy1", 8, 8, "Rhythm1", listOf(cc(0, 8, 0, 127), cc(0, 8, 32, 0), pc(0, 8, 0), on(0, 8)))
        val mainA = StyleSectionModel("MainA", 1920, listOf(rhy, bass(33),
            // Mid-section Program Change on the bass.
            part("Chord1", 11, 11, "Piano", listOf(cc(0, 11, 0, 0), cc(0, 11, 32, 112), pc(0, 11, 4), on(0, 11), pc(960, 11, 5))),
            // No tick-0 setup.
            part("Pad", 13, 13, "Pad", listOf(on(0, 13)))))
        val mainB = StyleSectionModel("MainB", 1920, listOf(rhy, bass(34)))
        val fillAA = StyleSectionModel("FillAA", 480, listOf(rhy, bass(33)))
        val intro = StyleSectionModel("IntroA", 1920, listOf(bass(33)))
        // Insertion order is deliberately scrambled.
        return ParsedStyle("T.STY", 480, linkedMapOf("FillAA" to fillAA, "MainB" to mainB, "IntroA" to intro, "MainA" to mainA), dialectIdentity = dialect)
    }

    private fun bound(ch: Int, bank: Int, pc: Int, reason: String, source: String = "PRIMARY", status: String = "BOUND") =
        ObservedBinding(ch, status, source, "Colombo.sf2", "abc", bank, pc, "Preset $bank:$pc", reason)

    private fun build(observed: Map<Int, ObservedBinding> = emptyMap(), catalog: List<CatalogPreset> = emptyList(),
                      active: String? = null, overrides: Map<Pair<String, Int>, RowOverride> = emptyMap(), s: ParsedStyle = style()) =
        StyleVoiceInventoryBuilder.build(s, catalog, observed, active, overrides)

    @Test fun sectionsAreOrderedAndLabelledFromTheStyle() {
        val inv = build()
        assertEquals(listOf("IntroA", "MainA", "MainB", "FillAA"), inv.sections)
        assertEquals(listOf("INTRO 1", "MAIN A", "MAIN B", "FILL A"), inv.rows.map { it.label })
        assertEquals("SFF1", inv.dialectText)
        assertEquals("SFF format not identified", build(s = style(StyleDialectIdentity())).dialectText)
    }

    @Test fun rawAndOneBasedRequestsAreBothShownAndNothingIsInvented() {
        val b = build().forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals("0:112:33", b.rawRequest); assertEquals("MSB 0 LSB 112 PC 34", b.displayRequest)
        assertEquals("Bass", b.partRole); assertEquals(Provenance.AUTHORED, b.requestProvenance)
        assertEquals("Fingered Bass", b.casmVoiceName)
        val pad = build().forSection("MainA").first { it.destinationChannel == 13 }
        assertEquals("-:-:-", pad.rawRequest); assertEquals("—", pad.displayRequest)
        assertEquals(VoiceStatus.UNKNOWN, pad.status); assertNotNull(pad.readOnlyReason)
        assertEquals("Pad", pad.partRole)
    }

    @Test fun differentProgramPerSectionAndMidSectionChangeAreKept() {
        val inv = build()
        assertEquals(33, inv.forSection("MainA").first { it.destinationChannel == 10 }.program)
        assertEquals(34, inv.forSection("MainB").first { it.destinationChannel == 10 }.program)
        val chord = inv.forSection("MainA").first { it.destinationChannel == 11 }
        assertEquals(4, chord.program); assertEquals(1, chord.dynamicChanges.size); assertEquals(960, chord.dynamicChanges[0].tick)
    }

    @Test fun nothingLoadedAndNothingObservedIsUnknownWithReason() {
        val r = build().forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.UNKNOWN, r.status); assertTrue(r.statusReason.contains("No SoundFont presets are loaded"))
        assertNull(r.resolutionProvenance)
    }

    @Test fun observedNumberMatchIsExactFromRuntime() {
        val inv = build(mapOf(10 to bound(10, 112, 33, "same_family")), active = "MainA")
        val r = inv.forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.EXACT, r.status); assertEquals(Provenance.RUNTIME_OBSERVED, r.resolutionProvenance)
        assertTrue(inv.runtimeObserved); assertEquals("Colombo.sf2", r.actualSoundFont)
    }

    @Test fun similarButDifferentPresetIsFallbackNeverExact() {
        val r = build(mapOf(10 to bound(10, 0, 33, "same_family")), active = "MainA").forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.FALLBACK, r.status); assertTrue(r.statusReason.contains("same_family"))
    }

    @Test fun unresolvedBindingIsMissingAndUnverifiedIsUnknown() {
        val miss = build(mapOf(10 to bound(10, -1, -1, "no_compatible_candidate", status = "UNRESOLVED")), active = "MainA")
        assertEquals(VoiceStatus.MISSING, miss.forSection("MainA").first { it.destinationChannel == 10 }.status)
        val unv = build(mapOf(10 to bound(10, 112, 33, "x", status = "UNVERIFIED")), active = "MainA")
        assertEquals(VoiceStatus.UNKNOWN, unv.forSection("MainA").first { it.destinationChannel == 10 }.status)
    }

    @Test fun runtimeBindingOnlyAppliesToTheActiveSection() {
        val inv = build(mapOf(10 to bound(10, 0, 33, "same_family")), active = "MainA")
        // Fill A has the same request (0:112:33, same CASM voice): inherited, labelled as not measured there.
        val fill = inv.forSection("FillAA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.FALLBACK, fill.status); assertEquals(Provenance.RESOLVED, fill.resolutionProvenance)
        assertTrue(fill.statusReason.contains("not measured for this section"))
        // Main B asks for Program 34: nothing observed for it.
        val b = inv.forSection("MainB").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.UNKNOWN, b.status)
    }

    @Test fun catalogNumberMatchSingleAndAmbiguous() {
        val one = listOf(CatalogPreset("Tyros.sf2", "p1", 112 , 33, "Fingered", false))
        val r = build(catalog = one).forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.EXACT, r.status); assertEquals(Provenance.RESOLVED, r.resolutionProvenance)
        assertTrue(r.statusReason.contains("not by name"))
        val two = one + CatalogPreset("Other.sf2", "p2", 112, 33, "Fingered", false)
        assertEquals(VoiceStatus.AMBIGUOUS, build(catalog = two).forSection("MainA").first { it.destinationChannel == 10 }.status)
        // Name alone never matches.
        val similarName = listOf(CatalogPreset("A.sf2", "p", 0, 0, "Fingered Bass", false))
        assertEquals(VoiceStatus.UNKNOWN, build(catalog = similarName).forSection("MainA").first { it.destinationChannel == 10 }.status)
    }

    @Test fun drumsAreReadOnlyAndClassifiedByDrumEvidence() {
        val rows = build(mapOf(8 to bound(8, 128, 0, "r23_exact_fingerprint_namespace_coverage", source = "DRUM_R23")), active = "MainA")
        val d = rows.forSection("MainA").first { it.destinationChannel == 8 }
        assertTrue(d.drum); assertNotNull(d.readOnlyReason); assertEquals(VoiceStatus.EXACT, d.status)
        assertEquals("Rhythm 1", d.partRole)
        val legacy = build(mapOf(8 to bound(8, 128, 0, "legacy_first_cached_kit_fallback", source = "DRUM_LEGACY")), active = "MainA")
        assertEquals(VoiceStatus.FALLBACK, legacy.forSection("MainA").first { it.destinationChannel == 8 }.status)
        val amb = build(mapOf(8 to bound(8, 128, 0, "legacy_same_pc_first_matching_cache_entry", source = "DRUM_LEGACY")), active = "MainA")
        assertEquals(VoiceStatus.AMBIGUOUS, amb.forSection("MainA").first { it.destinationChannel == 8 }.status)
    }

    @Test fun overrideIsOverriddenButKeepsTheOriginalRequestReason() {
        val ov = RowOverride("SF2", OverrideScope.SECTION, "Colombo.sf2 0:40 'Violin'", "ACTIVE")
        val inv = build(overrides = mapOf(("MainA" to 10) to ov))
        val r = inv.forSection("MainA").first { it.destinationChannel == 10 }
        assertEquals(VoiceStatus.OVERRIDDEN, r.status); assertTrue(r.statusReason.contains("Original request"))
        assertEquals("0:112:33", r.rawRequest)
        assertFalse(inv.forSection("MainB").first { it.destinationChannel == 10 }.status == VoiceStatus.OVERRIDDEN)
        val all = build(overrides = mapOf((StyleVoiceInventoryBuilder.ALL_SECTIONS to 10) to ov))
        assertTrue(all.flat.filter { it.destinationChannel == 10 }.all { it.status == VoiceStatus.OVERRIDDEN })
    }

    @Test fun partCountsAndSharedChannelOwnership() {
        val inv = build()
        assertEquals(4, inv.partCount)
        val shared = StyleSectionModel("MainA", 1920, listOf(
            part("NoPc", 12, 12, "Gtr", listOf(on(0, 12))),
            part("WithPc", 13, 12, "Gtr2", listOf(pc(0, 13, 25), on(0, 13)))))
        val rows = build(s = ParsedStyle("X", 480, mapOf("MainA" to shared))).forSection("MainA")
        assertNotNull(rows.first { it.partName == "NoPc" }.readOnlyReason)
        assertNull(rows.first { it.partName == "WithPc" }.readOnlyReason)
    }
}

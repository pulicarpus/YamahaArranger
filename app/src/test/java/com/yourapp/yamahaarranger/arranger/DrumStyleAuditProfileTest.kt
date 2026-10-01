package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.*
import org.junit.Assert.*
import org.junit.Test

class DrumStyleAuditProfileTest {
    private fun style(parts: List<StylePartModel>, section: String = "MainD") =
        ParsedStyle("Love Song", 1920, mapOf(section to StyleSectionModel(section, 30720, parts)))
    private fun on(key: Int, velocity: Int, channel: Int = 9) = StyleNoteEvent(1920, true, key, velocity, channel)
    @Test fun countsAllActualVelocitiesOnceAndExcludesNonNotesAndMelody() {
        val part = StylePartModel("Rhythm2", listOf(on(31,110), on(31,110), on(21,28), on(21,42),
            on(31,0), StyleNoteEvent(2400,false,31,0,9), StyleNoteEvent(564,false,11,126,9,0xB9)), program=73)
        val profile = DrumStyleAuditProfile.from(style(listOf(part, StylePartModel("Piano", listOf(on(60,100,11))))))
        assertArrayEquals(intArrayOf(9,21,28,1,9,21,42,1,9,31,110,2), profile.histogram)
        assertTrue(profile.header.contains("ch=9 hits=4 uniqueKeys=2"))
        assertTrue(profile.header.contains("PC=73"))
    }
    @Test fun readsCasmDestinationWithoutModifyingPolicyOrStyle() {
        val policy = CasmPolicyModel(3,8,"Drums",0,0,0,0,127,0,127,0,false)
        val part = StylePartModel("Rhythm1", listOf(on(82,28,3)), casm=policy)
        val profile = DrumStyleAuditProfile.from(style(listOf(part)))
        assertArrayEquals(intArrayOf(8,82,28,1), profile.histogram)
        assertEquals(3, part.casm!!.sourceChannel)
        assertEquals(82, part.events.single().note)
    }
    @Test fun missingMainDIsExplicitRatherThanAuditingAnotherSection() {
        val profile = DrumStyleAuditProfile.from(style(listOf(StylePartModel("Rhythm2",listOf(on(31,110)))),"MainA"))
        assertEquals(0, profile.histogram.size)
        assertTrue(profile.header.contains("MainD absent"))
    }
    @Test fun duplicateAlternativeRhythmPoliciesDoNotDoubleCountNotes() {
        val p = CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false)
        val part = StylePartModel("Rhythm2",listOf(on(31,110)),casmPolicies=listOf(p,p.copy(chordMuteMask=0)))
        assertArrayEquals(intArrayOf(9,31,110,1),DrumStyleAuditProfile.from(style(listOf(part))).histogram)
    }
    @Test fun ambiguousRhythmRoutesAreNotInvented() {
        val p = CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false)
        val part = StylePartModel("Rhythm2",listOf(on(31,110)),casmPolicies=listOf(p,p.copy(destinationChannel=8)))
        assertTrue(DrumStyleAuditProfile.from(style(listOf(part))).header.contains("ambiguous"))
    }
    @Test fun explicitSectionExportsActualFillVelocityWithoutAssumingMainD() {
        val part = StylePartModel("Rhythm2", listOf(on(31,110),on(21,28)), program=73)
        val parsed = style(listOf(part),"FillDD")
        assertArrayEquals(intArrayOf(9,21,28,1,9,31,110,1),DrumStyleAuditProfile.from(parsed,"FillDD").histogram)
        assertTrue(DrumStyleAuditProfile.from(parsed,"FillDD").header.contains("section='FillDD'"))
    }

    @Test fun compactProfilesAllSectionsExactlyOnceWithStableIds() {
        val rhythm = StylePartModel("Rhythm2",listOf(on(31,110),on(31,110)),program=73,bankMsb=127,bankLsb=2)
        val fill = StylePartModel("Rhythm2",listOf(on(21,28),on(21,42)),program=24)
        val parsed = ParsedStyle("Synthetic.prs",480, linkedMapOf(
            "MainD" to StyleSectionModel("MainD",1920,listOf(rhythm)),
            "FillDD" to StyleSectionModel("FillDD",480,listOf(fill))))
        val result = DrumCompatibilityProfile.from(parsed)
        assertTrue(result.complete)
        assertArrayEquals(intArrayOf(0,9,21,28,1,0,9,21,42,1,1,9,31,110,2),result.histogram)
        assertTrue(result.header.contains("SECTION id=0 name='FillDD'"))
        assertTrue(result.header.contains("headerBank=127:2 headerPC=73"))
        assertEquals(31,rhythm.events.first().note)
        assertEquals(127,rhythm.bankMsb)
    }
    @Test fun compactAmbiguousSectionInvalidatesUnionRatherThanInventingMissingZones() {
        val p=CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false)
        val bad=StylePartModel("Rhythm2",listOf(on(31,110)),casmPolicies=listOf(p,p.copy(destinationChannel=8)))
        val good=StylePartModel("Rhythm2",listOf(on(7,17)))
        val parsed=ParsedStyle("Synthetic",480,mapOf("MainA" to StyleSectionModel("MainA",1,listOf(good)),
            "MainD" to StyleSectionModel("MainD",1,listOf(bad))))
        val result=DrumCompatibilityProfile.from(parsed)
        assertFalse(result.complete)
        assertEquals(0,result.histogram.size)
        assertTrue(result.header.contains("UNKNOWN section="))
        assertTrue(result.header.contains("demandComplete=false"))
    }
    @Test fun compactPreservesSourcesAndMarksDynamicIdentityUnknown() {
        val p=CasmPolicyModel(3,8,"Drums",0,0,0,0,127,0,127,0,false)
        val part=StylePartModel("Rhythm1",listOf(on(7,17,3),
            StyleNoteEvent(200,false,24,0,3,0xC3)),casm=p,program=-1,bankMsb=126,bankLsb=5)
        val result=DrumCompatibilityProfile.from(style(listOf(part)))
        assertArrayEquals(intArrayOf(0,8,7,17,1),result.histogram)
        assertTrue(result.header.contains("src=3 dst=8 headerBank=126:5 headerPC=-1 PCknown=false dynamicBankPC=true identity=UNKNOWN"))
        assertEquals(3,p.sourceChannel)
    }
    @Test fun compactUnicodeHeaderIsBoundedAndOmissionsAreExplicit() {
        val part=StylePartModel("鼓".repeat(200),listOf(on(7,17)),program=1)
        val parsed=ParsedStyle("音".repeat(10000),480,(0..250).associate {
            "Section $it" to StyleSectionModel("Section $it",1,listOf(part)) })
        val result=DrumCompatibilityProfile.from(parsed)
        assertTrue(result.complete)
        assertEquals(251*5,result.histogram.size)
        assertTrue(result.header.toByteArray(Charsets.UTF_8).size <= 8*1024)
        assertFalse(result.header.contains("exportOmittedRows=0 "))
        assertTrue(result.header.contains("routingUnknown is separate."))
    }
    @Test fun compactHonorsDuplicatePolicyAndZeroVelocityExclusionWithoutMaskEvaluation() {
        val p=CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false,chordMuteMask=0)
        val part=StylePartModel("Rhythm2",listOf(on(31,110),on(21,0),StyleNoteEvent(300,false,31,0,9)),
            casmPolicies=listOf(p,p.copy(chordMuteMask=-1)))
        val result=DrumCompatibilityProfile.from(style(listOf(part)))
        assertTrue(result.complete)
        assertArrayEquals(intArrayOf(0,9,31,110,1),result.histogram)
        assertTrue(result.header.contains("Masks/mutes/overrides and actual sent/sample voices are UNKNOWN"))
        assertEquals(0L,p.chordMuteMask)
    }

    @Test fun comparisonSelectionIsExplicitGenericAndCanonical() {
        assertArrayEquals(intArrayOf(127,5,128,3,128,99),DrumCompatibilityProfile.comparisonKits("128:99, 127:5, 128:3,128:3"))
        assertArrayEquals(intArrayOf(),DrumCompatibilityProfile.comparisonKits("  "))
    }
    @Test fun comparisonRejectsMalformedAndExcessiveInputs() {
        for (value in listOf("1", "128:1,", "-1:3", "128:128", "65536:1", "x:1", "0:1,0:2,0:3,0:4,0:5"))
            assertNull(value,DrumCompatibilityProfile.comparisonKits(value))
    }
    @Test fun comparisonInputCannotChangeSourceHistogramOrVoiceSetup() {
        val part=StylePartModel("Rhythm2",listOf(on(7,17)),program=73,bankMsb=127)
        val parsed=style(listOf(part));val before=DrumCompatibilityProfile.from(parsed)
        DrumCompatibilityProfile.comparisonKits("128:0,128:1,128:24")
        assertArrayEquals(before.histogram,DrumCompatibilityProfile.from(parsed).histogram)
        assertEquals(73,part.program);assertEquals(127,part.bankMsb)
    }

}


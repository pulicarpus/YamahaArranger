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

}


package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.E343Compat
import com.yourapp.yamahaarranger.style.E343Compat.Status
import com.yourapp.yamahaarranger.style.ExternalVoice
import org.junit.Assert.*
import org.junit.Test

/** Pure classification logic with a SYNTHETIC list (not Yamaha data): proves the status rules and that nothing is guessed. */
class E343CompatTest {
    private val cat = listOf(ExternalVoice(0, 0, 0, "Grand Piano"), ExternalVoice(0, 112, 29, "Overdriven"), ExternalVoice(127, 0, 0, "Standard Kit"))

    @Test fun noCatalogIsUnknownNeverGuessed() {
        val r = E343Compat.classify(0, 0, 0, "Grand Piano", emptyList())
        assertEquals(Status.UNKNOWN, r.status); assertNull(r.candidate)
    }
    @Test fun incompleteRequestIsUnknown() {
        assertEquals(Status.UNKNOWN, E343Compat.classify(null, 0, 0, "x", cat).status)
        assertEquals(Status.UNKNOWN, E343Compat.classify(0, null, 0, "x", cat).status)
        assertEquals(Status.UNKNOWN, E343Compat.classify(0, 0, null, "x", cat).status)
    }
    @Test fun exactMatchUsesZeroBasedProgram() {
        assertEquals(Status.EXACT, E343Compat.classify(0, 112, 29, "", cat).status)
        assertNotEquals(Status.EXACT, E343Compat.classify(0, 112, 30, "", cat).status)
    }
    @Test fun sameNameOtherNumbersIsCompatible() {
        val r = E343Compat.classify(0, 115, 30, "overdriven", cat)
        assertEquals(Status.COMPATIBLE, r.status); assertEquals(29, r.candidate!!.program)
    }
    @Test fun sameMsbAndProgramOtherLsbIsFallbackCandidateOnly() {
        val r = E343Compat.classify(0, 114, 29, "Other", cat)
        assertEquals(Status.FALLBACK_CANDIDATE, r.status); assertEquals(112, r.candidate!!.lsb)
    }
    @Test fun drumKitMissingIsUnsupportedWhenListSupplied() {
        assertEquals(Status.UNSUPPORTED, E343Compat.classify(127, 0, 86, "Kit", cat).status)
        assertEquals(Status.EXACT, E343Compat.classify(127, 0, 0, "", cat).status)
    }
    @Test fun describeNamesTheStatus() {
        assertTrue(E343Compat.describe(E343Compat.classify(0, 0, 0, "", emptyList())).startsWith("UNKNOWN"))
    }
}

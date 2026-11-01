package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.E343Compat
import com.yourapp.yamahaarranger.style.E343Compat.Status
import com.yourapp.yamahaarranger.style.StyleVoiceEditorOps
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The PSR-E343 Voice List extracted from the official Yamaha Owner's Manual (docs/yamaha/E343_VOICES.tsv) read through the PRODUCTION parser
 * (StyleVoiceEditorOps.parseDeviceCatalog, the same function the app uses for /storage/emulated/0/YamahaArranger/E343_VOICES.tsv) and classified by the
 * PRODUCTION E343Compat. Requests are the Bank MSB / LSB / Program (0-based) that the router writes for HardRock2.S578 and ROSITA.STY
 * (proved on the wire by tests/rtdiag/E343MidiOutAuditTest). Read-only evidence: nothing is mapped or applied.
 */
class E343CatalogTest {
    private val catalog by lazy { StyleVoiceEditorOps.parseDeviceCatalog(File("docs/yamaha/E343_VOICES.tsv").readText()) }
    private fun st(msb: Int, lsb: Int, pc0: Int, name: String = "") = E343Compat.classify(msb, lsb, pc0, name, catalog)

    @Test fun catalogHasEveryMidiSelectableVoiceAndKit() {
        // 550 list numbers minus the 27 DUAL voices (153-179) that have no Bank/Program in the manual
        assertEquals(523, catalog.size)
        assertEquals(523, catalog.map { Triple(it.msb, it.lsb, it.program) }.toSet().size)
        assertEquals("Grand Piano", catalog.first { it.msb == 0 && it.lsb == 112 && it.program == 0 }.label)
        assertEquals("Standard Kit 1", catalog.first { it.msb == 127 && it.lsb == 0 && it.program == 0 }.label)
        assertEquals(10, catalog.count { it.msb == 127 }); assertEquals(8, catalog.count { it.msb == 126 })
    }

    @Test fun hardRock2SentRequests() {
        assertEquals(Status.EXACT, st(127, 0, 0).status)                 // Rhythm1 'perc' -> Standard Kit 1
        assertEquals(Status.UNSUPPORTED, st(127, 0, 86).status)          // Rhythm2 'drums' kit 87: no such E343 kit
        assertEquals(Status.FALLBACK_CANDIDATE, st(0, 114, 33, "Bass").status)
        assertEquals(Status.FALLBACK_CANDIDATE, st(0, 115, 30, "DistGt").status)
        assertEquals(Status.FALLBACK_CANDIDATE, st(0, 115, 18, "Organ").status)
        assertEquals(Status.FALLBACK_CANDIDATE, st(0, 125, 27, "RGuit").status)
    }

    @Test fun rositaSentRequestsAreAllExact() {
        for ((m, l, p) in listOf(Triple(127, 0, 0), Triple(127, 0, 16), Triple(127, 0, 8), Triple(0, 112, 33), Triple(0, 112, 27), Triple(0, 112, 3),
                                 Triple(0, 112, 48), Triple(0, 112, 52), Triple(0, 112, 54)))
            assertEquals("$m/$l/PC$p", Status.EXACT, st(m, l, p).status)
    }
}

package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder
import com.yourapp.yamahaarranger.style.StyleVoiceRequests
import com.yourapp.yamahaarranger.style.VoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real-fixture inventory: ROSITA.STY parsed by the unmodified production C++ parser (tools/dump_style_shadow_fixture.cpp),
 * then rebuilt into ParsedStyle exactly as StyleRepository does. Skipped when ROSITA_TSV is not set (tools/test_cpv.py sets it).
 */
class StyleVoiceRositaTest {
    private fun load(): ParsedStyle? {
        val path = System.getenv("ROSITA_TSV") ?: return null
        val sections = linkedMapOf<String, StyleSectionModel>()
        var ppq = 480
        var name = ""; var len = 0
        var parts = ArrayList<StylePartModel>()
        var events = ArrayList<StyleNoteEvent>(); var policies = ArrayList<CasmPolicyModel>(); var partName: String? = null
        fun closePart() {
            val n = partName ?: return
            val tick0 = events.filter { it.tick == 0 }
            var msb = 0; var lsb = 0; var program = -1
            tick0.forEach { e -> when {
                e.isControlChange && e.note == 0 -> msb = e.velocity
                e.isControlChange && e.note == 32 -> lsb = e.velocity
                e.isProgramChange -> program = e.note } }
            parts += StylePartModel(n, events, policies.firstOrNull(), policies, program, msb, lsb)
            events = ArrayList(); policies = ArrayList(); partName = null
        }
        fun closeSection() { closePart(); if (name.isNotEmpty()) sections[name] = StyleSectionModel(name, len, parts); parts = ArrayList() }
        File(path).readLines(Charsets.ISO_8859_1).forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "PPQ" -> ppq = f[1].toInt()
                "S" -> { closeSection(); name = f[1]; len = f[2].toInt() }
                "P" -> { closePart(); partName = f.getOrElse(1) { "" }.trim() }
                "C" -> policies += CasmPolicyModel(f[1].toInt(), f[2].toInt(), f[3].trim(), f[4].toInt(), f[5].toInt(), f[6].toInt(),
                    f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12] == "1", f[13].toLong(), f[14].toInt(), f[15].toInt())
                "E" -> { val status = f[2].toInt()
                    if (status in 0x80..0xEF) events += StyleNoteEvent(f[1].toInt(), (status and 0xF0) == 0x90 && f[4].toInt() > 0, f[3].toInt(), f[4].toInt(), status and 0x0F, status) }
            }
        }
        closeSection()
        return ParsedStyle("ROSITA.STY", ppq, sections)
    }

    @Test fun realStyleInventoryIsConsistentWithItsOwnBytes() {
        val style = load(); assumeTrue("ROSITA_TSV not set", style != null)
        style!!
        val inv = StyleVoiceInventoryBuilder.build(style, emptyList(), emptyMap(), null)
        println("ROSITA_INVENTORY_BEGIN\n" + StyleVoiceInventoryBuilder.report(inv) + "ROSITA_INVENTORY_END")
        assertTrue(inv.sections.size >= 8)
        val requests = StyleVoiceRequests.extract(style)
        assertEquals(requests.size, inv.flat.size)
        // Every row restates the file's own bytes; nothing is invented.
        for (row in inv.flat) {
            val src = requests.first { it.section == row.section && it.partIndex == row.partIndex }
            assertEquals(src.msb, row.msb); assertEquals(src.lsb, row.lsb); assertEquals(src.program, row.program)
            assertEquals(StyleVoiceInventoryBuilder.roleOf(row.destinationChannel) != null, true)
            // Nothing loaded and nothing observed: never EXACT, never OVERRIDDEN.
            assertTrue(row.status == VoiceStatus.UNKNOWN)
        }
        // The production parser's Rhythm requests are Bank 127 (kit) with the PC the fixture audit expects.
        val rhythm = inv.forSection("MainA").filter { it.destinationChannel == 8 || it.destinationChannel == 9 }
        assertTrue(rhythm.isNotEmpty()); assertTrue(rhythm.all { it.drum && it.readOnlyReason != null && it.msb == 127 })
    }
}

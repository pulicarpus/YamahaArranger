package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.ChannelBinding
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleVoiceRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpvStyleVoiceTest {
    private fun cc(tick: Int, ch: Int, n: Int, v: Int) = StyleNoteEvent(tick, false, n, v, ch, 0xB0 or ch)
    private fun pc(tick: Int, ch: Int, p: Int) = StyleNoteEvent(tick, false, p, 0, ch, 0xC0 or ch)
    private fun on(tick: Int, ch: Int) = StyleNoteEvent(tick, true, 60, 100, ch)
    private fun casm(src: Int, dst: Int, name: String) = CasmPolicyModel(src, dst, name, 0, 0, 0, 0, 0, 0, 127, 0, false)

    private fun part(name: String, src: Int, dst: Int, voice: String, events: List<StyleNoteEvent>): StylePartModel {
        val program = events.lastOrNull { it.tick == 0 && it.isProgramChange }?.note ?: -1
        val policy = casm(src, dst, voice)
        return StylePartModel(name, events, policy, listOf(policy), program,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 0 }?.velocity ?: 0,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 32 }?.velocity ?: 0)
    }

    private fun style(): ParsedStyle {
        val mainA = StyleSectionModel("Main A", 1920, listOf(
            part("Rhy1", 8, 8, "Rhythm1", listOf(cc(0, 8, 0, 127), cc(0, 8, 32, 0), pc(0, 8, 0), on(0, 8))),
            part("Bass", 10, 10, "Bass", listOf(cc(0, 10, 0, 0), cc(0, 10, 32, 112), pc(0, 10, 33), on(10, 10),
                cc(480, 10, 0, 104), pc(481, 10, 35))),
            // No tick-0 setup at all: request stays "-" rather than 0.
            part("Pad", 11, 11, "Pad", listOf(on(0, 11)))
        ))
        val mainB = StyleSectionModel("Main B", 1920, listOf(
            part("Rhy1", 8, 8, "Rhythm1", listOf(cc(0, 8, 0, 127), pc(0, 8, 16), on(0, 8)))
        ))
        return ParsedStyle("T.STY", 480, linkedMapOf("Main A" to mainA, "Main B" to mainB))
    }

    @Test fun requestsArePerSectionWithRawPresence() {
        val rows = StyleVoiceRequests.extract(style())
        assertEquals(4, rows.size)
        val rhyA = rows.first { it.section == "Main A" && it.destinationChannel == 8 }
        assertEquals(127, rhyA.msb); assertEquals(0, rhyA.lsb); assertEquals(0, rhyA.program); assertTrue(rhyA.drum)
        val rhyB = rows.first { it.section == "Main B" && it.destinationChannel == 8 }
        assertEquals(127, rhyB.msb); assertNull(rhyB.lsb); assertEquals(16, rhyB.program)
        val pad = rows.first { it.destinationChannel == 11 }
        assertNull(pad.msb); assertNull(pad.lsb); assertNull(pad.program); assertFalse(pad.drum)
        assertEquals("-:-", pad.bankText)
    }

    @Test fun dynamicProgramChangesCarryTheirBank() {
        val bass = StyleVoiceRequests.extract(style()).first { it.destinationChannel == 10 }
        assertEquals(1, bass.dynamicChanges.size)
        val change = bass.dynamicChanges.single()
        assertEquals(481, change.tick); assertEquals(104, change.msb); assertEquals(112, change.lsb); assertEquals(35, change.program)
        assertTrue(StyleVoiceRequests.report(listOf(bass)).contains("dynamicPC=481@104:112:35"))
    }

    @Test fun firstPartWithProgramOwnsTheDestination() {
        val section = StyleSectionModel("Main A", 1920, listOf(
            part("NoPc", 12, 12, "Gtr", listOf(on(0, 12))),
            part("WithPc", 13, 12, "Gtr2", listOf(pc(0, 13, 25), on(0, 13)))
        ))
        val rows = StyleVoiceRequests.extract(ParsedStyle("X", 480, mapOf("Main A" to section)))
        assertFalse(rows[0].appliedToDestination)
        assertTrue(rows[1].appliedToDestination)
    }

    @Test fun channelBindingParsesBoundAndUnresolved() {
        val bound = ChannelBinding.parse("10\tBOUND\tPRIMARY\tColombo.sf2\tabc\t0\t33\tFinger Bass\tsame_family")!!
        assertTrue(bound.resolved); assertEquals("Colombo.sf2 0:33 'Finger Bass'", bound.summary())
        val missing = ChannelBinding.parse("11\tUNRESOLVED\tMELODY\t\t\t-1\t-1\t\tno_compatible_candidate")!!
        assertFalse(missing.resolved); assertEquals("UNRESOLVED (no_compatible_candidate)", missing.summary())
        assertNull(ChannelBinding.parse(""))
    }
}

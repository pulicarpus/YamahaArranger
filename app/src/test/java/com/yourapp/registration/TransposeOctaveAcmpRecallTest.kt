package com.yourapp.yamahaarranger.registration

import java.nio.file.Files
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Registration 1-8 stores/recalls ACMP status and the RIGHT 1/2/3 octave; legacy slots stay readable and leave the live values alone. */
class TransposeOctaveAcmpRecallTest {
    private val voice = Sf2VoiceRef("a.sf2", 0, 24, "Strings")
    private fun current(acmp: Boolean = true, oct: List<Int> = listOf(0, 0, 0)) =
        RecallSnapshot(List(4) { PartSetup(voice, null, true, 100) }, ReleaseSetup(64, true), 120, 0, acmp = acmp, rightOctaves = oct)
    private fun slot(acmp: Boolean? = null, oct: List<Int>? = null, transpose: Int? = null) =
        RegistrationSlot("T", OutputMode.INTERNAL, List(4) { PartSetup(voice, null, true, 100) }, ReleaseSetup(64, true), null, transpose, 0, acmp, oct)
    private fun plan(s: RegistrationSlot, f: FreezeFlags = FreezeFlags(), c: RecallSnapshot = current()) =
        RecallPlanner.plan(c, s, f, listOf(voice), false).steps

    @Test fun planner_emitsAcmpAndOnlyChangedOctaves() {
        val steps = plan(slot(acmp = false, oct = listOf(1, 0, -1)))
        assertTrue(steps.contains(RecallStep.Acmp(false)))
        assertEquals(listOf(RecallStep.RightOctave(0, 1), RecallStep.RightOctave(2, -1)), steps.filterIsInstance<RecallStep.RightOctave>())
        assertTrue("equal values emit nothing", plan(slot(acmp = true, oct = listOf(0, 0, 0))).none { it is RecallStep.Acmp || it is RecallStep.RightOctave })
    }

    @Test fun planner_legacySlotLeavesLiveValuesAlone() {
        val steps = plan(slot(), c = current(acmp = false, oct = listOf(1, 1, 1)))
        assertTrue(steps.none { it is RecallStep.Acmp || it is RecallStep.RightOctave })
    }

    @Test fun planner_freezeFlagsSuppressTheirParameters() {
        val s = slot(acmp = false, oct = listOf(1, 1, 1), transpose = 4)
        assertTrue(plan(s, FreezeFlags(tempo = false, partOnOff = true)).none { it is RecallStep.Acmp })
        assertTrue(plan(s, FreezeFlags(tempo = false, transpose = true)).none { it is RecallStep.Transpose })
        assertTrue("octaves follow the VOICES freeze", plan(s, FreezeFlags(tempo = false, voices = true)).none { it is RecallStep.RightOctave })
        assertTrue(plan(s, FreezeFlags(tempo = false, transpose = true)).any { it is RecallStep.RightOctave })
    }

    @Test fun planner_acmpIsAppliedBeforeTransposeSoAnOffRecallActivatesTheTransposeAtOnce() {
        val steps = plan(slot(acmp = false, transpose = 5))
        assertTrue(steps.indexOf(RecallStep.Acmp(false)) < steps.indexOf(RecallStep.Transpose(5)))
    }

    @Test fun planner_outOfRangeOctaveIsClamped() {
        assertEquals(listOf(RecallStep.RightOctave(0, 1)), plan(slot(oct = listOf(5, 0, 0))).filterIsInstance<RecallStep.RightOctave>())
    }

    private fun bank(s: RegistrationSlot) = RegistrationBank("b", "B", listOf<RegistrationSlot?>(s) + List(7) { null })

    @Test fun codec_roundTripsNewFieldsAndLegacySlotsByteForByte() {
        val withNew = bank(slot(acmp = false, oct = listOf(-1, 0, 1), transpose = 2))
        assertEquals(withNew, RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(withNew)).value)
        val legacy = bank(slot(transpose = 2))
        val text = RegistrationCodec.encodeBank(legacy)
        assertFalse("legacy slots keep the old format", text.contains("rightOctaves") || text.contains("\"acmp\""))
        assertEquals(legacy, RegistrationCodec.decodeBank(text).value)
    }

    @Suppress("UNCHECKED_CAST") private fun mutated(raw: String, change: (MutableMap<String, Any?>) -> Unit): String {
        val e = RegistrationCodec.Json.parse(raw) as MutableMap<String, Any?>
        change(e["payload"] as MutableMap<String, Any?>)
        e["crc32"] = "%08x".format(CRC32().apply { update(RegistrationCodec.Json.write(e["payload"]).toByteArray(Charsets.UTF_8)) }.value)
        return RegistrationCodec.Json.write(e)
    }

    @Suppress("UNCHECKED_CAST") @Test fun codec_stillRejectsUnknownSlotFieldsAndBadOctaveShapes() {
        val raw = RegistrationCodec.encodeBank(bank(slot(acmp = true, oct = listOf(0, 0, 0))))
        fun slotOf(p: MutableMap<String, Any?>) = ((p["slots"] as List<Any?>)[0]) as MutableMap<String, Any?>
        try { RegistrationCodec.decodeBank(mutated(raw) { slotOf(it)["surprise"] = 1L }); fail("unknown field accepted") } catch (_: Exception) {}
        try { RegistrationCodec.decodeBank(mutated(raw) { slotOf(it)["rightOctaves"] = listOf(0L, 0L) }); fail("short octave list accepted") } catch (_: Exception) {}
        val clamped = RegistrationCodec.decodeBank(mutated(raw) { slotOf(it)["rightOctaves"] = listOf(9L, -9L, 0L) }).value.slots[0]!!
        assertEquals(listOf(1, -1, 0), clamped.rightOctaves)
    }

    @Test fun controller_saveStoresAcmpAndOctavesRegardlessOfTheTransposeOption() = runBlocking {
        var clock = 100L
        val c = RegistrationLibraryController(AtomicBankStore(Files.createTempDirectory("reg-oct")), { clock }, { 500 })
        c.load(); assertTrue(c.createBank("Test"))
        c.arm(600, false); assertTrue(c.saveSlot(0, "A", { false }) { current(acmp = false, oct = listOf(1, 0, -1)) })
        val saved = c.state.value.bank!!.slots[0]!!
        assertEquals(false, saved.acmp)
        assertEquals(listOf(1, 0, -1), saved.rightOctaves)
        assertNull("transpose option is off by default", saved.transpose)
        c.arm(600, false); assertFalse(c.saveSlot(0, "B", { false }) { error("first tap only arms the overwrite") })
        assertTrue(c.saveSlot(0, "B", { false }) { current(acmp = true, oct = listOf(1, 1, 1)) })
        assertEquals(listOf(1, 1, 1), c.state.value.bank!!.slots[0]!!.rightOctaves)
        assertEquals(true, c.state.value.bank!!.slots[0]!!.acmp)
    }
}

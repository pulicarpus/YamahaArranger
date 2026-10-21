package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.ManagedSf2Policy
import com.yourapp.yamahaarranger.audio.ManagedSf2Policy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedSf2PolicyTest {
    private fun c(name: String, mb: Long) = Candidate(name, name, mb * 1_000_000)
    private val colombo = c("ColomboGMGS2_BM.sf2", 60)
    private val tyros = c("yamaha tyros 4.sf2", 90)
    private val primeM = c("MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", 343)
    private val primeD = c("DRUM YAMAHA PSR-SX700 & SX900 PRIME.sf2", 153)

    @Test fun anyExtraSf2InTheFolderIsAttachedWithoutHardcodedNames() {
        val custom = c("MyDangdutKendang.sf2", 40)
        val p = ManagedSf2Policy.plan(listOf(colombo, tyros, primeM, primeD, custom))
        assertEquals("ColomboGMGS2_BM.sf2", p.primary!!.name); assertEquals("yamaha tyros 4.sf2", p.fallback!!.name)
        assertEquals(listOf(primeM.name, primeD.name, custom.name), p.manual.map { it.name })   // Prime Melody, Prime Drum, then the rest
        assertTrue(p.skipped.isEmpty())
    }

    @Test fun countAndSizeCapsSkipWithAReasonNeverSilently() {
        val extra = (1..3).map { c("Custom$it.sf2", 10) }
        val p = ManagedSf2Policy.plan(listOf(colombo, tyros, primeM, primeD) + extra)
        assertEquals(3, p.manual.size)
        assertEquals(extra.drop(1).map { it.name }, p.skipped.map { it.candidate.name }); assertTrue(p.skipped.all { it.reason == "LIMIT_3_MANUAL_FILES" })
        val big = ManagedSf2Policy.plan(listOf(colombo, tyros, primeM, c("Huge.sf2", 700)))
        assertEquals(listOf(primeM.name), big.manual.map { it.name })
        assertEquals("OVER_BUDGET_1000MB", big.skipped.single().reason)
        assertEquals("EMPTY_OR_UNREADABLE", ManagedSf2Policy.plan(listOf(colombo, tyros, c("Zero.sf2", 0))).skipped.single().reason)
    }
}

package com.yourapp.yamahaarranger.arranger
import com.yourapp.yamahaarranger.style.*

fun main() {
    val event=StyleNoteEvent(0,true,33,100,9)
    val part=StylePartModel("Rhythm2",listOf(event),program=8,bankMsb=127)
    val a=StyleSectionModel("MainA",480,listOf(part))
    val b=StyleSectionModel("MainB",480,listOf(part.copy(program=-1)))
    val style=ParsedStyle("test.sty",480,linkedMapOf("MainB" to b,"MainA" to a))
    val before=style.toString()
    val native="NATIVE ch=9 requestedBank=128 rawPC=8 resolverReason=legacy_first_cached_kit_fallback keysPresent=34,35 CC7=100 CC10=64 CC11=127 CC91=40 CC93=0"
    val report=StyleFidelityReport.export(style,"MainA",native)
    check(report==StyleFidelityReport.export(style,"MainA",native))
    check(style.toString()==before)
    check(report.indexOf("section=MainA")<report.indexOf("section=MainB"))
    check("rawDrumKeysMissing=33" in report)
    check("resolverReason=legacy_first_cached_kit_fallback" in report)
    check("UNSPECIFIED_NOT_GUESSED_BY_AUDIT" in report)
    check("activeBinding=UNOBSERVED" in report)
    check("all_keys_all_velocities=VERIFIED" !in report)
    check((8..15).all { "destinationCh=$it" in report })
    println("U0 FIDELITY PASS: deterministic, per-section/channel, real binding evidence, missing keys, unknown setup, no mutation")
}

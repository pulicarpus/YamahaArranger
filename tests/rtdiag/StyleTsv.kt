package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import java.io.File

/** Loads the Style dumped by the production C++ parser (SGAP_STYLE_TSV / ROSITA_TSV) into the Kotlin model. Same reader as SectionTransitionGapTest. */
object StyleTsv {
    fun load(): ParsedStyle? {
        val path = System.getenv("SGAP_STYLE_TSV") ?: System.getenv("ROSITA_TSV") ?: return null
        val sections = linkedMapOf<String, StyleSectionModel>(); var ppq = 480; var name = ""; var len = 0
        var parts = ArrayList<StylePartModel>(); var events = ArrayList<StyleNoteEvent>(); var policies = ArrayList<CasmPolicyModel>(); var partName: String? = null
        fun closePart() {
            val n = partName ?: return; var msb = 0; var lsb = 0; var program = -1
            events.filter { it.tick == 0 }.forEach { e -> when { e.isControlChange && e.note == 0 -> msb = e.velocity; e.isControlChange && e.note == 32 -> lsb = e.velocity; e.isProgramChange -> program = e.note } }
            parts += StylePartModel(n, events, policies.firstOrNull(), policies, program, msb, lsb); events = ArrayList(); policies = ArrayList(); partName = null
        }
        fun closeSection() { closePart(); if (name.isNotEmpty()) sections[name] = StyleSectionModel(name, len, parts); parts = ArrayList() }
        File(path).readLines(Charsets.ISO_8859_1).forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "PPQ" -> ppq = f[1].toInt(); "S" -> { closeSection(); name = f[1]; len = f[2].toInt() }; "P" -> { closePart(); partName = f.getOrElse(1) { "" }.trim() }
                "C" -> policies += CasmPolicyModel(f[1].toInt(), f[2].toInt(), f[3].trim(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12] == "1", f[13].toLong(), f[14].toInt(), f[15].toInt())
                "E" -> { val st = f[2].toInt(); if (st in 0x80..0xEF) events += StyleNoteEvent(f[1].toInt(), (st and 0xF0) == 0x90 && f[4].toInt() > 0, f[3].toInt(), f[4].toInt(), st and 0x0F, st) }
            }
        }
        closeSection(); return ParsedStyle(System.getenv("SGAP_STYLE_NAME") ?: "ROSITA.STY", ppq, sections, emptyMap(), 120)
    }

}

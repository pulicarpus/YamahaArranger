import androidx.compose.runtime.Composable
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.CatalogPreset
import com.yourapp.yamahaarranger.style.ObservedBinding
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleDialect
import com.yourapp.yamahaarranger.style.StyleDialectEvidence
import com.yourapp.yamahaarranger.style.StyleDialectIdentity
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder
import com.yourapp.yamahaarranger.ui.StyleVoiceInspectorContent
import java.io.File

/** SYNTHETIC specimen (built in code, not a Yamaha file) - used only to check layout and status colours. */
private fun specimenStyle(): ParsedStyle {
    fun cc(ch: Int, n: Int, v: Int) = StyleNoteEvent(0, false, n, v, ch, 0xB0 or ch)
    fun pc(t: Int, ch: Int, p: Int) = StyleNoteEvent(t, false, p, 0, ch, 0xC0 or ch)
    fun on(ch: Int) = StyleNoteEvent(0, true, 60, 100, ch)
    fun part(name: String, ch: Int, voice: String, events: List<StyleNoteEvent>): StylePartModel {
        val policy = CasmPolicyModel(ch, ch, voice, 0, 0, 0, 0, 0, 0, 127, 0, false)
        return StylePartModel(name, events, policy, listOf(policy),
            events.lastOrNull { it.tick == 0 && it.isProgramChange }?.note ?: -1,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 0 }?.velocity ?: 0,
            events.lastOrNull { it.tick == 0 && it.isControlChange && it.note == 32 }?.velocity ?: 0)
    }
    fun voiced(name: String, ch: Int, voice: String, msb: Int, lsb: Int, p: Int, extra: List<StyleNoteEvent> = emptyList()) =
        part(name, ch, voice, listOf(cc(ch, 0, msb), cc(ch, 32, lsb), pc(0, ch, p), on(ch)) + extra)
    val parts = listOf(
        voiced("Rhy1", 8, "Rhythm1", 127, 0, 0), voiced("Rhy2", 9, "Rhythm2", 127, 0, 0),
        voiced("Bass", 10, "Fingered Bass", 0, 112, 33), voiced("Chord1", 11, "Clean Guitar", 0, 112, 27, listOf(pc(960, 11, 28))),
        voiced("Chord2", 12, "Strings", 0, 112, 48), part("Pad", 13, "Pad", listOf(on(13))),
        voiced("Phrase1", 14, "Brass", 0, 112, 61), voiced("Phrase2", 15, "Lead", 0, 112, 80))
    return ParsedStyle("SPECIMEN.STY", 480,
        linkedMapOf("IntroA" to StyleSectionModel("IntroA", 1920, parts.take(4)), "MainA" to StyleSectionModel("MainA", 1920, parts),
            "MainB" to StyleSectionModel("MainB", 1920, parts), "FillAA" to StyleSectionModel("FillAA", 480, parts.take(5)),
            "EndingA" to StyleSectionModel("EndingA", 1920, parts.take(4))),
        dialectIdentity = StyleDialectIdentity(StyleDialect.SFF1, StyleDialectEvidence.SMF_TICK0_MARKER_SFF1))
}

fun renderVoiceInspector(dir: File, render: (String, Int, Int, Float, @Composable () -> Unit) -> Unit) {
    fun bound(ch: Int, bank: Int, p: Int, name: String, reason: String, source: String = "PRIMARY") =
        ch to ObservedBinding(ch, "BOUND", source, "Colombo.sf2", "", bank, p, name, reason)
    val observed = mapOf(
        bound(8, 128, 0, "Standard Kit", "r23_exact_fingerprint_namespace_coverage", "DRUM_R23"),
        bound(9, 128, 0, "Standard Kit", "legacy_first_cached_kit_fallback", "DRUM_LEGACY"),
        bound(10, 112 * 1, 33, "Finger Bass", "same_family"),
        bound(11, 0, 27, "Clean Gtr", "same_family"),
        12 to ObservedBinding(12, "UNRESOLVED", "MELODY", "", "", -1, -1, "", "no_compatible_candidate"),
        bound(14, 0, 61, "Brass Sect", "same_family"))
    val style = specimenStyle()
    val inv = StyleVoiceInventoryBuilder.build(style, listOf(CatalogPreset("Tyros.sf2", "", 112, 33, "Fingered Bass 2", false)), observed, "MainA")
    render("04_voice_inspector_1280x800.png", 1280, 800, 1.6f) {
        StyleVoiceInspectorContent(inv, true, "MIDI OUT + internal", {}, {}, "MainA", "MainA" to 3)
    }
    render("05_voice_inspector_nostyle.png", 1280, 800, 1.0f) { StyleVoiceInspectorContent(null, true, "Internal (BASSMIDI)", {}, {}) }
}

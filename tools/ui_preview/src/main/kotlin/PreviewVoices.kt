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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yourapp.yamahaarranger.style.ExternalVoice
import com.yourapp.yamahaarranger.ui.ReplacePanel
import com.yourapp.yamahaarranger.ui.StyleVoiceEditorActions
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
    val catalog = listOf(
        CatalogPreset("Colombo.sf2", "a".repeat(64), 0, 33, "Finger Bass", false), CatalogPreset("Colombo.sf2", "a".repeat(64), 0, 40, "Violin", false),
        CatalogPreset("Colombo.sf2", "a".repeat(64), 0, 48, "Strings Ensemble", false), CatalogPreset("Colombo.sf2", "a".repeat(64), 128, 0, "Standard Kit", true),
        CatalogPreset("Tyros.sf2", "b".repeat(64), 112, 33, "Fingered Bass 2", false), CatalogPreset("Tyros.sf2", "b".repeat(64), 112, 61, "Brass Section", false),
        CatalogPreset("Tyros.sf2", "b".repeat(64), 16256, 5, "Cool! Strings", false))
    val ext = listOf(ExternalVoice(0, 112, 48, "My Strings"), ExternalVoice(0, 113, 61, "My Brass"))
    fun actions(dirty: Boolean, playing: Boolean = false) = StyleVoiceEditorActions(catalog, ext, playing, true, dirty, true, "", "", false,
        { _, _, _, _ -> }, { _, _, _, _ -> }, {}, {}, {}, {}, { })
    val edited = StyleVoiceInventoryBuilder.build(style, catalog, observed, "MainA", mapOf(
        ("MainA" to 10) to com.yourapp.yamahaarranger.style.RowOverride("INTERNAL + MIDI OUT", com.yourapp.yamahaarranger.style.OverrideScope.SECTION,
            "INTERNAL Tyros.sf2 0:40 'Violin' · MIDI OUT 0:112:48 (display PC 49) 'My Strings'", "ACTIVE")))
    val bassRow = edited.forSection("MainA").first { it.destinationChannel == 10 }
    render("04_voice_inspector_1280x800.png", 1280, 800, 1.6f) {
        StyleVoiceInspectorContent(inv, true, "MIDI OUT + internal", {}, {}, "MainA", "MainA" to 3, actions(false))
    }
    render("05_voice_inspector_nostyle.png", 1280, 800, 1.0f) { StyleVoiceInspectorContent(null, true, "Internal (BASSMIDI)", {}, {}) }
    render("06_voice_inspector_edited_unsaved.png", 1280, 800, 1.6f) {
        StyleVoiceInspectorContent(edited, true, "MIDI OUT + internal", {}, {}, "MainA", "MainA" to 2, actions(true, playing = true))
    }
    render("07_replace_sf2.png", 900, 640, 1.6f) {
        Box(Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(12.dp), contentAlignment = Alignment.Center) { ReplacePanel(bassRow, actions(false), {}, {}, 0, "") }
    }
    render("08_replace_external.png", 900, 640, 1.6f) {
        Box(Modifier.fillMaxSize().background(Color(0xFF080A0D)).padding(12.dp), contentAlignment = Alignment.Center) { ReplacePanel(bassRow, actions(false), {}, {}, 1, "") }
    }
}

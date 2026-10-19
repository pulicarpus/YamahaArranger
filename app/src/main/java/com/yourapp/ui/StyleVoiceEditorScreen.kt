package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourapp.yamahaarranger.style.CatalogPreset
import com.yourapp.yamahaarranger.style.EditScope
import com.yourapp.yamahaarranger.style.ExternalVoice
import com.yourapp.yamahaarranger.style.OriginalRef
import com.yourapp.yamahaarranger.style.Provenance
import com.yourapp.yamahaarranger.style.Replacement
import com.yourapp.yamahaarranger.style.StyleVoiceEditorOps
import com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder
import com.yourapp.yamahaarranger.style.StyleVoiceInventory
import com.yourapp.yamahaarranger.style.StyleVoiceRow
import com.yourapp.yamahaarranger.style.VoiceStatus

/** Process-local switch for the full-screen Style Voice overlay (same pattern as ToolPanels). */
object StyleVoiceEditorHost {
    var visible by mutableStateOf(false)
    fun open() { visible = true }
    fun close() { visible = false }
}

/** Everything the editor needs from the app; null in the read-only preview. */
class StyleVoiceEditorActions(
    val sf2Catalog: List<CatalogPreset>,
    val externalChoices: List<ExternalVoice>,
    val isPlaying: Boolean,
    val midiOut: Boolean,
    val dirty: Boolean,
    val writable: Boolean,
    val savedInfo: String,
    val message: String,
    val auditioning: Boolean,
    val onApply: (StyleVoiceRow, EditScope, Replacement, (String?) -> Unit) -> Unit,
    val onAudition: (StyleVoiceRow, Replacement?, Boolean, (String?) -> Unit) -> Unit,
    val onAuditionStop: () -> Unit,
    val onResetPart: (Int) -> Unit,
    val onResetSection: (String) -> Unit,
    val onResetAll: () -> Unit,
    val onSave: ((String) -> Unit) -> Unit
)

private val SvBlack = Color(0xFF080A0D)
private val SvPanel = Color(0xFF12161B)
private val SvPanelHi = Color(0xFF1B222A)
private val SvLine = Color(0xFF2A333D)
private val SvText = Color(0xFFE6EAEE)
private val SvDim = Color(0xFF9AA3AD)
private val SvBlue = Color(0xFF58B8FF)
private val SvGreen = Color(0xFF27D887)

fun voiceStatusColor(status: VoiceStatus): Color = when (status) {
    VoiceStatus.EXACT -> Color(0xFF27D887)
    VoiceStatus.FALLBACK -> Color(0xFFFFB347)
    VoiceStatus.MISSING -> Color(0xFFFF5C5C)
    VoiceStatus.AMBIGUOUS -> Color(0xFFFF8A00)
    VoiceStatus.UNKNOWN -> Color(0xFF9AA3AD)
    VoiceStatus.OVERRIDDEN -> Color(0xFF58B8FF)
}

private fun provenanceText(p: Provenance?) = when (p) {
    Provenance.AUTHORED -> "authored in file"
    Provenance.INTERPRETED -> "interpreted by app"
    Provenance.RESOLVED -> "resolved (static check)"
    Provenance.RUNTIME_OBSERVED -> "observed at runtime"
    null -> "not resolved"
}

@Composable
fun StyleVoiceInspectorContent(
    inventory: StyleVoiceInventory?,
    loaded: Boolean,
    output: String,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    initialSection: String? = null,
    initialSelection: Pair<String, Int>? = null,
    actions: StyleVoiceEditorActions? = null,
    initialReplaceFor: Pair<String, Int>? = null
) {
    var replaceFor by remember(inventory?.styleName) { mutableStateOf(initialReplaceFor) }
    var toast by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember(inventory?.styleName) { mutableStateOf(initialSection) }
    var selectedRow by remember(inventory?.styleName) { mutableStateOf(initialSelection) }
    val section = inventory?.let { inv ->
        selectedSection?.takeIf { it in inv.sections } ?: inv.activeSection?.takeIf { it in inv.sections } ?: inv.sections.firstOrNull()
    }
    Surface(color = SvBlack, modifier = Modifier.fillMaxSize().clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Header(inventory, loaded, output, actions, onRefresh, onClose) { toast = it }
            if (inventory == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (loaded) "No Style is loaded. Open a Style first; this page is read-only." else "Reading Style…",
                        color = SvDim, fontSize = 14.sp)
                }
                return@Column
            }
            SectionBar(inventory, section, actions) { selectedSection = it; selectedRow = null }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val rows = if (section == null) emptyList() else inventory.forSection(section)
                Column(Modifier.weight(1.65f).fillMaxHeight()) {
                    TableHead()
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(rows, key = { "${it.section}/${it.partIndex}/${it.destinationChannel}" }) { r ->
                            val sel = selectedRow == (r.section to r.partIndex)
                            TableRow(r, sel) { selectedRow = r.section to r.partIndex }
                        }
                    }
                }
                val picked = rows.firstOrNull { selectedRow == (it.section to it.partIndex) }
                DetailPanel(picked, inventory, actions, Modifier.weight(1f).fillMaxHeight(), onReplace = { replaceFor = it.section to it.partIndex }) { toast = it }
            }
            toast?.let { Text(it, color = SvBlue, fontSize = 12.sp, modifier = Modifier.clickable { toast = null }) }
            val target = replaceFor?.let { rf -> inventory.flat.firstOrNull { it.section == rf.first && it.partIndex == rf.second } }
            if (target != null && actions != null) ReplaceDialog(target, actions, onClose = { replaceFor = null }) { toast = it }
        }
    }
}

@Composable
private fun Header(inv: StyleVoiceInventory?, loaded: Boolean, output: String, actions: StyleVoiceEditorActions?,
    onRefresh: () -> Unit, onClose: () -> Unit, onToast: (String) -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("STYLE VOICES", color = SvGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(inv?.styleName ?: if (loaded) "no style" else "…", color = SvText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inv != null) {
            Chip("FORMAT", inv.dialectText)
            Chip("OUTPUT", output)
            Chip("PARTS", "${inv.partCount}")
            Chip("VOICES", "${inv.voiceCount} requests")
            Chip("ACTIVE", inv.activeSection?.let { StyleVoiceInventoryBuilder.sectionLabel(it) } ?: "—")
            val overrides = inv.flat.count { it.status == VoiceStatus.OVERRIDDEN }
            Chip("PROFILE", if (actions == null) "$overrides override" else if (actions.dirty) "UNSAVED" else if (overrides > 0) "SAVED · $overrides" else "none")
        }
        if (actions != null && inv != null) {
            HeaderButton("SAVE PROFILE", enabled = actions.dirty && actions.writable) { actions.onSave(onToast) }
            HeaderButton("RESET ALL", enabled = inv.flat.any { it.override != null }) { confirmReset = true }
        }
        HeaderButton("REFRESH", onClick = onRefresh)
        HeaderButton("CLOSE", onClick = onClose)
    }
    if (confirmReset && actions != null) Dialog(onDismissRequest = { confirmReset = false }) {
        Column(Modifier.background(SvPanel, RoundedCornerShape(10.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reset every replacement of this Style?", color = SvText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("The Style plays exactly as authored. The saved profile is only removed when you press SAVE PROFILE afterwards.", color = SvDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderButton("RESET ALL") { confirmReset = false; actions.onResetAll() }
                HeaderButton("CANCEL") { confirmReset = false }
            }
        }
    }
}

@Composable
private fun Chip(label: String, value: String) {
    Column(Modifier.background(SvPanel, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = SvDim, fontSize = 8.sp, maxLines = 1)
        Text(value, color = SvText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun HeaderButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).background(SvPanelHi, RoundedCornerShape(6.dp))
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) SvBlue else SvDim.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SectionBar(inv: StyleVoiceInventory, selected: String?, actions: StyleVoiceEditorActions?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            inv.rows.forEach { g ->
                val on = g.section == selected
                val live = g.section == inv.activeSection
                val edited = g.rows.any { it.override != null }
                Box(Modifier.heightIn(min = 40.dp)
                    .background(if (on) SvBlue.copy(alpha = 0.25f) else SvPanel, RoundedCornerShape(6.dp))
                    .clickable { onPick(g.section) }
                    .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(g.label + (if (live) " ●" else "") + (if (edited) " ✎" else ""), color = if (on) SvText else SvDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        maxLines = 1, softWrap = false)
                }
            }
            Text("● playing  ✎ has replacement · only sections present in this Style are listed", color = SvDim, fontSize = 10.sp, maxLines = 1, softWrap = false)
        }
        if (actions != null && selected != null) {
            val hasSectionLevel = inv.forSection(selected).any { it.override?.scope == com.yourapp.yamahaarranger.style.OverrideScope.SECTION }
            HeaderButton("RESET SECTION", enabled = hasSectionLevel) { actions.onResetSection(selected) }
        }
    }
}

private val colPart = 150.dp
private val colStatus = 104.dp
private val colEdit = 78.dp

@Composable
private fun TableHead() {
    Row(Modifier.fillMaxWidth().background(SvPanel).padding(horizontal = 8.dp, vertical = 5.dp)) {
        HeadCell("PART", Modifier.width(colPart))
        HeadCell("STYLE REQUEST", Modifier.weight(1f))
        HeadCell("ACTUAL VOICE", Modifier.weight(1.2f))
        HeadCell("STATUS", Modifier.width(colStatus))
        HeadCell("EDIT", Modifier.width(colEdit))
    }
}

@Composable
private fun HeadCell(text: String, modifier: Modifier) = Text(text, color = SvDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = modifier, maxLines = 1)

@Composable
private fun TableRow(r: StyleVoiceRow, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .background(if (selected) SvBlue.copy(alpha = 0.16f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(colPart)) {
            Text(r.partRole.uppercase(), color = SvText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${r.partName} · src ${r.sourceChannel + 1}→${r.destinationChannel + 1}", color = SvDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            Text(r.displayRequest, color = SvText, fontSize = 12.sp, maxLines = 1, softWrap = false)
            Text("raw ${r.rawRequest}" + if (r.dynamicChanges.isNotEmpty()) " · +${r.dynamicChanges.size} PC" else "",
                color = SvDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Column(Modifier.weight(1.2f)) {
            Text(if (r.override != null) r.override.description else if (r.actualPresetName.isNotEmpty()) r.actualPresetName else r.actualDescription,
                color = if (r.override != null) voiceStatusColor(VoiceStatus.OVERRIDDEN) else SvText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (r.override != null) "replacement · ${r.override.state.lowercase()} · was ${r.actualPresetName.ifEmpty { "unresolved" }}" else if (r.actualSoundFont.isNotEmpty()) "${r.actualSoundFont} ${r.actualBank}:${r.actualProgram}" else provenanceText(r.resolutionProvenance),
                color = SvDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(colStatus)) { StatusBadge(r.status) }
        Text(if (r.readOnlyReason != null) "locked" else "REPLACE", color = if (r.readOnlyReason != null) SvDim else SvBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(colEdit))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(SvLine))
}

@Composable
fun StatusBadge(status: VoiceStatus) {
    val c = voiceStatusColor(status)
    Box(Modifier.background(c.copy(alpha = 0.18f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(status.name, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun DetailPanel(r: StyleVoiceRow?, inv: StyleVoiceInventory, actions: StyleVoiceEditorActions?, modifier: Modifier,
    onReplace: (StyleVoiceRow) -> Unit, onToast: (String) -> Unit) {
    Column(modifier.background(SvPanel, RoundedCornerShape(8.dp)).padding(10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (r == null) {
            Text("Tap a part to see its MSB / LSB / Program, channel routing, resolution and why.", color = SvDim, fontSize = 12.sp)
            return@Column
        }
        Text("${r.partRole.uppercase()}  ·  ${r.sectionLabel}", color = SvGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        StatusBadge(r.status)
        Field("Request (raw)", "MSB ${r.msb ?: "none"} · LSB ${r.lsb ?: "none"} · Program ${r.program ?: "none"}", provenanceText(r.requestProvenance))
        Field("Request (display, 1-based)", r.displayRequest, "same bytes + 1 on Program")
        Field("Channels", "source ${r.sourceChannel + 1} → destination ${r.destinationChannel + 1}" +
            if (r.hasCasm) "" else "  (no CASM entry; source used)", provenanceText(r.routingProvenance))
        Field("Style's own Voice name", r.casmVoiceName.ifBlank { "not carried by the file" }, "CASM table, as authored")
        Field("Resolved to", r.actualDescription, provenanceText(r.resolutionProvenance))
        Field("Why", r.statusReason, null)
        if (r.dynamicChanges.isNotEmpty()) Field("Later Program Changes in this section",
            r.dynamicChanges.joinToString("\n") { "tick ${it.tick}: ${it.msb ?: "-"}:${it.lsb ?: "-"}:${it.program}" } +
                if (r.override != null) "\nHeld back while the replacement is active (the replacement holds for the whole section)." else "",
            "authored in file")
        r.override?.let { Field("Replacement", "${it.kind} · ${it.scope} · ${it.state}\n${it.description}", "profile") }
        Field("Editing", r.readOnlyReason ?: "Replace this part's Voice for this section or for the whole Style.", null)
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.readOnlyReason == null) HeaderButton("REPLACE", enabled = actions.writable) { onReplace(r) }
                if (r.override != null || inv.flat.any { it.destinationChannel == r.destinationChannel && it.override != null }) {
                    HeaderButton("RESET PART") { actions.onResetPart(r.destinationChannel) }
                }
            }
            if (r.readOnlyReason == null) {
                val why = if (actions.isPlaying) "Stop the Style to audition: the test notes use this part's own channel." else null
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderButton("AUDITION ORIGINAL", enabled = !actions.isPlaying && r.program != null && !actions.auditioning) {
                        actions.onAudition(r, null, true) { it?.let(onToast) }
                    }
                    HeaderButton(if (r.override != null) "AUDITION REPLACEMENT" else "AUDITION CURRENT", enabled = !actions.isPlaying && !actions.auditioning) {
                        actions.onAudition(r, null, false) { it?.let(onToast) }
                    }
                }
                if (actions.auditioning) HeaderButton("STOP TEST NOTES") { actions.onAuditionStop() }
                if (why != null) Text(why, color = SvDim, fontSize = 10.sp)
            }
            if (actions.message.isNotBlank()) Text(actions.message, color = SvDim, fontSize = 10.sp)
        }
    }
}

private fun programLabel(v: ExternalVoice) = "MSB ${v.msb} LSB ${v.lsb} PC ${v.program + 1}"

@Composable
private fun ReplaceDialog(row: StyleVoiceRow, actions: StyleVoiceEditorActions, onClose: () -> Unit, onToast: (String) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReplacePanel(row, actions, onClose, onToast)
    }
}

@Composable
fun ReplacePanel(row: StyleVoiceRow, actions: StyleVoiceEditorActions, onClose: () -> Unit, onToast: (String) -> Unit,
    initialTab: Int = 0, initialQuery: String = "") {
    var tab by remember { mutableStateOf(initialTab) }
    var scope by remember { mutableStateOf(EditScope.THIS_SECTION) }
    var query by remember { mutableStateOf(initialQuery) }
    var pickedSf2 by remember { mutableStateOf<CatalogPreset?>(null) }
    var pickedExt by remember { mutableStateOf<ExternalVoice?>(null) }
    var msb by remember { mutableStateOf("") }; var lsb by remember { mutableStateOf("") }
    var pc by remember { mutableStateOf("") }; var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val candidates = remember(actions.sf2Catalog, query) { StyleVoiceEditorOps.sf2Candidates(actions.sf2Catalog, query) }
    val typed = remember(msb, lsb, pc, label) {
        if (msb.isBlank() && lsb.isBlank() && pc.isBlank()) null else StyleVoiceEditorOps.parseExternal(msb, lsb, pc, label)
    }
    val replacement: Replacement? = if (tab == 0) pickedSf2?.let { Replacement.Sf2(it) }
        else (typed?.first ?: pickedExt)?.let { Replacement.External(it) }
    Column(Modifier.widthIn(max = 820.dp).heightIn(max = 560.dp).background(SvPanel, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("REPLACE · ${row.partRole.uppercase()} · ${row.sectionLabel}", color = SvGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Style asks for ${row.displayRequest} (raw ${row.rawRequest})" + (if (row.casmVoiceName.isNotBlank()) " · '${row.casmVoiceName}'" else ""), color = SvDim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("INTERNAL SOUND (SF2)", tab == 0) { tab = 0; error = null }
            TabChip("EXTERNAL MIDI OUT", tab == 1) { tab = 1; error = null }
            Box(Modifier.weight(1f))
            TabChip("THIS SECTION", scope == EditScope.THIS_SECTION) { scope = EditScope.THIS_SECTION }
            TabChip("ALL SECTIONS", scope == EditScope.ALL_SECTIONS) { scope = EditScope.ALL_SECTIONS }
        }
        Text(if (scope == EditScope.THIS_SECTION) "Only ${row.partRole} in ${row.sectionLabel}. A section replacement beats a whole-Style one."
            else "${row.partRole} in every section of this Style, unless a section has its own replacement.", color = SvDim, fontSize = 10.sp)
        Box(Modifier.weight(1f, fill = false).heightIn(min = 160.dp)) {
            if (tab == 0) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search name, SoundFont or bank:program") }, modifier = Modifier.fillMaxWidth())
                if (candidates.isEmpty()) Text(if (actions.sf2Catalog.isEmpty()) "No SoundFont presets are loaded." else "No loaded preset matches.", color = SvDim, fontSize = 12.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(candidates, key = { "${it.soundFontName}/${it.pdta}/${it.bank}/${it.program}" }) { p ->
                        val on = pickedSf2 == p
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (on) SvBlue.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { pickedSf2 = p; error = null }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, color = SvText, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.soundFontName}  ${p.bank}:${p.program}", color = SvDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }
                Text("Internal sound only. MIDI OUT keeps the Style's own request unless you also set an external Voice.", color = SvDim, fontSize = 10.sp)
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("From your Registration EXT list", color = SvDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (actions.externalChoices.isEmpty()) Text("The EXT list is empty. Type the numbers below.", color = SvDim, fontSize = 12.sp)
                actions.externalChoices.forEach { v ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (pickedExt == v) SvBlue.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { pickedExt = v; error = null }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(v.label, color = SvText, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(programLabel(v), color = SvDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Text("Or type a Voice", color = SvDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(msb, { msb = it; pickedExt = null }, singleLine = true, label = { Text("MSB") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(lsb, { lsb = it; pickedExt = null }, singleLine = true, label = { Text("LSB") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(pc, { pc = it; pickedExt = null }, singleLine = true, label = { Text("Program 1-128") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Name (yours)") }, modifier = Modifier.weight(1.4f))
                }
                typed?.second?.let { Text(it, color = voiceStatusColor(VoiceStatus.MISSING), fontSize = 11.sp) }
                Text("This app has no built-in PSR-E343 Voice list. Numbers are sent as typed (Program shown 1-based, sent 0-based) and are not verified against the instrument." +
                    if (actions.midiOut) "" else " MIDI OUT is off: the Voice is stored and sent when MIDI OUT is turned on.", color = SvDim, fontSize = 10.sp)
            }
        }
        error?.let { Text(it, color = voiceStatusColor(VoiceStatus.MISSING), fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton("AUDITION", enabled = replacement != null && !actions.isPlaying && !actions.auditioning) {
                actions.onAudition(row, replacement, false) { it?.let(onToast) }
            }
            HeaderButton("AUDITION ORIGINAL", enabled = row.program != null && !actions.isPlaying && !actions.auditioning) {
                actions.onAudition(row, null, true) { it?.let(onToast) }
            }
            if (actions.auditioning) HeaderButton("STOP") { actions.onAuditionStop() }
            Box(Modifier.weight(1f))
            HeaderButton("APPLY", enabled = replacement != null) {
                actions.onApply(row, scope, replacement!!) { err -> if (err == null) { onToast("Applied — press SAVE PROFILE to keep it."); onClose() } else error = err }
            }
            HeaderButton("CANCEL") { actions.onAuditionStop(); onClose() }
        }
        if (actions.isPlaying) Text("Audition plays four test notes on this part's own channel, so it needs the Style stopped. APPLY works while playing.", color = SvDim, fontSize = 10.sp)
    }
}

@Composable
private fun TabChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).background(if (on) SvBlue.copy(alpha = 0.25f) else SvPanelHi, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (on) SvText else SvDim, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Field(label: String, value: String, note: String?) {
    Column {
        Text(label.uppercase(), color = SvDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = SvText, fontSize = 12.sp)
        if (note != null) Text(note, color = SvDim, fontSize = 9.sp)
    }
}

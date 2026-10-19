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
import com.yourapp.yamahaarranger.style.Provenance
import com.yourapp.yamahaarranger.style.StyleVoiceInventory
import com.yourapp.yamahaarranger.style.StyleVoiceRow
import com.yourapp.yamahaarranger.style.VoiceStatus

/** Process-local switch for the full-screen Style Voice overlay (same pattern as ToolPanels). */
object StyleVoiceEditorHost {
    var visible by mutableStateOf(false)
    fun open() { visible = true }
    fun close() { visible = false }
}

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
    initialSelection: Pair<String, Int>? = null
) {
    var selectedSection by remember(inventory?.styleName) { mutableStateOf(initialSection) }
    var selectedRow by remember(inventory?.styleName) { mutableStateOf(initialSelection) }
    val section = inventory?.let { inv ->
        selectedSection?.takeIf { it in inv.sections } ?: inv.activeSection?.takeIf { it in inv.sections } ?: inv.sections.firstOrNull()
    }
    Surface(color = SvBlack, modifier = Modifier.fillMaxSize().clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Header(inventory, loaded, output, onRefresh, onClose)
            if (inventory == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (loaded) "No Style is loaded. Open a Style first; this page is read-only." else "Reading Style…",
                        color = SvDim, fontSize = 14.sp)
                }
                return@Column
            }
            SectionBar(inventory, section) { selectedSection = it; selectedRow = null }
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
                DetailPanel(picked, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Header(inv: StyleVoiceInventory?, loaded: Boolean, output: String, onRefresh: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("STYLE VOICES", color = SvGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(inv?.styleName ?: if (loaded) "no style" else "…", color = SvText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (inv != null) {
            Chip("FORMAT", inv.dialectText)
            Chip("OUTPUT", output)
            Chip("PARTS", "${inv.partCount}")
            Chip("VOICES", "${inv.voiceCount} requests")
            Chip("ACTIVE", inv.activeSection?.let { com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder.sectionLabel(it) } ?: "—")
            Chip("OVERRIDES", "${inv.flat.count { it.status == VoiceStatus.OVERRIDDEN }}")
        }
        HeaderButton("REFRESH", onRefresh)
        HeaderButton("CLOSE", onClose)
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
private fun HeaderButton(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).background(SvPanelHi, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = SvBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SectionBar(inv: StyleVoiceInventory, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        inv.rows.forEach { g ->
            val on = g.section == selected
            val live = g.section == inv.activeSection
            Box(Modifier.heightIn(min = 40.dp)
                .background(if (on) SvBlue.copy(alpha = 0.25f) else SvPanel, RoundedCornerShape(6.dp))
                .then(Modifier.clickable { onPick(g.section) })
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(g.label + if (live) " ●" else "", color = if (on) SvText else SvDim, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    maxLines = 1, softWrap = false)
            }
        }
        Text("only sections present in this Style are listed", color = SvDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
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
            Text(if (r.actualPresetName.isNotEmpty()) r.actualPresetName else r.actualDescription, color = SvText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.actualSoundFont.isNotEmpty()) "${r.actualSoundFont} ${r.actualBank}:${r.actualProgram}" else provenanceText(r.resolutionProvenance),
                color = SvDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(colStatus)) { StatusBadge(r.status) }
        Text(if (r.readOnlyReason != null) "locked" else "view", color = SvDim, fontSize = 10.sp, modifier = Modifier.width(colEdit))
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
private fun DetailPanel(r: StyleVoiceRow?, modifier: Modifier) {
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
            r.dynamicChanges.joinToString("\n") { "tick ${it.tick}: ${it.msb ?: "-"}:${it.lsb ?: "-"}:${it.program}" }, "authored in file")
        r.override?.let { Field("Override", "${it.kind} · ${it.scope} · ${it.state}\n${it.description}", "saved profile") }
        Field("Editing", r.readOnlyReason ?: "Replacement is available in the editor.", null)
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

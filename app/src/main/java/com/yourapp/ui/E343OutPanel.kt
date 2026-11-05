package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourapp.yamahaarranger.e343out.E343MapStatus
import com.yourapp.yamahaarranger.e343out.E343OutMode

private val EoBg = Color(0xFF12161B)
private val EoCard = Color(0xFF1B222A)
private val EoText = Color(0xFFE6EAEE)
private val EoDim = Color(0xFF9AA3AD)
private val EoBlue = Color(0xFF58B8FF)
private val EoGreen = Color(0xFF27D887)
private val EoOrange = Color(0xFFFFB347)
private val EoRed = Color(0xFFFF5C5C)

fun e343StatusColor(s: E343MapStatus): Color = when (s) {
    E343MapStatus.ORIGINAL_COMPATIBLE -> EoGreen
    E343MapStatus.AUTO_MAPPED -> EoBlue
    E343MapStatus.ROLE_FALLBACK -> EoOrange
    E343MapStatus.NO_SAFE_CANDIDATE -> EoRed
    E343MapStatus.MANUAL -> Color(0xFFC792EA)
}

/** MIDI OUT -> PSR-E343: mode (ORIGINAL / AUTO E343 / AUTO E343 + BALANCE), per part the Style request, the voice sent and why, and the CC7 trims. */
@Composable
fun E343OutDialog(vm: MainViewModel, midiOut: Boolean, onClose: () -> Unit) {
    val ui by vm.e343OutUi.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.e343OutRefresh(); kotlinx.coroutines.delay(500) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).background(EoBg, RoundedCornerShape(10.dp)).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MIDI OUT · PSR-E343 AUTO VOICE & BALANCE", color = EoGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Only what is sent to the external keyboard changes. The internal sound, the Style file, CASM/NTT, notes and velocities stay as authored. " +
                "Priority: manual voice / trim saved with the Style > Mixer slider you moved > automatic. AUTO BALANCE only lowers parts that are louder than their role allows; " +
                "it is an estimate from the Style data and must be checked by ear on the E343.", color = EoDim, fontSize = 11.sp)
            if (!midiOut) Text("MIDI OUT is OFF now: the mode is kept and applies when MIDI OUT is turned on.", color = EoOrange, fontSize = 12.sp)
            if (!ui.catalogOk && ui.mode == E343OutMode.ORIGINAL) Text("The E343 list is read when an AUTO mode is chosen.", color = EoDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in E343OutMode.entries) EoButton(m.label, selected = ui.mode == m) { vm.e343OutSetMode(m) }
            }
            if (ui.deviceNote.isNotBlank()) Text(ui.deviceNote, color = if (ui.deviceNote.startsWith("Applied")) EoGreen else EoOrange, fontSize = 12.sp)
            if (ui.message.isNotBlank()) Text(ui.message, color = EoBlue, fontSize = 12.sp)
            Text("Style: ${ui.styleName.ifBlank { "none" }}", color = EoText, fontSize = 12.sp)
            for (p in ui.parts) PartCard(vm, ui.mode, p)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EoButton("SAVE MANUAL TRIMS TO STYLE", enabled = ui.dirty) { vm.e343OutSave() }
                EoButton("WRITE REPORT TO LOG") { DebugLog.add(vm.e343OutReport()) }
                EoButton("CLOSE", onClick = onClose)
                if (ui.dirty) Text("unsaved manual trims", color = EoOrange, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PartCard(vm: MainViewModel, mode: E343OutMode, p: MainViewModel.E343OutPartUi) {
    Column(Modifier.fillMaxWidth().background(EoCard, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${p.role.uppercase()}  ·  ch${p.channel + 1}", color = EoGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (p.decisions.isEmpty()) Text("No Bank/Program request planned for this part.", color = EoDim, fontSize = 11.sp)
        for (d in p.decisions) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.status.label, color = e343StatusColor(d.status), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.widthIn(min = 120.dp))
                Text("Style ${d.requestText}  →  ${if (d.status == E343MapStatus.NO_SAFE_CANDIDATE) "unchanged" else d.outText}", color = EoText, fontSize = 12.sp)
            }
            Text(d.reason, color = EoDim, fontSize = 10.sp)
            for (w in d.warnings) Text("⚠ $w", color = EoOrange, fontSize = 10.sp)
        }
        p.live?.decision?.takeIf { mode.mapsVoices }?.let { Text("Sent now: ${it.outText} (${it.status.label})", color = EoBlue, fontSize = 11.sp) }
        val auto = p.balance?.autoTrim ?: 127
        val live = p.live
        Text("Volume: auto trim $auto" + (p.balance?.let { " — ${it.reason}" } ?: ""), color = EoDim, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("manual ${p.savedTrim ?: "auto"} · slider ${p.slider} · effective ${live?.trim ?: 127} (${live?.trimSource ?: "-"}) · Style CC7 ${live?.rawCc7?.takeIf { it >= 0 } ?: "-"} → sent ${live?.sentCc7?.takeIf { it >= 0 } ?: "-"}",
                color = EoText, fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (p.channel in 8..15) {
                val base = p.savedTrim ?: auto
                for (step in listOf(-10, -3, 3, 10)) EoButton(if (step > 0) "+$step" else "$step", small = true) { vm.e343OutSetManualTrim(p.channel, (base + step).coerceIn(0, 127)) }
                EoButton("AUTO", small = true, enabled = p.savedTrim != null) { vm.e343OutSetManualTrim(p.channel, null) }
            }
        }
    }
}

@Composable
private fun EoButton(text: String, selected: Boolean = false, enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = if (small) 48.dp else 64.dp)
        .background(if (selected) Color(0xFF1F4E79) else Color(0xFF26303A), RoundedCornerShape(6.dp))
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = if (small) 8.dp else 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (!enabled) EoDim.copy(alpha = 0.5f) else if (selected) Color.White else EoBlue, fontWeight = FontWeight.Bold, fontSize = if (small) 12.sp else 13.sp, maxLines = 1)
    }
}

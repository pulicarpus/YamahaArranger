package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourapp.yamahaarranger.routing.DeviceRoutingController
import com.yourapp.yamahaarranger.routing.OutputMode
import com.yourapp.yamahaarranger.routing.RouteStatus
import com.yourapp.yamahaarranger.routing.RoutingResolver
import com.yourapp.yamahaarranger.routing.RoutingSettings

/** Process-local switch for SETTINGS -> MIDI & AUDIO (same pattern as ToolPanels / StyleVoiceEditorHost). */
object DeviceRoutingHost {
    var visible by mutableStateOf(false)
    fun toggle() { visible = !visible }
}

private val DrBg = Color(0xFF12161B)
private val DrCard = Color(0xFF1B222A)
private val DrText = Color(0xFFE6EAEE)
private val DrDim = Color(0xFF9AA3AD)
private val DrBlue = Color(0xFF58B8FF)
private val DrGreen = Color(0xFF27D887)
private val DrOrange = Color(0xFFFFB347)

private fun statusColor(s: RouteStatus) = when (s) { RouteStatus.CONNECTED -> DrGreen; RouteStatus.DISCONNECTED -> DrOrange; RouteStatus.UNAVAILABLE -> DrDim; RouteStatus.OFF -> DrDim }

@Composable
fun DeviceRoutingOverlay(vm: MainViewModel) {
    if (!DeviceRoutingHost.visible) return
    val u by vm.deviceRouting.ui.collectAsState()
    val r = vm.deviceRouting
    Dialog(onDismissRequest = { DeviceRoutingHost.visible = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.96f).background(DrBg, RoundedCornerShape(10.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SETTINGS · MIDI & AUDIO", color = DrGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                DrButton("RECONNECT") { r.reconnect() }
                DrButton("CLOSE") { DeviceRoutingHost.visible = false }
            }
            Text("Audio Output chooses where the internal BASSMIDI sound plays. MIDI Output chooses where MIDI messages go. They are independent.", color = DrDim, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().heightIn(max = 520.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MidiInSection(u, r)
                    MidiOutSection(u, r)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioSection(u, r)
                    ModeSection(u, r)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, status: RouteStatus, statusText: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(DrCard, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = DrText, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(statusText, color = statusColor(status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Text(detail, color = DrDim, fontSize = 11.sp)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in items) DrButton(label, selected = key == selected) { onPick(key) }
    }
}

private fun openText(status: RouteStatus, open: Boolean) = if (status == RouteStatus.CONNECTED && !open) "CONNECTING…" else status.name

@Composable
private fun MidiInSection(u: DeviceRoutingController.Ui, r: DeviceRoutingController) {
    val s = u.settings
    val items = listOf(RoutingSettings.AUTO to "AUTO") + u.midiDevices.filter { it.canIn }.map { it.key to "${it.label} (${it.transport})" } +
        (if (s.midiIn != RoutingSettings.AUTO && u.midiDevices.none { it.key == s.midiIn }) listOf(s.midiIn to "${s.labels[s.midiIn] ?: "Saved device"} (not connected)") else emptyList())
    Section("MIDI INPUT", u.midiIn.status, openText(u.midiIn.status, u.inOpen), u.midiIn.text + "  ·  RIGHT / LEFT / ACMP, split and velocity are unchanged.") {
        Choices(items, s.midiIn) { r.setMidiIn(it) }
    }
}

@Composable
private fun MidiOutSection(u: DeviceRoutingController.Ui, r: DeviceRoutingController) {
    val s = u.settings
    val items = listOf(RoutingSettings.OFF to "OFF", RoutingSettings.AUTO to "AUTO") + u.midiDevices.filter { it.canOut }.map { it.key to "${it.label} (${it.transport})" } +
        (if (s.midiOut != RoutingSettings.OFF && s.midiOut != RoutingSettings.AUTO && u.midiDevices.none { it.key == s.midiOut }) listOf(s.midiOut to "${s.labels[s.midiOut] ?: "Saved device"} (not connected)") else emptyList())
    Section("MIDI OUTPUT", u.midiOut.status, if (u.midiOut.status == RouteStatus.OFF) "OFF" else openText(u.midiOut.status, u.outOpen), u.midiOut.text) {
        Choices(items, s.midiOut) { r.setMidiOut(it) }
        val out = u.midiOut.endpoint
        if (out != null) {
            val confirmed = out.key in s.e343Devices
            val byName = RoutingResolver.isE343(out, s.copy(e343Devices = emptySet()))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PSR-E343 profile on '${out.label}': " + when { confirmed -> "ON (confirmed by you)"; byName -> "ON (the device names itself E343)"; else -> "OFF (not confirmed)" },
                    color = if (u.e343Active) DrGreen else DrDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (!byName) DrButton(if (confirmed) "NOT AN E343" else "THIS IS MY PSR-E343", selected = confirmed) { r.setE343(out.key, !confirmed) }
            }
            Text("Auto E343 Voice Mapping / Balance (Style Voice Editor → E343 AUTO) is applied only on a device marked here; any other MIDI device gets the Style's own voices.", color = DrDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AudioSection(u: DeviceRoutingController.Ui, r: DeviceRoutingController) {
    val s = u.settings
    val items = listOf(RoutingSettings.DEFAULT to "System Default") + u.audioDevices.map { it.key to it.label } +
        (if (s.audioOut != RoutingSettings.DEFAULT && u.audioDevices.none { it.key == s.audioOut }) listOf(s.audioOut to "${s.labels[s.audioOut] ?: "Saved device"} (not connected)") else emptyList())
    val actual = u.audioDevices.firstOrNull { it.id == u.actualAudioId }?.label ?: if (u.actualAudioId > 0) "device #${u.actualAudioId}" else "—"
    Section("AUDIO OUTPUT", u.audioOut.status, u.audioOut.status.name,
        u.audioOut.text + "  ·  stream now on: $actual" + (if (u.audioFallback) "  ·  the chosen device could not be opened: System Default" else "")) {
        Choices(items, s.audioOut) { r.setAudioOut(it) }
        Text("Switching reopens the audio stream (a short gap); notes keep playing. Bluetooth adds noticeable latency.", color = DrDim, fontSize = 10.sp)
    }
}

@Composable
private fun ModeSection(u: DeviceRoutingController.Ui, r: DeviceRoutingController) {
    val mode = u.settings.mode
    val warn = mode.midiOut && !u.outOpen
    Section("OUTPUT MODE", if (warn) RouteStatus.DISCONNECTED else RouteStatus.CONNECTED, mode.label,
        "INTERNAL = BASSMIDI only · MIDI OUT = external only · BOTH = both" + if (warn) "\n⚠ MIDI OUT has no connected device: nothing is sent until one is connected (never redirected)." else "") {
        Choices(OutputMode.entries.map { it.name to it.label }, mode.name) { k -> OutputMode.entries.firstOrNull { it.name == k }?.let(r::setMode) }
    }
}

@Composable
private fun DrButton(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).background(if (selected) Color(0xFF1F4E79) else Color(0xFF26303A), RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Color.White else DrBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
    }
}

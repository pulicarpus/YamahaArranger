package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yourapp.yamahaarranger.audio.RightVelocityMode
import com.yourapp.yamahaarranger.audio.RightVelocityMonitor
import com.yourapp.yamahaarranger.audio.RightVelocityTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Non-modal diagnostic panel: the Voice Selector and keyboard stay usable while it is open.
 * It polls read-only readback twice per second only while expanded. Diagnostic build only; not a fix.
 */
@Composable
fun RightVoiceDiagOverlay(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(RightVelocityTest.mode) }
    var snapshot by remember { mutableStateOf<MainViewModel.RightDiagSnapshot?>(null) }
    var fxText by remember { mutableStateOf("") }
    val fxScope = androidx.compose.runtime.rememberCoroutineScope()
    var e343Open by remember { mutableStateOf(false) }
    if (!ToolPanels.diag) return
    if (e343Open) E343ExperimentDialog(viewModel) { e343Open = false }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = try { viewModel.rightVoiceDiagSnapshot() } catch (_: Exception) { snapshot }
            delay(500)
        }
    }
    Column(
        modifier.width(560.dp).heightIn(max = 330.dp).background(Color(0xEE10151B), RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(8.dp)
    ) {
        // CLOSE and the E343 entry come first and every chip row scrolls sideways: nothing can be pushed out of the 560 dp panel any more.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BigChip("CLOSE", Color(0xFF7A2B2B)) { ToolPanels.diag = false }
            BigChip("E343 EXPERIMENT ▸ " + viewModel.e343ExperimentUi().mode, Color(0xFF7A5A2B)) { e343Open = true }
            Text("RIGHT DIAG — diagnostic only, not a fix", color = Color(0xFFFFC857), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            Chip("RESET STATS", Color(0xFF444C56)) { viewModel.resetRightDiagStats() }
            Chip("LOG PROBE", Color(0xFF2B5C7A)) { viewModel.logRightInputProbe() }
            Chip("SF2 PITCH/FX", Color(0xFF2B5C7A)) { fxScope.launch { fxText = viewModel.rightSf2PitchFxReport() } }
            Chip("MIDI OUT STATE", Color(0xFF2B5C7A)) { fxText = viewModel.midiOutReport() }
            Chip("CLEAR OUT NOTES", Color(0xFF444C56)) { viewModel.resetMidiOutReport() }
            Chip("E343 OFF STATS", Color(0xFF2B5C7A)) { fxText = viewModel.e343DrumNoteOffReport() }
        }
        val touch by viewModel.touchResponse.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState())) {
            Text("TOUCH RESPONSE (USB→RIGHT only)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            com.yourapp.yamahaarranger.audio.TouchResponse.values().forEach { t ->
                Chip(t.label, if (touch == t) Color(0xFF1E8E3E) else Color(0xFF444C56)) { viewModel.setTouchResponse(t) }
            }
            if (touch == com.yourapp.yamahaarranger.audio.TouchResponse.CUSTOM) {
                val (g, f) = viewModel.touchCustom.collectAsState().value
                Chip("γ−", Color(0xFF444C56)) { viewModel.setTouchCustom(g - 0.05, f) }
                Text("γ=%.2f".format(g), color = Color.White, fontSize = 9.sp)
                Chip("γ+", Color(0xFF444C56)) { viewModel.setTouchCustom(g + 0.05, f) }
                Chip("floor−", Color(0xFF444C56)) { viewModel.setTouchCustom(g, f - 5) }
                Text("floor=$f", color = Color.White, fontSize = 9.sp)
                Chip("floor+", Color(0xFF444C56)) { viewModel.setTouchCustom(g, f + 5) }
            }
            Text("(saved · not MIDI OUT · not Virtual Keyboard)", color = Color(0xFF9AA4AF), fontSize = 9.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            Text("RIGHT VELOCITY TEST", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            RightVelocityMode.values().forEach { m ->
                Chip(m.label, if (mode == m) Color(0xFF1E8E3E) else Color(0xFF444C56)) {
                    RightVelocityTest.set(m); mode = m
                }
            }
            Text("(RIGHT internal synth only · not saved)", color = Color(0xFF9AA4AF), fontSize = 9.sp)
        }
        val gainDb by viewModel.internalGainDb.collectAsState()
        var gainText by remember { mutableStateOf("") }
        var gainWarn by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { while (true) { val r = try { viewModel.internalGainReport() } catch (_: Exception) { null }; gainText = r?.summary() ?: "no meter"; gainWarn = r?.wouldClip == true; delay(500) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState())) {
            Text("INTERNAL GAIN (BASSMIDI mix only)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            com.yourapp.yamahaarranger.audio.InternalGain.STEPS.forEach { d ->
                Chip(if (d == 0) "0 dB (default)" else "+$d dB", if (gainDb == d) Color(0xFF1E8E3E) else Color(0xFF444C56)) { viewModel.setInternalGainDb(d) }
            }
            Chip("RESET METER", Color(0xFF444C56)) { viewModel.resetInternalGainStats() }
        }
        Text((if (gainWarn) "CLIP RISK  " else "OK  ") + gainText, color = if (gainWarn) Color(0xFFFF6B6B) else Color(0xFF4CD37B), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        var probe by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { while (true) { probe = com.yourapp.yamahaarranger.audio.RightInputProbe.report(); delay(500) } }
        Text(probe, color = Color(0xFF7FDBFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        val snap = snapshot
        val first = snap?.backend?.firstOrNull { r -> snap.app.firstOrNull { it.label.replace(" ", "") == r.part }?.enabled == true }
        val sf = first?.get("actualLabel")?.takeIf { it == "COLOMBO" || it == "TYROS" }
        val testId = when {
            sf == "COLOMBO" && mode == RightVelocityMode.ORIGINAL -> "A1 Colombo / Original"
            sf == "COLOMBO" -> "A2 Colombo / Fixed 100"
            sf == "TYROS" && mode == RightVelocityMode.ORIGINAL -> "B1 Tyros / Original"
            sf == "TYROS" -> "B2 Tyros / Fixed 100"
            else -> "SF2 of first enabled RIGHT = ${first?.get("actualLabel") ?: "UNKNOWN"} / ${mode.label}"
        }
        Text("TEST ID: $testId (derived from BASS readback, not from UI)", color = Color(0xFF7FDBFF), fontSize = 10.sp)
        Column {
            if (fxText.isNotEmpty()) Text(fxText, color = Color(0xFFFFC857), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            if (snap == null) Text("waiting for first readback…", color = Color.White, fontSize = 10.sp)
            snap?.backend?.forEach { row ->
                val app = snap.app.firstOrNull { it.label.replace(" ", "") == row.part }
                RowBlock(row, app)
            }
        }
    }
}

/** Larger chip for the always-visible actions (CLOSE, E343 entry). */
@Composable
private fun BigChip(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).background(color, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** Full-screen, modal E343 experiment page (vertical scroll, big buttons, CLOSE / Android Back). State is polled from the experiment twice per second. */
@Composable
fun E343ExperimentDialog(viewModel: MainViewModel, onClose: () -> Unit) {
    var state by remember { mutableStateOf(viewModel.e343ExperimentUi()) }
    LaunchedEffect(Unit) { while (true) { state = viewModel.e343ExperimentUi(); delay(500) } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        E343ExperimentContent(
            state = state,
            onMode = { viewModel.e343SetMode(it); state = viewModel.e343ExperimentUi() },
            onGate = { viewModel.e343SetGate(it); state = viewModel.e343ExperimentUi() },
            onResetStats = { viewModel.e343ResetStats(); state = viewModel.e343ExperimentUi() },
            onClose = onClose,
        )
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.background(color, RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

private fun cc(v: Int) = if (v < 0) "NA" else v.toString()

@Composable
private fun RowBlock(r: com.yourapp.yamahaarranger.audio.RightVoiceDiagRow, app: MainViewModel.RightDiagApp?) {
    val layer = r.int("ch")
    val color = when (r.status) { "MATCH" -> Color(0xFF4CD37B); "MISMATCH" -> Color(0xFFFF6B6B); else -> Color(0xFFFFC857) }
    val kb = RightVelocityMonitor.shared.stats(layer)
    val mono = FontFamily.Monospace
    Column(Modifier.padding(vertical = 3.dp)) {
        Text("${r.part} ${if (app?.enabled == true) "[ON]" else "[off]"}  SF2 STATUS: ${r.status}  (${r.get("reason")})",
            color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = mono)
        Text("APP selected: '${app?.voiceName ?: "?"}' sf2=${app?.soundFontName ?: "?"} bank=${app?.bank} pc=${app?.program}",
            color = Color(0xFFCFD8DC), fontSize = 9.sp, fontFamily = mono)
        Text("REQUESTED→BASS: ${r.get("reqLabel")} '${r.get("reqSf2")}' fontId=${r.get("reqFontId")} bank=${r.get("reqBank")} pc=${r.get("reqPC")} '${r.get("reqName")}' selVerified=${r.get("selVerified")}",
            color = Color(0xFFCFD8DC), fontSize = 9.sp, fontFamily = mono)
        Text("BASS LIVE readback ok=${r.get("liveOk")}: ${r.get("actualLabel")} '${r.get("actualSf2")}' handle=${r.get("liveFont")} bank=${r.get("liveBank")} pc=${r.get("livePC")} '${r.get("liveName")}'",
            color = Color.White, fontSize = 9.sp, fontFamily = mono)
        Text("FONT ID: BASS name='${r.get("bassFontName")}' presets=${r.get("bassFontPresets")} | FILE INAM='${r.get("fileInam")}' presets=${r.get("filePresets")} countAgree=${r.get("presetCountAgree")} stream table intact=${r.get("tableIntact")}",
            color = Color.White, fontSize = 9.sp, fontFamily = mono)
        Text("CONTROLLERS (BASS readback): CC7=${cc(r.int("cc7"))} CC11=${cc(r.int("cc11"))} CC91=${cc(r.int("cc91"))} CC93=${cc(r.int("cc93"))} " +
            "CC71=${cc(r.int("cc71"))} CC74=${cc(r.int("cc74"))} CC72(release)=${cc(r.int("release"))} pan=${cc(r.int("pan"))} fontVol=${r.get("fontVolume")} streamVol=${r.get("streamMidiVol")}",
            color = Color.White, fontSize = 9.sp, fontFamily = mono)
        Text("APP STATE (not read from backend): knob=${app?.volumeKnob} expressionSent=${app?.expressionSent} reverb=${app?.reverb} chorus=${app?.chorus}   [NA = not queryable in this build]",
            color = Color(0xFF9AA4AF), fontSize = 9.sp, fontFamily = mono)
        Text("VELOCITY from PSR-E343: last=${kb.last} min=${kb.min} med=${kb.median} max=${kb.max} n=${kb.count}",
            color = Color(0xFF7FDBFF), fontSize = 9.sp, fontFamily = mono)
        Text("VELOCITY sent to BASSMIDI: last=${r.get("fwdLast")} min=${r.get("fwdMin")} med=${r.get("fwdMed")} max=${r.get("fwdMax")} n=${r.get("fwdCount")}",
            color = Color(0xFF7FDBFF), fontSize = 9.sp, fontFamily = mono)
    }
}

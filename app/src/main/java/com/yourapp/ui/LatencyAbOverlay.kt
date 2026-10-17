package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BUFFER_LABELS = listOf("A: 2× BURST (baseline)", "B: 1.5× BURST", "C: 1× BURST", "D: ADAPTIVE")

/**
 * Non-modal RIGHT latency A/B panel: software stage statistics (observer-only), output-buffer A/B with
 * live XRUN counters, offline onset test of the selected RIGHT preset, and a report export.
 * Diagnostic only. Buffer mode is never saved: every launch starts at A (production behaviour).
 */
@Composable
fun LatencyAbOverlay(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    if (!ToolPanels.latency) return
    val mode by viewModel.bufferMode.collectAsState()
    var status by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var onset by remember { mutableStateOf("") }
    var liveStatus by remember { mutableStateOf("idle") }
    var liveReport by remember { mutableStateOf("") }
    var leanLog by remember { mutableStateOf(viewModel.leanNoteLog()) }
    var saved by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            status = try { viewModel.bufferStatusText() } catch (_: Exception) { status }
            report = try { viewModel.latencyReportText() } catch (_: Exception) { report }
            liveStatus = try { viewModel.liveOnsetStatusText() } catch (_: Exception) { liveStatus }
            if (liveStatus == "ready" && liveReport.isEmpty()) liveReport = try { viewModel.liveOnsetReportText() } catch (_: Exception) { "" }
            delay(700)
        }
    }
    val rows = report.lineSequence().filter { line ->
        line.isNotBlank() && listOf("section", "chord", "styleEvent", "scheduler").none { line.contains(it, ignoreCase = true) }
    }.toList()
    Column(modifier.width(720.dp).heightIn(max = 340.dp).background(Color(0xEE10151B), RoundedCornerShape(6.dp)).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RIGHT LATENCY A/B — diagnostic", color = Color(0xFFFFC857), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Chip("RESET STATS", Color(0xFF444C56)) { viewModel.latencyReset() }
            Chip("ONSET TEST (RIGHT, C4)", Color(0xFF3A5A8A)) { scope.launch { onset = viewModel.runOnsetTest() } }
            Chip("SAVE REPORT", Color(0xFF2E6B3A)) {
                scope.launch { saved = saveLatencyReport(context, viewModel.latencyReportText(), onset + "\n" + liveReport) ?: "save failed" }
            }
            Chip("CLOSE", Color(0xFF7A2B2B)) { ToolPanels.latency = false }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Text("OUTPUT BUFFER", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            BUFFER_LABELS.forEachIndexed { i, label ->
                Chip(label, if (mode == i) Color(0xFF1E8E3E) else Color(0xFF444C56)) { viewModel.setBufferMode(i) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            Text("LOG MODE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Chip("DIAGNOSTIC (per-note logs)", if (!leanLog) Color(0xFF1E8E3E) else Color(0xFF444C56)) { viewModel.setLeanNoteLog(false); leanLog = false }
            Chip("NORMAL (default)", if (leanLog) Color(0xFF1E8E3E) else Color(0xFF444C56)) { viewModel.setLeanNoteLog(true); leanLog = true }
            Text("LIVE vs ISOLATED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Chip("ARM", Color(0xFF3A5A8A)) { liveReport = ""; scope.launch { liveStatus = viewModel.armLiveOnset() } }
            Text(liveStatus, color = Color(0xFFFFE08A), fontSize = 9.sp)
        }
        Text(status.trim(), color = Color(0xFF7FDBFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        if (saved.isNotEmpty()) Text("saved: $saved", color = Color(0xFF4CD37B), fontSize = 9.sp)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (liveReport.isNotEmpty()) {
                Text(liveReport.trim(), color = Color(0xFF9BE7A0), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            if (onset.isNotEmpty()) {
                Text(onset.trim(), color = Color(0xFFFFE08A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            rows.forEach { Text(it, color = Color(0xFFCFD8DC), fontSize = 8.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.background(color, RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

private fun saveLatencyReport(context: Context, report: String, onset: String): String? = try {
    val name = "YamahaArranger_RightLatency_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YamahaArranger")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri == null) null else {
        context.contentResolver.openOutputStream(uri)?.use { it.write((report + "\n" + onset + "\n").toByteArray()) }
        "Download/YamahaArranger/$name"
    }
} catch (_: Exception) { null }

package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.media.MediaPlayer
import java.io.File
import androidx.compose.runtime.DisposableEffect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val InspectorBg = Color(0xFF101114)
private val InspectorPanel = Color(0xFF1B1D21)
private val InspectorText = Color(0xFFE7EAF0)
private val InspectorDim = Color(0xFF9CA3AF)
private val InspectorGood = Color(0xFF58C77A)
private val InspectorWarn = Color(0xFFFFB347)
private val InspectorBad = Color(0xFFFF6B6B)

@Composable
fun Sf2StyleInspectorDialog(
    onDismiss: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val exportScope = rememberCoroutineScope()
    var exportBusy by remember { mutableStateOf(false) }
    var auditionBank by remember { mutableStateOf("128") }
    var auditionPc by remember { mutableStateOf("1") }
    var auditionKey by remember { mutableStateOf("31") }
    var auditionVelocity by remember { mutableStateOf("110") }
    val auditionPlayer = remember { arrayOfNulls<MediaPlayer>(1) }
    val auditionFile = remember { arrayOfNulls<File>(1) }
    DisposableEffect(Unit) {
        onDispose { auditionPlayer[0]?.release(); auditionFile[0]?.delete() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = InspectorBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("SF2 / STYLE INSPECTOR", color = InspectorText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Diagnostic view • no changes to arranger playback", color = InspectorDim, fontSize = 9.sp)
                    }
                    val context = LocalContext.current
                    OutlinedButton(enabled = !exportBusy, onClick = {
                        exportBusy = true
                        exportScope.launch {
                            try {
                                val kitAudit = viewModel.drumKitAuditReport()
                                val fileName = withContext(Dispatchers.IO) {
                                    saveInspectorReport(context, state, kitAudit)
                                }
                                Toast.makeText(
                                    context,
                                    if (fileName != null) "Inspector disimpan: Downloads/YamahaArranger/$fileName"
                                    else "Gagal menyimpan inspector",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally { exportBusy = false }
                        }
                    }) { Text(if (exportBusy) "EXPORTING…" else "SAVE REPORT") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onDismiss) { Text("CLOSE") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !exportBusy, onClick = viewModel::armChordDiagnostic) { Text("CAPTURE CHORD (60s)") }
                    OutlinedButton(enabled = !exportBusy, onClick = viewModel::stopChordDiagnostic) { Text("END CAPTURE") }
                }
                Text("Play C → F → G → C, end capture, then STOP and SAVE REPORT.", color = InspectorDim, fontSize = 10.sp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !exportBusy, onClick = viewModel::diagnosticSoloStrings2) { Text("SOLO STRINGS2") }
                    Text("Unmutes ch14, mutes ch8–13/15. Play a full MainD section; restore mix with existing mute controls.",
                        color = InspectorDim, fontSize = 10.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(auditionBank, { auditionBank = it }, label = { Text("Bank") }, modifier = Modifier.width(82.dp), singleLine = true)
                    OutlinedTextField(auditionPc, { auditionPc = it }, label = { Text("Kit PC") }, modifier = Modifier.width(82.dp), singleLine = true)
                    OutlinedTextField(auditionKey, { auditionKey = it }, label = { Text("Key") }, modifier = Modifier.width(82.dp), singleLine = true)
                    OutlinedTextField(auditionVelocity, { auditionVelocity = it }, label = { Text("Velocity") }, modifier = Modifier.width(90.dp), singleLine = true)
                    val context = LocalContext.current
                    OutlinedButton(enabled = !exportBusy && !state.isPlaying, onClick = {
                        val bank = auditionBank.toIntOrNull()
                        val pc = auditionPc.toIntOrNull()
                        val key = auditionKey.toIntOrNull()
                        val velocity = auditionVelocity.toIntOrNull()
                        if (bank != null && pc != null && key != null && velocity != null) {
                            auditionPlayer[0]?.release(); auditionPlayer[0] = null
                            auditionFile[0]?.delete(); auditionFile[0] = null
                            exportBusy = true
                            exportScope.launch {
                                try {
                                    val wav = viewModel.diagnosticDrumWav(bank, pc, key, velocity)
                                    if (wav.isEmpty()) Toast.makeText(context, "Audition unavailable: STOP, load style/fonts, use an actual event and a kit with a valid zone", Toast.LENGTH_LONG).show()
                                    else {
                                        val file = withContext(Dispatchers.IO) {
                                            File.createTempFile("drum-audition-", ".wav", context.cacheDir).also { it.writeBytes(wav) }
                                        }
                                        auditionFile[0] = file
                                        val player = MediaPlayer()
                                        auditionPlayer[0] = player
                                        player.setDataSource(file.absolutePath)
                                        player.setOnCompletionListener { it.release(); auditionPlayer[0] = null; file.delete(); auditionFile[0] = null }
                                        player.prepare(); player.start()
                                    }
                                } catch (error: Exception) {
                                    auditionPlayer[0]?.release(); auditionPlayer[0] = null
                                    auditionFile[0]?.delete(); auditionFile[0] = null
                                    Toast.makeText(context, "Audition failed: ${error.message}", Toast.LENGTH_LONG).show()
                                } finally { exportBusy = false }
                            }
                        }
                    }) { Text("AUDITION (STOP)") }
                    Text("Separate 2s sample; default controllers, no kit installed.", color = InspectorDim, fontSize = 10.sp)
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("SF2") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("STYLE") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("RESOLVER") })
                }

                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> Sf2Inspector(state, query, { query = it }, viewModel::refreshSoundFontList)
                    1 -> StyleInspector(state)
                    else -> ResolverInspector(state)
                }
            }
        }
    }
}

@Composable
private fun Sf2Inspector(
    state: MainUiState,
    query: String,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Text("Managed SF2 files: ${state.availableSoundFonts.size}", color = InspectorText, fontWeight = FontWeight.Bold)
    Text(
        if (state.sf2ScanInProgress) {
            "Scanning all SF2 files… ${state.sf2Reports.size}/${state.availableSoundFonts.size}"
        } else {
            "Inspected=${state.sf2Reports.size} • Loaded-engine presets=${state.sf2Presets.size}"
        },
        color = InspectorDim,
        fontSize = 11.sp
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Filter file / preset / bank / program") }
        )
        Button(enabled = !state.sf2ScanInProgress, onClick = onRefresh) {
            Text(if (state.sf2ScanInProgress) "SCANNING…" else "SCAN ALL SF2")
        }
    }
    Spacer(Modifier.height(6.dp))

    val reports = state.sf2Reports
    if (reports.isEmpty()) {
        if (state.sf2ScanInProgress) {
            Text("Reading SF2 metadata without loading sample audio into RAM…", color = InspectorDim, fontSize = 11.sp)
        } else {
            Text("Press SCAN ALL SF2 to inspect every managed .sf2 file.", color = InspectorDim, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            val filtered = state.sf2Presets.filter {
                query.isBlank() || it.name.contains(query, true) ||
                    it.bank.toString().contains(query) || it.program.toString().contains(query)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered) { p ->
                    val bankText = if (p.bank >= 128) "packed=${p.bank} / ${p.bank / 128}:${p.bank % 128}" else "${p.bank} / ${p.bank}:0"
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(p.role, color = InspectorDim, modifier = Modifier.width(70.dp), fontSize = 11.sp)
                        Text(bankText, color = InspectorText, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                        Text(p.program.toString(), color = InspectorText, modifier = Modifier.width(45.dp), fontSize = 11.sp)
                        Text(p.name.ifBlank { "(unnamed)" }, color = InspectorText, fontSize = 11.sp)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        reports.forEach { report ->
            val matching = report.presets.filter {
                query.isBlank() ||
                    report.fileName.contains(query, true) ||
                    it.name.contains(query, true) ||
                    it.bank.toString().contains(query) ||
                    it.program.toString().contains(query)
            }
            if (query.isBlank() || matching.isNotEmpty()) {
                item(key = "header:${report.fileName}") {
                    Column(Modifier.fillMaxWidth().background(InspectorPanel).padding(9.dp)) {
                        Text(
                            "${if (report.validSf2) "✓" else "✗"} ${report.fileName}",
                            color = if (report.validSf2) InspectorGood else InspectorBad,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            "${report.fileSize / 1024.0 / 1024.0} MB • presets=${report.presets.size} • instruments=${report.instrumentCount} • samples=${report.sampleCount}",
                            color = InspectorDim,
                            fontSize = 10.sp
                        )
                        report.soundFontName?.takeIf { it.isNotBlank() }?.let {
                            Text("Name: $it", color = InspectorText, fontSize = 10.sp)
                        }
                        report.engine?.takeIf { it.isNotBlank() }?.let {
                            Text("Engine: $it", color = InspectorDim, fontSize = 10.sp)
                        }
                    }
                }
                items(
                    items = matching,
                    key = { "${report.fileName}:${it.bank}:${it.program}:${it.name}" }
                ) { p ->
                    val bankText = if (p.bank >= 128) "${p.bank / 128}:${p.bank % 128} (packed=${p.bank})" else "${p.bank}:0"
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 5.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(bankText, color = InspectorText, modifier = Modifier.width(105.dp), fontSize = 10.sp)
                        Text(p.program.toString(), color = InspectorText, modifier = Modifier.width(35.dp), fontSize = 10.sp)
                        Text(p.name.ifBlank { "(unnamed)" }, color = InspectorText, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleInspector(state: MainUiState) {
    Text("STYLE: ${state.styleName}", color = InspectorText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text("Active section=${state.activeSection} • tempo=${state.tempoBpm} BPM", color = InspectorDim, fontSize = 11.sp)
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth().background(InspectorPanel).padding(7.dp)) {
        Text("CH", color = InspectorDim, modifier = Modifier.width(38.dp), fontSize = 10.sp)
        Text("VOICE", color = InspectorDim, modifier = Modifier.width(145.dp), fontSize = 10.sp)
        Text("YAMAHA BANK", color = InspectorDim, modifier = Modifier.width(110.dp), fontSize = 10.sp)
        Text("PC", color = InspectorDim, modifier = Modifier.width(45.dp), fontSize = 10.sp)
        Text("MIX", color = InspectorDim, fontSize = 10.sp)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.voiceAssignments) { v ->
            val msb = if (v.bank >= 128) v.bank / 128 else v.bank
            val lsb = if (v.bank >= 128) v.bank % 128 else 0
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(v.channel.toString(), color = InspectorText, modifier = Modifier.width(38.dp), fontSize = 11.sp)
                Text(v.displayName(), color = InspectorText, modifier = Modifier.width(145.dp), fontSize = 11.sp)
                Text("${msb}:${lsb} (${v.bank})", color = InspectorText, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                Text(v.program.toString(), color = InspectorText, modifier = Modifier.width(45.dp), fontSize = 11.sp)
                Text("${v.styleVolume}/${v.stylePan}/${v.styleExpression}", color = if (v.styleMuted) InspectorBad else InspectorDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ResolverInspector(state: MainUiState) {
    Text("STYLE → SF2 → BASSMIDI", color = InspectorText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text("Exact-match diagnostic. It does not modify the selected voice.", color = InspectorDim, fontSize = 11.sp)
    Spacer(Modifier.height(8.dp))

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.voiceAssignments) { v ->
            val exact = state.sf2Presets.firstOrNull { it.bank == v.bank && it.program == v.program }
            val packedMsb = if (v.bank >= 128) v.bank / 128 else v.bank
            val packedLsb = if (v.bank >= 128) v.bank % 128 else 0
            val sameProgram = state.sf2Presets.filter { it.program == v.program }
            val closest = sameProgram.firstOrNull()

            Column(Modifier.fillMaxWidth().background(InspectorPanel).padding(9.dp)) {
                Text("Ch${v.channel} • ${v.displayName()}", color = InspectorText, fontWeight = FontWeight.Bold)
                Text("REQUEST  Yamaha bank=${packedMsb}:${packedLsb} packed=${v.bank} PC=${v.program}", color = InspectorDim, fontSize = 10.sp)
                if (exact != null) {
                    Text("✓ EXACT SF2 MATCH → bank=${exact.bank} PC=${exact.program} '${exact.name}'", color = InspectorGood, fontSize = 11.sp)
                } else if (closest != null) {
                    Text("⚠ NO EXACT MATCH → same PC found at bank=${closest.bank} '${closest.name}'", color = InspectorWarn, fontSize = 11.sp)
                } else {
                    Text("✗ NO SF2 PRESET MATCH for this bank/program", color = InspectorBad, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

private fun saveInspectorReport(context: Context, state: MainUiState, kitAudit: String): String? {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "YamahaArranger_Inspector_$stamp.txt"

    val report = buildString {
        appendLine("YAMAHA ARRANGER — SF2 / STYLE INSPECTOR")
        appendLine("Generated: $stamp")
        appendLine()
        appendLine("=== NOTE ZONES / ALL DEDICATED KITS PER SECTION ===")
        appendLine(kitAudit)
        appendLine()
        appendLine("=== SF2 ===")
        appendLine("Loaded SF2: ${state.soundFontName}")
        appendLine("Preset count: ${state.sf2Presets.size}")
        appendLine("Managed SF2 files: ${state.availableSoundFonts.size}")
        state.availableSoundFonts.forEach { appendLine("  FILE: $it") }
        appendLine()
        appendLine("=== ALL MANAGED SF2 INVENTORY ===")
        appendLine("Inspected files: ${state.sf2Reports.size}")
        state.sf2Reports.forEach { report ->
            appendLine()
            appendLine("FILE: ${report.fileName}")
            appendLine("  sizeBytes=${report.fileSize}")
            appendLine("  validSf2=${report.validSf2}")
            appendLine("  name='${report.soundFontName.orEmpty()}'")
            appendLine("  engine='${report.engine.orEmpty()}'")
            appendLine("  instruments=${report.instrumentCount} samples=${report.sampleCount} presets=${report.presets.size}")
            report.presets.forEach { p ->
                appendLine("  PRESET bank=${p.bank} pc=${p.program} name='${p.name}'")
            }
        }
        appendLine()
        appendLine("=== LOADED ENGINE SF2 PRESETS ===")
        state.sf2Presets.forEach { p ->
            val msb = if (p.bank >= 128) p.bank / 128 else p.bank
            val lsb = if (p.bank >= 128) p.bank % 128 else 0
            appendLine("PRESET role=${p.role} bank=$msb:$lsb packed=${p.bank} pc=${p.program} name='${p.name}'")
        }

        appendLine()
        appendLine("=== STYLE ===")
        appendLine("Style: ${state.styleName}")
        appendLine("Active section: ${state.activeSection}")
        appendLine("Tempo: ${state.tempoBpm}")
        state.voiceAssignments.forEach { v ->
            val msb = if (v.bank >= 128) v.bank / 128 else v.bank
            val lsb = if (v.bank >= 128) v.bank % 128 else 0
            appendLine("VOICE ch=${v.channel} name='${v.displayName()}' bank=$msb:$lsb packed=${v.bank} pc=${v.program} volume=${v.styleVolume} pan=${v.stylePan} expression=${v.styleExpression} muted=${v.styleMuted}")
        }

        appendLine()
        appendLine("=== RESOLVER ===")
        state.voiceAssignments.forEach { v ->
            val exact = state.sf2Presets.firstOrNull { it.bank == v.bank && it.program == v.program }
            val sameProgram = state.sf2Presets.firstOrNull { it.program == v.program }
            val msb = if (v.bank >= 128) v.bank / 128 else v.bank
            val lsb = if (v.bank >= 128) v.bank % 128 else 0
            appendLine("REQUEST ch=${v.channel} name='${v.displayName()}' bank=$msb:$lsb packed=${v.bank} pc=${v.program}")
            when {
                exact != null -> appendLine("  EXACT MATCH bank=${exact.bank} pc=${exact.program} name='${exact.name}'")
                sameProgram != null -> appendLine("  NO EXACT MATCH; SAME PC bank=${sameProgram.bank} name='${sameProgram.name}'")
                else -> appendLine("  NO SF2 PRESET MATCH")
            }
        }
    }

    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YamahaArranger")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
            writer?.write(report)
        }
        fileName
    } catch (_: Exception) {
        null
    }
}


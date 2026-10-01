package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yourapp.audio.ManagedSf2Audition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local diagnostic UI state only; selection never activates an arranger font or preset. */
@Composable
fun ManagedSf2AuditionDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    var fonts by remember { mutableStateOf(emptyList<ManagedSf2Audition.Font>()) }
    var selected by remember { mutableStateOf<ManagedSf2Audition.Font?>(null) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("STOP, scan fingerprints, then explicitly select a managed SF2.") }
    var bank by remember { mutableStateOf("128") }
    var pc by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var velocity by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("MANAGED SF2 AUDITION") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diagnostic only. One note on an isolated decode stream. No candidate winner or production mapping.")
                OutlinedButton(enabled = !busy && !state.isPlaying, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            fonts = viewModel.managedAuditionFonts(); selected = null
                            status = "${fonts.size} fingerprints verified. Select a source SF2."
                        } catch (e: Exception) { status = e.message ?: "Scan failed" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "WORKING…" else "SCAN FINGERPRINTS") }
                Box {
                    OutlinedButton(enabled = !busy && !state.isPlaying && fonts.isNotEmpty(), onClick = { menu = true }) {
                        Text(selected?.name ?: "SELECT MANAGED SF2")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        fonts.forEach { font ->
                            DropdownMenuItem(text = { Text("${font.name}\nSHA256 ${font.sha256.take(16)}…") },
                                onClick = { selected = font; menu = false })
                        }
                    }
                }
                selected?.let { Text("SHA256 ${it.sha256}") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(bank, { bank = it }, enabled = !busy, singleLine = true,
                        label = { Text("SF2 bank") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(pc, { pc = it }, enabled = !busy, singleLine = true,
                        label = { Text("Raw PC (0–127)") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(key, { key = it }, enabled = !busy, singleLine = true,
                        label = { Text("Source key") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(velocity, { velocity = it }, enabled = !busy, singleLine = true,
                        label = { Text("Velocity (1–127)") }, modifier = Modifier.weight(1f))
                }
                Text("Cross-key allowed. SF2 fingerprint and every eligible layer are verified before rendering. WAV + evidence sidecar saved together in ZIP.")
                Text(status)
            }
        }, confirmButton = {
            Button(enabled = !busy && !state.isPlaying && selected != null, onClick = {
                val font = selected ?: return@Button
                val b = bank.toIntOrNull(); val p = pc.toIntOrNull(); val k = key.toIntOrNull(); val v = velocity.toIntOrNull()
                if (b == null || p == null || k == null || v == null) { status = "Enter numeric bank/rawPC/sourceKey/velocity"; return@Button }
                val request = ManagedSf2Audition.Request(font,b,p,k,v)
                busy = true
                scope.launch {
                    try {
                        val result = viewModel.managedSf2Audition(request, context.cacheDir)
                        val name = withContext(Dispatchers.IO) { saveManagedAudition(context, request, result) }
                        status = "Saved Downloads/YamahaArranger/$name (WAV + sidecar)"
                    } catch (e: Exception) { status = e.message ?: "Audition failed" }
                    finally { busy = false }
                }
            }) { Text("SAVE AUDITION WAV + SIDECAR") }
        }, dismissButton = { OutlinedButton(enabled = !busy, onClick = onDismiss) { Text("CLOSE") } })
}

/** Publish one atomic bundle; failed/pending exports are removed. No MIDI or audio calls. */
private fun saveManagedAudition(context: Context, request: ManagedSf2Audition.Request, result: ManagedSf2Audition.Result): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val stem = "YamahaArranger_SF2Audition_${request.font.sha256.take(12)}_B${request.bank}_PC${request.rawPc}_K${request.sourceKey}_V${request.velocity}_${stamp}_${UUID.randomUUID().toString().take(8)}"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.zip")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YamahaArranger")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create audition bundle")
    try {
        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open audition output")
        output.use { result.zip(it, stem) }
        check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) }, null,null) == 1) { "Cannot publish audition bundle" }
        return "$stem.zip"
    } catch (e: Exception) {
        context.contentResolver.delete(uri,null,null);throw e
    }
}

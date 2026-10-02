package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun DrumShadowDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    var busy by remember {mutableStateOf(false)}
    var evidence by remember {mutableStateOf("")}
    var approximation by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("STOP after warming the current style/SF2. Export prepares a shadow plan; playback never uses it.")}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Shadow Drum Resolver")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Stage 1–2 only. Original raw style demand, current STOP production snapshot and proposed decisions are separate. No synth/MIDI events or routing changes.")
            Text("Blank evidence = no semantic authority; UNKNOWN/ABSTAIN is expected. Names, coverage and WAV presence never declare identity.")
            OutlinedTextField(evidence,{evidence=it},enabled=!busy,label={Text("Optional explicit evidence catalog")},modifier=Modifier.fillMaxWidth().heightIn(min=90.dp,max=180.dp))
            Text("One claim per line: MSB|LSB|rawPC|targetKey|SF2_SHA256|bank|PC|sourceKey|class|confidence|provenance. Imported claims cannot bypass engineering gates.")
            Row {Checkbox(approximation,{approximation=it},enabled=!busy);Text("Consider APPROXIMATION in shadow only")}
            Text(status)
        }
    },confirmButton={Button(enabled=!busy && !state.isPlaying && !state.sf2ScanInProgress,onClick={
        busy=true
        scope.launch {
            try {
                val report=viewModel.exportShadowDrum(evidence,approximation)
                val name=withContext(Dispatchers.IO) {
                    val name="YamahaArranger_DrumShadow_${UUID.randomUUID()}.txt"
                    val values=ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/YamahaArranger");put(MediaStore.MediaColumns.IS_PENDING,1)}
                    val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create shadow export")
                    try {
                        val stream=context.contentResolver.openOutputStream(uri) ?: error("Cannot open shadow export")
                        stream.use {it.write(report.toByteArray(Charsets.UTF_8))}
                        check(context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1)
                        name
                    } catch(e:Exception) {context.contentResolver.delete(uri,null,null);throw e}
                }
                status="Saved Downloads/YamahaArranger/$name (${report.toByteArray().size} bytes)"
            } catch(e:Exception) {status=e.message ?: "Shadow export failed"}
            finally {busy=false}
        }
    }) {Text(if(busy)"PREPARING…" else "EXPORT")}},dismissButton={OutlinedButton(enabled=!busy,onClick=onDismiss){Text("CLOSE")}})
}

package com.yourapp.yamahaarranger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen SONG page (MIDI Song Player V1A), opened from the SONG tab. Closing it does not stop the Song. */
@Composable
fun SongDialog(viewModel: MainViewModel, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openSong) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { uri -> uri?.let(viewModel::recorderSave) }
    var rec by remember { mutableStateOf(viewModel.recorderStatus()) }
    var recNote by remember { mutableStateOf(viewModel.recorderNotice()) }
    var recMode by remember { mutableStateOf(viewModel.recorderMode == com.yourapp.yamahaarranger.song.RecordMode.FULL) }
    var recGm by remember { mutableStateOf(viewModel.recorderFlavor == com.yourapp.yamahaarranger.song.ExportFlavor.GM) }
    var status by remember { mutableStateOf(viewModel.songStatus()) }
    var notice by remember { mutableStateOf(viewModel.songNotice()) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    val routing by viewModel.songRouting.collectAsState()
    var voices by remember { mutableStateOf(emptyList<com.yourapp.yamahaarranger.song.SongVoice>()) }
    var live by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { status = viewModel.songStatus(); notice = viewModel.songNotice(); now = System.nanoTime()
        rec = viewModel.recorderStatus(); recNote = viewModel.recorderNotice(); delay(100) } }
    LaunchedEffect(Unit) { while (true) { live = withContext(Dispatchers.Default) { viewModel.songChannelReport() }; delay(500) } }
    val mixer = SongMixerActions(
        onVoice = { ch, v -> viewModel.songSetVoice(ch, v); status = viewModel.songStatus(); notice = viewModel.songNotice() },
        onVolume = { ch, v -> viewModel.songSetVolume(ch, v) }, onPan = { ch, v -> viewModel.songSetPan(ch, v) },
        onSustain = { ch, m -> viewModel.songSetSustain(ch, m) }, onMute = { viewModel.songToggleMute(it) },
        onSolo = { viewModel.songToggleSolo(it) }, onReset = { viewModel.songResetChannel(it) },
    )
    fun refresh() { status = viewModel.songStatus(); notice = viewModel.songNotice() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        SongPanel(
            status = status, routing = routing, notice = notice, nowNs = now,
            onOpen = { picker.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "*/*")) },
            onPlayPause = { viewModel.songPlayPause(); refresh() }, onStop = { viewModel.songStop(); refresh() },
            onPrevBar = { viewModel.songSeekBars(-1); refresh() }, onNextBar = { viewModel.songSeekBars(1); refresh() },
            onSeek = { viewModel.songSeekFraction(it); refresh() }, onTempoPercent = { viewModel.songSetTempoPercent(it); refresh() },
            onRouting = { viewModel.songSetRouting(it); refresh() }, onMute = { viewModel.songToggleMute(it); refresh() }, onClose = onClose,
            voices = voices, mixer = mixer, liveReport = live,
            recorder = RecorderUi(true, rec.recording, rec.elapsedUs, rec.events, rec.hasTake, rec.takeInfo, recNote, fullAvailable = true,
                full = if (rec.recording) rec.mode == com.yourapp.yamahaarranger.song.RecordMode.FULL else recMode,
                gm = recGm),
            onRecordMode = { f -> viewModel.recorderSetMode(if (f) com.yourapp.yamahaarranger.song.RecordMode.FULL else com.yourapp.yamahaarranger.song.RecordMode.KEYBOARD); recMode = viewModel.recorderMode == com.yourapp.yamahaarranger.song.RecordMode.FULL },
            onExportGm = { g -> viewModel.recorderSetFlavor(if (g) com.yourapp.yamahaarranger.song.ExportFlavor.GM else com.yourapp.yamahaarranger.song.ExportFlavor.NATIVE); recGm = g },
            onRecord = { viewModel.recorderToggle(); rec = viewModel.recorderStatus(); recNote = viewModel.recorderNotice() },
            onSaveTake = { saver.launch(viewModel.recorderSuggestedName()) },
            onLoadTake = { viewModel.recorderLoadIntoPlayer(); refresh(); recNote = viewModel.recorderNotice() },
            onPickerOpen = { scope.launch { voices = withContext(Dispatchers.Default) { viewModel.songVoices() } } },
        )
    }
}

/**
 * MIDI RECORDER: a small floating "● REC mm:ss  STOP" badge on the main screen while a take is recording (the SONG page may be closed
 * while the player uses the on-screen keyboard). It is a Popup, so it never moves the main screen's layout. Nothing is shown otherwise.
 */
@Composable
fun RecorderBadge(viewModel: MainViewModel) {
    var rec by remember { mutableStateOf(viewModel.recorderStatus()) }
    LaunchedEffect(Unit) { while (true) { rec = viewModel.recorderStatus(); delay(250) } }
    if (!rec.recording) return
    androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.TopCenter) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.padding(top = 6.dp)
                .background(androidx.compose.ui.graphics.Color(0xE68E2B2B), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .clickable { viewModel.recorderToggle(); rec = viewModel.recorderStatus() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            val s = rec.elapsedUs / 1_000_000
            androidx.compose.material3.Text("● REC %d:%02d   ■ STOP".format(s / 60, s % 60), color = androidx.compose.ui.graphics.Color.White,
                fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

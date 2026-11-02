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
import kotlinx.coroutines.delay

/** Full-screen SONG page (MIDI Song Player V1A), opened from the SONG tab. Closing it does not stop the Song. */
@Composable
fun SongDialog(viewModel: MainViewModel, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openSong) }
    var status by remember { mutableStateOf(viewModel.songStatus()) }
    var notice by remember { mutableStateOf(viewModel.songNotice()) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    val routing by viewModel.songRouting.collectAsState()
    LaunchedEffect(Unit) { while (true) { status = viewModel.songStatus(); notice = viewModel.songNotice(); now = System.nanoTime(); delay(100) } }
    fun refresh() { status = viewModel.songStatus(); notice = viewModel.songNotice() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        SongPanel(
            status = status, routing = routing, notice = notice, nowNs = now,
            onOpen = { picker.launch(arrayOf("audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "*/*")) },
            onPlayPause = { viewModel.songPlayPause(); refresh() }, onStop = { viewModel.songStop(); refresh() },
            onPrevBar = { viewModel.songSeekBars(-1); refresh() }, onNextBar = { viewModel.songSeekBars(1); refresh() },
            onSeek = { viewModel.songSeekFraction(it); refresh() }, onTempoPercent = { viewModel.songSetTempoPercent(it); refresh() },
            onRouting = { viewModel.songSetRouting(it); refresh() }, onMute = { viewModel.songToggleMute(it); refresh() }, onClose = onClose,
        )
    }
}

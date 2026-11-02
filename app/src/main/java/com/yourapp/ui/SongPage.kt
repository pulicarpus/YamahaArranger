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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen SONG page (MIDI Song Player V1A), opened from the SONG tab. Closing it does not stop the Song. */
@Composable
fun SongDialog(viewModel: MainViewModel, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::openSong) }
    var status by remember { mutableStateOf(viewModel.songStatus()) }
    var notice by remember { mutableStateOf(viewModel.songNotice()) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    val routing by viewModel.songRouting.collectAsState()
    var voices by remember { mutableStateOf(emptyList<com.yourapp.yamahaarranger.song.SongVoice>()) }
    var live by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { status = viewModel.songStatus(); notice = viewModel.songNotice(); now = System.nanoTime(); delay(100) } }
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
            onPickerOpen = { scope.launch { voices = withContext(Dispatchers.Default) { viewModel.songVoices() } } },
        )
    }
}

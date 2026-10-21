package com.yourapp.yamahaarranger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yourapp.yamahaarranger.audio.MetronomeMeters
import kotlinx.coroutines.delay

/** Stateful wrapper. The beat indicator polls ONE atomic at ~30 Hz only while the panel is open; it is a display, never a clock. */
@Composable
fun MetronomeOverlay(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    if (!ToolPanels.metronome) return
    val state by viewModel.metronomeState.collectAsState()
    var pulse by remember { mutableStateOf(-1) }
    var level by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var lastSeq = viewModel.metronomeBeat() shr 2
        while (true) {
            viewModel.metronomeRefresh()
            val packed = viewModel.metronomeBeat()
            val seq = packed shr 2
            if (seq != lastSeq) {
                lastSeq = seq
                level = (packed and 3L).toInt()
                val meter = viewModel.metronomeState.value.let { if (it.mode == com.yourapp.yamahaarranger.audio.MetronomeMode.FOLLOW) it.styleMeter ?: it.meter else it.meter }
                pulse = if (level == MetronomeMeters.STRONG) 0 else (pulse + 1).coerceAtMost(meter.pulsesPerBar - 1)
            }
            delay(33)
        }
    }
    MetronomePanel(state, pulse, level,
        onEnabled = viewModel::metronomeSetEnabled, onMode = viewModel::metronomeSetMode, onRunning = viewModel::metronomeSetRunning,
        onMeter = viewModel::metronomeSetMeter, onVolume = viewModel::metronomeSetVolume, onClose = { ToolPanels.metronome = false }, modifier = modifier)
}

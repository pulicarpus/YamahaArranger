package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yourapp.yamahaarranger.audio.SplitPoint
import kotlinx.coroutines.delay

/** Visibility of the Split dialog (opened from Live Control's SPLIT key and the on-screen keyboard). */
object SplitHost {
    var visible by mutableStateOf(false)
    fun open() { visible = true }
    fun close() { visible = false }
}

private val SpPanel = Color(0xFF12161B)
private val SpKey = Color(0xFF1B222A)
private val SpText = Color(0xFFE6EAEE)
private val SpDim = Color(0xFF9AA3AD)
private val SpAmber = Color(0xFFFFB347)
private val SpBlue = Color(0xFF58B8FF)

@Composable
private fun SpButton(text: String, enabled: Boolean = true, color: Color = SpBlue, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 52.dp).widthIn(min = 64.dp).background(SpKey, RoundedCornerShape(6.dp))
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) color else SpDim.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
    }
}

/**
 * SPLIT POINT: ± per semitone, SET BY KEY (next key from the E343 or the screen keyboard), RESET, plus the MIDI IN channel report and filter.
 * A key AT the split belongs to the LEFT area. A new split applies from the next NOTE_ON; held notes finish through the owner they started with.
 */
@Composable
fun SplitDialogHost(vm: MainViewModel) {
    if (!SplitHost.visible) return
    val split by vm.splitNote.collectAsState()
    val armed by vm.splitArmed.collectAsState()
    val extra by vm.extraInputChannels.collectAsState()
    var report by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { report = vm.midiInReport(); delay(500) } }
    Dialog(onDismissRequest = { vm.cancelSplitByKey(); SplitHost.close() }) {
        Column(Modifier.background(SpPanel, RoundedCornerShape(10.dp)).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SPLIT POINT", color = SpAmber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${SplitPoint.name(split)}   (note $split)", color = SpText, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Text(SplitPoint.describe(split), color = SpDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SpButton("−12", split - 12 >= SplitPoint.MIN) { vm.nudgeSplit(-12) }
                SpButton("−", split > SplitPoint.MIN) { vm.nudgeSplit(-1) }
                SpButton("+", split < SplitPoint.MAX) { vm.nudgeSplit(1) }
                SpButton("+12", split + 12 <= SplitPoint.MAX) { vm.nudgeSplit(12) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (armed) SpButton("PRESS A KEY… (CANCEL)", color = SpAmber) { vm.cancelSplitByKey() } else SpButton("SET BY KEY") { vm.armSplitByKey() }
                SpButton("RESET (${SplitPoint.name(SplitPoint.DEFAULT)})", split != SplitPoint.DEFAULT) { vm.resetSplit() }
            }
            Text("A new split applies from the next key press; notes already held finish normally and Style playback is not interrupted. Master Transpose and MIDI OUT octave correction are not affected.", color = SpDim, fontSize = 11.sp)

            Text("MIDI IN (PSR-E343)", color = SpAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(report.ifEmpty { "…" }, color = SpText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("Channel 1 always plays. If a keyboard zone arrives on another channel and says IGNORED above, switch that channel on to play it as keyboard (do not enable a channel that repeats the same keys, e.g. Dual).", color = SpDim, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (ch in 1..7) {
                    val on = (extra shr ch) and 1 != 0
                    SpButton("CH${ch + 1}${if (on) " ✓" else ""}", true, if (on) Color(0xFF3DDC84) else SpDim) { vm.toggleInputChannel(ch) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpButton("CLEAR REPORT", color = SpDim) { vm.resetMidiInReport() }
                SpButton("CLOSE") { vm.cancelSplitByKey(); SplitHost.close() }
            }
        }
    }
}

package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.audio.MetronomeMeter
import com.yourapp.yamahaarranger.audio.MetronomeMeters
import com.yourapp.yamahaarranger.audio.MetronomeMode
import com.yourapp.yamahaarranger.audio.MetronomeUiState

private val MnBg = Color(0xF010151B)
private val MnLine = Color(0xFF2A333D)
private val MnText = Color(0xFFE6EAEE)
private val MnDim = Color(0xFF9AA3AD)
private val MnOn = Color(0xFF58B8FF)
private val MnStrong = Color(0xFFFFB347)
private val MnMedium = Color(0xFF7FD6A0)
private val MnWeak = Color(0xFF58B8FF)
private val MnOff = Color(0xFF2A333D)

/** Stateless metronome panel (also rendered by the desktop preview). `activePulse` < 0 = no pulse lit. */
@Composable
fun MetronomePanel(
    state: MetronomeUiState, activePulse: Int, activeLevel: Int,
    onEnabled: (Boolean) -> Unit, onMode: (MetronomeMode) -> Unit, onRunning: (Boolean) -> Unit,
    onMeter: (MetronomeMeter) -> Unit, onVolume: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier
) {
    val follow = state.mode == MetronomeMode.FOLLOW
    val meter = if (follow) (state.styleMeter ?: state.meter) else state.meter
    Column(modifier.width(520.dp).background(MnBg, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("METRONOME", color = MnStrong, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Chip(if (state.enabled) "ON" else "OFF", state.enabled) { onEnabled(!state.enabled) }
            Spacer(Modifier.weight(1f))
            PulseDots(meter, if (state.enabled) activePulse else -1, activeLevel)
            Spacer(Modifier.weight(1f))
            Chip("CLOSE", false, onClick = onClose)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MODE", color = MnDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(54.dp))
            Chip("STANDALONE", !follow) { onMode(MetronomeMode.STANDALONE) }
            Chip("FOLLOW STYLE", follow) { onMode(MetronomeMode.FOLLOW) }
            Spacer(Modifier.weight(1f))
            if (!follow) Chip(if (state.standaloneRunning) "STOP" else "START", state.standaloneRunning, enabled = state.enabled) { onRunning(!state.standaloneRunning) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TIME", color = MnDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(54.dp))
            MetronomeMeters.OPTIONS.forEach { m -> Chip(m.label, !follow && state.meter == m, enabled = !follow) { onMeter(m) } }
            Spacer(Modifier.weight(1f))
            if (follow) Text("STYLE " + (state.styleMeter?.label ?: "—") + (if (state.styleRunning) "" else "  (Style stopped: no click)"), color = MnDim, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VOLUME", color = MnDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(54.dp))
            Slider(value = state.volumePermille / 1000f, onValueChange = { onVolume((it * 1000).toInt()) }, modifier = Modifier.weight(1f).height(28.dp))
            Text("${state.volumePermille / 10}%", color = MnText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
        }
        Text("TEMPO ♩ = ${state.tempoBpm}" + (if (follow) "  ·  follows the Style clock and every tempo change" else "  ·  follows the main YA tempo") +
            (if (meter.denominator == 8) "  ·  ${meter.label}: ${meter.numerator} eighth pulses, groups of 3" else ""),
            color = MnDim, fontSize = 10.sp)
        Text("Internal click only. Nothing is sent to MIDI OUT; Style, ACMP, RIGHT/LEFT and note ownership are not touched.", color = MnDim, fontSize = 9.sp)
    }
}

@Composable
private fun PulseDots(meter: MetronomeMeter, active: Int, level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until meter.pulsesPerBar) {
            val lv = MetronomeMeters.levelFor(i, meter.numerator, meter.denominator)
            val lit = i == active
            val base = when (lv) { MetronomeMeters.STRONG -> MnStrong; MetronomeMeters.MEDIUM -> MnMedium; else -> MnWeak }
            val size = if (lv == MetronomeMeters.STRONG) 16.dp else if (lv == MetronomeMeters.MEDIUM) 13.dp else 11.dp
            Box(Modifier.size(size).background(if (lit) base else MnOff, CircleShape))
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.height(32.dp).background(if (!enabled) Color(0xFF1A2027) else if (on) MnOn.copy(alpha = 0.30f) else Color(0xFF1B222A), RoundedCornerShape(5.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (!enabled) Color(0xFF59626C) else if (on) MnText else MnDim, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

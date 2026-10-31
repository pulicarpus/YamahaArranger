package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.audio.mixerFaderValue
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Mixer channel strips (concept: tall console strips side by side; own implementation, no third-party assets).
 * The indicator beside each fader is a NOTE / MIDI OUT ACTIVITY meter, never an audio level — see [MeterKind]. Only [MixerMasterStrip] shows a real audio level.
 */
enum class MeterKind(val title: String, val caption: String) {
    /** Last NOTE_ON velocity handed to the internal engine for this part, decaying. NOT an audio measurement. */
    NOTES("NOTE ACTIVITY", "note velocity sent to the internal sound engine (not an audio level)"),
    /** NOTE_ON actually written to the external module. NOT an audio measurement of the PSR-E343. */
    MIDI_OUT("MIDI OUT ACTIVITY", "NOTE ON messages written to the keyboard (not an audio measurement)")
}

private val MxBlack = Color(0xFF0B0D10)
private val MxPanel = Color(0xFF191D22)
private val MxPanelHi = Color(0xFF242A31)
private val MxDim = Color(0xFF8B96A3)
private val MxRed = Color(0xFFE5484D)
private val MxAmber = Color(0xFFFFB347)

/** One shared ~30 Hz time source for every meter; each meter reads it only inside its draw scope, so a tick redraws the meters but recomposes nothing. */
@Composable
fun rememberMeterClock(): State<Long> {
    val clock = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { clock.longValue = System.nanoTime(); delay(33) } }
    return clock
}

/** Real master output level (0..1, peak of the last audio block) with a short UI-side fall, polled at the meter rate. */
@Composable
fun rememberMasterLevel(read: () -> Float): State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    val reader by rememberUpdatedState(read)
    LaunchedEffect(Unit) {
        while (true) {
            val peak = reader().coerceIn(0f, 1f)
            level.floatValue = if (peak >= level.floatValue) peak else level.floatValue * 0.82f
            delay(33)
        }
    }
    return level
}

@Composable
private fun SegmentMeter(modifier: Modifier, clock: State<Long>, level: (Long) -> Float, tint: Color, dim: Boolean) {
    Canvas(modifier) {
        val l = level(clock.value).coerceIn(0f, 1f)
        val segments = 24
        val gap = 1.5.dp.toPx()
        val h = (size.height - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val lit = l > (i + 0.5f) / segments
            val top = size.height - (i + 1) * h - i * gap
            val color = when {
                !lit -> MxPanelHi
                i >= segments - 3 -> MxRed
                i >= segments - 8 -> MxAmber
                else -> tint
            }
            drawRoundRect(if (dim && lit) color.copy(alpha = 0.45f) else color, Offset(0f, top), Size(size.width, h), CornerRadius(1.dp.toPx()))
        }
    }
}

/** Large vertical fader with the same 0..127 semantics as before; the numeric value and scale are shown next to it. */
@Composable
private fun BigFader(name: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier, accent: Color) {
    val update by rememberUpdatedState(onChange)
    Canvas(modifier
        .semantics {
            contentDescription = "$name volume"
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(), 0f..127f, 126)
            setProgress { update(it.roundToInt().coerceIn(0, 127)); true }
        }
        .pointerInput(Unit) { detectTapGestures { p -> update(mixerFaderValue(p.y - 22.dp.toPx(), size.height - 44.dp.toPx())) } }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { p -> update(mixerFaderValue(p.y - 22.dp.toPx(), size.height - 44.dp.toPx())) }) { change, _ ->
                change.consume()
                update(mixerFaderValue(change.position.y - 22.dp.toPx(), size.height - 44.dp.toPx()))
            }
        }) {
        val pad = 22.dp.toPx()
        val bottom = (size.height - pad).coerceAtLeast(pad)
        val x = size.width * 0.42f
        drawLine(MxBlack, Offset(x, pad), Offset(x, bottom), 6.dp.toPx())
        for (tick in 0..16) {
            val y = pad + (bottom - pad) * tick / 16f
            val major = tick % 4 == 0
            drawLine(MxDim.copy(alpha = if (major) 0.9f else 0.45f), Offset(x + 20.dp.toPx(), y), Offset(x + (if (major) 30 else 26).dp.toPx(), y), 1.dp.toPx())
        }
        val y = bottom - (bottom - pad) * value.coerceIn(0, 127) / 127f
        drawRoundRect(Color(0xFFCFD4DA), Offset(x - 18.dp.toPx(), y - 20.dp.toPx()), Size(36.dp.toPx(), 40.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawRoundRect(accent, Offset(x - 18.dp.toPx(), y - 3.dp.toPx()), Size(36.dp.toPx(), 6.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawLine(MxBlack, Offset(x - 14.dp.toPx(), y - 12.dp.toPx()), Offset(x + 14.dp.toPx(), y - 12.dp.toPx()), 1.5.dp.toPx())
        drawLine(MxBlack, Offset(x - 14.dp.toPx(), y + 12.dp.toPx()), Offset(x + 14.dp.toPx(), y + 12.dp.toPx()), 1.5.dp.toPx())
    }
}

@Composable
private fun StripButton(label: String, on: Boolean, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).background(if (on) onColor else MxPanelHi, RoundedCornerShape(5.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) MxBlack else Color(0xFFE6EAEE), fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/**
 * One part. Top to bottom: part name + active LED, voice name, [activity meter | big fader], numeric volume, M / S, PAN, DETAIL.
 * [pan] null hides the pan control (buses). Volume, mute, solo and pan call the same view-model callbacks the old strips used.
 */
@Composable
fun MixerStrip(
    name: String, voice: String, volume: Int, muted: Boolean, solo: Boolean, pan: Int?,
    accent: Color, stripWidth: Dp, clock: State<Long>, level: (Long) -> Float, kind: MeterKind,
    onVolume: (Int) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, onPan: (Int) -> Unit, onDetail: () -> Unit,
    badge: String? = null
) {
    Surface(color = MxPanel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        modifier = Modifier.width(stripWidth).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                Canvas(Modifier.width(9.dp).height(9.dp)) {
                    val on = level(clock.value) > 0f && !muted
                    drawCircle(if (on) Color(0xFF3DDC84) else MxPanelHi, radius = size.minDimension / 2f)
                }
                Text(name, color = if (muted) MxDim else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(voice, color = MxDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp,
                modifier = Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onDetail)
                    .semantics { contentDescription = "$name voice and details: $voice" })
            if (badge != null) Text(badge, color = MxAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                SegmentMeter(Modifier.width(11.dp).fillMaxHeight().semantics { contentDescription = "${kind.title} $name" }, clock,
                    { if (muted) 0f else level(it) }, if (kind == MeterKind.NOTES) Color(0xFF3DDC84) else Color(0xFF58B8FF), muted)
                BigFader(name, volume, onVolume, Modifier.weight(1f).fillMaxHeight(), accent)
            }
            Text(volume.toString(), color = if (muted) MxDim else accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StripButton("M", muted, MxAmber, Modifier.weight(1f).semantics { contentDescription = "$name mute" }, onMute)
                StripButton("S", solo, Color(0xFFFFD94A), Modifier.weight(1f).semantics { contentDescription = "$name solo" }, onSolo)
            }
            if (pan != null) {
                Text(panText(pan), color = MxDim, fontSize = 10.sp)
                Slider(value = pan.toFloat(), onValueChange = { onPan(snapPan(it.roundToInt())) }, valueRange = 0f..127f,
                    modifier = Modifier.fillMaxWidth().height(34.dp).semantics { contentDescription = "$name pan ${panText(pan)}" })
            } else Box(Modifier.height(44.dp))
        }
    }
}

/** Bus strip (STYLE trim, MASTER): fader + number; MASTER may carry the real audio level, STYLE has no meter of its own. */
@Composable
fun MixerBusStrip(name: String, caption: String, volume: Int, accent: Color, stripWidth: Dp, clock: State<Long>, level: ((Long) -> Float)?, onVolume: (Int) -> Unit, onDetail: () -> Unit) {
    Surface(color = MxPanel, shape = RoundedCornerShape(8.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.width(stripWidth).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            Text(caption, color = MxDim, fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp, modifier = Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onDetail))
            Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (level != null) SegmentMeter(Modifier.width(11.dp).fillMaxHeight().semantics { contentDescription = "$name output level (audio)" }, clock, level, Color(0xFF3DDC84), false)
                else Box(Modifier.width(11.dp))
                BigFader(name, volume, onVolume, Modifier.weight(1f).fillMaxHeight(), accent)
            }
            Text(volume.toString(), color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { Text("DETAIL", color = MxDim, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onDetail)) }
        }
    }
}

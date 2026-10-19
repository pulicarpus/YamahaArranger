package com.yourapp.yamahaarranger.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** LED tones that carry meaning. Any other colour is a lit lamp; [Off] and [Unknown] never glow. */
object LedTone {
    val Off = Color(0xFF2A3139)
    val Unknown = Color(0xFF8AA0B5)
    fun glows(led: Color) = led != Off && led != Unknown
}

private val Bezel = Color(0xFF060708)
private val CapTop = Color(0xFF565B64)
private val CapMid = Color(0xFF30343A)
private val CapBottom = Color(0xFF16181C)

/**
 * Hardware arranger key. Three visible layers: a dark bezel (frame with a metal rim and a hard drop shadow), a smaller graphite cap inset
 * inside it (strong top-to-bottom gradient, light top/left and dark bottom/right bevel, rim highlight), and a separate LED lamp in its own dark housing above
 * the label. Pressing sinks the cap [HardwareKeyStyle.PRESS_DEPTH_DP] and shortens the shadow. The hit box is the outer static clickable and
 * [onClick] is the plain click callback, never behind an animation and with no second gesture path.
 */
@Composable
fun HardwareLedButton(label: String, sub: String?, led: Color, text: Color, enabled: Boolean, modifier: Modifier,
    big: Boolean = false, active: Boolean = false, empty: Boolean = false, fontSp: Int? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(modifier
        .semantics { contentDescription = label + (if (sub != null) " $sub" else ""); role = Role.Button; if (!enabled) disabled() }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)) {
        HardwareKeyFace(Modifier.fillMaxSize(), pressed && enabled, enabled, led, big, active, empty) {
            Text(label, color = text, fontSize = (fontSp ?: if (label.length > 2) 15 else if (big) 28 else 22).sp,
                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            if (sub != null) Text(sub, color = Color(0xFFB4BCC6), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Visual key only (no input handling), so other gestures such as press-and-hold can reuse the same look. */
@Composable
fun HardwareKeyFace(modifier: Modifier, pressed: Boolean, enabled: Boolean, led: Color, big: Boolean, active: Boolean, empty: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    val bezelShape = RoundedCornerShape((HardwareKeyStyle.CORNER_DP + 1f).dp)
    val capShape = RoundedCornerShape((HardwareKeyStyle.CORNER_DP - 1f).dp)
    val sink by animateDpAsState(if (pressed) HardwareKeyStyle.PRESS_DEPTH_DP.dp else 0.dp, tween(HardwareKeyStyle.PRESS_ANIMATION_MS), label = "keySink")
    val shadowDrop by animateDpAsState(if (pressed) 1.dp else HardwareKeyStyle.SHADOW_DROP_DP.dp, tween(HardwareKeyStyle.PRESS_ANIMATION_MS), label = "keyShadow")
    val flat = !enabled || empty
    val capBrush = when {
        flat -> Brush.verticalGradient(listOf(Color(0xFF24272C), Color(0xFF191B1F), Color(0xFF111316)))
        pressed -> Brush.verticalGradient(listOf(Color(0xFF1D1F23), Color(0xFF292C31), Color(0xFF212328)))
        else -> Brush.verticalGradient(listOf(CapTop, CapMid, CapBottom))
    }
    Box(modifier) {
        // 1. bezel: hard drop shadow below, near-black frame, thin metal rim
        Box(Modifier.fillMaxSize()
            .drawBehind {
                val d = shadowDrop.toPx()
                drawRoundRect(Color(0x99000000), Offset(0f, d), size, CornerRadius(7.dp.toPx()))
                drawRoundRect(Color(0x55000000), Offset(-1.dp.toPx(), d + 1.5.dp.toPx()), Size(size.width + 2.dp.toPx(), size.height), CornerRadius(8.dp.toPx()))
            }
            .background(Bezel, bezelShape)
            .border(1.dp, Brush.linearGradient(listOf(Color(0xFF7C8491), Color(0xFF2C3037), Color(0xFF0A0B0D))), bezelShape))
        // 2. cap, inset 3 dp inside the bezel, moves down when pressed
        Box(Modifier.fillMaxSize().padding(HardwareKeyStyle.BEZEL_DP.dp)
            .graphicsLayer { translationY = sink.toPx() }
            .background(capBrush, capShape)
            .drawBehind {
                if (!flat) {
                    val w = size.width; val h = size.height
                    if (!pressed) {
                        drawLine(Color(0x99FFFFFF), Offset(3.dp.toPx(), 0.75.dp.toPx()), Offset(w - 3.dp.toPx(), 0.75.dp.toPx()), 1.5.dp.toPx())       // top bevel
                        drawLine(Color(0x55FFFFFF), Offset(0.75.dp.toPx(), 3.dp.toPx()), Offset(0.75.dp.toPx(), h - 3.dp.toPx()), 1.2.dp.toPx())         // left bevel
                    } else drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)), 0f, 8.dp.toPx()), size = Size(w, 8.dp.toPx())) // inner shadow
                    drawLine(Color(0xCC000000), Offset(3.dp.toPx(), h - 1.dp.toPx()), Offset(w - 3.dp.toPx(), h - 1.dp.toPx()), 2.dp.toPx())           // bottom bevel
                    drawLine(Color(0x88000000), Offset(w - 0.75.dp.toPx(), 3.dp.toPx()), Offset(w - 0.75.dp.toPx(), h - 3.dp.toPx()), 1.5.dp.toPx())  // right bevel
                }
            }
            .border(if (active) 1.5.dp else 1.dp,
                if (active) led.copy(alpha = 0.95f) else if (flat) Color(0x33000000) else Color(0x40FFFFFF), capShape)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                HardwareLedLamp(led, enabled, big, Modifier.padding(top = 4.dp))
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 3.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally, content = content)
            }
        }
    }
}

/** Small horizontal LED in its own dark housing: lit tones glow a little, off is gray, unknown is a dull neutral. Never a full-width bar. */
@Composable
fun HardwareLedLamp(led: Color, enabled: Boolean, big: Boolean, modifier: Modifier = Modifier) {
    val housingW = if (big) HardwareKeyStyle.LED_HOUSING_BIG_W_DP else HardwareKeyStyle.LED_HOUSING_W_DP
    val lit = enabled && LedTone.glows(led)
    val lamp = when { lit -> led; enabled && led == LedTone.Unknown -> Color(0xFF6E7C8A); else -> Color(0xFF4B5057) }
    Box(modifier.size(housingW.dp, HardwareKeyStyle.LED_HOUSING_H_DP.dp)
        .drawBehind {
            if (lit) {                                                                       // controlled glow: two soft layers, bounded to ~3 dp around the housing
                drawRoundRect(led.copy(alpha = 0.16f), Offset(-3.dp.toPx(), -2.dp.toPx()), Size(size.width + 6.dp.toPx(), size.height + 4.dp.toPx()), CornerRadius(6.dp.toPx()))
                drawRoundRect(led.copy(alpha = 0.26f), Offset(-1.5.dp.toPx(), -1.dp.toPx()), Size(size.width + 3.dp.toPx(), size.height + 2.dp.toPx()), CornerRadius(5.dp.toPx()))
            }
        }
        .background(Color(0xFF020304), RoundedCornerShape(3.dp))
        .border(1.dp, Color(0xFF3A3F46), RoundedCornerShape(3.dp))
        .padding(horizontal = 3.dp, vertical = 2.dp)) {
        Box(Modifier.fillMaxSize().background(lamp, RoundedCornerShape(2.dp)))
        if (lit) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x66FFFFFF)))                // specular streak on the lens
    }
}

/** Brushed dark-graphite panel with a metal frame; used for header, style groups, mixer and the registration bank. */
@Composable
fun HardwarePanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier
        .shadow(3.dp, shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF2B2E34), Color(0xFF1C1E22))), shape)
        .drawBehind {
            val step = 3.dp.toPx(); var y = step
            while (y < size.height) { drawLine(Color(0x0DFFFFFF), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        .border(1.5.dp, Brush.linearGradient(listOf(Color(0xFF8A929F), Color(0xFF30343B), Color(0xFF0C0D0F))), shape)) { content() }
}

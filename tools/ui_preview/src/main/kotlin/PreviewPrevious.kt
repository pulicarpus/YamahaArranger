package com.yourapp.preview
import com.yourapp.yamahaarranger.ui.*

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PrevKeyTop = Color(0xFF3A3E45)
private val PrevKeyBottom = Color(0xFF1C1F24)
private val PrevKeyTopPressed = Color(0xFF2A2D33)
private val PrevKeyBottomPressed = Color(0xFF17191D)
private val PrevKeyOff = Color(0xFF14161A)

/**
 * Premium arranger key: rectangular graphite cap, top/left bevel light and bottom/right bevel dark, soft drop shadow,
 * thin LED bar on top. Pressing lowers the cap by [1.5f] and shrinks the shadow. The visual
 * offset moves only the cap; the hit area stays put and [onClick] is the plain clickable callback, never delayed by the
 * animation, so there is no extra trigger path.
 */
@Composable
fun PrevLedButton(label: String, sub: String?, led: Color, text: Color, enabled: Boolean, modifier: Modifier,
    big: Boolean = false, active: Boolean = false, empty: Boolean = false, fontSp: Int? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(modifier
        .semantics { contentDescription = label + (if (sub != null) " $sub" else ""); role = Role.Button; if (!enabled) disabled() }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)) {
        PrevKeyFace(Modifier.fillMaxSize(), pressed && enabled, enabled, led, big, active, empty) {
            Text(label, color = text, fontSize = (fontSp ?: if (label.length > 2) 17 else if (big) 34 else 26).sp,
                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            if (sub != null) Text(sub, color = Color(0xFFAEB6C0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Visual cap only (no input handling), so other gestures such as press-and-hold can reuse the same look. */
@Composable
fun PrevKeyFace(modifier: Modifier, pressed: Boolean, enabled: Boolean, led: Color, big: Boolean, active: Boolean, empty: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(5f.dp)
    val depth by animateDpAsState(if (pressed) 1.5f.dp else 0.dp, tween(45), label = "keyDepth")
    val lift by animateDpAsState(if (pressed) 1f.dp else 4f.dp,
        tween(45), label = "keyLift")
    val face = when {
        !enabled || empty -> Brush.verticalGradient(listOf(Color(0xFF1B1E23), PrevKeyOff))
        pressed -> Brush.verticalGradient(listOf(PrevKeyTopPressed, PrevKeyBottomPressed))
        else -> Brush.verticalGradient(listOf(PrevKeyTop, PrevKeyBottom))
    }
    val bevel = if (!enabled) Brush.linearGradient(listOf(Color(0x14FFFFFF), Color(0x00FFFFFF), Color(0x66000000)))
        else if (pressed) Brush.linearGradient(listOf(Color(0x33000000), Color(0x00000000), Color(0x40FFFFFF)))   // light now falls on the lower edge
        else Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x00FFFFFF), Color(0xB0000000)))
    val ledOn = enabled && led != Color(0xFF2A3139)
    val ledHeight = if (big) 7f else 5f
    Column(modifier
        .graphicsLayer { translationY = depth.toPx() }
        .shadow(if (enabled) lift else 0.dp, shape)
        .background(face, shape)
        .border(if (active) 2.dp else 1.dp, if (active) led else Color.Transparent, shape)
        .border(1.dp, bevel, shape)
        .drawBehind {
            // inner highlight under the top edge: a faint 1 px line that gives the cap its rounded-over look
            if (enabled && !pressed) drawLine(Color(0x22FFFFFF), Offset(6.dp.toPx(), 1.5.dp.toPx()), Offset(size.width - 6.dp.toPx(), 1.5.dp.toPx()), 1.dp.toPx())
        }) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 3.dp).fillMaxWidth().height(ledHeight.dp)) {
            if (ledOn) Box(Modifier.fillMaxSize().background(led.copy(alpha = 0.28f), RoundedCornerShape(3.dp)).padding(0.dp))   // soft glow behind the lamp
            Box(Modifier.fillMaxSize().background(led, RoundedCornerShape(2.dp)))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 3.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Brushed dark-graphite panel with a thin metal frame; used for header, style groups, mixer and the registration bank. */
@Composable
fun PrevPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(modifier
        .background(Brush.verticalGradient(listOf(Color(0xFF26292E), Color(0xFF1A1C20))), shape)
        .drawBehind {
            // fine horizontal brushing, drawn once per frame as hairlines at very low contrast
            val step = 3.dp.toPx(); var y = step
            while (y < size.height) { drawLine(Color(0x0AFFFFFF), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        .border(1.dp, Brush.linearGradient(listOf(Color(0xFF6B7380), Color(0xFF2B2F36), Color(0xFF0E0F11))), shape)) { content() }
}

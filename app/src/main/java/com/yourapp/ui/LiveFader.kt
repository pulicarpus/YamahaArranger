package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.audio.mixerFaderValue
import kotlin.math.roundToInt

/** Vertical 0..127 fader: bottom = 0, top = 127. Reports every changed integer value to [onChange] (the existing volume action). */
@Composable
fun LiveFader(name: String, value: Int, accent: Color, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val update by rememberUpdatedState(onChange)
    val shown = value.coerceIn(0, 127)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF070B12), RoundedCornerShape(4.dp)).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
            Text("$shown", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).width(40.dp)
            .semantics {
                contentDescription = "$name volume"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..127f, 126)
                setProgress { update(it.roundToInt().coerceIn(0, 127)); true }
            }
            .pointerInput(Unit) { detectTapGestures { p -> update(mixerFaderValue(p.y, size.height.toFloat())) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { p -> update(mixerFaderValue(p.y, size.height.toFloat())) }) { change, _ ->
                    change.consume()
                    update(mixerFaderValue(change.position.y, size.height.toFloat()))
                }
            }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = maxHeight
                val thumb = 26.dp
                val fraction = shown / 127f
                // groove, level fill, tick marks, and a cap with a centre line in the channel accent colour
                Box(Modifier.align(Alignment.TopCenter).width(6.dp).fillMaxHeight().background(Color(0xFF05080D), RoundedCornerShape(3.dp)))
                Box(Modifier.align(Alignment.BottomCenter).width(6.dp).fillMaxHeight(fraction).background(accent.copy(alpha = 0.75f), RoundedCornerShape(3.dp)))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                    Box(Modifier.align(Alignment.BottomStart).offset(y = -(h * t) + 1.dp).width(7.dp).height(2.dp).background(Color(0xFF4A5868)))
                }
                Box(Modifier.align(Alignment.BottomCenter).offset(y = -(h * fraction - thumb / 2)).fillMaxWidth().height(thumb)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF55616F), Color(0xFF2A333D))), RoundedCornerShape(4.dp))) {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.75f).height(3.dp).background(accent))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
        Text(name, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

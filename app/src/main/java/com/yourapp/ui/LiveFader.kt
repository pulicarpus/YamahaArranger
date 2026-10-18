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
        Text("$shown", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).width(60.dp)
            .background(Color(0xFF0B0F14), RoundedCornerShape(6.dp))
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
                val thumb = 30.dp
                val fraction = shown / 127f
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.34f).fillMaxHeight(fraction).background(accent.copy(alpha = 0.55f)))
                Box(Modifier.align(Alignment.TopCenter).width(4.dp).fillMaxHeight().background(Color(0xFF1E2630)))
                Box(Modifier.align(Alignment.BottomCenter).offset(y = -(h * fraction - thumb / 2)).fillMaxWidth().height(thumb)
                    .background(Color(0xFF39424C), RoundedCornerShape(4.dp))) {
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(3.dp).background(accent))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(name, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

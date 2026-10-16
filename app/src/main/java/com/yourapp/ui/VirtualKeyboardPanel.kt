package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.audio.VirtualKeyboardLayout
import kotlinx.coroutines.delay

/** Height the panel occupies at the bottom of the screen (other overlays sit above it). */
val VirtualKeyboardPanelHeight = 250.dp

/**
 * Non-modal on-screen RIGHT keyboard (61 keys, C2..C7, horizontal scroll strip).
 * Touch -> VirtualKeyboardController -> ArrangerBrain virtual path -> existing RIGHT 1/2/3 engine channels.
 */
@Composable
fun VirtualKeyboardPanel(viewModel: MainViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val controller = viewModel.virtualKeyboard
    val epoch by viewModel.virtualKeyboardEpoch.collectAsState()
    var octave by remember(epoch) { mutableStateOf(controller.octaveShift) }
    var velocity by remember(epoch) { mutableStateOf(controller.velocity) }
    var sustain by remember(epoch) { mutableStateOf(controller.sustain) }
    var pressed by remember { mutableLongStateOf(0L) }
    var scrollPx by remember { mutableFloatStateOf(0f) }
    var diag by remember { mutableStateOf("") }

    // Closing the panel (or leaving composition) always releases every virtual note and the virtual sustain.
    DisposableEffect(Unit) { onDispose { viewModel.closeVirtualKeyboard() } }
    LaunchedEffect(Unit) {
        while (true) {
            diag = try {
                val snap = viewModel.rightVoiceDiagSnapshot()
                snap.backend.joinToString("   ") { r ->
                    val app = snap.app.firstOrNull { it.label.replace(" ", "") == r.part }
                    val tag = if (app?.enabled == true) "ON" else "off"
                    "${r.part.replace("RIGHT", "R")}[$tag] ${r.get("actualLabel")} '${r.get("liveName")}' ${r.status} sent=${r.get("fwdLast")}"
                }
            } catch (_: Exception) { diag }
            delay(1000)
        }
    }

    Column(modifier.fillMaxWidth().height(VirtualKeyboardPanelHeight).background(Color(0xF0151A20)).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("CLOSE", Color(0xFF8A2D2D)) { viewModel.closeVirtualKeyboard(); onClose() }
            Btn("OCT −", Color(0xFF3A4450)) { controller.setOctave(octave - 1); octave = controller.octaveShift }
            Text("OCT ${if (octave >= 0) "+" else ""}$octave  ${VirtualKeyboardLayout.noteName(VirtualKeyboardLayout.pitchOf(0, octave))}–" +
                VirtualKeyboardLayout.noteName(VirtualKeyboardLayout.pitchOf(VirtualKeyboardLayout.KEY_COUNT - 1, octave)),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Btn("OCT +", Color(0xFF3A4450)) { controller.setOctave(octave + 1); octave = controller.octaveShift }
            Text("VELOCITY $velocity", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = velocity.toFloat(), onValueChange = { controller.setVelocity(it.toInt()); velocity = controller.velocity },
                valueRange = 1f..127f, modifier = Modifier.width(220.dp)
            )
            Btn(if (sustain) "SUSTAIN ON" else "SUSTAIN OFF", if (sustain) Color(0xFF1E8E3E) else Color(0xFF3A4450)) {
                controller.setSustain(!sustain); sustain = controller.sustain
            }
        }
        Text(diag.ifEmpty { "RIGHT readback…" }, color = Color(0xFF7FDBFF), fontSize = 9.sp, maxLines = 1,
            modifier = Modifier.padding(vertical = 2.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val whitePx = with(density) { 56.dp.toPx() }
            val viewW = with(density) { maxWidth.toPx() }
            val totalW = VirtualKeyboardLayout.totalWidth(whitePx)
            val maxScroll = (totalW - viewW).coerceAtLeast(0f)
            val currentScroll by rememberUpdatedState(scrollPx.coerceIn(0f, maxScroll))
            val octaveNow by rememberUpdatedState(octave)
            val labelPaint = remember {
                android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                }
            }

            Column(Modifier.fillMaxSize()) {
                // Overview strip: drag to scroll the 61 keys horizontally.
                Canvas(
                    Modifier.fillMaxWidth().height(20.dp).pointerInput(maxScroll) {
                        awaitPointerEventScope {
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull() ?: continue
                                if (c.pressed) {
                                    scrollPx = ((c.position.x / size.width) * totalW - viewW / 2f).coerceIn(0f, maxScroll)
                                    c.consume()
                                }
                            }
                        }
                    }
                ) {
                    drawRect(Color(0xFF252C34), size = size)
                    val w = size.width / VirtualKeyboardLayout.WHITE_KEY_COUNT
                    for (i in 0 until VirtualKeyboardLayout.WHITE_KEY_COUNT) {
                        drawRect(Color(0xFF8993A0), Offset(i * w + 0.5f, 2f), Size(w - 1f, size.height - 4f))
                    }
                    val left = if (totalW > 0f) currentScroll / totalW * size.width else 0f
                    val width = if (totalW > 0f) (viewW / totalW).coerceAtMost(1f) * size.width else size.width
                    drawRect(Color(0x883FA7FF), Offset(left, 0f), Size(width, size.height))
                    drawRect(Color(0xFF3FA7FF), Offset(left, 0f), Size(width, size.height), style = Stroke(2f))
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val height = size.height.toFloat()
                                    for (c in event.changes) {
                                        val id = c.id.value.toInt()
                                        val index = VirtualKeyboardLayout.hitTest(c.position.x + currentScroll, c.position.y, height, whitePx)
                                        when {
                                            c.pressed && !c.previousPressed -> controller.pointerDown(id, index)
                                            c.pressed -> controller.pointerMove(id, index)
                                            c.previousPressed -> controller.pointerUp(id)
                                        }
                                        c.consume()
                                    }
                                    pressed = controller.pressedMask
                                }
                            }
                        } finally {
                            // Gesture coroutine cancelled (ACTION_CANCEL, recomposition, close): nothing may stay held.
                            controller.cancelAll(); pressed = controller.pressedMask
                        }
                    }
                ) {
                    val h = size.height
                    val shift = currentScroll
                    for (i in 0 until VirtualKeyboardLayout.KEY_COUNT) {
                        if (VirtualKeyboardLayout.isBlack(i)) continue
                        val x = VirtualKeyboardLayout.keyLeft(i, whitePx) - shift
                        if (x + whitePx < 0f || x > size.width) continue
                        val down = (pressed shr i) and 1L != 0L
                        drawRect(if (down) Color(0xFF7FC8FF) else Color(0xFFF4F4F0), Offset(x, 0f), Size(whitePx - 1.5f, h))
                        drawRect(Color(0xFF555B63), Offset(x, 0f), Size(whitePx - 1.5f, h), style = Stroke(1.5f))
                        val label = VirtualKeyboardLayout.cLabel(i, octaveNow)
                        if (label.isNotEmpty()) {
                            labelPaint.textSize = 15.sp.toPx()
                            drawContext.canvas.nativeCanvas.drawText(label, x + whitePx / 2f, h - 10f, labelPaint)
                        }
                    }
                    for (i in 0 until VirtualKeyboardLayout.KEY_COUNT) {
                        if (!VirtualKeyboardLayout.isBlack(i)) continue
                        val x = VirtualKeyboardLayout.keyLeft(i, whitePx) - shift
                        val w = VirtualKeyboardLayout.keyWidth(i, whitePx)
                        if (x + w < 0f || x > size.width) continue
                        val down = (pressed shr i) and 1L != 0L
                        drawRect(if (down) Color(0xFF2F83C9) else Color(0xFF15181C), Offset(x, 0f), Size(w, VirtualKeyboardLayout.blackHeight(h)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Btn(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.background(color, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

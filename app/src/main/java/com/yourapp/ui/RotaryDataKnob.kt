package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object RotaryKnobTags {
    const val KNOB = "data.knob"; const val LABEL = "data.knob.label"; const val VALUE = "data.knob.value"; const val MINUS = "data.minus"; const val PLUS = "data.plus"
    fun param(name: String) = "data.param.$name"
    fun paramValue(name: String) = "data.param.$name.value"
}

private val KBlue = Color(0xFF4DA3FF); private val KGreen = Color(0xFF27D887); private val KOrange = Color(0xFFFF8A00); private val KDim = Color(0xFF8D9AA6)

/** Height of everything in [DataControlSection] except the knob (title, spacers, -/+ row, three parameter buttons). */
fun dataControlFixedHeight(compact: Boolean): Dp = if (compact) (22 + 6 + 31 + 4 + 3 * 36 + 2 * 3).dp else (26 + 10 + 36 + 6 + 3 * 44 + 2 * 4).dp

/** Knob size that fits the space left in the column (never taller than the room, never below a touchable 80 dp). */
fun dataKnobSize(maxWidth: Dp, maxHeight: Dp, reservedBelow: Dp, compact: Boolean): Dp =
    minOf(maxWidth, maxHeight - dataControlFixedHeight(compact) - reservedBelow).coerceIn(80.dp, 160.dp)

fun dataValueText(param: String, v: Int) = when (param) { "TRANSPOSE" -> if (v >= 0) "+$v" else v.toString(); else -> v.toString() }

/**
 * DATA CONTROL body (HOME, left column): title, rotary knob, fine -/+ and the three parameter buttons (name + live value).
 * [values] is read from the app state by the caller; tapping a parameter button only selects it (never changes a value).
 */
@Composable
fun DataControlSection(
    compact: Boolean, selectedParameter: String, values: Map<String, Int>, transposePending: Boolean,
    onSelect: (String) -> Unit, onKnobSteps: (Int) -> Unit, onStep: (Int) -> Unit, knobSize: Dp = if (compact) 116.dp else 148.dp,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DATA CONTROL", color = KBlue, fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        Text("ROTATE ↻ +  ↺ −", color = KDim, fontSize = if (compact) 6.sp else 7.sp, maxLines = 1)
        Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
        val spec = DataParamSpec.of(selectedParameter)
        val value = values[selectedParameter] ?: spec.min
        val pendingHere = selectedParameter == "TRANSPOSE" && transposePending
        RotaryDataKnob(
            label = if (pendingHere) "TRANSPOSE ▸ NEXT" else selectedParameter, valueText = dataValueText(selectedParameter, value), value = value,
            spec = spec, onSteps = onKnobSteps, size = knobSize, accent = KBlue, valueColor = if (pendingHere) KOrange else Color.White
        )
        Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((tag, sign, d) in listOf(Triple(RotaryKnobTags.MINUS, "−", -1), Triple(RotaryKnobTags.PLUS, "+", 1))) {
                Surface(color = Color(0xFF252D35), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).height(if (compact) 31.dp else 36.dp).testTag(tag).clickable { onStep(d) }) {
                    Box(contentAlignment = Alignment.Center) { Text(sign, color = Color.White, fontSize = if (compact) 15.sp else 18.sp) }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
            listOf("TEMPO", "TRANSPOSE", "RELEASE").forEach { item ->
                val active = selectedParameter == item
                val pending = item == "TRANSPOSE" && transposePending
                Surface(
                    color = if (active) Color(0xFF1F5E8C) else Color(0xFF1D2329), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().height(if (compact) 36.dp else 44.dp).testTag(RotaryKnobTags.param(item))
                        .border(if (active) 2.dp else 1.dp, if (active) KGreen else Color(0xFF35404A), RoundedCornerShape(4.dp))
                        .clickable { onSelect(item) }
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item, color = if (active) Color.White else KDim, fontSize = if (compact) 8.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            softWrap = false, modifier = Modifier.weight(1f))
                        Text(dataValueText(item, values[item] ?: 0) + if (pending) " ▸" else "", color = if (pending) KOrange else Color.White,
                            fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                            modifier = Modifier.testTag(RotaryKnobTags.paramValue(item)))
                    }
                }
            }
        }
    }
}

/**
 * DATA CONTROL rotary encoder. Rotate the finger around the knob: clockwise = up, counter-clockwise = down.
 * The value shown is always the caller's [value] (the app state); the knob keeps no copy of it. [onSteps] receives whole detents
 * (never 0) and the caller applies them through the existing view-model path, only when the clamped value really changes.
 */
@Composable
fun RotaryDataKnob(
    label: String, valueText: String, value: Int, spec: DataParamSpec, onSteps: (Int) -> Unit,
    size: Dp, accent: Color, modifier: Modifier = Modifier, valueColor: Color = Color.White,
) {
    val steps by rememberUpdatedState(onSteps)
    val currentSpec by rememberUpdatedState(spec)
    var spin by remember { mutableFloatStateOf(0f) }                  // visual grip rotation that follows the finger
    val acc = remember { RotaryAccumulator() }
    Box(
        modifier.size(size).testTag(RotaryKnobTags.KNOB).pointerInput(Unit) {
            awaitEachGesture {
                val c = Offset(this.size.width / 2f, this.size.height / 2f); val r = min(this.size.width, this.size.height) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                acc.down(down.position.x, down.position.y, c.x, c.y, r)
                val base = spin
                while (true) {
                    val ev = awaitPointerEvent()
                    val p = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!p.pressed) break
                    if (p.positionChange() != Offset.Zero) {
                        val n = acc.move(p.position.x, p.position.y, c.x, c.y, r, currentSpec.degreesPerStep)
                        spin = base + acc.turned
                        p.consume()
                        if (n != 0) steps(n)
                    }
                }
                acc.up()
            }
        },
        contentAlignment = Alignment.Center
    ) {
        val indicator = RotaryKnobMath.indicatorAngle(value, spec.min, spec.max)
        Canvas(Modifier.fillMaxSize()) {
            val r = min(this.size.width, this.size.height) / 2f
            val c = center
            val track = r * 0.10f
            val arcR = r - track / 2f
            val topLeft = Offset(c.x - arcR, c.y - arcR); val arcSize = Size(arcR * 2, arcR * 2)
            // dial angle a (0 = 12 o'clock, clockwise) -> canvas angle a - 90 (0 = 3 o'clock, clockwise)
            drawArc(Color(0xFF26313B), RotaryKnobMath.ARC_START - 90f, RotaryKnobMath.ARC_SWEEP, false, topLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))
            drawArc(accent, RotaryKnobMath.ARC_START - 90f, indicator - RotaryKnobMath.ARC_START, false, topLeft, arcSize, style = Stroke(track, cap = StrokeCap.Round))
            val bodyR = r * 0.78f
            drawCircle(Color(0xFF151A20), bodyR, c); drawCircle(Color(0xFF2E3740), bodyR, c, style = Stroke(r * 0.03f))
            for (i in 0 until 24) {                                   // knurled grip: turns with the finger
                val a = Math.toRadians((spin + i * 15f - 90f).toDouble())
                val o = Offset(c.x + (bodyR * 0.93f * cos(a)).toFloat(), c.y + (bodyR * 0.93f * sin(a)).toFloat())
                val in2 = Offset(c.x + (bodyR * 0.84f * cos(a)).toFloat(), c.y + (bodyR * 0.84f * sin(a)).toFloat())
                drawLine(Color(0xFF3A4550), in2, o, strokeWidth = r * 0.018f)
            }
            val ia = Math.toRadians((indicator - 90f).toDouble())       // value pointer at the value's angle on the arc
            drawLine(accent, Offset(c.x + (bodyR * 0.45f * cos(ia)).toFloat(), c.y + (bodyR * 0.45f * sin(ia)).toFloat()),
                Offset(c.x + (bodyR * 0.80f * cos(ia)).toFloat(), c.y + (bodyR * 0.80f * sin(ia)).toFloat()), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color(0xFF8D9AA6), fontSize = (size.value * 0.075f).sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag(RotaryKnobTags.LABEL))
            Text(valueText, color = valueColor, fontSize = (size.value * 0.19f).sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag(RotaryKnobTags.VALUE))
        }
    }
}

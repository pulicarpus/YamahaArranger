package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object RotaryKnobTags {
    const val PANEL = "data.panel"; const val KNOB = "data.knob"; const val LABEL = "data.knob.label"; const val VALUE = "data.knob.value"
    const val MINUS = "data.minus"; const val PLUS = "data.plus"; const val STYLE = "data.style"; const val PARAMS = "data.params"
    fun param(name: String) = "data.param.$name"
    fun paramValue(name: String) = "data.param.$name.value"
}

private val KBlue = Color(0xFF3FA2FF); private val KBlueDeep = Color(0xFF1F5E8C); private val KOrange = Color(0xFFFF8A00); private val KDim = Color(0xFF8D9AA6)

fun dataValueText(param: String, v: Int) = when (param) { "TRANSPOSE" -> if (v >= 0) "+$v" else v.toString(); else -> v.toString() }

/**
 * HOME DATA CONTROL column: title, rotary encoder, the three parameter buttons in ONE row (always visible) and a bottom row
 * [-] STYLE SELECT [+]. Heights come from [DataControlLayout.plan] on the column's real size: the knob takes what is left,
 * so nothing is clipped and no scrolling is needed. [values] is read from the app state by the caller; tapping a parameter
 * button only selects it.
 */
@Composable
fun DataControlPanel(
    selectedParameter: String, values: Map<String, Int>, transposePending: Boolean,
    onSelect: (String) -> Unit, onKnobSteps: (Int) -> Unit, onStep: (Int) -> Unit, onPickStyle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag(RotaryKnobTags.PANEL)) {
        val plan = DataControlLayout.plan(maxWidth.value, maxHeight.value)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(plan.gap.dp)) {
            Text("DATA CONTROL", color = KBlue, fontSize = if (plan.titleH < 14f) 8.sp else 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                maxLines = 1, modifier = Modifier.height(plan.titleH.dp))
            val spec = DataParamSpec.of(selectedParameter)
            val value = values[selectedParameter] ?: spec.min
            val pendingHere = selectedParameter == "TRANSPOSE" && transposePending
            Box(Modifier.fillMaxWidth().height(plan.knob.dp), contentAlignment = Alignment.Center) {
                RotaryDataKnob(
                    label = if (pendingHere) "NEXT CHORD" else selectedParameter, valueText = dataValueText(selectedParameter, value), value = value,
                    spec = spec, onSteps = onKnobSteps, size = plan.knob.dp, accent = KBlue, valueColor = if (pendingHere) KOrange else Color.White
                )
            }
            Row(Modifier.fillMaxWidth().height(plan.paramH.dp).testTag(RotaryKnobTags.PARAMS), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("TEMPO", "TRANSPOSE", "RELEASE").forEach { item ->
                    val active = selectedParameter == item
                    val pending = item == "TRANSPOSE" && transposePending
                    Surface(
                        color = if (active) KBlueDeep else Color(0xFF1B2128), shape = RoundedCornerShape(5.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag(RotaryKnobTags.param(item))
                            .border(if (active) 2.dp else 1.dp, if (active) KBlue else Color(0xFF353F49), RoundedCornerShape(5.dp))
                            .clickable { onSelect(item) }
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 2.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item, color = if (active) Color.White else KDim, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                            Text(dataValueText(item, values[item] ?: 0) + if (pending) "▸" else "", color = if (pending) KOrange else Color.White,
                                fontSize = if (plan.paramH < 44f) 12.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                                textAlign = TextAlign.Center, modifier = Modifier.testTag(RotaryKnobTags.paramValue(item)))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(plan.bottomH.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallKey("−", RotaryKnobTags.MINUS, Modifier.width(plan.bottomH.coerceAtLeast(DataControlLayout.MIN_TOUCH).dp)) { onStep(-1) }
                Surface(color = Color(0xFF1B2128), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(RotaryKnobTags.STYLE).border(1.dp, Color(0xFF343C45), RoundedCornerShape(4.dp)).clickable(onClick = onPickStyle)) {
                    Box(contentAlignment = Alignment.Center) { Text("STYLE SELECT", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                }
                SmallKey("+", RotaryKnobTags.PLUS, Modifier.width(plan.bottomH.coerceAtLeast(DataControlLayout.MIN_TOUCH).dp)) { onStep(1) }
            }
        }
    }
}

@Composable
private fun SmallKey(text: String, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(color = Color(0xFF252D35), shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().testTag(tag).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * Hardware-style rotary encoder. Rotate the finger around the knob: clockwise = up, counter-clockwise = down (angle accumulation,
 * see RotaryAccumulator; a tap never changes a value). The value shown is always the caller's [value] (the app state); the knob
 * keeps no copy of it. [onSteps] receives whole detents (never 0); the caller applies them through the existing view-model path.
 */
@Composable
fun RotaryDataKnob(
    label: String, valueText: String, value: Int, spec: DataParamSpec, onSteps: (Int) -> Unit,
    size: Dp, accent: Color, modifier: Modifier = Modifier, valueColor: Color = Color.White,
) {
    val steps by rememberUpdatedState(onSteps)
    val currentSpec by rememberUpdatedState(spec)
    var spin by remember { mutableFloatStateOf(0f) }                  // grip rotation that follows the finger (visual only)
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
            fun at(dialDeg: Float, radius: Float): Offset {                      // dial angle: 0 = 12 o'clock, clockwise
                val a = Math.toRadians((dialDeg - 90f).toDouble())
                return Offset(c.x + (radius * cos(a)).toFloat(), c.y + (radius * sin(a)).toFloat())
            }
            // tick marks on the 270 degree scale; the ones up to the value are lit
            val ticks = 28
            for (i in 0..ticks) {
                val d = RotaryKnobMath.ARC_START + RotaryKnobMath.ARC_SWEEP * i / ticks
                val major = i % 7 == 0
                val lit = d <= indicator + 0.01f
                drawLine(if (lit) accent else Color(0xFF3A4652), at(d, r * (if (major) 0.84f else 0.88f)), at(d, r * 0.97f),
                    strokeWidth = r * (if (major) 0.035f else 0.022f), cap = StrokeCap.Round)
            }
            // value ring: glow + core
            val ringR = r * 0.79f
            val tl = Offset(c.x - ringR, c.y - ringR); val sz = Size(ringR * 2, ringR * 2)
            drawArc(Color(0xFF1A222B), RotaryKnobMath.ARC_START - 90f, RotaryKnobMath.ARC_SWEEP, false, tl, sz, style = Stroke(r * 0.07f, cap = StrokeCap.Round))
            drawArc(accent.copy(alpha = 0.25f), RotaryKnobMath.ARC_START - 90f, indicator - RotaryKnobMath.ARC_START, false, tl, sz, style = Stroke(r * 0.13f, cap = StrokeCap.Round))
            drawArc(accent, RotaryKnobMath.ARC_START - 90f, indicator - RotaryKnobMath.ARC_START, false, tl, sz, style = Stroke(r * 0.05f, cap = StrokeCap.Round))
            // knob body: dark metal with a top-left highlight, rim and knurled edge that turns with the finger
            val bodyR = r * 0.68f
            drawCircle(Color(0xFF07090B), bodyR * 1.04f, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF4A5560), Color(0xFF232A31), Color(0xFF12161A)), center = Offset(c.x - bodyR * 0.35f, c.y - bodyR * 0.45f), radius = bodyR * 1.5f), bodyR, c)
            drawCircle(Color(0xFF5B6773).copy(alpha = 0.6f), bodyR, c, style = Stroke(r * 0.018f))
            for (i in 0 until 36) {
                val d = spin + i * 10f
                drawLine(Color(0xFF0C0F12), at(d, bodyR * 0.90f), at(d, bodyR * 0.99f), strokeWidth = r * 0.016f)
            }
            drawCircle(Color(0xFF0E1216), bodyR * 0.62f, c)                       // recessed centre for the number
            // pointer at the value's angle
            drawLine(accent, at(indicator, bodyR * 0.66f), at(indicator, bodyR * 0.88f), strokeWidth = r * 0.055f, cap = StrokeCap.Round)
            drawCircle(Color.White, r * 0.022f, at(indicator, bodyR * 0.88f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = KDim, fontSize = (size.value * 0.065f).coerceIn(7f, 11f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.testTag(RotaryKnobTags.LABEL))
            Text(valueText, color = valueColor, fontSize = (size.value * 0.17f).coerceIn(14f, 34f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.testTag(RotaryKnobTags.VALUE))
        }
    }
}

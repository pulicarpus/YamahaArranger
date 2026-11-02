import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yourapp.yamahaarranger.ui.DataControlPanel
import com.yourapp.yamahaarranger.ui.DataParamSpec
import com.yourapp.yamahaarranger.ui.RotaryKnobTags as K
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The real HOME DATA CONTROL section (app/src/main/java/com/yourapp/ui/RotaryDataKnob.kt) driven by circular touch paths.
 * The harness plays the view-model: it keeps the app state and applies detents with DataParamSpec.target, exactly like MainViewModel.dataKnobSteps.
 * Host evidence of the UI contract; not a device test.
 */
@OptIn(ExperimentalTestApi::class)
class RotaryDataKnobTest {
    private class Harness {
        val values = mutableStateMapOf("TEMPO" to 120, "TRANSPOSE" to 0, "RELEASE" to 64)
        var selected by mutableStateOf("TEMPO")
        val sent = ArrayList<Pair<String, Int>>()      // what would reach the engine (only real changes; -/+ recorded as STEP)
        var detentCalls = 0
        fun steps(n: Int) { detentCalls++; val s = DataParamSpec.of(selected); s.target(values[selected]!!, n)?.let { values[selected] = it; sent += selected to it } }
    }

    /** The real DATA CONTROL column (DataControlPanel, as SxSideMenu uses it) in a panel of the given size. */
    @androidx.compose.runtime.Composable
    private fun SideColumn(h: Harness, widthDp: Int, heightDp: Int) {
        Box(Modifier.size(widthDp.dp, heightDp.dp).background(Color(0xFF151A20))) {
            DataControlPanel(h.selected, h.values, transposePending = false, onSelect = { h.selected = it }, onKnobSteps = { h.steps(it) },
                onStep = { h.sent += "STEP" to it }, onPickStyle = {}, modifier = Modifier.fillMaxSize().padding(6.dp))
        }
    }

    /** Finger path around the knob from [from] to [to] dial degrees (0 = 12 o'clock, clockwise), 2-degree samples, at 38 % of the size from the centre. */
    private fun TouchInjectionScope.turn(from: Float, to: Float) {
        val c = Offset(width / 2f, height / 2f); val rad = minOf(width, height) * 0.38f
        fun p(a: Float) = Offset(c.x + rad * sin(Math.toRadians(a.toDouble())).toFloat(), c.y - rad * cos(Math.toRadians(a.toDouble())).toFloat())
        down(p(from)); val n = (kotlin.math.abs(to - from) / 2f).toInt(); val dir = if (to >= from) 2f else -2f
        for (i in 1..n) moveTo(p(from + dir * i)); up()
    }

    @Test fun clockwiseRaisesCounterClockwiseLowersAndThePointerFollowsTheState() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); setContent { SideColumn(h, 240, 520) }
        onNodeWithTag(K.KNOB).performTouchInput { turn(30f, 120f) }            // +90 deg / 6 deg = +15 BPM
        waitForIdle(); assertEquals(135, h.values["TEMPO"])
        onNodeWithTag(K.VALUE).assertTextEquals("135"); onNodeWithTag(K.paramValue("TEMPO"), useUnmergedTree = true).assertTextEquals("135")
        onNodeWithTag(K.KNOB).performTouchInput { turn(200f, 140f) }           // -60 deg = -10
        waitForIdle(); assertEquals(125, h.values["TEMPO"])
    }

    @Test fun turningThroughTwelveOClockHasNoJump() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); setContent { SideColumn(h, 240, 520) }
        onNodeWithTag(K.KNOB).performTouchInput { turn(330f, 390f) }           // through 0/360: +60 deg = +10
        waitForIdle(); assertEquals(130, h.values["TEMPO"])
        onNodeWithTag(K.KNOB).performTouchInput { turn(20f, -40f) }            // back through 0/360: -60 deg = -10
        waitForIdle(); assertEquals(120, h.values["TEMPO"])
    }

    @Test fun aTapOnTheKnobOrOnAParameterButtonNeverChangesAValue() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); setContent { SideColumn(h, 240, 520) }
        onNodeWithTag(K.KNOB).performClick()
        onNodeWithTag(K.KNOB).performTouchInput { val p = Offset(width * 0.8f, height * 0.3f); down(p); moveTo(p); up() }
        onNodeWithTag(K.param("RELEASE")).performClick(); onNodeWithTag(K.param("TEMPO")).performClick()
        waitForIdle()
        assertEquals(mapOf("TEMPO" to 120, "TRANSPOSE" to 0, "RELEASE" to 64), h.values.toMap()); assertTrue("nothing was sent", h.sent.isEmpty())
    }

    @Test fun limitsHoldAndNothingIsSentAtALimit() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); h.values["TRANSPOSE"] = 11; setContent { SideColumn(h, 240, 520) }
        onNodeWithTag(K.param("TRANSPOSE")).performClick()
        onNodeWithTag(K.KNOB).performTouchInput { turn(0f, 132f) }             // 6 detents of 22 deg, only one is possible
        waitForIdle()
        assertEquals(12, h.values["TRANSPOSE"]); onNodeWithTag(K.VALUE).assertTextEquals("+12")
        assertEquals("only the change 11 -> 12 is sent", listOf("TRANSPOSE" to 12), h.sent)
        h.values["RELEASE"] = 1; onNodeWithTag(K.param("RELEASE")).performClick()
        onNodeWithTag(K.KNOB).performTouchInput { turn(180f, 140f) }           // -10 detents of 4 deg
        waitForIdle(); assertEquals(0, h.values["RELEASE"]); assertEquals(listOf("TRANSPOSE" to 12, "RELEASE" to 0), h.sent)
    }

    @Test fun switchingTheActiveParameterRetargetsTheKnobOnly() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); setContent { SideColumn(h, 240, 520) }
        onNodeWithTag(K.param("RELEASE")).performClick(); waitForIdle()
        onNodeWithTag(K.LABEL).assertTextEquals("RELEASE"); onNodeWithTag(K.VALUE).assertTextEquals("64")
        onNodeWithTag(K.KNOB).performTouchInput { turn(90f, 130f) }            // +40 deg / 4 = +10
        waitForIdle(); assertEquals(74, h.values["RELEASE"]); assertEquals("tempo untouched", 120, h.values["TEMPO"])
        onNodeWithTag(K.param("TRANSPOSE")).performClick(); waitForIdle()
        onNodeWithTag(K.LABEL).assertTextEquals("TRANSPOSE"); onNodeWithTag(K.VALUE).assertTextEquals("+0")
    }

    @Test fun anExternalChangeIsShownAndTheNextTurnStartsFromIt() = runDesktopComposeUiTest(240, 520) {
        val h = Harness(); setContent { SideColumn(h, 240, 520) }
        h.values["TEMPO"] = 90                                                   // e.g. a Style load / registration recall / -/+ button
        waitForIdle(); onNodeWithTag(K.VALUE).assertTextEquals("90"); onNodeWithTag(K.paramValue("TEMPO"), useUnmergedTree = true).assertTextEquals("90")
        onNodeWithTag(K.KNOB).performTouchInput { turn(0f, 12f) }              // +2
        waitForIdle(); assertEquals(92, h.values["TEMPO"])
    }

    /** Panel heights from ~1000x600 dp (tight, MIUI large display size) to 1333x800 dp; widths of the 18 % side column. */
    @Test fun allThreeParametersAndTheBottomRowAreFullyInsideThePanelWithoutOverlap() {
        for (w in listOf(180, 210, 240)) for (hgt in listOf(220, 260, 300, 340, 420, 560)) runDesktopComposeUiTest(w, hgt) {
            val h = Harness(); setContent { SideColumn(h, w, hgt) }
            fun b(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val knob = b(K.KNOB); val params = b(K.PARAMS); val style = b(K.STYLE); val minus = b(K.MINUS); val plus = b(K.PLUS)
            for ((name, r) in listOf("knob" to knob, "params" to params, "style" to style, "minus" to minus, "plus" to plus) +
                    listOf("TEMPO", "TRANSPOSE", "RELEASE").map { it to b(K.param(it)) }) {
                assertTrue("${w}x$hgt $name inside the panel: $r", r.left >= -0.5f && r.top >= -0.5f && r.right <= w + 0.5f && r.bottom <= hgt + 0.5f)
                assertTrue("${w}x$hgt $name has a size: $r", r.width > 1f && r.height > 1f)
            }
            assertTrue("${w}x$hgt knob above the parameters", knob.bottom <= params.top + 0.5f)
            assertTrue("${w}x$hgt parameters above the bottom row", params.bottom <= minOf(style.top, minus.top, plus.top) + 0.5f)
            for (p in listOf("TEMPO", "TRANSPOSE", "RELEASE")) {
                onNodeWithTag(K.param(p)).assertIsDisplayed().assertHeightIsAtLeast(40.dp)
                assertTrue("${w}x$hgt $p button wide enough to touch", b(K.param(p)).width >= 40f)
            }
            onNodeWithTag(K.KNOB).assertIsDisplayed().assertHeightIsAtLeast(80.dp)
            // RELEASE can be selected and changed at every size
            onNodeWithTag(K.param("RELEASE")).performClick(); waitForIdle()
            onNodeWithTag(K.LABEL).assertTextEquals("RELEASE")
            onNodeWithTag(K.KNOB).performTouchInput { turn(90f, 130f) }        // +40 deg / 4 = +10
            waitForIdle(); assertEquals("${w}x$hgt RELEASE changed by the knob", 74, h.values["RELEASE"])
            onNodeWithTag(K.paramValue("RELEASE"), useUnmergedTree = true).assertTextEquals("74")
            println("KNOBUI panel ${w}x$hgt dp: knob ${knob.height.toInt()} dp, TEMPO/TRANSPOSE/RELEASE row ${params.height.toInt()} dp, [-] STYLE [+] ${style.height.toInt()} dp, inside and no overlap; RELEASE 64->74")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderPngEvidence() {
        val dir = File(System.getProperty("knob.png.dir") ?: "build/png-knob").apply { mkdirs() }
        for ((label, sel) in listOf("tempo" to "TEMPO", "transpose" to "TRANSPOSE", "release" to "RELEASE")) for ((w, hgt) in listOf(210 to 280, 230 to 440)) {
            val h = Harness(); h.selected = sel; h.values["TRANSPOSE"] = 3; h.values["TEMPO"] = 96
            val d = 1.5f
            val scene = ImageComposeScene((w * d).toInt(), (hgt * d).toInt(), Density(d)) { SideColumn(h, w, hgt) }
            scene.render(0L); val img = scene.render(250_000_000L)
            File(dir, "data_control_${label}_${w}x$hgt.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close()
        }
        assertTrue(dir.listFiles()!!.size >= 6)
    }
}

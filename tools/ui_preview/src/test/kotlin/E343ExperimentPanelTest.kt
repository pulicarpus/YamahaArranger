import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yourapp.yamahaarranger.ui.E343ExperimentContent
import com.yourapp.yamahaarranger.ui.E343ExperimentTags as T
import com.yourapp.yamahaarranger.ui.E343ExperimentUiState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PSR-E343 experiment page, compiled from the app's own source (app/src/main/java/com/yourapp/ui/E343ExperimentPanel.kt).
 * Desktop density 1 => 1 px = 1 dp. Sizes: Redmi Pad 10.61" landscape (2000x1200 px @ 1.5 = 1333x800 dp), Redmi Pad SE / Pro landscape (1280x800 dp),
 * and a small landscape phone (800x360 dp) as a stress case. Host evidence of layout and wiring only, not a device test.
 */
@OptIn(ExperimentalTestApi::class)
class E343ExperimentPanelTest {
    private val sizes = listOf(Triple("redmipad_1333x800", 1333, 800), Triple("redmipad_se_pro_1280x800", 1280, 800), Triple("small_landscape_800x360", 800, 360))
    private val inScope = E343ExperimentUiState(styleName = "EnglishWaltz2.S519.prs", styleInScope = true, routerWired = true)

    private fun everyControl(s: E343ExperimentUiState) = s.modes.map { T.mode(it) } + s.gates.map { T.gate(it) } + listOf(T.RESET)

    @Test fun everyControlIsReachableVisibleAndLargeAtEverySize() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            setContent { E343ExperimentContent(inScope, {}, {}, {}, {}) }
            onNodeWithTag(T.CLOSE).assertIsDisplayed().assertHeightIsAtLeast(48.dp)          // fixed header: visible without scrolling
            for (tag in everyControl(inScope)) onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            onNodeWithTag(T.STATS).performScrollTo().assertIsDisplayed()
            onNodeWithTag(T.VERIFIED).performScrollTo().assertIsDisplayed().assertTextContains("SHORT_GATE 120 ms", substring = true)
            onNodeWithTag(T.CLOSE).assertIsDisplayed()                                           // still visible after scrolling the body
            println("E343UI size=$name ${w}x$h dp: CLOSE + 3 modes + 5 gates + stats + reset reachable, buttons >= 48 dp")
        }
    }

    @Test fun buttonsDriveTheStateAndTheActiveModeIsShown() = runDesktopComposeUiTest(1333, 800) {
        val calls = ArrayList<String>(); var closed = false
        setContent {
            var st by remember { mutableStateOf(inScope) }
            E343ExperimentContent(st,
                onMode = { calls += "mode:$it"; st = st.copy(mode = it) },
                onGate = { calls += "gate:$it"; st = st.copy(gateMs = it) },
                onResetStats = { calls += "reset" }, onClose = { closed = true })
        }
        onNodeWithTag(T.ACTIVE).assertTextContains("LEGACY", substring = true)
        onNodeWithTag(T.mode("AUTHORED")).performScrollTo().performClick()
        onNodeWithTag(T.ACTIVE).assertTextContains("AUTHORED", substring = true)
        onNodeWithTag(T.mode("SHORT_GATE")).performScrollTo().performClick()
        onNodeWithTag(T.gate(80)).performScrollTo().performClick()
        onNodeWithTag(T.ACTIVE).assertTextContains("SHORT_GATE · gate 80 ms", substring = true)
        onNodeWithTag(T.mode("LEGACY")).performScrollTo().performClick()
        onNodeWithTag(T.RESET).performScrollTo().performClick()
        onNodeWithTag(T.CLOSE).performClick()
        assertEquals(listOf("mode:AUTHORED", "mode:SHORT_GATE", "gate:80", "mode:LEGACY", "reset"), calls)
        assertTrue(closed)
    }

    @Test fun anotherStyleIsLabelledNotApplicable() = runDesktopComposeUiTest(1280, 800) {
        setContent { E343ExperimentContent(E343ExperimentUiState(mode = "AUTHORED", styleName = "ROSITA.STY", styleInScope = false, routerWired = true), {}, {}, {}, {}) }
        onNodeWithTag(T.SCOPE).assertTextContains("NOT APPLICABLE", substring = true).assertTextContains("ROSITA.STY", substring = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderPngEvidence() {
        val dir = File(System.getProperty("e343.png.dir") ?: "build/png-e343").apply { mkdirs() }
        val states = listOf("authored" to inScope.copy(mode = "AUTHORED", tracked = 24, offAuthored = 23, offRelease = 1),
            "short_gate_80" to inScope.copy(mode = "SHORT_GATE", gateMs = 80, tracked = 12, offGate = 12),
            "not_applicable_rosita" to E343ExperimentUiState(styleName = "ROSITA.STY", routerWired = true))
        for ((sn, w, h) in sizes.take(2)) for ((label, st) in states) {
            val density = 1.5f
            val scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) { E343ExperimentContent(st, {}, {}, {}, {}) }
            scene.render(0L); val img = scene.render(250_000_000L)
            File(dir, "e343_${sn}_$label.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close()
        }
        assertTrue(dir.listFiles()!!.size >= 6)
    }
}

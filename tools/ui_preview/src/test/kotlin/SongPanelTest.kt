import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SongPlayer
import com.yourapp.yamahaarranger.song.SongRouting
import com.yourapp.yamahaarranger.song.SongStatus
import com.yourapp.yamahaarranger.song.SongTimeline
import com.yourapp.yamahaarranger.ui.SongPanel
import com.yourapp.yamahaarranger.ui.SongTags as T
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** SONG page (MIDI Song Player V1A) on desktop Compose, driven by the real SongPlayer + an SMF fixture. Host evidence, not a device test. */
@OptIn(ExperimentalTestApi::class)
class SongPanelTest {
    private val fixtures = File(System.getProperty("song.fixtures") ?: "../../tests/fixtures/midi")
    private fun loadedPlayer(clock: () -> Long = { 0L }) = SongPlayer(clock).apply {
        load(SongTimeline.from(Smf.parse(File(fixtures, "type1_16ch.mid").readBytes())), "type1_16ch.mid")
    }
    private val sizes = listOf(Triple("redmipad_1333x800", 1333, 800), Triple("redmipad_se_1280x800", 1280, 800), Triple("miui_large_1000x600", 1000, 600))

    @Test fun everyControlIsReachableAndLargeAtRedmiPadSizes() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            val p = loadedPlayer()
            setContent { SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
            onNodeWithTag(T.CLOSE).assertIsDisplayed()
            for (tag in listOf(T.OPEN, T.PREV, T.PLAY, T.STOP, T.NEXT, T.RECORD, T.SAVE)) onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            for (tag in listOf(T.TEMPO_DOWN, T.TEMPO_RESET, T.TEMPO_UP) + SongRouting.values().map { T.routing(it) })
                onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(40.dp)
            onNodeWithTag(T.SEEK).performScrollTo().assertIsDisplayed()
            for (ch in 0 until 16) onNodeWithTag(T.channel(ch)).performScrollTo().assertIsDisplayed()
            onNodeWithTag(T.MODE_KEYBOARD).performScrollTo().assertIsNotEnabled(); onNodeWithTag(T.MODE_FULL).assertIsNotEnabled()
            onNodeWithTag(T.CLOSE).assertIsDisplayed()
            println("SONGUI $name ${w}x$h dp: transport, tempo, output, seek, 16 channels reachable; recording shown as not available")
        }
    }

    @Test fun statusComesFromThePlayer() = runDesktopComposeUiTest(1333, 800) {
        val p = loadedPlayer()
        setContent { SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        onNodeWithTag(T.TITLE).assertTextEquals("type1_16ch.mid")
        onNodeWithTag(T.POSITION).assertTextEquals("001:01")
        onNodeWithTag(T.TIMESIG).assertTextEquals("4/4")
        onNodeWithTag(T.TIME).assertTextContains("0:00 / 0:09", substring = true)
        onNodeWithTag(T.PLAY).assertIsEnabled(); onNodeWithTag(T.RECORD).assertIsNotEnabled(); onNodeWithTag(T.SAVE).assertIsNotEnabled()
    }

    @Test fun buttonsReachTheCallbacksAndMuteShowsOnTheChannel() = runDesktopComposeUiTest(1333, 800) {
        val p = loadedPlayer(); val calls = ArrayList<String>()
        var st by mutableStateOf(p.status(0)); var routing by mutableStateOf(SongRouting.INTERNAL)
        setContent {
            SongPanel(st, routing, null, 0, { calls += "open" }, { calls += "play" }, { calls += "stop" }, { calls += "prev" }, { calls += "next" },
                { calls += "seek" }, { calls += "tempo:$it" }, { routing = it; calls += "routing:$it" }, { p.setMute(it, true); st = p.status(0); calls += "mute:$it" }, { calls += "close" })
        }
        onNodeWithTag(T.PLAY).performClick(); onNodeWithTag(T.STOP).performClick(); onNodeWithTag(T.OPEN).performClick()
        onNodeWithTag(T.TEMPO_UP).performScrollTo().performClick(); onNodeWithTag(T.routing(SongRouting.BOTH)).performScrollTo().performClick()
        onNodeWithTag(T.RECORD).performScrollTo().performClick()                       // disabled: no callback
        onNodeWithTag(T.channel(9)).performScrollTo().performClick()
        onNodeWithTag(T.CLOSE).performClick()
        assertEquals(listOf("play", "stop", "open", "tempo:105", "routing:BOTH", "mute:9", "close"), calls)
        assertTrue(p.status(0).muted[9])
        onNodeWithText("MUTE", useUnmergedTree = true).assertExists()
    }

    @Test fun emptyStateDisablesTransport() = runDesktopComposeUiTest(1280, 800) {
        val p = SongPlayer { 0L }
        setContent { SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        onNodeWithTag(T.TITLE).assertTextContains("No MIDI song loaded", substring = true)
        onNodeWithTag(T.OPEN).assertIsEnabled(); onNodeWithTag(T.PLAY).assertIsNotEnabled(); onNodeWithTag(T.STOP).assertIsNotEnabled()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderPngEvidence() {
        val dir = File(System.getProperty("song.png.dir") ?: "build/png-song").apply { mkdirs() }
        val p = loadedPlayer(); p.setMute(3, true)
        for ((name, w, h) in sizes) {
            val d = 1.5f
            val scene = ImageComposeScene((w * d).toInt(), (h * d).toInt(), Density(d)) {
                SongPanel(p.status(0), SongRouting.BOTH, "Loaded type1_16ch.mid: SMF Type 1, PPQ 960", 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
            scene.render(0L); val img = scene.render(250_000_000L)
            File(dir, "song_$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close()
        }
        assertTrue(dir.listFiles()!!.size >= 3)
    }
}

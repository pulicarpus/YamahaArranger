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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.yourapp.yamahaarranger.song.SongVoice
import com.yourapp.yamahaarranger.song.SustainMode
import com.yourapp.yamahaarranger.ui.SongMixerActions
import com.yourapp.yamahaarranger.ui.SongMixerTags as M
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
            for (tag in listOf(T.TEMPO_DOWN, T.TEMPO_RESET, T.TEMPO_UP, T.TRANSPOSE_DOWN, T.TRANSPOSE_UP, T.TRANSPOSE_RESET) + SongRouting.values().map { T.routing(it) })
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
                { calls += "seek" }, { calls += "tempo:$it" }, { routing = it; calls += "routing:$it" }, { p.setMute(it, true); st = p.status(0); calls += "mute:$it" }, { calls += "close" },
                onTempoNudge = { calls += "tempoNudge:$it" })
        }
        onNodeWithTag(T.PLAY).performClick(); onNodeWithTag(T.STOP).performClick(); onNodeWithTag(T.OPEN).performClick()
        onNodeWithTag(T.TEMPO_UP).performScrollTo().performClick(); onNodeWithTag(T.routing(SongRouting.BOTH)).performScrollTo().performClick()
        onNodeWithTag(T.RECORD).performScrollTo().performClick()                       // disabled: no callback
        onNodeWithTag(T.channel(9)).performScrollTo().performClick()
        onNodeWithTag(T.CLOSE).performClick()
        assertEquals(listOf("play", "stop", "open", "tempoNudge:1", "routing:BOTH", "mute:9", "close"), calls)
        assertTrue(p.status(0).muted[9])
        onNodeWithText("MUTE", useUnmergedTree = true).assertExists()
    }

    @Test fun emptyStateDisablesTransport() = runDesktopComposeUiTest(1280, 800) {
        val p = SongPlayer { 0L }
        setContent { SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        onNodeWithTag(T.TITLE).assertTextContains("No MIDI song loaded", substring = true)
        onNodeWithTag(T.OPEN).assertIsEnabled(); onNodeWithTag(T.PLAY).assertIsNotEnabled(); onNodeWithTag(T.STOP).assertIsNotEnabled()
    }

    // ---------------- SONG TEMPO / SONG TRANSPOSE ----------------
    @Test fun songTempoAndTransposeKeysDriveTheRealPlayer() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            val p = loadedPlayer(); var st by mutableStateOf(p.status(0))
            setContent {
                SongPanel(st, SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onTempoNudge = { p.nudgeTempoBpm(it); st = p.status(0) }, onTempoReset = { p.resetTempo(); st = p.status(0) },
                    onTranspose = { p.setTranspose(it); st = p.status(0) })
            }
            val file = st.fileBpm
            onNodeWithTag(T.TEMPO_UP).performScrollTo().assertHeightIsAtLeast(48.dp).performClick(); onNodeWithTag(T.TEMPO_UP).performClick()
            assertEquals("$name: +2 BPM", Math.round(file) + 2.0, p.status(0).effectiveBpm, 1e-6)
            onNodeWithTag(T.TEMPO).assertTextContains("%.1f BPM".format(Math.round(file) + 2.0))
            onNodeWithTag(T.TEMPO_DOWN).performScrollTo().performClick(); assertEquals(Math.round(file) + 1.0, p.status(0).effectiveBpm, 1e-6)
            onNodeWithTag(T.TEMPO_RESET).performScrollTo().assertIsEnabled().performClick(); assertEquals(1.0, p.status(0).tempoFactor, 0.0)
            onNodeWithTag(T.TEMPO_RESET).assertIsNotEnabled()
            repeat(3) { onNodeWithTag(T.TRANSPOSE_UP).performScrollTo().performClick() }
            assertEquals(3, p.status(0).transpose); onNodeWithTag(T.TRANSPOSE).assertTextEquals("+3")
            repeat(5) { onNodeWithTag(T.TRANSPOSE_DOWN).performScrollTo().performClick() }
            assertEquals(-2, p.status(0).transpose); onNodeWithTag(T.TRANSPOSE).assertTextEquals("-2")
            onNodeWithTag(T.TRANSPOSE_RESET).performScrollTo().assertHeightIsAtLeast(48.dp).performClick(); assertEquals(0, p.status(0).transpose)
            println("SONGUI $name song tempo -1/+1/ORIGINAL and transpose -/+/0 reachable (>= 48 dp) and drive the real player")
        }
    }

    // ---------------- V1A.2 SONG MIXER ----------------
    private val voices = listOf(SongVoice(1, "colombo.sf2", 0, 48, "Strings Ensemble"), SongVoice(1, "colombo.sf2", 128, 0, "Standard Kit"),
        SongVoice(2, "tyros4.sf2", 0, 49, "Slow Strings"), SongVoice(2, "tyros4.sf2", 128, 25, "Analog Kit"))

    @Test fun mixerControlsAreLargeAndReachableAtRedmiPadSizes() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            val p = loadedPlayer()
            setContent { SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, voices = voices) }
            onNodeWithTag(M.TAB_MIXER).performScrollTo().performClick()
            for (ch in 0 until 16) onNodeWithTag(M.strip(ch)).performScrollTo().assertIsDisplayed()
            for (tag in listOf(M.VOICE, M.MUTE, M.SOLO, M.RESET, M.VOL_MIDI, M.PAN_MIDI) + SustainMode.values().map { M.sustain(it) })
                onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            onNodeWithTag(M.VOL).performScrollTo().assertIsDisplayed(); onNodeWithTag(M.PAN).performScrollTo().assertIsDisplayed()
            onNodeWithTag(T.CLOSE).assertIsDisplayed()
            println("SONGUI $name mixer: 16 strips, voice / mute / solo / reset / volume / pan / 3 sustain modes reachable, keys >= 48 dp")
        }
    }

    @Test fun mixerDrivesTheRealPlayer() = runDesktopComposeUiTest(1333, 800) {
        val p = loadedPlayer(); var st by mutableStateOf(p.status(0))
        fun r() { st = p.status(0) }
        val acts = SongMixerActions(onVoice = { c, v -> p.setVoice(c, v); r() }, onVolume = { c, v -> p.setVolume(c, v); r() }, onPan = { c, v -> p.setPan(c, v); r() },
            onSustain = { c, m -> p.setSustainMode(c, m); r() }, onMute = { p.setMute(it, !st.muted[it]); r() }, onSolo = { p.setSolo(it, !st.solo[it]); r() },
            onReset = { p.resetChannel(it); r() })
        setContent { SongPanel(st, SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, voices = voices, mixer = acts) }
        onNodeWithTag(M.TAB_MIXER).performScrollTo().performClick()
        onNodeWithTag(M.strip(2)).performScrollTo().performClick()
        onNodeWithTag(M.sustain(SustainMode.IGNORE)).performScrollTo().performClick()
        assertEquals(SustainMode.IGNORE, p.overrides()[2].sustain)
        onNodeWithTag(M.VOL).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        assertEquals(30, p.overrides()[2].volume); onNodeWithTag(M.VOL_TEXT).assertTextContains("30 USER")
        onNodeWithTag(M.PAN).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        assertEquals(20, p.overrides()[2].pan); onNodeWithTag(M.PAN_TEXT).assertTextContains("L44", substring = true)
        onNodeWithTag(M.SOLO).performScrollTo().performClick(); assertTrue(p.status(0).solo[2]); assertTrue(p.status(0).silent[0])
        onNodeWithTag(M.VOICE).performScrollTo().performClick()
        onNodeWithTag(M.PICKER).assertIsDisplayed()
        onNodeWithTag(M.pickerSf2("tyros4.sf2")).performClick()
        onNodeWithTag(M.pickerItem(0)).performClick()
        assertEquals("melodic channel: only melodic presets, filtered by SF2", "Slow Strings", p.overrides()[2].voice?.name)
        onNodeWithTag(M.VOICE_TEXT).assertTextContains("Slow Strings", substring = true)
        onNodeWithTag(M.RESET).performScrollTo().performClick()
        assertTrue(p.overrides()[2].isDefault); assertTrue(!p.status(0).solo[2])
        // the drum channel offers drum kits only
        onNodeWithTag(M.strip(9)).performScrollTo().performClick()
        onNodeWithTag(M.VOICE).performScrollTo().performClick()
        onNodeWithTag(M.pickerItem(0)).assertTextContains("Standard Kit", substring = true)
        onNodeWithTag(M.pickerItem(1)).assertTextContains("Analog Kit", substring = true); onNodeWithTag(M.pickerItem(2)).assertDoesNotExist()
        onNodeWithTag(M.PICKER_CANCEL).performClick(); onNodeWithTag(M.PICKER).assertDoesNotExist()
        assertTrue(p.overrides()[9].isDefault)
    }

    // ---------------- V1B MIDI RECORDER (KEYBOARD ONLY) ----------------
    @Test fun recorderControlsAreEnabledOnlyWhereTheyWork() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            val p = loadedPlayer(); val calls = ArrayList<String>()
            var rec by mutableStateOf(com.yourapp.yamahaarranger.ui.RecorderUi(available = true))
            setContent {
                SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, recorder = rec,
                    onRecord = { calls += "rec"; rec = rec.copy(recording = !rec.recording, elapsedUs = 65_000_000, events = 12, hasTake = rec.recording) },
                    onSaveTake = { calls += "save" }, onLoadTake = { calls += "load" })
            }
            onNodeWithTag(T.RECORD).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            onNodeWithTag(T.SAVE).assertIsNotEnabled(); onNodeWithTag(T.REC_LOAD).performScrollTo().assertIsNotEnabled()
            onNodeWithTag(T.MODE_FULL).performScrollTo().assertIsNotEnabled()                        // V1C: not before V1B is verified
            onNodeWithTag(T.RECORD).performScrollTo().performClick()                                  // start
            onNodeWithTag(T.RECORD).assertTextContains("STOP REC", substring = true)
            onNodeWithTag(T.REC_TIME).performScrollTo().assertTextContains("● REC 1:05")
            onNodeWithTag(T.SAVE).assertIsNotEnabled()                                               // not while recording
            onNodeWithTag(T.RECORD).performScrollTo().performClick()                                  // stop -> a take exists
            onNodeWithTag(T.SAVE).performScrollTo().assertIsEnabled().performClick()
            onNodeWithTag(T.REC_LOAD).performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf("rec", "rec", "save", "load"), calls)
            println("SONGUI $name recorder: RECORD / STOP REC / SAVE MIDI / LOAD TAKE reachable; FULL ARRANGER disabled (V1C)")
        }
    }

    @Test fun fullArrangerModeAndExportFlavourAreSelectable() {
        for ((name, w, h) in sizes) runDesktopComposeUiTest(w, h) {
            val p = loadedPlayer(); val calls = ArrayList<String>()
            var rec by mutableStateOf(com.yourapp.yamahaarranger.ui.RecorderUi(available = true, fullAvailable = true))
            setContent {
                SongPanel(p.status(0), SongRouting.INTERNAL, null, 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, recorder = rec,
                    onRecord = { calls += "rec"; rec = rec.copy(recording = !rec.recording) },
                    onRecordMode = { f -> calls += "full:$f"; rec = rec.copy(full = f) }, onExportGm = { g -> calls += "gm:$g"; rec = rec.copy(gm = g) })
            }
            onNodeWithTag(T.MODE_FULL).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(40.dp).performClick()
            onNodeWithTag(T.RECORD).performScrollTo().assertTextContains("FULL ARRANGER", substring = true)
            onNodeWithTag(T.EXPORT_GM).performScrollTo().assertHeightIsAtLeast(40.dp).performClick()
            onNodeWithTag(T.EXPORT_NATIVE).performScrollTo().performClick()
            onNodeWithTag(T.RECORD).performScrollTo().performClick()
            onNodeWithTag(T.MODE_KEYBOARD).performScrollTo().assertIsNotEnabled(); onNodeWithTag(T.MODE_FULL).assertIsNotEnabled()   // locked while recording
            assertEquals(listOf("full:true", "gm:true", "gm:false", "rec"), calls)
            println("SONGUI $name recorder V1C: FULL ARRANGER + export YA NATIVE / GM selectable, mode locked while recording")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderMixerPngEvidence() {
        val dir = File(System.getProperty("song.png.dir") ?: "build/png-song").apply { mkdirs() }
        val p = loadedPlayer(); p.setVoice(2, voices[2]); p.setVolume(2, 90); p.setSustainMode(2, SustainMode.IGNORE); p.setSolo(9, true)
        for ((name, w, h) in sizes) for ((kind, picker) in listOf("mixer" to false, "picker" to true)) {
            val d = 1.5f
            val scene = ImageComposeScene((w * d).toInt(), (h * d).toInt(), Density(d)) {
                SongPanel(p.status(0), SongRouting.INTERNAL, "CH3: Slow Strings — voice active", 0, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    voices = voices, startInMixer = true, startChannel = 2, startPicker = picker)
            }
            scene.render(0L); val img = scene.render(250_000_000L)
            File(dir, "song_${kind}_$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes); scene.close()
        }
        assertTrue(dir.listFiles()!!.count { it.name.startsWith("song_mixer_") } >= 3)
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

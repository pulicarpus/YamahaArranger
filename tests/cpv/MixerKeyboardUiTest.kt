package com.yourapp.yamahaarranger.cpv

import com.yourapp.midi.ChannelActivity
import com.yourapp.midi.ChannelActivity.Source
import com.yourapp.midi.LiveKeyState
import com.yourapp.yamahaarranger.audio.MixerConsoleState
import com.yourapp.yamahaarranger.audio.VirtualKeyboardController
import com.yourapp.yamahaarranger.audio.VirtualKeyboardLayout
import com.yourapp.yamahaarranger.audio.VirtualKeyboardSink
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Live MIDI IN key mirror, per-part activity indicators and the wiring of the new Mixer page. Compose layout is compiled in CI, not rendered here. */
class MixerKeyboardUiTest {
    private fun src(rel: String) = File(System.getProperty("user.dir"), "app/src/main/java/com/yourapp/$rel").readText()

    // ---- LiveKeyState (what the on-screen keyboard shows for the physical keyboard) ----
    @Test fun noteOn60LightsC4AndNoteOffReleasesIt() {
        val k = LiveKeyState()
        k.noteOn(60, 90); assertTrue(k.isDown(60)); assertEquals(90, k.velocityOf(60)); assertEquals(listOf(60), k.downNotes())
        k.noteOff(60); assertFalse(k.isDown(60)); assertEquals(0, k.downCount())
    }

    @Test fun noteOnWithVelocityZeroIsNoteOff() {
        val k = LiveKeyState(); k.noteOn(60, 80); k.noteOn(60, 0)
        assertFalse(k.isDown(60))
    }

    @Test fun chordLightsThreeKeysAndOnlyThoseReleaseOneByOne() {
        val k = LiveKeyState()
        listOf(60, 64, 67).forEach { k.noteOn(it, 100) }
        assertEquals(listOf(60, 64, 67), k.downNotes())
        k.noteOff(64); assertEquals(listOf(60, 67), k.downNotes())
        k.noteOff(60); k.noteOff(67); assertEquals(0, k.downCount()); assertNull(k.range())
    }

    @Test fun fastRepeatedPressesNeverLeaveAKeyLit() {
        val k = LiveKeyState()
        repeat(500) { i -> k.noteOn(60 + (i % 5), 1 + i % 127); if (i % 3 != 0) k.noteOff(60 + (i % 5)) }
        for (n in 60..64) k.noteOff(n)
        assertEquals(0, k.downCount())
        val v = k.version; k.noteOn(61, 50); k.noteOn(61, 50); assertEquals("a repeated identical press is not a new visible change", v + 1, k.version)
        k.noteOff(61); k.noteOff(61); assertEquals(v + 2, k.version)
    }

    @Test fun clearOnDisconnectResetsEveryKeyAndIgnoresOutOfRangeNotes() {
        val k = LiveKeyState(); listOf(36, 60, 96, 127, 0).forEach { k.noteOn(it, 70) }
        k.noteOn(-1, 70); k.noteOn(128, 70); k.noteOff(200)
        assertEquals(5, k.downCount())
        k.clear(); assertEquals(0, k.downCount())
        val v = k.version; k.clear(); assertEquals("clearing an already clear keyboard changes nothing", v, k.version)
    }

    @Test fun maskAndRangeFollowOriginalNoteNumbersWindowedToTheKeyboard() {
        val k = LiveKeyState(); k.noteOn(36, 60); k.noteOn(48, 60); k.noteOn(100, 60)
        val low = VirtualKeyboardLayout.pitchOf(0, 0)
        val mask = k.mask(low, VirtualKeyboardLayout.KEY_COUNT)
        assertEquals((1L shl (36 - low)) or (1L shl (48 - low)), mask and ((1L shl 61) - 1).let { it })
        assertEquals(36..100, k.range())
        assertEquals("shifting the on-screen octave only moves the window, never the stored notes", listOf(36, 48, 100), k.downNotes())
    }

    @Test fun touchAndPhysicalOnTheSameNoteDoNotDisturbEachOther() {
        class Sink : VirtualKeyboardSink { override fun noteOn(pitch: Int, velocity: Int) {}; override fun noteOff(pitch: Int) {}; override fun setSustain(enabled: Boolean) {}; override fun releaseAll() {} }
        val touch = VirtualKeyboardController(Sink()); val phys = LiveKeyState()
        val index = 12; val pitch = VirtualKeyboardLayout.pitchOf(index, 0)
        touch.pointerDown(1, index); phys.noteOn(pitch, 99)
        fun lit() = LiveKeyState.combine(touch.pressedMask, phys.mask(VirtualKeyboardLayout.pitchOf(0, 0), VirtualKeyboardLayout.KEY_COUNT)) and (1L shl index) != 0L
        assertTrue(lit())
        phys.noteOff(pitch); assertTrue("finger still holds it", lit())
        phys.noteOn(pitch, 99); touch.pointerUp(1); assertTrue("physical key still holds it", lit())
        phys.noteOff(pitch); assertFalse(lit())
    }

    // ---- ChannelActivity (indicator data) ----
    @Test fun activityFollowsRealNoteOnsAndDecaysAndSourcesAreSeparate() {
        ChannelActivity.reset()
        val t0 = 1_000_000_000L
        assertEquals(0f, ChannelActivity.level(Source.NOTES, 11, t0), 0f)
        ChannelActivity.noteOn(Source.NOTES, 11, 127, t0)
        assertEquals(1f, ChannelActivity.level(Source.NOTES, 11, t0), 1e-6f)
        assertTrue("decays", ChannelActivity.level(Source.NOTES, 11, t0 + 450_000_000L) in 0.3f..0.4f)
        assertEquals("fully gone after a few time constants", 0f, ChannelActivity.level(Source.NOTES, 11, t0 + 5_000_000_000L), 0f)
        assertEquals("another channel is untouched", 0f, ChannelActivity.level(Source.NOTES, 10, t0), 0f)
        assertEquals("the MIDI OUT indicator does not move for internal note activity", 0f, ChannelActivity.level(Source.MIDI_OUT, 11, t0), 0f)
        ChannelActivity.noteOn(Source.MIDI_OUT, 12, 64, t0); assertEquals(64 / 127f, ChannelActivity.level(Source.MIDI_OUT, 12, t0), 1e-6f)
        ChannelActivity.noteOn(Source.NOTES, 11, 0, t0)    // velocity 0 is not an activity
        ChannelActivity.noteOn(Source.NOTES, 99, 100, t0); ChannelActivity.noteOn(Source.NOTES, -1, 100, t0)
        assertTrue(ChannelActivity.active(Source.NOTES, 11, t0))
        ChannelActivity.reset(); assertFalse(ChannelActivity.active(Source.NOTES, 11, t0))
    }

    // ---- Mute / Solo semantics (unchanged, gain-only) ----
    @Test fun muteAndSoloControlTheRightChannelsOnly() {
        var c = MixerConsoleState()
        assertFalse(c.muted(1)); c = c.toggleMute(1); assertTrue(c.muted(1)); assertFalse(c.muted(0)); assertFalse(c.muted(2))
        c = c.toggleSolo(11)
        assertFalse("the soloed channel plays", c.muted(11)); assertTrue("every other part is silenced", c.muted(10) && c.muted(0) && c.muted(12))
        assertEquals(0, c.keyboardGain(0, 100)); assertEquals(0, c.keyboardGain(1, 100))
        c = c.toggleSolo(11).toggleMute(1); assertEquals(100, c.keyboardGain(0, 100)); assertEquals(100, c.keyboardGain(1, 100))
    }

    // ---- Wiring of the new Mixer / keyboard UI ----
    @Test fun mixerPageHasBackActiveFilterKeyboardToggleAndHonestMeterLabels() {
        val page = src("ui/SxMainScreen.kt"); val strips = src("ui/MixerStripUi.kt")
        assertTrue(page.contains("\"← BACK\"") && page.contains("onClick = onDismiss"))
        assertTrue(page.contains("\"ACTIVE ONLY\"") && page.contains("\"ALL CHANNELS\"") && page.contains("\"HIDE KEYBOARD\"") && page.contains("\"SHOW KEYBOARD\""))
        assertTrue("old strips are gone", !page.contains("SxConsoleStrip") && !page.contains("SxVerticalFader") && !page.contains("SxConsoleBus"))
        assertTrue(page.contains("MixerStrip(") && page.contains("MixerBusStrip(\"MASTER\"") && page.contains("horizontalScroll(rememberScrollState())"))
        assertTrue("part meters use MIDI OUT activity while MIDI OUT is on, note activity otherwise", page.contains("if (state.midiOutEnabled) MeterKind.MIDI_OUT else MeterKind.NOTES"))
        assertTrue(strips.contains("not an audio level") && strips.contains("not an audio measurement"))
        assertFalse("part meters never read the fader value", Regex("level\\(.*(volume|styleVolume)").containsMatchIn(page))
        assertTrue("only MASTER gets an audio level", page.contains("{ masterLevel.value }") && page.contains("MixerBusStrip(\"STYLE\"") && page.contains("clock, null, vm::onStyleVolumeChange"))
        assertTrue(strips.contains("Slider(value = pan.toFloat()") && strips.contains("onPan(snapPan(") && src("ui/MixerPure.kt").contains("fun snapPan"))
    }

    @Test fun snapPanAndLabels() {
        assertEquals(64, com.yourapp.yamahaarranger.ui.snapPan(62)); assertEquals(64, com.yourapp.yamahaarranger.ui.snapPan(67)); assertEquals(60, com.yourapp.yamahaarranger.ui.snapPan(60)); assertEquals(0, com.yourapp.yamahaarranger.ui.snapPan(-5))
        assertEquals("PAN C", com.yourapp.yamahaarranger.ui.panText(64)); assertEquals("PAN L64", com.yourapp.yamahaarranger.ui.panText(0)); assertEquals("PAN R63", com.yourapp.yamahaarranger.ui.panText(127))
    }

    @Test fun keyboardPanelOnlyReadsTheLiveKeysAndNeverSendsNotes() {
        val panel = src("ui/VirtualKeyboardPanel.kt")
        assertTrue(panel.contains("viewModel.liveKeys") && panel.contains("liveKeys.velocityOf(") && panel.contains("\"HIDE KEYBOARD\""))
        assertFalse("the mirror never writes to the live key state or plays anything", Regex("liveKeys\\.(noteOn|noteOff|clear)\\(").containsMatchIn(panel))
        val parser = src("midi/MidiInputManager.kt")
        assertTrue(parser.contains("liveKeys.noteOn(d1, d2)") && parser.contains("liveKeys.noteOff(d1)") && parser.contains("liveKeys.clear()"))
    }
}

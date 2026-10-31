package com.yourapp.yamahaarranger.cpv

import com.yourapp.midi.LiveKeyState
import com.yourapp.midi.MidiInStats
import com.yourapp.yamahaarranger.audio.KeyboardInputSettings
import com.yourapp.yamahaarranger.audio.KeyboardInputSettingsStore
import com.yourapp.yamahaarranger.audio.KeySource
import com.yourapp.yamahaarranger.audio.SplitByKey
import com.yourapp.yamahaarranger.audio.SplitPoint
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Split point rules, SET BY KEY, persisted keyboard input settings, MIDI IN diagnostics and the wiring of the new UI. Compose is compiled in CI, not rendered here. */
class KeyboardSplitMidiInTest {
    private fun src(rel: String) = File(System.getProperty("user.dir"), "app/src/main/java/com/yourapp/$rel").readText()

    @Test fun defaultSplitIsTheBaselineF3AndNamesFollowC4Is60() {
        assertEquals(54, SplitPoint.DEFAULT); assertEquals("F#3", SplitPoint.name(54)); assertEquals("C4", SplitPoint.name(60)); assertEquals("C2", SplitPoint.name(36)); assertEquals("C7", SplitPoint.name(96))
        assertEquals("LEFT ≤ F#3  ·  RIGHT ≥ G3", SplitPoint.describe(54))
    }

    @Test fun theKeyAtTheSplitIsLeftAndEverythingAboveIsRightLikeTheBaseline() {
        assertEquals(SplitPoint.Zone.LEFT, SplitPoint.zoneOf(54, 54)); assertEquals(SplitPoint.Zone.RIGHT, SplitPoint.zoneOf(55, 54)); assertEquals(SplitPoint.Zone.LEFT, SplitPoint.zoneOf(36, 54))
        val brain = src("arranger/ArrangerBrain.kt")
        assertTrue("the baseline routing test is `midiNote > splitNote` = RIGHT", brain.contains("if (midiNote > splitNote) {"))
    }

    @Test fun splitMovesPerSemitoneAndStaysInsideTheKeyboardWithAKeyOnBothSides() {
        var s = SplitPoint.DEFAULT
        s = SplitPoint.step(s, 1); assertEquals(55, s); s = SplitPoint.step(s, -2); assertEquals(53, s)
        assertEquals(SplitPoint.MAX, SplitPoint.step(94, 5)); assertEquals(SplitPoint.MIN, SplitPoint.step(37, -9))
        assertEquals(SplitPoint.MIN, SplitPoint.clamp(0)); assertEquals(SplitPoint.MAX, SplitPoint.clamp(127))
        assertTrue("at least one key right of the highest split and one left of the lowest", SplitPoint.MAX < 96 && SplitPoint.MIN >= 36)
    }

    @Test fun setByKeyUsesTheNextPressFromUsbOrTouchAndSwallowsItsRelease() {
        for (source in KeySource.values()) {
            val k = SplitByKey()
            assertNull("not armed: normal playing", k.onPress(source, 60)); assertFalse(k.onRelease(source, 60))
            k.arm(); assertTrue(k.armed)
            assertEquals(67, k.onPress(source, 67)); assertFalse("consumed: armed once", k.armed)
            assertNull("the following press plays normally", k.onPress(source, 70))
            assertTrue("release of the swallowed key is swallowed", k.onRelease(source, 67)); assertFalse("only once", k.onRelease(source, 67))
            assertFalse("other keys release normally", k.onRelease(source, 70))
        }
    }

    @Test fun setByKeySourcesAreIndependentAndCancelWorks() {
        val k = SplitByKey(); k.arm(); assertEquals(50, k.onPress(KeySource.TOUCH, 50))
        assertFalse("a USB release of the same note number is not the swallowed touch key", k.onRelease(KeySource.USB_MIDI, 50)); assertTrue(k.onRelease(KeySource.TOUCH, 50))
        k.arm(); k.cancel(); assertNull(k.onPress(KeySource.USB_MIDI, 60))
        k.arm(); assertEquals("a key outside the valid split range is clamped", SplitPoint.MAX, k.onPress(KeySource.USB_MIDI, 120))
    }

    @Test fun splitAndChannelSettingsSurviveARestartAndBadFilesFallBackToTheBaseline() {
        val dir = Files.createTempDirectory("kbin").toFile(); val f = File(dir, "keyboard_input.txt")
        assertEquals(KeyboardInputSettings(54, 0), KeyboardInputSettingsStore(f).read())
        KeyboardInputSettingsStore(f).write(KeyboardInputSettings(60, 1 shl 2))
        assertEquals(KeyboardInputSettings(60, 4), KeyboardInputSettingsStore(f).read())     // a new store object = app restart
        KeyboardInputSettingsStore(f).write(KeyboardInputSettings(500, 0xFFFF)); val clamped = KeyboardInputSettingsStore(f).read()
        assertEquals(SplitPoint.MAX, clamped.split); assertEquals("channel 1 and the Style output channels 9..16 can never be added", 0xFE, clamped.extraChannels)
        f.writeText("split=7\nchannels=abc\n"); assertEquals(KeyboardInputSettings(54, 0), KeyboardInputSettingsStore(f).read())
        f.writeText("garbage"); assertEquals(KeyboardInputSettings(54, 0), KeyboardInputSettingsStore(f).read())
    }

    @Test fun midiInStatsSeparatePlayedFromIgnoredAndNeverReceivedChannels() {
        val s = MidiInStats()
        assertTrue(s.summary(54).contains("no NOTE_ON received"))
        for (n in 60..64) s.noteOn(0, n, true)                      // right zone on channel 1, played
        for (n in 40..48) s.noteOn(2, n, false)                      // left zone arrives on channel 3, filtered out
        val lines = s.lines(54)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("ch1:") && lines[0].contains("only RIGHT-area keys") && lines[0].endsWith("played"))
        assertTrue(lines[1], lines[1].startsWith("ch3:") && lines[1].contains("only LEFT-area keys") && lines[1].contains("IGNORED by the channel filter"))
        assertEquals(0, s.noteOnCount(5)); assertEquals(9, s.ignoredCount(2)); s.reset(); assertTrue(s.summary(54).contains("no NOTE_ON received"))
    }

    @Test fun ignoredKeysAreShownSeparatelyFromPlayedKeysAndClearWithTheDevice() {
        val k = LiveKeyState()
        k.noteOn(60, 90); k.ignoredNoteOn(45, 80)
        assertEquals(listOf(60), k.downNotes()); assertEquals(listOf(45), k.ignoredNotes()); assertEquals(0, k.velocityOf(45)); assertEquals(80, k.ignoredVelocityOf(45))
        k.ignoredNoteOn(45, 0); assertTrue(k.ignoredNotes().isEmpty())
        k.ignoredNoteOn(46, 50); k.ignoredNoteOn(-1, 50); k.ignoredNoteOn(200, 50)
        k.clear(); assertEquals(0, k.downCount()); assertTrue(k.ignoredNotes().isEmpty())
    }

    @Test fun uiAndViewModelWiringFollowsTheContract() {
        val live = src("ui/LiveControlPanels.kt"); val screen = src("ui/LiveControlScreen.kt")
        assertTrue(live.contains("HardwareLedButton(\"SPLIT\", splitName") && screen.contains("onSplit={ SplitHost.open() }") && screen.contains("SplitPoint.name(splitNote)"))
        val dlg = src("ui/SplitDialog.kt")
        for (s in listOf("\"SET BY KEY\"", "\"−\"", "\"+\"", "RESET (", "SplitPoint.describe(split)", "vm.toggleInputChannel(ch)", "vm.midiInReport()")) assertTrue(s, dlg.contains(s))
        assertTrue(src("ui/MainActivity.kt").contains("SplitDialogHost(viewModel)"))
        val panel = src("ui/VirtualKeyboardPanel.kt")
        assertTrue(panel.contains("Split line") && panel.contains("liveKeys.ignoredVelocityOf(") && panel.contains("SplitHost.open()") && panel.contains("LEFT area slightly cool"))
        val vm = src("ui/MainViewModel.kt")
        assertTrue("split and channels are persisted and restored", vm.contains("keyboardInputStore.read()") && vm.contains("keyboardInputStore.write(s)") && vm.contains("keyboard_input.txt"))
        assertTrue("moving the split uses the non-resetting setter", vm.contains("arrangerBrain.setSplitNote(") && !vm.contains("arrangerBrain.setSplitPoint("))
        assertTrue("both USB and touch consult SET BY KEY before playing", vm.contains("splitByKey.onPress(com.yourapp.yamahaarranger.audio.KeySource.USB_MIDI, note)") && vm.contains("KeySource.TOUCH, pitch"))
        val brain = src("arranger/ArrangerBrain.kt"); val fn = brain.substring(brain.indexOf("fun setSplitNote("), brain.indexOf("fun splitNoteValue()"))
        assertFalse("moving the split never resets chords, transpose or sounding notes", Regex("chordDetector|chordHeld|appliedChord|commitTranspose|noteOff|allNotesOff|reset").containsMatchIn(fn))
    }
}

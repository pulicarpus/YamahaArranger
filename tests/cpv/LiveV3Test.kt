package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.mixerFaderValue
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.LiveSectionGrid
import com.yourapp.yamahaarranger.ui.LiveSectionInventory
import com.yourapp.yamahaarranger.ui.SectionLed
import com.yourapp.yamahaarranger.ui.SectionPresence
import com.yourapp.yamahaarranger.ui.liveSectionEnabled
import com.yourapp.yamahaarranger.ui.liveSectionLed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIVE Control V3: section LED model, independent faders, no ghost controls, protected paths untouched. */
class LiveV3Test {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { if (File(dir, "app/src/main/java").exists()) return dir; dir = dir.parentFile }
        throw AssertionError("repo root not found")
    }
    private fun ui(name: String) = File(root(), "app/src/main/java/com/yourapp/ui/$name").readText()
    private fun style(vararg sections: String) = ParsedStyle(
        fileName = "t.sty", ppq = 480,
        sections = sections.associateWith { StyleSectionModel(it, 1920, emptyList()) })
    private val all = LiveSectionGrid.rows.flatten()
    private fun button(label: String) = all.first { it.label == label }

    // A style that has only INTRO 1/2, MAIN A/B/C and ENDING 1/2 (the Dangdut example, built here, not hardcoded in the UI).
    private val dangdut = LiveSectionInventory.from(style("IntroA", "IntroB", "MainA", "MainB", "MainC", "FillAA", "FillBB", "FillCC", "EndingA", "EndingB"))

    @Test fun gridHasEveryRequestedKeyOnce() {
        assertEquals(listOf(3, 4, 4, 3), LiveSectionGrid.rows.map { it.size })
        assertEquals(14 + 0 + 0, all.size)
        assertEquals(all.size, all.map { it.label }.distinct().size)
        assertEquals(listOf("Intro 1", "Intro 2", "Intro 3"), LiveSectionGrid.intro.map { it.label })
        assertEquals(listOf("Fill A", "Fill B", "Fill C", "Fill D"), LiveSectionGrid.fill.map { it.label })
        assertEquals(listOf("Ending 1", "Ending 2", "Ending 3"), LiveSectionGrid.ending.map { it.label })
    }

    @Test fun availableAbsentAndActiveFromParsedMetadata() {
        assertEquals(SectionLed.AVAILABLE, liveSectionLed(dangdut, button("Intro 1"), "Main A"))
        assertEquals(SectionLed.ABSENT, liveSectionLed(dangdut, button("Intro 3"), "Main A"))
        assertEquals(SectionLed.ABSENT, liveSectionLed(dangdut, button("Main D"), "Main A"))
        assertEquals(SectionLed.ABSENT, liveSectionLed(dangdut, button("Ending 3"), "Main A"))
        assertEquals(SectionLed.ABSENT, liveSectionLed(dangdut, button("Fill D"), "Main A"))
        assertEquals(SectionLed.ACTIVE, liveSectionLed(dangdut, button("Main B"), "Main B"))
        assertEquals(SectionLed.AVAILABLE, liveSectionLed(dangdut, button("Main A"), "Main B"))
        assertFalse("absent keys cannot be pressed", liveSectionEnabled(SectionLed.ABSENT))
        assertTrue(liveSectionEnabled(SectionLed.AVAILABLE)); assertTrue(liveSectionEnabled(SectionLed.ACTIVE))
        // An absent section is never shown as active, even if the stale label still names it.
        assertEquals(SectionLed.ABSENT, liveSectionLed(dangdut, button("Main D"), "Main D"))
    }

    @Test fun unknownIsNeitherAbsentNorFabricated() {
        for (inv in listOf(LiveSectionInventory.UNKNOWN, LiveSectionInventory.from(null), LiveSectionInventory.from(style()))) {
            assertFalse(inv.known)
            for (b in all) {
                assertEquals(SectionPresence.UNKNOWN, inv.presence(b.styleName))
                val led = liveSectionLed(inv, b, "Main A")
                assertTrue("unknown must not be claimed absent: ${b.label}", led != SectionLed.ABSENT)
                assertTrue(liveSectionEnabled(led))
            }
        }
        assertEquals(SectionLed.UNKNOWN, liveSectionLed(LiveSectionInventory.UNKNOWN, button("Intro 3"), "Main A"))
        assertEquals(SectionLed.ACTIVE, liveSectionLed(LiveSectionInventory.UNKNOWN, button("Main A"), "Main A"))
        // The inventory holds exactly the parsed names: nothing is added to fill gaps.
        val full = LiveSectionInventory.from(style("MainA"))
        assertEquals(SectionPresence.PRESENT, full.presence("MainA"))
        for (b in all.filter { it.styleName != "MainA" }) assertEquals(SectionPresence.ABSENT, full.presence(b.styleName))
    }

    @Test fun everyKeyHasARealViewModelAction() {
        val vm = ui("MainViewModel.kt")
        val map = vm.substring(vm.indexOf("SECTION_BUTTON_MAP = mapOf("), vm.indexOf("MAIN_VARIATIONS ="))
        for (b in all) assertTrue("${b.label} -> ${b.styleName}", map.contains("\"${b.label}\" to ArrangerSection.${b.styleName}"))
    }

    @Test fun fadersAreFourIndependentExistingVolumeActions() {
        val screen = ui("LiveControlScreen.kt")
        val faders = Regex("LiveFader\\(\"(R1|R2|R3|STYLE)\",state\\.(\\w+),\\w+,vm::(\\w+),").findAll(screen).map { it.groupValues }.toList()
        assertEquals(listOf("R1", "R2", "R3", "STYLE"), faders.map { it[1] })
        assertEquals(listOf("right1Volume", "right2Volume", "right3Volume", "styleVolume"), faders.map { it[2] })
        assertEquals(listOf("onRight1VolumeChange", "onRight2VolumeChange", "onRight3VolumeChange", "onStyleVolumeChange"), faders.map { it[3] })
        val vm = ui("MainViewModel.kt")
        for (action in faders.map { it[3] }) assertTrue("$action exists", vm.contains("fun $action(value: Int)"))
        // bottom = 0, top = 127 and monotonic over the whole track
        assertEquals(0, mixerFaderValue(200f, 200f)); assertEquals(127, mixerFaderValue(0f, 200f))
        var last = -1
        for (y in 200 downTo 0) { val v = mixerFaderValue(y.toFloat(), 200f); assertTrue(v >= last); last = v }
    }

    @Test fun noGhostControlsAndNoProtectedCalls() {
        val screen = ui("LiveControlScreen.kt")
        for (ghost in listOf("N/A", "onSyncStart", "onTapTempo", "{}")) assertFalse("ghost: $ghost", Regex("LiveButton\\([^\\n]*" + Regex.escape(ghost)).containsMatchIn(screen) || screen.contains("\"$ghost\""))
        for (forbidden in listOf("sendProgramChange", "loadStyle", "allNotesOff", "midiInputManager", "audioEngine", "arrangerBrain", "applyFonts", "toggleMidiOut"))
            assertFalse("LIVE UI must not call $forbidden", screen.contains(forbidden))
        for (file in listOf("LiveV3Model.kt", "LiveFader.kt"))
            for (forbidden in listOf("AudioEngineManager", "MidiInputManager", "ArrangerBrain", "NativeAudioBridge", "StyleSequencer"))
                assertFalse("$file must stay presentation-only: $forbidden", ui(file).contains(forbidden))
    }

    @Test fun landscapeViewportAndCompactHeader() {
        val layout = ui("LiveControlLayout.kt")
        assertTrue(layout.contains("width >= 960f && height >= 600f"))
        val screen = ui("LiveControlScreen.kt")
        // header: style name, section, voice chips, tempo and transpose steppers, ACMP and MIDI OUT status
        for (needle in listOf("state.styleName", "state.activeSection.uppercase()", "LiveStepper(\"TEMPO\"", "LiveStepper(\"TRANSPOSE\"", "LiveStatusLed(\"ACMP\"", "LiveStatusLed(\"MIDI OUT\""))
            assertTrue(needle, screen.contains(needle))
        assertTrue("header stays compact", Regex("height\\((\\d+)\\.dp\\),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy\\(8.dp\\)\\) \\{\\s+LiveButton\\(\"ARRANGER\"")
            .find(screen)!!.groupValues[1].toInt() <= 80)
        // 8 registration slots and bank navigation are kept
        assertTrue(screen.contains("(0..7).forEach { index ->")); assertTrue(screen.contains("vm.onLiveBankChange(-1)"))
    }
}

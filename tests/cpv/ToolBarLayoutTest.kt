package com.yourapp.yamahaarranger.cpv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guards for the bottom-bar tool buttons (no floating chips over CONNECT / MIDI OUT). */
class ToolBarLayoutTest {
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { if (File(dir, "app/src/main/java").exists()) return dir; dir = dir.parentFile }
        throw AssertionError("repo root not found")
    }
    private fun ui(name: String) = File(root(), "app/src/main/java/com/yourapp/ui/$name").readText()

    @Test fun allSixButtonsShareOneFooterRowInOrder() {
        val bar = ui("SxMainScreen.kt").lineSequence().first { it.contains("private fun SxMidiBar") }
        val order = listOf("KEYBOARD", "\"LATENCY\"", "\"RIGHT DIAG\"", "\"METRONOME\"", "\"CONNECT\"", "\"MIDI OUT\"").map { bar.indexOf(it) }
        assertTrue("every button is in SxMidiBar: $order", order.all { it >= 0 })
        assertEquals("order KEYBOARD, LATENCY, RIGHT DIAG, METRONOME, CONNECT, MIDI OUT", order.sorted(), order)
        val between = bar.substring(order[0], order[1])
        assertTrue("KEYBOARD sits directly next to LATENCY", !between.contains("OutlinedButton(") && Regex("SxToolButton\\(").findAll(between).count() == 1)
    }

    @Test fun noFloatingChipsOverTheMainScreen() {
        val activity = ui("MainActivity.kt")
        assertFalse(activity.contains("KEYBOARD"))
        // The metronome panel may sit at BottomEnd only because it is drawn ABOVE the footer (BAR_RESERVE); no chip over CONNECT / MIDI OUT.
        assertTrue(Regex("BottomEnd").findAll(activity).count() == 1 && activity.substring(activity.indexOf("BottomEnd"), activity.indexOf("BottomEnd") + 160).contains("BAR_RESERVE"))
        for (file in listOf("LatencyAbOverlay.kt", "RightVoiceDiagOverlay.kt"))
            for (chip in listOf("\"LATENCY\", color", "\"RIGHT DIAG\", color"))
                assertFalse("$file keeps no floating open-chip", ui(file).contains(chip))
    }

    @Test fun panelsAreDrawnAboveTheFooterNeverOverIt() {
        val activity = ui("MainActivity.kt")
        assertEquals(4, Regex("ToolPanels\\.BAR_RESERVE").findAll(activity).count())
        val tp = ui("ToolPanels.kt")
        assertTrue(tp.contains("val BAR_RESERVE = 40.dp"))
        assertTrue("footer is 28/32 dp", ui("SxMainScreen.kt").contains("val footerH = if (compact) 28.dp else 32.dp"))
        assertTrue(tp.contains("if (latency) diag = false") && tp.contains("if (diag) latency = false"))
        assertFalse("tool panel state is never persisted", tp.contains("SharedPreferences") || tp.contains("DataStore"))
    }

    @Test fun overlaysOpenFromToolPanelsAndCloseThemselves() {
        assertTrue(ui("LatencyAbOverlay.kt").contains("if (!ToolPanels.latency) return"))
        assertTrue(ui("RightVoiceDiagOverlay.kt").contains("if (!ToolPanels.diag) return"))
        assertTrue(ui("LatencyAbOverlay.kt").contains("ToolPanels.latency = false"))
        assertTrue(ui("RightVoiceDiagOverlay.kt").contains("ToolPanels.diag = false"))
    }
}

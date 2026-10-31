package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.StyleEditorNavigation
import com.yourapp.yamahaarranger.style.StyleEditorNavigation.Leave
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Style Editor exit rule (← BACK, Android Back, CLOSE) and the wiring of its screen; the Compose layout itself is not rendered on the host. */
class StyleEditorNavigationTest {
    private fun src(rel: String) = File(System.getProperty("user.dir"), "app/src/main/java/com/yourapp/$rel").readText()

    @Test fun cleanProfileLeavesAtOnceAndUnsavedChangesAsk() {
        assertEquals(Leave.CLOSE_NOW, StyleEditorNavigation.onBack(dirty = false))
        assertEquals(Leave.ASK, StyleEditorNavigation.onBack(dirty = true))
    }

    @Test fun saveClosesOnlyWhenTheProfileWasReallyWritten() {
        for (ok in listOf("Profile saved (revision 3).", "Profile removed: the Style plays as authored.")) assertTrue(ok, StyleEditorNavigation.closeAfterSave(ok))
        for (bad in listOf("Not saved: IOException", "Not saved: the stored profile is newer or damaged and is kept read-only.", "No Style is loaded.")) assertFalse(bad, StyleEditorNavigation.closeAfterSave(bad))
    }

    @Test fun saveMessagesOfTheViewModelStayInSyncWithTheRule() {
        val vm = src("ui/MainViewModel.kt")
        val save = vm.substring(vm.indexOf("fun editorSave("), vm.indexOf("private var auditionJob"))
        for (text in listOf("\"No Style is loaded.\"", "\"Not saved: the stored profile is newer or damaged", "\"Profile removed", "\"Profile saved", "\"Not saved: \${error.javaClass.simpleName}\"")) assertTrue("editorSave message: $text", save.contains(text.removeSuffix("\"")))
    }

    @Test fun screenHasBackFirstAndOneSharedExitPath() {
        val screen = src("ui/StyleVoiceEditorScreen.kt")
        val header = screen.substring(screen.indexOf("private fun Header("), screen.indexOf("private val MIDI_OUT_OCTAVE_PARTS"))
        assertTrue("← BACK is the first element of the header row", header.indexOf("HeaderButton(\"← BACK\"") in 1 until header.indexOf("Text(\"STYLE VOICES\""))
        assertTrue("BACK and CLOSE use the same callback", header.contains("HeaderButton(\"← BACK\", onClick = onClose)") && header.contains("HeaderButton(\"CLOSE\", onClick = onClose)"))
        assertTrue("the header gets the shared request, not the raw close", screen.contains("Header(inventory, loaded, output, actions, onRefresh, requestBack)"))
        assertTrue(screen.contains("backHandler(requestBack)"))
        val dialog = screen.substring(screen.indexOf("private fun LeaveConfirmDialog"))
        for (label in listOf("\"SAVE\"", "\"DISCARD\"", "\"CANCEL\"")) assertTrue(label, dialog.contains("HeaderButton($label"))
        val overlay = src("ui/StyleVoiceEditorOverlay.kt")
        assertTrue("Android Back is installed by the Android-only overlay", overlay.contains("androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)"))
        assertFalse("shared screen stays free of Android-only APIs (desktop preview)", screen.contains("androidx.activity"))
        assertTrue(overlay.contains("onClose = StyleVoiceEditorHost::close"))
    }

    @Test fun discardReappliesTheSavedProfileThroughTheExistingDiffAndTouchesNothingElse() {
        val vm = src("ui/MainViewModel.kt")
        val discard = vm.substring(vm.indexOf("fun editorDiscard("), vm.indexOf("fun editorSave("))
        assertTrue(discard.contains("executeOverrideChanges(old, saved ?: emptyProfile())") && discard.contains("currentOverrides = saved"))
        assertFalse("no direct MIDI, sequencer or engine calls", Regex("midiInputManager|audioEngine|sequencer|startStop").containsMatchIn(discard))
    }
}

package com.yourapp.yamahaarranger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yourapp.yamahaarranger.style.CatalogPreset
import com.yourapp.yamahaarranger.style.OriginalRef
import com.yourapp.yamahaarranger.style.StyleVoiceInventory

/** Stateful wrapper: loads the inventory and the SoundFont catalog off the main thread and binds the editor actions. */
@Composable
fun StyleVoiceEditorOverlay(vm: MainViewModel) {
    if (!StyleVoiceEditorHost.visible) return
    val state by vm.uiState.collectAsState()
    val editor by vm.styleEditorUi.collectAsState()
    val library by vm.liveLibrary.collectAsState()
    val memory by vm.browserMemory.collectAsState()
    val attachments by vm.sf2Attachments.collectAsState()
    var inventory by remember { mutableStateOf<StyleVoiceInventory?>(null) }
    var catalog by remember { mutableStateOf<List<CatalogPreset>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    // Reload on style, section, MIDI OUT, profile edits and on START/STOP. The engine binds channels when a section
    // starts, so after START the reading is retried a bounded number of times (at most 4 reads, 400 ms apart, only
    // while nothing is bound yet); the SoundFont catalog is read once per trigger, never in the retry loop.
    LaunchedEffect(state.activeSection, state.styleName, state.voiceAssignments, state.midiOutEnabled, editor.revision, editor.dirty, refresh, state.isPlaying) {
        val cat = vm.loadEditorCatalog()
        catalog = cat
        var tries = 0
        while (true) {
            val inv = vm.loadStyleVoiceInventory(cat)
            inventory = inv
            loaded = true
            val bound = inv?.runtimeObserved == true
            if (!state.isPlaying || bound || ++tries >= 4) break
            kotlinx.coroutines.delay(400)
        }
    }
    val actions = StyleVoiceEditorActions(
        sf2Catalog = catalog,
        externalChoices = remember(library) { vm.editorExternalChoices() },
        isPlaying = state.isPlaying,
        midiOut = state.midiOutEnabled,
        dirty = editor.dirty,
        writable = editor.writable,
        savedInfo = if (editor.savedAt > 0) "saved" else "not saved",
        message = editor.message,
        auditioning = editor.auditioning,
        onApply = { row, scope, replacement, done ->
            vm.editorApply(row.section, row.destinationChannel, OriginalRef(row.msb, row.lsb, row.program), scope, replacement, done)
        },
        onAudition = { row, replacement, original, done -> vm.editorAudition(row, replacement, original, done) },
        onAuditionStop = vm::editorAuditionStop,
        onResetPart = vm::editorResetPart,
        onResetSection = vm::editorResetSection,
        onResetAll = vm::editorResetAll,
        onSave = { done -> vm.editorSave(done) },
        onAuditionNote = { row, replacement, original, note, done -> vm.editorAudition(row, replacement, original, done, listOf(note)) },
        attachments = attachments,
        octaveOf = { section, channel -> vm.editorOctave(section, channel) },
        onOctave = { row, scope, octaves, done -> vm.editorApplyOctave(row.section, row.destinationChannel, scope, octaves, done) },
        drumUsage = { section, channel -> vm.editorDrumUsage(section, channel) },
        drumMapOf = { section, channel, scope, output -> vm.editorDrumMap(section, channel, scope, output) },
        onDrumMap = { row, scope, output, notes, done -> vm.editorApplyDrumMap(row.section, row.destinationChannel, scope, output, notes, done) },
        onAuditionNotes = { row, original, notes, done -> vm.editorAudition(row, null, original, done, notes) },
        hasExtras = vm.editorHasExtras(),
        deviceCatalog = remember(library) { vm.editorDeviceCatalog() },
        rememberedPick = memory?.voices?.get("STYLE_EDITOR")?.itemKey,
        onRememberPick = { key -> vm.rememberVoicePosition("STYLE_EDITOR", com.yourapp.yamahaarranger.browser.SavedPosition(key.substringBefore('|'), key)) }
    )
    StyleVoiceInspectorContent(
        inventory = inventory, loaded = loaded,
        output = if (state.midiOutEnabled) "MIDI OUT + internal" else "Internal (BASSMIDI)",
        onRefresh = { refresh++ }, onClose = StyleVoiceEditorHost::close, actions = actions)
}

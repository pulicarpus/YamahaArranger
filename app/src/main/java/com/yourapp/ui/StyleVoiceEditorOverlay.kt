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
    var inventory by remember { mutableStateOf<StyleVoiceInventory?>(null) }
    var catalog by remember { mutableStateOf<List<CatalogPreset>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(state.activeSection, state.styleName, state.voiceAssignments, state.midiOutEnabled, editor.revision, editor.dirty, refresh) {
        inventory = vm.loadStyleVoiceInventory()
        catalog = vm.loadEditorCatalog()
        loaded = true
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
        onSave = { done -> vm.editorSave(done) }
    )
    StyleVoiceInspectorContent(
        inventory = inventory, loaded = loaded,
        output = if (state.midiOutEnabled) "MIDI OUT + internal" else "Internal (BASSMIDI)",
        onRefresh = { refresh++ }, onClose = StyleVoiceEditorHost::close, actions = actions)
}

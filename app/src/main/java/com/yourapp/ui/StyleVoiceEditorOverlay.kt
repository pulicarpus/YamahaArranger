package com.yourapp.yamahaarranger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yourapp.yamahaarranger.style.StyleVoiceInventory

/** Stateful wrapper: loads the inventory off the main thread. */
@Composable
fun StyleVoiceEditorOverlay(vm: MainViewModel) {
    if (!StyleVoiceEditorHost.visible) return
    val state by vm.uiState.collectAsState()
    var inventory by remember { mutableStateOf<StyleVoiceInventory?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(state.activeSection, state.styleName, state.voiceAssignments, refresh) {
        inventory = vm.loadStyleVoiceInventory()
        loaded = true
    }
    StyleVoiceInspectorContent(
        inventory = inventory, loaded = loaded,
        output = if (state.midiOutEnabled) "MIDI OUT + internal" else "Internal (BASSMIDI)",
        onRefresh = { refresh++ }, onClose = StyleVoiceEditorHost::close)
}


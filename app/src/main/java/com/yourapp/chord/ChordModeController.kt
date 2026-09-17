package com.yourapp.yamahaarranger.chord

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global chord-fingering selection shared by the live UI and the MIDI chord
 * detector. The default matches the PSR-E343: Multi Finger.
 */
object ChordModeController {
    private val _mode = MutableStateFlow(ChordMode.MultiFinger)
    val mode: StateFlow<ChordMode> = _mode.asStateFlow()

    fun setMode(newMode: ChordMode) {
        _mode.value = newMode
    }
}

package com.yourapp.yamahaarranger.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Open/closed state of the tool panels (virtual keyboard, latency A/B, RIGHT DIAG). The buttons live in the
 * bottom bar (SxMidiBar); the panels are drawn above that bar, never over it. Process-local, not persisted.
 */
object ToolPanels {
    var keyboard by mutableStateOf(false)
    var latency by mutableStateOf(false)
    var diag by mutableStateOf(false)

    /** Space kept free at the bottom for the footer bar (footerH 28/32 dp + gap). */
    val BAR_RESERVE = 40.dp

    fun toggleKeyboard() { keyboard = !keyboard }
    fun toggleLatency() { latency = !latency; if (latency) diag = false }
    fun toggleDiag() { diag = !diag; if (diag) latency = false }
}

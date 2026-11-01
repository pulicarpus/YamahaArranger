package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI state of the PSR-E343 drum NOTE OFF experiment page (plain values, no Android or engine types: the same file is rendered and
 * UI-tested on desktop by tools/ui_preview). Built by MainViewModel from E343DrumNoteOff.snapshot().
 */
data class E343ExperimentUiState(
    val mode: String = "LEGACY", val gateMs: Int = 120, val gates: List<Int> = listOf(40, 80, 120, 200, 300),
    val styleName: String = "", val styleInScope: Boolean = false, val routerWired: Boolean = false,
    val tracked: Long = 0, val offAuthored: Long = 0, val offGate: Long = 0, val offRetrigger: Long = 0, val offRelease: Long = 0, val dropped: Long = 0, val open: Int = 0,
) {
    val modes get() = listOf("LEGACY", "AUTHORED", "SHORT_GATE")
    /** One line that says whether the experiment can act on what is loaded now. */
    val scopeText get() = when {
        !routerWired -> "NOT ACTIVE: MIDI OUT router not wired in this build."
        !styleInScope -> "NOT APPLICABLE to the current Style '" + styleName.ifBlank { "none" } + "': the experiment only acts on EnglishWaltz2. This Style plays exactly as LEGACY in every mode."
        mode == "LEGACY" -> "EnglishWaltz2 loaded. LEGACY = old behaviour (no drum NOTE OFF)."
        else -> "ACTIVE on EnglishWaltz2: Rhythm 2 (ch 10), note 26 Brush Swirl, MIDI OUT only. Internal sound unchanged."
    }
}

object E343ExperimentTags {
    const val PANEL = "e343.panel"; const val CLOSE = "e343.close"; const val SCOPE = "e343.scope"; const val ACTIVE = "e343.active"
    const val STATS = "e343.stats"; const val RESET = "e343.reset"; const val VERIFIED = "e343.verified"
    fun mode(name: String) = "e343.mode.$name"
    fun gate(ms: Int) = "e343.gate.$ms"
}

private val Bg = Color(0xFF10151B); private val Panel = Color(0xFF1B232C); private val Amber = Color(0xFFFFC857)
private val Green = Color(0xFF1E8E3E); private val Gray = Color(0xFF3A434D); private val Red = Color(0xFF8E2B2B); private val Dim = Color(0xFF9AA4AF)

/** Large touch target (>= 52 dp high) with a visible selected state. */
@Composable
private fun BigButton(text: String, tag: String, selected: Boolean = false, color: Color = Gray, minWidth: Int = 120, onClick: () -> Unit) {
    Box(
        Modifier.testTag(tag).heightIn(min = 52.dp).widthIn(min = minWidth.dp)
            .background(if (selected) Green else color, RoundedCornerShape(6.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

/**
 * Full page: fixed header with CLOSE (always visible), then a vertically scrolling body. Every row of buttons also scrolls horizontally,
 * so nothing is ever clipped on a narrow screen.
 */
@Composable
fun E343ExperimentContent(
    state: E343ExperimentUiState, onMode: (String) -> Unit, onGate: (Int) -> Unit, onResetStats: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Bg).testTag(E343ExperimentTags.PANEL)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("E343 DRUM NOTE OFF EXPERIMENT", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("EnglishWaltz2 · Rhythm 2 (ch 10) · note 26 · MIDI OUT only · the choice is saved; LEGACY = rollback", color = Dim, fontSize = 12.sp)
            }
            BigButton("CLOSE", E343ExperimentTags.CLOSE, color = Red, minWidth = 110, onClick = onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.scopeText, color = if (state.styleInScope && state.routerWired) Color(0xFF4CD37B) else Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(E343ExperimentTags.SCOPE))
            Text("Active mode: " + state.mode + (if (state.mode == "SHORT_GATE") " · gate " + state.gateMs + " ms" else ""), color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag(E343ExperimentTags.ACTIVE))
            Text("MODE", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.modes.forEach { m -> BigButton(m, E343ExperimentTags.mode(m), selected = state.mode == m, minWidth = 170) { onMode(m) } }
            }
            Text("LEGACY = old behaviour (no drum NOTE OFF) · AUTHORED = NOTE OFF at the Style's own length (~1 beat) · SHORT_GATE = NOTE OFF after the gate below",
                color = Dim, fontSize = 12.sp)
            Text("Verified on PSR-E343 (user test): SHORT_GATE 120 ms sounds normal; LEGACY and AUTHORED keep the 'tres-tres-tres'. Other Styles are never changed.",
                color = Color(0xFF4CD37B), fontSize = 12.sp, modifier = Modifier.testTag(E343ExperimentTags.VERIFIED))
            Text("GATE for SHORT_GATE" + (if (state.mode != "SHORT_GATE") " (used only in SHORT_GATE)" else ""), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.gates.forEach { g -> BigButton("$g ms", E343ExperimentTags.gate(g), selected = state.gateMs == g, minWidth = 96) { onGate(g) } }
            }
            Text("STATISTICS (since the last reset; refreshed twice per second)", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "note 26 NOTE ON tracked : ${state.tracked}\n" +
                    "NOTE OFF authored        : ${state.offAuthored}\n" +
                    "NOTE OFF by gate         : ${state.offGate}\n" +
                    "NOTE OFF before retrigger: ${state.offRetrigger}\n" +
                    "NOTE OFF at stop/section : ${state.offRelease}\n" +
                    "not sent (MIDI OUT off)  : ${state.dropped}\n" +
                    "open now                 : ${state.open}",
                color = Color(0xFF7FDBFF), fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(E343ExperimentTags.STATS)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { BigButton("RESET STATS", E343ExperimentTags.RESET, onClick = onResetStats) }
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

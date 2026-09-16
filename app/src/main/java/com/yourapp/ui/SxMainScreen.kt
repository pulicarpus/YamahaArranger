package com.yourapp.yamahaarranger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private val SxBlack = Color(0xFF080A0D)
private val SxPanel = Color(0xFF12161B)
private val SxPanel2 = Color(0xFF20262D)
private val SxBlue = Color(0xFF0878D1)
private val SxBlueDark = Color(0xFF063A67)
private val SxOrange = Color(0xFFB65300)
private val SxOrangeBright = Color(0xFFFF8A00)
private val SxGreen = Color(0xFF27D887)
private val SxDim = Color(0xFF9AA3AD)

@Composable
fun SxMainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val stylePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onStyleFilePicked)
    }
    val soundFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onSoundFontFilePicked)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SxBlack)) {
        val compact = maxHeight < 620.dp
        val gap = if (compact) 4.dp else 6.dp
        val headerH = if (compact) 34.dp else 40.dp
        val navH = if (compact) 38.dp else 44.dp
        val controlH = if (compact) 106.dp else 124.dp
        val regH = if (compact) 48.dp else 56.dp
        val footerH = if (compact) 28.dp else 32.dp

        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp)) {
            SxHeader(state, headerH)
            Spacer(Modifier.height(gap))
            SxNavBar(navH)
            Spacer(Modifier.height(gap))

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SxSideMenu(Modifier.weight(0.17f).fillMaxHeight(), compact)
                SxCenterDisplay(
                    state = state,
                    vm = viewModel,
                    compact = compact,
                    onPickStyle = { stylePicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onPickSoundFont = { soundFontPicker.launch(arrayOf("audio/x-soundfont", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(0.66f).fillMaxHeight()
                )
                SxRightPanel(state, Modifier.weight(0.17f).fillMaxHeight(), compact)
            }

            Spacer(Modifier.height(gap))
            SxStyleControls(state, viewModel, controlH, compact)
            Spacer(Modifier.height(gap))
            SxRegistration(state, viewModel, regH, compact)
            Spacer(Modifier.height(gap))
            SxMidiBar(state, viewModel, footerH, compact)
        }
    }
}

@Composable
private fun SxHeader(state: MainUiState, height: androidx.compose.ui.unit.Dp) {
    Surface(
        color = Color(0xFF080A0C),
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF333A42), RoundedCornerShape(5.dp))
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YAMAHA", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.width(9.dp))
            Text("PSR-SX900", color = SxDim, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.width(8.dp))
            Text("YamahaArranger", color = Color(0xFF6EA7D2), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("● MIDI", color = if (state.midiStatus.startsWith("No")) SxDim else SxGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("SF2 ${state.soundFontName.take(18)}", color = SxDim, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SxNavBar(height: androidx.compose.ui.unit.Dp) {
    val tabs = listOf("HOME", "STYLE", "VOICE", "SONG", "MULTI PAD", "REGIST", "MIXER", "UTILITY")
    Row(
        Modifier.fillMaxWidth().height(height).background(Color(0xFF0D1115), RoundedCornerShape(5.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            Surface(
                color = if (index == 0) Color(0xFF073E82) else Color(0xFF1C2228),
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SxSideMenu(modifier: Modifier, compact: Boolean) {
    val labels = listOf("STYLE SELECT", "FAVORITE", "STYLE CONTROL", "OTS LINK", "SYNC START", "STYLE SETTING")
    Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            Text("STYLE", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            labels.forEach { label ->
                Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF343C45), RoundedCornerShape(3.dp))) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, color = Color.White, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SxCenterDisplay(
    state: MainUiState,
    vm: MainViewModel,
    compact: Boolean,
    onPickStyle: () -> Unit,
    onPickSoundFont: () -> Unit,
    modifier: Modifier
) {
    Surface(color = Color(0xFF101419), shape = RoundedCornerShape(6.dp), modifier = modifier.border(2.dp, Color(0xFF39424C), RoundedCornerShape(6.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 7.dp)) {
            Row(Modifier.fillMaxWidth().height(if (compact) 26.dp else 30.dp).background(Color(0xFF080B0E), RoundedCornerShape(2.dp)), verticalAlignment = Alignment.CenterVertically) {
                Text("HOME", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                Text("STYLE", color = Color(0xFF55A9E6), fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Text("44.1 kHz", color = SxDim, fontSize = 7.sp, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.height(if (compact) 4.dp else 5.dp))

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                SxMainContent(state, vm, compact, onPickStyle, onPickSoundFont, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SxMainContent(
    state: MainUiState,
    vm: MainViewModel,
    compact: Boolean,
    onPickStyle: () -> Unit,
    onPickSoundFont: () -> Unit,
    modifier: Modifier
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
        Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            SxStyleCard(state, onPickStyle, Modifier.weight(1.25f))
            SxMultiPad(Modifier.weight(0.75f))
        }
        Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            val voices = state.voiceAssignments
            SxVoiceCard("RIGHT 1", state.voiceName, true, Modifier.weight(1f))
            SxVoiceCard("RIGHT 2", state.right2Name, false, Modifier.weight(1f))
            SxVoiceCard("RIGHT 3", voices.getOrNull(2)?.displayName() ?: "OFF", false, Modifier.weight(1f))
            SxVoiceCard("LEFT", voices.getOrNull(3)?.displayName() ?: "OFF", false, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(0.55f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SxValue("CHORD", state.detectedChordLabel.ifBlank { "—" }, Modifier.weight(1f), null, null, compact)
            SxValue("TEMPO", state.tempoBpm.toString(), Modifier.weight(0.8f), vm::onTempoDown, vm::onTempoUp, compact)
            SxValue("TRANSPOSE", if (state.transpose >= 0) "+${state.transpose}" else state.transpose.toString(), Modifier.weight(0.9f), vm::onTransposeDown, vm::onTransposeUp, compact)
            SxValue("SPLIT", state.splitPoint, Modifier.weight(0.8f), null, null, compact)
            Surface(color = Color(0xFF172029), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1.25f).fillMaxHeight().border(1.dp, Color(0xFF34404C), RoundedCornerShape(3.dp))) {
                Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("PART", color = SxDim, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                    listOf("A", "B", "C", "D").forEach { part ->
                        Box(Modifier.weight(1f).fillMaxHeight().background(if (state.activeSection.endsWith(" $part")) SxOrangeBright else Color(0xFF26313B), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                            Text(part, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SxStyleCard(state: MainUiState, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        color = SxOrange,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick).border(1.dp, Color(0xFFFF9B32), RoundedCornerShape(4.dp))
    ) {
        Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("STYLE", color = Color(0xFFFFD1A5), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                Text(state.styleName.take(25), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("4/4   ♪ ${state.tempoBpm}", color = Color.White, fontSize = 8.sp, maxLines = 1)
                Text(state.activeSection, color = Color(0xFFFFC27A), fontSize = 7.sp, maxLines = 1)
            }
            Text("LOAD", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp))
        }
    }
}

@Composable
private fun SxMultiPad(modifier: Modifier) {
    Surface(color = SxBlueDark, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().border(1.dp, Color(0xFF146CB3), RoundedCornerShape(4.dp))) {
        Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.Center) {
            Text("MULTI PAD", color = Color(0xFFA8D8FF), fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Ready", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Shaker & Tamb   1/4", color = SxDim, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SxVoiceCard(label: String, name: String, active: Boolean, modifier: Modifier) {
    Surface(
        color = if (active) Color(0xFF063F85) else Color(0xFF24292F),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier.fillMaxHeight().border(1.dp, if (active) Color(0xFF2C91FF) else Color(0xFF3A424B), RoundedCornerShape(3.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (active) Color(0xFFA8D8FF) else SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Text(name.ifBlank { "OFF" }.take(18), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (active) "ON" else "OFF", color = if (active) Color(0xFF58D8FF) else SxDim, fontSize = 6.sp)
        }
    }
}

@Composable
private fun SxValue(label: String, value: String, modifier: Modifier, minus: (() -> Unit)?, plus: (() -> Unit)?, compact: Boolean) {
    Surface(color = SxPanel2, shape = RoundedCornerShape(3.dp), modifier = modifier.fillMaxHeight().border(1.dp, Color(0xFF343B44), RoundedCornerShape(3.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = SxDim, fontSize = if (compact) 5.sp else 6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 3.dp), maxLines = 1)
            if (minus != null && plus != null) SmallKey("−", minus, compact)
            Text(value, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (minus != null && plus != null) SmallKey("+", plus, compact)
        }
    }
}

@Composable
private fun SxRightPanel(state: MainUiState, modifier: Modifier, compact: Boolean) {
    Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            Text("VOICE SELECT", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            val categories = listOf("PIANO", "ORGAN", "GUITAR", "STRINGS", "BRASS", "SAX/WOODWIND", "SYNTH", "CHOIR/PAD", "BASS", "PERCUSSION", "WORLD", "USER")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                categories.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { category ->
                            Surface(color = if (category == "PIANO") SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF35404A), RoundedCornerShape(3.dp))) {
                                Box(contentAlignment = Alignment.Center) { Text(category, color = Color.White, fontSize = if (category.length > 8) 5.sp else 6.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
            Text("PART ON/OFF", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("R1", "R2", "R3", "L").forEach { part ->
                    Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(contentAlignment = Alignment.Center) { Text(part, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("ONE TOUCH SETTING", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (1..4).forEach { n ->
                    Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(contentAlignment = Alignment.Center) { Text(n.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SxStyleControls(state: MainUiState, vm: MainViewModel, height: androidx.compose.ui.unit.Dp, compact: Boolean) {
    Surface(color = Color(0xFF111519), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF303841), RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STYLE CONTROL", color = SxOrangeBright, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF6D3600)))
            }
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                SxSectionGroup("INTRO", listOf("Intro 1", "Intro 2", "Intro 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact)
                SxSectionGroup("MAIN VARIATION", listOf("Main A", "Main B", "Main C", "Main D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f), compact)
                SxSectionGroup("FILL IN", listOf("Fill A", "Fill B", "Fill C", "Fill D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f), compact)
                SxSectionGroup("ENDING", listOf("Ending 1", "Ending 2", "Ending 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact)
                Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                    SxAction("SYNC START", false, vm::onSyncStart, compact)
                    SxAction(if (state.isPlaying) "STOP" else "START / STOP", state.isPlaying, vm::onStartStop, compact)
                    SxAction("TAP TEMPO", false, vm::onTapTempo, compact)
                }
            }
        }
    }
}

@Composable
private fun SxSectionGroup(title: String, labels: List<String>, active: String, onClick: (String) -> Unit, modifier: Modifier, compact: Boolean) {
    Column(modifier.fillMaxHeight()) {
        Text(title, color = SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEach { label ->
                val selected = active.equals(label, ignoreCase = true)
                Surface(
                    color = if (selected) SxOrange else Color(0xFF242A31),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable { onClick(label) }.border(1.dp, if (selected) Color(0xFFFFB25A) else Color(0xFF3A424C), RoundedCornerShape(3.dp))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label.substringAfterLast(" "), color = Color.White, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SxAction(label: String, active: Boolean, onClick: () -> Unit, compact: Boolean) {
    val actionHeight = if (compact) 30.dp else 35.dp
    Surface(
        color = if (active) Color(0xFF155B3C) else Color(0xFF20262C),
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.fillMaxWidth().height(actionHeight).clickable(onClick = onClick).border(1.dp, if (active) SxGreen else Color(0xFF39414A), RoundedCornerShape(3.dp))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = if (compact) 7.sp else 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SxRegistration(state: MainUiState, vm: MainViewModel, height: androidx.compose.ui.unit.Dp, compact: Boolean) {
    Surface(color = Color(0xFF111419), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF303740), RoundedCornerShape(5.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REGISTRATION MEMORY", color = Color(0xFF55A9E6), fontSize = if (compact) 7.sp else 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp), maxLines = 1)
            (1..8).forEach { slot ->
                val selected = slot - 1 == state.activeRegSlot
                Surface(color = if (selected) SxBlue else Color(0xFF20252B), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp).clickable { vm.onRegSlotTap(slot - 1) }) {
                    Box(contentAlignment = Alignment.Center) { Text(slot.toString(), color = Color.White, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.width(4.dp))
            SmallKey("−", { vm.onBankChange((state.activeBank - 1).coerceIn(1, 8)) }, compact)
            Text("BANK ${state.activeBank}", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, modifier = Modifier.padding(horizontal = 3.dp), maxLines = 1)
            SmallKey("+", { vm.onBankChange((state.activeBank + 1).coerceIn(1, 8)) }, compact)
        }
    }
}

@Composable
private fun SxMidiBar(state: MainUiState, vm: MainViewModel, height: androidx.compose.ui.unit.Dp, compact: Boolean) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Text("YamahaArranger v0.1.0   |   MIDI: ${state.midiStatus}", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedButton(onClick = vm::refreshMidiConnection, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("CONNECT", fontSize = if (compact) 6.sp else 7.sp, maxLines = 1) }
        Spacer(Modifier.width(4.dp))
        Button(onClick = vm::toggleMidiOut, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.midiOutEnabled) SxBlue else SxPanel2)) { Text("MIDI OUT", fontSize = if (compact) 6.sp else 7.sp, maxLines = 1) }
    }
}

@Composable
private fun SmallKey(text: String, onClick: () -> Unit, compact: Boolean = false) {
    Button(onClick = onClick, modifier = Modifier.size(if (compact) 23.dp else 25.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39434B)), shape = RoundedCornerShape(2.dp)) {
        Text(text, fontSize = if (compact) 10.sp else 12.sp, maxLines = 1)
    }
}

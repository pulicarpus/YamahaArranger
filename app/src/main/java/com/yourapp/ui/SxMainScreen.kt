package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

private val SxBlack = Color(0xFF090A0C)
private val SxPanel = Color(0xFF15181C)
private val SxPanel2 = Color(0xFF20252B)
private val SxBlue = Color(0xFF0D72B8)
private val SxBlueDark = Color(0xFF063A67)
private val SxOrange = Color(0xFFB65300)
private val SxOrangeBright = Color(0xFFFF8A00)
private val SxGreen = Color(0xFF27D887)
private val SxDim = Color(0xFF9299A2)

@Composable
fun SxMainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(SxBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        SxHeader(state)
        Spacer(Modifier.height(8.dp))
        SxTouchDisplay(state, viewModel)
        Spacer(Modifier.height(8.dp))
        SxStyleControls(state, viewModel)
        Spacer(Modifier.height(8.dp))
        SxRegistration(state, viewModel)
        Spacer(Modifier.height(6.dp))
        SxMidiBar(state, viewModel)
    }
}

@Composable
private fun SxHeader(state: MainUiState) {
    Surface(
        color = Color(0xFF08090B),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF343940), RoundedCornerShape(6.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YAMAHA", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.width(10.dp))
            Text("PSR-SX900", color = SxDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            Text("STYLE ENGINE", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SxTouchDisplay(state: MainUiState, vm: MainViewModel) {
    Surface(
        color = Color(0xFF111418),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF3A4048), RoundedCornerShape(7.dp))
    ) {
        Column(Modifier.padding(7.dp)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF080A0D)).padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HOME", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                Text("STYLE", color = Color(0xFF55A9E6), fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("● MIDI", color = if (state.midiStatus.startsWith("No")) SxDim else SxGreen, fontSize = 8.sp)
                Spacer(Modifier.width(12.dp))
                Text("44.1 kHz", color = SxDim, fontSize = 8.sp)
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val voices = state.voiceAssignments
                SxVoice("RIGHT 1", state.voiceName, true, Modifier.weight(1f))
                SxVoice("RIGHT 2", state.right2Name, false, Modifier.weight(1f))
                SxVoice("RIGHT 3", voices.getOrNull(2)?.displayName() ?: "OFF", false, Modifier.weight(1f))
                SxVoice("LEFT", voices.getOrNull(3)?.displayName() ?: "OFF", false, Modifier.weight(1f))
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = SxOrange,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1.35f).height(62.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
                        Text("STYLE", color = Color(0xFFFFD1A5), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text(state.styleName.take(28), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(state.activeSection, color = Color(0xFFFFC27A), fontSize = 8.sp)
                    }
                }
                Surface(
                    color = SxBlueDark,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.weight(1f).height(62.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
                        Text("MULTI PAD", color = Color(0xFFA8D8FF), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        Text("Ready", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("1 / 4", color = SxDim, fontSize = 8.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Voice", "Chord Looper", "Live Control", "Assignable", "Channel", "Menu").forEachIndexed { i, label ->
                    Surface(
                        color = if (i == 0) SxBlue else Color(0xFF2A3138),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.weight(1f).height(27.dp)
                    ) { Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 7.sp, maxLines = 1) } }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SxValue("TEMPO", state.tempoBpm.toString(), Modifier.weight(1f), vm::onTempoDown, vm::onTempoUp)
                SxValue("TRANSPOSE", if (state.transpose >= 0) "+${state.transpose}" else state.transpose.toString(), Modifier.weight(1f), vm::onTransposeDown, vm::onTransposeUp)
                SxValue("SPLIT POINT", state.splitPoint, Modifier.weight(1f), null, null)
                SxValue("CHORD", state.detectedChordLabel.ifBlank { "—" }, Modifier.weight(1.35f), null, null)
                Surface(color = Color(0xFF151C23), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1.45f).height(49.dp).border(1.dp, Color(0xFF34404C), RoundedCornerShape(3.dp))) {
                    Column(Modifier.padding(5.dp)) {
                        Text("STYLE PART", color = SxDim, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf("A", "B", "C", "D").forEach { part ->
                                val active = state.activeSection.endsWith(" $part")
                                Box(Modifier.weight(1f).height(23.dp).background(if (active) SxOrangeBright else Color(0xFF26313B), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                                    Text(part, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SxVoice(label: String, name: String, active: Boolean, modifier: Modifier) {
    Surface(
        color = if (active) Color(0xFF073F86) else Color(0xFF24282E),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier.height(55.dp).border(1.dp, if (active) Color(0xFF2D91FF) else Color(0xFF343940), RoundedCornerShape(3.dp))
    ) {
        Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = if (active) Color(0xFFA8D8FF) else SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Text(name.ifBlank { "OFF" }.take(18), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (active) "ON" else "OFF", color = if (active) Color(0xFF58D8FF) else SxDim, fontSize = 6.sp)
        }
    }
}

@Composable
private fun SxValue(label: String, value: String, modifier: Modifier, minus: (() -> Unit)?, plus: (() -> Unit)?) {
    Surface(color = SxPanel2, shape = RoundedCornerShape(3.dp), modifier = modifier.height(49.dp).border(1.dp, Color(0xFF343B44), RoundedCornerShape(3.dp))) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = SxDim, fontSize = 6.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (minus != null && plus != null) {
                    SmallKey("−", minus)
                }
                Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp), maxLines = 1)
                if (minus != null && plus != null) {
                    SmallKey("+", plus)
                }
            }
        }
    }
}

@Composable
private fun SxStyleControls(state: MainUiState, vm: MainViewModel) {
    Surface(color = Color(0xFF121519), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF303740), RoundedCornerShape(6.dp))) {
        Column(Modifier.padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STYLE CONTROL", color = SxOrangeBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF6D3600)))
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SxSectionGroup("INTRO", listOf("Intro 1", "Intro 2", "Intro 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f))
                SxSectionGroup("MAIN", listOf("Main A", "Main B", "Main C", "Main D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f))
                SxSectionGroup("FILL", listOf("Fill A", "Fill B", "Fill C", "Fill D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f))
                SxSectionGroup("ENDING", listOf("Ending 1", "Ending 2", "Ending 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SxAction("SYNC START", false, vm::onSyncStart)
                    SxAction(if (state.isPlaying) "STOP" else "START / STOP", state.isPlaying, vm::onStartStop)
                    SxAction("TAP TEMPO", false, vm::onTapTempo)
                }
            }
        }
    }
}

@Composable
private fun SxSectionGroup(title: String, labels: List<String>, active: String, onClick: (String) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            labels.forEach { label ->
                val selected = active.equals(label, ignoreCase = true)
                Surface(
                    color = if (selected) SxOrange else Color(0xFF242A31),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.weight(1f).height(47.dp).clickable { onClick(label) }.border(1.dp, if (selected) Color(0xFFFFB25A) else Color(0xFF3A424C), RoundedCornerShape(3.dp))
                ) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(7.dp).background(if (selected) Color.White else Color(0xFF6A737D), RoundedCornerShape(1.dp)))
                        Spacer(Modifier.height(4.dp))
                        Text(label.substringAfterLast(" "), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SxAction(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) Color(0xFF155B3C) else Color(0xFF20262C),
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.fillMaxWidth().height(27.dp).clickable(onClick = onClick).border(1.dp, if (active) SxGreen else Color(0xFF39414A), RoundedCornerShape(3.dp))
    ) { Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun SxRegistration(state: MainUiState, vm: MainViewModel) {
    Surface(color = Color(0xFF111419), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF303740), RoundedCornerShape(5.dp))) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REGISTRATION MEMORY", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            (1..8).forEach { slot ->
                val selected = slot - 1 == state.activeRegSlot
                Surface(color = if (selected) SxBlue else Color(0xFF20252B), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).height(35.dp).padding(horizontal = 2.dp).clickable { vm.onRegSlotTap(slot - 1) }) {
                    Box(contentAlignment = Alignment.Center) { Text(slot.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.width(5.dp))
            SmallKey("−") { vm.onBankChange((state.activeBank - 1).coerceIn(1, 8)) }
            Text("BANK ${state.activeBank}", color = SxDim, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 4.dp))
            SmallKey("+") { vm.onBankChange((state.activeBank + 1).coerceIn(1, 8)) }
        }
    }
}

@Composable
private fun SxMidiBar(state: MainUiState, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("MIDI  ${state.midiStatus}", color = SxDim, fontSize = 7.sp, modifier = Modifier.weight(1f), maxLines = 1)
        OutlinedButton(onClick = vm::refreshMidiConnection, modifier = Modifier.height(30.dp)) { Text("CONNECT", fontSize = 7.sp) }
        Spacer(Modifier.width(5.dp))
        Button(onClick = vm::toggleMidiOut, modifier = Modifier.height(30.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.midiOutEnabled) SxBlue else SxPanel2)) { Text("MIDI OUT", fontSize = 7.sp) }
    }
}

@Composable
private fun SmallKey(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.size(25.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39434B)), shape = RoundedCornerShape(2.dp)) {
        Text(text, fontSize = 12.sp)
    }
}

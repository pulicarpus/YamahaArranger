package com.yourapp.yamahaarranger.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val PanelBlack = Color(0xFF111214)
private val PanelDark = Color(0xFF191A1D)
private val PanelMid = Color(0xFF26282C)
private val LcdBlue = Color(0xFF0D72B8)
private val LcdBlueDark = Color(0xFF063A67)
private val AccentOrange = Color(0xFFFF8A00)
private val AccentBlue = Color(0xFF55A9E6)
private val LampBlue = Color(0xFF5B8CFF)
private val TextDim = Color(0xFF9DA3AA)

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onImportStyleClicked: () -> Unit = {},
    onImportSoundFontClicked: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showVoicePicker by remember { mutableStateOf<VoiceSlot?>(null) }
    var showStyleEditor by remember { mutableStateOf(false) }
    var showStyleVoicePicker by remember { mutableStateOf<VoiceSlot?>(null) }
    var showSf2Manager by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        TopHeader(uiState, onImportStyleClicked, onImportSoundFontClicked)
        Spacer(Modifier.height(8.dp))

        // Main SX-style touchscreen area: 800x480 reference layout, responsive on Android.
        LcdHome(
            styleName = uiState.styleName,
            tempo = uiState.tempoBpm,
            transpose = uiState.transpose,
            chord = uiState.detectedChordLabel,
            section = uiState.activeSection,
            soundFont = uiState.soundFontName,
            onImportStyle = onImportStyleClicked,
            onImportSoundFont = onImportSoundFontClicked,
            onTempoDown = viewModel::onTempoDown,
            onTempoUp = viewModel::onTempoUp,
            onTransposeDown = viewModel::onTransposeDown,
            onTransposeUp = viewModel::onTransposeUp,
            onSection = viewModel::onSectionSelected
        )
        Spacer(Modifier.height(8.dp))

        // Six assignable-style soft keys directly below the display.
        AssignableRow(
            labels = listOf("Voice", "Chord Looper", "Live Control", "Assignable", "Channel", "Demo"),
            active = uiState.activeSection
        )
        Spacer(Modifier.height(8.dp))

        MidiStatusBar(
            midiStatus = uiState.midiStatus,
            midiOutEnabled = uiState.midiOutEnabled,
            onConnect = viewModel::refreshMidiConnection,
            onToggleMidiOut = viewModel::toggleMidiOut
        )
        Spacer(Modifier.height(8.dp))

        // Physical-panel inspired transport/style controls.
        PanelSection("STYLE CONTROL") {
            SectionRow(listOf("Intro 1", "Intro 2", "Intro 3"), uiState.activeSection, viewModel::onSectionSelected)
            Spacer(Modifier.height(5.dp))
            SectionRow(listOf("Main A", "Main B", "Main C", "Main D"), uiState.activeSection, viewModel::onSectionSelected)
            Spacer(Modifier.height(5.dp))
            Button(
                onClick = viewModel::toggleAutoFill,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(3.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.autoFill) LcdBlue else PanelMid,
                    contentColor = Color.White
                )
            ) {
                Text(if (uiState.autoFill) "AUTO FILL  •  ON" else "AUTO FILL  •  OFF", fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(5.dp))
            SectionRow(listOf("Ending 1", "Ending 2", "Ending 3"), uiState.activeSection, viewModel::onSectionSelected)
            Spacer(Modifier.height(7.dp))
            TransportRow(uiState.isPlaying, viewModel::onSyncStart, viewModel::onStartStop, viewModel::onTapTempo)
        }
        Spacer(Modifier.height(8.dp))

        RegistrationRow(
            activeBank = uiState.activeBank,
            activeRegSlot = uiState.activeRegSlot,
            onBankChange = viewModel::onBankChange,
            onRegSlotTap = viewModel::onRegSlotTap,
            onRegSlotSave = viewModel::onRegSlotSave
        )
        Spacer(Modifier.height(8.dp))

        ChannelVoicePanel(
            voices = uiState.voiceAssignments,
            onTapVoice = { showVoicePicker = it },
            onToggleLock = viewModel::toggleChannelLock
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                viewModel.refreshSoundFontList()
                showSf2Manager = true
            },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PanelMid, contentColor = Color.White),
            shape = RoundedCornerShape(4.dp)
        ) { Text("SF2 MANAGER  •  SELECT SOUND FONT", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { showStyleEditor = true },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LcdBlue, contentColor = Color.White),
            shape = RoundedCornerShape(4.dp)
        ) { Text("STYLE EDITOR  •  MIX / VOICE / MUTE", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        Spacer(Modifier.height(8.dp))

        PanelSection("MASTER / MIXER") {
            VolumeSlider("STYLE", uiState.styleVolume, viewModel::onStyleVolumeChange)
            VolumeSlider("VOICE", uiState.voiceVolume, viewModel::onVoiceVolumeChange)
            VolumeSlider("MASTER", uiState.masterVolume, viewModel::onMasterVolumeChange)
        }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = viewModel::playTestTone,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PanelMid, contentColor = Color.White),
            shape = RoundedCornerShape(4.dp)
        ) { Text("TEST TONE  •  C  E  G", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))

        BottomStatusBar(uiState.voiceName, uiState.right2Name, uiState.splitPoint)
        Spacer(Modifier.height(8.dp))
        DebugPanel()
    }

    if (showStyleEditor) {
        StyleMixerDialog(
            voices = uiState.voiceAssignments,
            onDismiss = { showStyleEditor = false },
            onMixer = viewModel::setStyleChannelMixer,
            onMute = viewModel::toggleStyleChannelMute,
            onVoice = { showStyleVoicePicker = it }
        )
    }

    if (showSf2Manager) {
        Sf2ManagerDialog(
            files = uiState.availableSoundFonts,
            currentName = uiState.soundFontName,
            onDismiss = { showSf2Manager = false },
            onSelect = { uri, name ->
                viewModel.selectSoundFont(uri, name)
                showSf2Manager = false
            },
            onRefresh = viewModel::refreshSoundFontList,
            onImport = {
                showSf2Manager = false
                onImportSoundFontClicked()
            }
        )
    }

    showStyleVoicePicker?.let { slot ->
        VoicePickerDialog(
            slot = slot,
            onDismiss = { showStyleVoicePicker = null },
            onSelect = { program, bank ->
                viewModel.setStyleChannelVoice(slot.channel, program, bank)
                showStyleVoicePicker = null
            }
        )
    }

    showVoicePicker?.let { slot ->
        VoicePickerDialog(
            slot = slot,
            onDismiss = { showVoicePicker = null },
            onSelect = { program, bank ->
                viewModel.setChannelVoice(slot.channel, program, bank)
                showVoicePicker = null
            }
        )
    }
}

@Composable
private fun StyleMixerDialog(
    voices: List<VoiceSlot>,
    onDismiss: () -> Unit,
    onMixer: (Int, Int, Int, Int, Int, Int) -> Unit,
    onMute: (Int) -> Unit,
    onVoice: (VoiceSlot) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("STYLE EDITOR  •  CHANNEL MIXER") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Per-channel overrides apply only during style playback. The original STY/PRS file is untouched.", color = TextDim, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                voices.forEach { slot ->
                    Surface(color = if (slot.styleMuted) PanelMid else PanelDark, shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("CH${slot.channel}", color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                                Text(slot.displayName(), color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = { onVoice(slot) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("VOICE", fontSize = 8.sp) }
                                TextButton(onClick = { onMute(slot.channel) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text(if (slot.styleMuted) "MUTED" else "MUTE", fontSize = 8.sp) }
                            }
                            MixerSlider("VOL", slot.styleVolume) { onMixer(slot.channel, it, slot.stylePan, slot.styleExpression, slot.styleReverb, slot.styleChorus) }
                            MixerSlider("PAN", slot.stylePan) { onMixer(slot.channel, slot.styleVolume, it, slot.styleExpression, slot.styleReverb, slot.styleChorus) }
                            MixerSlider("EXP", slot.styleExpression) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, it, slot.styleReverb, slot.styleChorus) }
                            MixerSlider("REV", slot.styleReverb) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, slot.styleExpression, it, slot.styleChorus) }
                            MixerSlider("CHO", slot.styleChorus) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, slot.styleExpression, slot.styleReverb, it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } }
    )
}

@Composable
private fun TopHeader(
    state: MainUiState,
    onImportStyle: () -> Unit,
    onImportSoundFont: () -> Unit
) {
    Surface(color = PanelDark, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("YAMAHA ARRANGER", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("SX900 STYLE ENGINE", color = AccentBlue, fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderChip("STYLE", state.styleName.ifBlank { "—" }, onImportStyle)
                HeaderChip("SF2", state.soundFontName.ifBlank { "—" }, onImportSoundFont)
            }
        }
    }
}

@Composable
private fun HeaderChip(title: String, value: String, onClick: () -> Unit) {
    Surface(
        color = PanelMid,
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
            Text(title, color = TextDim, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            Text(value.take(18), color = Color.White, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LcdHome(
    styleName: String,
    tempo: Int,
    transpose: Int,
    chord: String,
    section: String,
    soundFont: String,
    onImportStyle: () -> Unit,
    onImportSoundFont: () -> Unit,
    onTempoDown: () -> Unit,
    onTempoUp: () -> Unit,
    onTransposeDown: () -> Unit,
    onTransposeUp: () -> Unit,
    onSection: (String) -> Unit
) {
    Surface(
        color = Color(0xFF111A21),
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF3B4148), RoundedCornerShape(3.dp))
    ) {
        Column(modifier = Modifier.padding(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF20272D)).padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HOME", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("STYLE", color = TextDim, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Text("44.1 kHz", color = TextDim, fontSize = 7.sp)
                Spacer(Modifier.width(8.dp))
                Text("● MIDI", color = AccentBlue, fontSize = 7.sp)
            }

            Spacer(Modifier.height(5.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LcdTile("VOICE", "GrandPiano", "Right1", Modifier.weight(1f))
                LcdTile("VOICE", "OFF", "Right2", Modifier.weight(1f))
                LcdTile("VOICE", "OFF", "Right3", Modifier.weight(1f))
                LcdTile("VOICE", "OFF", "Left", Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("STYLE", color = TextDim, fontSize = 7.sp)
                    Surface(color = AccentOrange, shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth().height(37.dp).clickable(onClick = onImportStyle)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("♫", color = Color.White, fontSize = 18.sp)
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(styleName.ifBlank { "No Style Loaded" }.take(25), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(section, color = Color.White.copy(alpha = .8f), fontSize = 7.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("MULTI PAD", color = TextDim, fontSize = 7.sp)
                    Surface(color = Color(0xFF4A5962), shape = RoundedCornerShape(2.dp), modifier = Modifier.fillMaxWidth().height(37.dp)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("MP", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("Ready", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Voice", "Chord Looper", "Live Control", "Assignable", "Channel", "Demo").forEachIndexed { index, label ->
                    Surface(
                        color = if (index == 0) LcdBlue else Color(0xFF303A42),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.weight(1f).height(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 7.sp, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CHORD", color = TextDim, fontSize = 7.sp)
                    Text(chord.ifBlank { "—" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                CompactNumber("TRANSPOSE", if (transpose >= 0) "+$transpose" else "$transpose", onTransposeDown, onTransposeUp)
                Spacer(Modifier.width(5.dp))
                CompactNumber("TEMPO", "$tempo", onTempoDown, onTempoUp)
            }

            Spacer(Modifier.height(4.dp))
            Text("SF2  ${soundFont.ifBlank { "Not loaded" }}", color = TextDim, fontSize = 7.sp, modifier = Modifier.clickable(onClick = onImportSoundFont))
        }
    }
}

@Composable
private fun LcdTile(category: String, name: String, part: String, modifier: Modifier) {
    Surface(color = LcdBlueDark, shape = RoundedCornerShape(2.dp), modifier = modifier.height(48.dp)) {
        Column(modifier = Modifier.padding(5.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(category, color = Color(0xFFA8D8FF), fontSize = 6.sp)
            Text(name, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(part, color = TextDim, fontSize = 6.sp)
        }
    }
}

@Composable
private fun CompactNumber(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextDim, fontSize = 6.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniButton("−", onMinus)
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
            MiniButton("+", onPlus)
        }
    }
}

@Composable
private fun MiniButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(27.dp),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39434B), contentColor = Color.White)
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun AssignableRow(labels: List<String>, active: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Surface(
                color = if (index == 0) PanelMid else PanelDark,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.weight(1f).height(34.dp).border(1.dp, Color(0xFF36393E), RoundedCornerShape(2.dp))
            ) { Box(contentAlignment = Alignment.Center) { Text(label, color = if (index == 0) Color.White else TextDim, fontSize = 8.sp, maxLines = 1) } }
        }
    }
}

@Composable
private fun PanelSection(title: String, content: @Composable Column.() -> Unit) {
    Surface(color = PanelDark, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, color = TextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun MidiStatusBar(midiStatus: String, midiOutEnabled: Boolean, onConnect: () -> Unit, onToggleMidiOut: () -> Unit) {
    Surface(color = PanelDark, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (midiStatus.startsWith("No")) "●" else "●", color = if (midiStatus.startsWith("No")) TextDim else Color(0xFF58C77C), fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text("MIDI  $midiStatus", color = Color.White, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Button(onClick = onToggleMidiOut, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(2.dp), colors = ButtonDefaults.buttonColors(containerColor = if (midiOutEnabled) LcdBlue else PanelMid)) {
                Text(if (midiOutEnabled) "OUT ON" else "OUT", fontSize = 8.sp)
            }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onConnect, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 9.dp), shape = RoundedCornerShape(2.dp), colors = ButtonDefaults.buttonColors(containerColor = PanelMid)) {
                Text("CONNECT", fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun SectionRow(sections: List<String>, activeSection: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        sections.forEach { section ->
            val active = section == activeSection
            Button(
                onClick = { onSelect(section) },
                modifier = Modifier.weight(1f).height(38.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(3.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) AccentOrange else PanelMid,
                    contentColor = Color.White
                )
            ) { Text(section, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal) }
        }
    }
}

@Composable
private fun TransportRow(isPlaying: Boolean, onSyncStart: () -> Unit, onStartStop: () -> Unit, onTapTempo: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        PanelButton("SYNC START", onSyncStart, Modifier.weight(1f))
        PanelButton(if (isPlaying) "■ STOP" else "▶ START", onStartStop, Modifier.weight(1f), AccentOrange)
        PanelButton("TAP TEMPO", onTapTempo, Modifier.weight(1f))
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier, color: Color = PanelMid) {
    Button(onClick = onClick, modifier = modifier.height(38.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(3.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RegistrationRow(activeBank: Int, activeRegSlot: Int, onBankChange: (Int) -> Unit, onRegSlotTap: (Int) -> Unit, onRegSlotSave: (Int) -> Unit) {
    Surface(color = PanelDark, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REGISTRATION MEMORY", color = TextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("BANK", color = TextDim, fontSize = 8.sp)
                MiniPanelButton("−") { onBankChange(activeBank - 1) }
                Text("$activeBank", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
                MiniPanelButton("+") { onBankChange(activeBank + 1) }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                (1..8).forEach { slot ->
                    val active = slot == activeRegSlot
                    Button(onClick = { onRegSlotTap(slot) }, modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(3.dp), colors = ButtonDefaults.buttonColors(containerColor = if (active) LampBlue else PanelMid)) {
                        Text("$slot", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPanelButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.size(28.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(2.dp), colors = ButtonDefaults.buttonColors(containerColor = PanelMid)) { Text(text, fontSize = 12.sp) }
}

@Composable
private fun ChannelVoicePanel(voices: List<VoiceSlot>, onTapVoice: (VoiceSlot) -> Unit, onToggleLock: (Int) -> Unit) {
    PanelSection("MIXER / CHANNEL VOICES") {
        voices.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                pair.forEach { slot ->
                    Surface(color = PanelMid, shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).height(43.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { onTapVoice(slot) }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(slot.label, color = TextDim, fontSize = 7.sp, maxLines = 1)
                                Text(slot.displayName(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Text(if (slot.locked) "L" else "", color = AccentOrange, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onToggleLock(slot.channel) })
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextDim, fontSize = 8.sp, modifier = Modifier.width(55.dp))
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..127f, modifier = Modifier.weight(1f))
        Text("$value", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
    }
}

@Composable
private fun Sf2ManagerDialog(
    files: List<Pair<Uri, String>>,
    currentName: String,
    onDismiss: () -> Unit,
    onSelect: (Uri, String) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SF2 MANAGER") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("Folder: Download/YamahaArranger/SF2", color = TextDim, fontSize = 9.sp)
                Text("Current: " + currentName.ifBlank { "None" }, color = AccentBlue, fontSize = 9.sp)
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text("No .sf2 files found.", color = TextDim, fontSize = 10.sp)
                } else {
                    files.forEach { (uri, name) ->
                        val active = name == currentName
                        Surface(
                            color = if (active) LcdBlueDark else PanelMid,
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp).clickable { onSelect(uri, name) }
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (active) "✓" else "○", color = if (active) AccentBlue else TextDim, fontSize = 12.sp)
                                Spacer(Modifier.width(7.dp))
                                Text(name, color = Color.White, fontSize = 9.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefresh) { Text("REFRESH") }
                TextButton(onClick = onImport) { Text("IMPORT") }
                TextButton(onClick = onDismiss) { Text("CLOSE") }
            }
        }
    )
}

@Composable
private fun MixerSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextDim, fontSize = 7.sp, modifier = Modifier.width(34.dp))
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..127f, modifier = Modifier.weight(1f))
        Text("$value", color = Color.White, fontSize = 8.sp, modifier = Modifier.width(28.dp))
    }
}

@Composable
private fun VoicePickerDialog(slot: VoiceSlot, onDismiss: () -> Unit, onSelect: (program: Int, bank: Int) -> Unit) {
    var search by remember { mutableStateOf("") }
    val filteredVoices = remember(search) { if (search.isBlank()) GM_VOICES else GM_VOICES.filter { it.first.contains(search, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CH ${slot.channel}  •  SELECT VOICE") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                if (slot.channel == 9 || slot.isDrum()) {
                    TextButton(onClick = { onSelect(0, 128) }, modifier = Modifier.fillMaxWidth()) { Text("DRUM KIT  •  BANK 128", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search voice…") }, singleLine = true)
                Spacer(Modifier.height(5.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(filteredVoices) { item ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(item.second, 0) }.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${item.second}", fontSize = 10.sp, color = TextDim, modifier = Modifier.width(35.dp))
                            Text(item.first, fontSize = 13.sp, color = if (item.second == slot.program && slot.bank == 0) LcdBlue else MaterialTheme.colorScheme.onSurface, fontWeight = if (item.second == slot.program && slot.bank == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun BottomStatusBar(voiceName: String, right2Name: String, splitPoint: String) {
    Surface(color = PanelDark, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RIGHT1  $voiceName", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text("RIGHT2  $right2Name", color = TextDim, fontSize = 8.sp)
            Text("SPLIT  $splitPoint", color = TextDim, fontSize = 8.sp)
        }
    }
}

@Composable
private fun DebugPanel() {
    var logs by remember { mutableStateOf(listOf<String>()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            logs = DebugLog.getAll()
            delay(250)
        }
    }

    val logText = logs.joinToString("\n")

    Surface(color = PanelDark, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ENGINE LOG  •  LIVE", color = TextDim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            val fullTrace = DebugLog.getLongText()
                            val text = when {
                                fullTrace.isNotBlank() -> fullTrace
                                logText.isNotBlank() -> logText
                                else -> "(no log yet)"
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, if (fullTrace.isNotBlank()) "YamahaArranger Full String/CASM Trace" else "YamahaArranger Engine Log")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Kirim YamahaArranger Log"))
                        },
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                    ) { Text("SEND LOG", fontSize = 8.sp) }

                    TextButton(
                        onClick = { DebugLog.clear() },
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
                    ) { Text("CLEAR", fontSize = 8.sp) }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF0B0C0D), RoundedCornerShape(3.dp))
                    .border(1.dp, PanelMid, RoundedCornerShape(3.dp))
                    .padding(5.dp)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    logs.forEach { line ->
                        Text(
                            line,
                            color = Color(0xFFB8C0C8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                    if (logs.isEmpty()) Text("(no log yet)", color = TextDim, fontSize = 9.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "SEND LOG = kirim teks log lewat WhatsApp/Telegram/email atau pilih ChatGPT.",
                color = TextDim,
                fontSize = 7.sp
            )
        }
    }
}

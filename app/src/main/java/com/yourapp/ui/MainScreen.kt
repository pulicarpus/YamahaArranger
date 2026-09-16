package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onImportStyleClicked: () -> Unit = {},
    onImportSoundFontClicked: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        LcdDisplay(
            styleName = uiState.styleName,
            tempoBpm = uiState.tempoBpm,
            transpose = uiState.transpose,
            chordLabel = uiState.detectedChordLabel,
            soundFontName = uiState.soundFontName,
            onImportStyleClicked = onImportStyleClicked,
            onImportSoundFontClicked = onImportSoundFontClicked,
            onTempoDown = viewModel::onTempoDown,
            onTempoUp = viewModel::onTempoUp,
            onTransposeDown = viewModel::onTransposeDown,
            onTransposeUp = viewModel::onTransposeUp
        )

        Spacer(Modifier.height(8.dp))
        MidiStatusBar(             midiStatus = uiState.midiStatus,             midiOutEnabled = uiState.midiOutEnabled,             onConnect = viewModel::refreshMidiConnection,             onToggleMidiOut = viewModel::toggleMidiOut         )
        Spacer(Modifier.height(8.dp))

        SectionLabel("INTRO")
        SectionRow(listOf("Intro 1", "Intro 2", "Intro 3"), uiState.activeSection, viewModel::onSectionSelected)
        Spacer(Modifier.height(6.dp))

        SectionLabel("MAIN VARIATION")
        SectionRow(listOf("Main A", "Main B", "Main C", "Main D"), uiState.activeSection, viewModel::onSectionSelected)
        Spacer(Modifier.height(6.dp))

        SectionLabel("FILL IN")
        SectionRow(listOf("Fill A", "Fill B", "Fill C", "Fill D"), uiState.activeSection, viewModel::onSectionSelected)
        Spacer(Modifier.height(6.dp))

        SectionLabel("ENDING")
        SectionRow(listOf("Ending 1", "Ending 2", "Ending 3"), uiState.activeSection, viewModel::onSectionSelected)
        Spacer(Modifier.height(10.dp))

        TransportRow(uiState.isPlaying, viewModel::onSyncStart, viewModel::onStartStop, viewModel::onTapTempo)
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { viewModel.playTestTone() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("🔊 TEST TONE (C-E-G)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))

        VoiceSelectPanel(
            voices = uiState.voiceAssignments,
            onCycle = viewModel::cycleVoice
        )
        Spacer(Modifier.height(12.dp))

        RegistrationRow(
            activeBank = uiState.activeBank,
            activeRegSlot = uiState.activeRegSlot,
            onBankChange = viewModel::onBankChange,
            onRegSlotTap = viewModel::onRegSlotTap,
            onRegSlotSave = viewModel::onRegSlotSave
        )
        Spacer(Modifier.height(12.dp))

        VolumePanel(
            styleVolume = uiState.styleVolume,
            voiceVolume = uiState.voiceVolume,
            masterVolume = uiState.masterVolume,
            onStyleChange = viewModel::onStyleVolumeChange,
            onVoiceChange = viewModel::onVoiceVolumeChange,
            onMasterChange = viewModel::onMasterVolumeChange
        )
        Spacer(Modifier.height(12.dp))

        DebugPanel()
        Spacer(Modifier.height(10.dp))

        BottomBar(uiState.voiceName, uiState.right2Name, uiState.splitPoint)
    }
}

// ═════════════════════════════════════════════════════
// VOICE SELECT PANEL
// ═════════════════════════════════════════════════════
@Composable
private fun VoiceSelectPanel(
    voices: List<VoiceSlot>,
    onCycle: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "VOICE ASSIGN (tap untuk ganti)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(6.dp))
            voices.forEach { slot ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${slot.label} (ch${slot.channel})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onCycle(slot.channel) }) {
                        Text(
                            "🎼 ${slot.displayName()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// DEBUG PANEL
// ═════════════════════════════════════════════════════
@Composable
private fun DebugPanel() {
    var logs by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            logs = DebugLog.getAll()
            delay(500)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DEBUG LOG",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { DebugLog.clear() }) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .padding(6.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "(no log yet)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        logs.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// HEADER
// ═════════════════════════════════════════════════════
@Composable
private fun LcdDisplay(
    styleName: String,
    tempoBpm: Int,
    transpose: Int,
    chordLabel: String,
    soundFontName: String,
    onImportStyleClicked: () -> Unit,
    onImportSoundFontClicked: () -> Unit,
    onTempoDown: () -> Unit,
    onTempoUp: () -> Unit,
    onTransposeDown: () -> Unit,
    onTransposeUp: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    styleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = onImportStyleClicked, contentPadding = PaddingValues(0.dp)) {
                    Text("Import .sty…", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onImportSoundFontClicked, contentPadding = PaddingValues(0.dp)) {
                    Text("🎼 SF2: $soundFontName", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "TRANSPOSE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSquareButton("−", onTransposeDown)
                    Text(
                        if (transpose >= 0) "+$transpose" else "$transpose",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    SmallSquareButton("+", onTransposeUp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "TEMPO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSquareButton("−", onTempoDown)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$tempoBpm", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("BPM", style = MaterialTheme.typography.labelSmall)
                    }
                    SmallSquareButton("+", onTempoUp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    chordLabel.ifEmpty { "—" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SmallSquareButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

// ═════════════════════════════════════════════════════
// MIDI BAR
// ═════════════════════════════════════════════════════
@Composable
private fun MidiStatusBar(
    midiStatus: String,
    midiOutEnabled: Boolean,
    onConnect: () -> Unit,
    onToggleMidiOut: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (midiStatus.startsWith("No")) "⚪" else "🟢", fontSize = 14.sp)
                Text(
                    "  MIDI: $midiStatus",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle MIDI OUT
                Button(
                    onClick = onToggleMidiOut,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (midiOutEnabled)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (midiOutEnabled)
                            MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (midiOutEnabled) "📤 OUT: ON" else "📤 OUT",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Connect
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// SECTION LABEL + ROW
// ═════════════════════════════════════════════════════
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
    )
}

@Composable
private fun SectionRow(sections: List<String>, activeSection: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sections.forEach { section ->
            val isActive = section == activeSection
            Button(
                onClick = { onSelect(section) },
                modifier = Modifier.weight(1f).height(42.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.surface,
                    contentColor = if (isActive) MaterialTheme.colorScheme.onSecondary
                    else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(section, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// TRANSPORT
// ═════════════════════════════════════════════════════
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    onSyncStart: () -> Unit,
    onStartStop: () -> Unit,
    onTapTempo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(onClick = onSyncStart, modifier = Modifier.weight(1f).height(46.dp)) {
            Text("Sync Start", style = MaterialTheme.typography.labelMedium)
        }
        Button(onClick = onStartStop, modifier = Modifier.weight(1f).height(46.dp)) {
            Text(if (isPlaying) "⏸ Stop" else "▶ Start", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(onClick = onTapTempo, modifier = Modifier.weight(1f).height(46.dp)) {
            Text("Tap Tempo", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ═════════════════════════════════════════════════════
// REGISTRATION
// ═════════════════════════════════════════════════════
@Composable
private fun RegistrationRow(
    activeBank: Int,
    activeRegSlot: Int,
    onBankChange: (Int) -> Unit,
    onRegSlotTap: (Int) -> Unit,
    onRegSlotSave: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "REGISTRATION MEMORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BANK", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    SmallSquareButton("−") { onBankChange(activeBank - 1) }
                    Text("$activeBank", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                    SmallSquareButton("+") { onBankChange(activeBank + 1) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (1..4).forEach { slot ->
                    val isActive = slot == activeRegSlot
                    Button(
                        onClick = { onRegSlotTap(slot) },
                        modifier = Modifier.weight(1f).height(42.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.background,
                            contentColor = if (isActive) MaterialTheme.colorScheme.onSecondary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("REG $slot", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// VOLUME
// ═════════════════════════════════════════════════════
@Composable
private fun VolumePanel(
    styleVolume: Int,
    voiceVolume: Int,
    masterVolume: Int,
    onStyleChange: (Int) -> Unit,
    onVoiceChange: (Int) -> Unit,
    onMasterChange: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            VolumeSlider("STYLE", styleVolume, onStyleChange)
            Spacer(Modifier.height(4.dp))
            VolumeSlider("VOICE", voiceVolume, onVoiceChange)
            Spacer(Modifier.height(4.dp))
            VolumeSlider("MASTER", masterVolume, onMasterChange)
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 8.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..127f,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).height(20.dp)
        )
    }
}

// ═════════════════════════════════════════════════════
// BOTTOM BAR
// ═════════════════════════════════════════════════════
@Composable
private fun BottomBar(voiceName: String, right2Name: String, splitPoint: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("🎹 $voiceName", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text("Right2: $right2Name", style = MaterialTheme.typography.labelMedium)
            Text("Split: $splitPoint", style = MaterialTheme.typography.labelMedium)
        }
    }
}
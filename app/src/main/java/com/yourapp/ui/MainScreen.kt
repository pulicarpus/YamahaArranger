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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onImportStyleClicked: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        // 1. HEADER
        LcdDisplay(
            styleName = uiState.styleName,
            tempoBpm = uiState.tempoBpm,
            transpose = uiState.transpose,
            chordLabel = uiState.detectedChordLabel,
            onImportStyleClicked = onImportStyleClicked,
            onTempoDown = viewModel::onTempoDown,
            onTempoUp = viewModel::onTempoUp,
            onTransposeDown = viewModel::onTransposeDown,
            onTransposeUp = viewModel::onTransposeUp
        )

        Spacer(Modifier.height(8.dp))

        // 2. MIDI BAR
        MidiStatusBar(
            midiStatus = uiState.midiStatus,
            onConnect = viewModel::refreshMidiConnection
        )

        Spacer(Modifier.height(8.dp))

        // 3. SECTION — Baris 1: Intro 1-3
        SectionLabel("INTRO")
        SectionRow(
            sections = listOf("Intro 1", "Intro 2", "Intro 3"),
            activeSection = uiState.activeSection,
            onSelect = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(6.dp))

        // 4. SECTION — Baris 2: Main A-D
        SectionLabel("MAIN VARIATION")
        SectionRow(
            sections = listOf("Main A", "Main B", "Main C", "Main D"),
            activeSection = uiState.activeSection,
            onSelect = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(6.dp))

        // 5. SECTION — Baris 3: Fill A-D
        SectionLabel("FILL IN")
        SectionRow(
            sections = listOf("Fill A", "Fill B", "Fill C", "Fill D"),
            activeSection = uiState.activeSection,
            onSelect = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(6.dp))

        // 6. SECTION — Baris 4: Break + Ending 1-3
        SectionLabel("BREAK / ENDING")
        SectionRow(
            sections = listOf("Break", "Ending 1", "Ending 2", "Ending 3"),
            activeSection = uiState.activeSection,
            onSelect = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(10.dp))

        // 7. TRANSPORT
        TransportRow(
            isPlaying = uiState.isPlaying,
            onSyncStart = viewModel::onSyncStart,
            onStartStop = viewModel::onStartStop,
            onTapTempo = viewModel::onTapTempo
        )

        Spacer(Modifier.height(12.dp))

        // 8. REGISTRATION
        RegistrationRow(
            activeBank = uiState.activeBank,
            activeRegSlot = uiState.activeRegSlot,
            onBankChange = viewModel::onBankChange,
            onRegSlotTap = viewModel::onRegSlotTap,
            onRegSlotSave = viewModel::onRegSlotSave
        )

        Spacer(Modifier.height(12.dp))

        // 9. VOLUME SLIDERS
        VolumePanel(
            styleVolume = uiState.styleVolume,
            voiceVolume = uiState.voiceVolume,
            masterVolume = uiState.masterVolume,
            onStyleChange = viewModel::onStyleVolumeChange,
            onVoiceChange = viewModel::onVoiceVolumeChange,
            onMasterChange = viewModel::onMasterVolumeChange
        )

        Spacer(Modifier.height(12.dp))

        // 10. PANEL PLACEHOLDER — untuk fitur mendatang
        PanelPlaceholder()

        Spacer(Modifier.height(10.dp))

        // 11. BOTTOM BAR
        BottomBar(
            voiceName = uiState.voiceName,
            right2Name = uiState.right2Name,
            splitPoint = uiState.splitPoint
        )
    }
}

// ═════════════════════════════════════════════════════
// HEADER — Style + BPM ± + Transpose ± + Chord
// ═════════════════════════════════════════════════════
@Composable
private fun LcdDisplay(
    styleName: String,
    tempoBpm: Int,
    transpose: Int,
    chordLabel: String,
    onImportStyleClicked: () -> Unit,
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
            // Kiri: Style info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = styleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = onImportStyleClicked,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Import .sty…", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Tengah: Transpose
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TRANSPOSE", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSquareButton("−", onTransposeDown)
                    Text(
                        text = if (transpose >= 0) "+$transpose" else "$transpose",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    SmallSquareButton("+", onTransposeUp)
                }
            }

            // Kanan: BPM + Chord
            Column(horizontalAlignment = Alignment.End) {
                Text("TEMPO", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSquareButton("−", onTempoDown)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$tempoBpm",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text("BPM", style = MaterialTheme.typography.labelSmall)
                    }
                    SmallSquareButton("+", onTempoUp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = chordLabel.ifEmpty { "—" },
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
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
}

// ═════════════════════════════════════════════════════
// MIDI Bar
// ═════════════════════════════════════════════════════
@Composable
private fun MidiStatusBar(midiStatus: String, onConnect: () -> Unit) {
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
                    text = "  MIDI: $midiStatus",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Connect", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// Section Label
// ═════════════════════════════════════════════════════
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
    )
}

// ═════════════════════════════════════════════════════
// Section Row
// ═════════════════════════════════════════════════════
@Composable
private fun SectionRow(
    sections: List<String>,
    activeSection: String,
    onSelect: (String) -> Unit
) {
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
// Transport
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
        OutlinedButton(
            onClick = onSyncStart,
            modifier = Modifier.weight(1f).height(46.dp)
        ) { Text("Sync Start", style = MaterialTheme.typography.labelMedium) }

        Button(
            onClick = onStartStop,
            modifier = Modifier.weight(1f).height(46.dp)
        ) { Text(if (isPlaying) "⏸ Stop" else "▶ Start", style = MaterialTheme.typography.labelMedium) }

        OutlinedButton(
            onClick = onTapTempo,
            modifier = Modifier.weight(1f).height(46.dp)
        ) { Text("Tap Tempo", style = MaterialTheme.typography.labelMedium) }
    }
}

// ═════════════════════════════════════════════════════
// Registration
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
                    text = "REGISTRATION MEMORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BANK", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    SmallSquareButton("−", { onBankChange(activeBank - 1) })
                    Text(
                        text = "$activeBank",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    SmallSquareButton("+", { onBankChange(activeBank + 1) })
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
// Volume Panel
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
            text = label,
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
            text = "$value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).height(20.dp)
        )
    }
}

// ═════════════════════════════════════════════════════
// Panel Placeholder
// ═════════════════════════════════════════════════════
@Composable
private fun PanelPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "PANEL AREA — Voice · Style Browser · MIDI Router",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}

// ═════════════════════════════════════════════════════
// Bottom Bar
// ═════════════════════════════════════════════════════
@Composable
private fun BottomBar(
    voiceName: String,
    right2Name: String,
    splitPoint: String
) {
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
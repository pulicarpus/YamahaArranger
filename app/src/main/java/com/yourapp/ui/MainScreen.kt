package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

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
            .padding(12.dp)
    ) {
        // ═══════════════════════════════════════════════
        // 1. HEADER — Style info + Tempo + Chord
        // ═══════════════════════════════════════════════
        LcdDisplay(
            styleName = uiState.styleName,
            tempoBpm = uiState.tempoBpm,
            activeSection = uiState.activeSection,
            chordLabel = uiState.detectedChordLabel,
            onImportStyleClicked = onImportStyleClicked
        )

        Spacer(Modifier.height(10.dp))

        // ═══════════════════════════════════════════════
        // 2. MIDI STATUS BAR
        // ═══════════════════════════════════════════════
        MidiStatusBar(
            midiStatus = uiState.midiStatus,
            onConnect = { viewModel.refreshMidiConnection() }
        )

        Spacer(Modifier.height(10.dp))

        // ═══════════════════════════════════════════════
        // 3. SECTION BUTTONS — Baris 1 (Intro + Main)
        // ═══════════════════════════════════════════════
        SectionRow(
            sections = listOf("Intro", "Main A", "Main B"),
            activeSection = uiState.activeSection,
            onSectionSelected = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(6.dp))

        // Baris 2 (Fill + Break + Ending)
        SectionRow(
            sections = listOf("Fill", "Break", "Ending"),
            activeSection = uiState.activeSection,
            onSectionSelected = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(10.dp))

        // ═══════════════════════════════════════════════
        // 4. TRANSPORT — Sync, Start/Stop, Tap
        // ═══════════════════════════════════════════════
        TransportRow(
            isPlaying = uiState.isPlaying,
            onSyncStart = viewModel::onSyncStart,
            onStartStop = viewModel::onStartStop,
            onTapTempo = viewModel::onTapTempo
        )

        Spacer(Modifier.height(14.dp))

        // ═══════════════════════════════════════════════
        // 5. FUTURE PANEL — tempat fitur mendatang
        //    (Volume, Voice, Style Browser, Registration)
        // ═══════════════════════════════════════════════
        FuturePanelPlaceholder()

        Spacer(Modifier.weight(1f))

        // ═══════════════════════════════════════════════
        // 6. BOTTOM INFO BAR
        // ═══════════════════════════════════════════════
        BottomBar(
            voiceName = "GrandPiano",
            right2Name = "OFF",
            splitPoint = "C4"
        )
    }
}

// ═════════════════════════════════════════════════════
// COMPONENT: LCD Display (Header)
// ═════════════════════════════════════════════════════
@Composable
private fun LcdDisplay(
    styleName: String,
    tempoBpm: Int,
    activeSection: String,
    chordLabel: String,
    onImportStyleClicked: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Kiri — Style info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = styleName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Section: $activeSection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onImportStyleClicked,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("Import .sty…", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Kanan — Tempo + Chord
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$tempoBpm",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
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

// ═════════════════════════════════════════════════════
// COMPONENT: MIDI Status Bar
// ═════════════════════════════════════════════════════
@Composable
private fun MidiStatusBar(
    midiStatus: String,
    onConnect: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (midiStatus.startsWith("No")) "⚪" else "🟢",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  MIDI: $midiStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp
                )
            ) {
                Text("Connect", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ═════════════════════════════════════════════════════
// COMPONENT: Section Row
// ═════════════════════════════════════════════════════
@Composable
private fun SectionRow(
    sections: List<String>,
    activeSection: String,
    onSectionSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sections.forEach { section ->
            val isActive = section == activeSection
            Button(
                onClick = { onSectionSelected(section) },
                modifier = Modifier.weight(1f),
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
// COMPONENT: Transport Row
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSyncStart,
            modifier = Modifier.weight(1f)
        ) { Text("Sync Start", style = MaterialTheme.typography.labelMedium) }

        Button(
            onClick = onStartStop,
            modifier = Modifier.weight(1f)
        ) { Text(if (isPlaying) "⏸ Stop" else "▶ Start", style = MaterialTheme.typography.labelMedium) }

        OutlinedButton(
            onClick = onTapTempo,
            modifier = Modifier.weight(1f)
        ) { Text("Tap Tempo", style = MaterialTheme.typography.labelMedium) }
    }
}

// ═════════════════════════════════════════════════════
// COMPONENT: Future Panel Placeholder
// ═════════════════════════════════════════════════════
@Composable
private fun FuturePanelPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PANEL AREA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Volume · Voice · Registration",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═════════════════════════════════════════════════════
// COMPONENT: Bottom Info Bar
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🎹 $voiceName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Right2: $right2Name",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Split: $splitPoint",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
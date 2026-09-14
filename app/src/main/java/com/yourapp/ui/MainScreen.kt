package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        LcdDisplay(
            styleName = uiState.styleName,
            tempoBpm = uiState.tempoBpm,
            activeSection = uiState.activeSection,
            chordLabel = uiState.detectedChordLabel,
            onImportStyleClicked = onImportStyleClicked
        )

        Spacer(Modifier.height(12.dp))

        SectionButtonRow(
            activeSection = uiState.activeSection,
            onSectionSelected = viewModel::onSectionSelected
        )

        Spacer(Modifier.height(12.dp))

        TransportRow(
            isPlaying = uiState.isPlaying,
            onSyncStart = viewModel::onSyncStart,
            onStartStop = viewModel::onStartStop,
            onTapTempo = viewModel::onTapTempo
        )

        Spacer(Modifier.weight(1f))

        PianoKeyboard(
            onNoteOn = viewModel::onKeyboardNoteOn,
            onNoteOff = viewModel::onKeyboardNoteOff
        )
    }
}

@Composable
private fun LcdDisplay(
    styleName: String, tempoBpm: Int, activeSection: String, chordLabel: String,
    onImportStyleClicked: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(styleName, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("Section: $activeSection", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onImportStyleClicked, contentPadding = PaddingValues(0.dp)) {
                    Text("Import .sty...", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$tempoBpm BPM", style = MaterialTheme.typography.titleMedium)
                Text(chordLabel.ifEmpty { "--" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun SectionButtonRow(activeSection: String, onSectionSelected: (String) -> Unit) {
    val sections = listOf("Intro", "Main A", "Main B", "Fill", "Break", "Ending")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sections.forEach { section ->
            val isActive = section == activeSection
            Button(
                onClick = { onSectionSelected(section) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.secondary
                                     else MaterialTheme.colorScheme.surface
                )
            ) {
                Text(section, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TransportRow(isPlaying: Boolean, onSyncStart: () -> Unit,
                          onStartStop: () -> Unit, onTapTempo: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSyncStart) { Text("Sync Start") }
        Button(onClick = onStartStop) { Text(if (isPlaying) "Stop" else "Start") }
        OutlinedButton(onClick = onTapTempo) { Text("Tap Tempo") }
    }
}

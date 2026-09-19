package com.yourapp.yamahaarranger.ui

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

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
    val stylePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onStyleFilePicked) }
    val soundFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onSoundFontFilePicked) }
    val context = LocalContext.current
    val saveLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val fullTrace = DebugLog.getFullText()
            val text = if (fullTrace.isNotBlank()) fullTrace else DebugLog.getAll().joinToString("\n")
            runCatching {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                Toast.makeText(context, "All log saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Failed to save log: " + it.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    var showUtilityLog by remember { mutableStateOf(false) }
    var showMixer by remember { mutableStateOf(false) }
    var showSf2Manager by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(SxBlack)) {
        val compact = maxHeight < 620.dp
        val gap = if (compact) 4.dp else 6.dp
        val headerH = if (compact) 34.dp else 40.dp
        val navH = if (compact) 38.dp else 44.dp
        val controlH = if (compact) 106.dp else 124.dp
        val regH = if (compact) 48.dp else 56.dp
        val footerH = if (compact) 28.dp else 32.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp)) {
            SxHeader(state, headerH) { showSf2Manager = true }
            Spacer(Modifier.height(gap))
            SxNavBar(navH, onMixer = { showMixer = true }, onUtility = { showUtilityLog = true })
            Spacer(Modifier.height(gap))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SxSideMenu(Modifier.weight(.17f).fillMaxHeight(), compact)
                SxCenterDisplay(state, viewModel, compact,
                    onPickStyle = { stylePicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(.66f).fillMaxHeight())
                SxRightPanel(state, Modifier.weight(.17f).fillMaxHeight(), compact)
            }
            Spacer(Modifier.height(gap)); SxStyleControls(state, viewModel, controlH, compact)
            Spacer(Modifier.height(gap)); SxRegistration(state, viewModel, regH, compact)
            Spacer(Modifier.height(gap)); SxMidiBar(state, viewModel, footerH, compact)
        }
    }

    if (showMixer) {
        SxStyleMixerDialog(
            voices = state.voiceAssignments,
            onDismiss = { showMixer = false },
            onMixer = viewModel::setStyleChannelMixer,
            onMute = viewModel::toggleStyleChannelMute,
        )
    }

    if (showSf2Manager) {
        SxSf2ManagerDialog(
            files = state.availableSoundFonts,
            currentName = state.soundFontName,
            onDismiss = { showSf2Manager = false },
            onSelect = { uri, name ->
                viewModel.selectSoundFont(uri, name)
                showSf2Manager = false
            },
            onRefresh = viewModel::refreshSoundFontList,
            onImport = {
                showSf2Manager = false
                soundFontPicker.launch(arrayOf("audio/x-soundfont", "application/octet-stream", "audio/*", "*/*"))
            }
        )
    }

    if (showUtilityLog) {
        SxEngineLogDialog(onDismiss = { showUtilityLog = false }, onSaveAllLog = { saveLogLauncher.launch("YamahaArranger_AllLog_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt") })
    }
}

@Composable
private fun SxStyleMixerDialog(voices: List<VoiceSlot>, onDismiss: () -> Unit, onMixer: (Int, Int, Int, Int, Int, Int) -> Unit, onMute: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SxPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("STYLE MIXER • 16 CHANNEL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                Text("Per-channel volume, pan, expression, reverb and chorus.", color = SxDim, fontSize = 9.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(voices, key = { it.channel }) { slot ->
                        Surface(color = if (slot.styleMuted) SxPanel2 else Color(0xFF171D23), shape = RoundedCornerShape(4.dp)) {
                            Column(Modifier.padding(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("CH" + slot.channel, color = SxBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                                    Text(slot.displayName(), color = Color.White, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { onMute(slot.channel) }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text(if (slot.styleMuted) "UNMUTE" else "MUTE", fontSize = 7.sp) }
                                }
                                SxMixRow("VOL", slot.styleVolume) { onMixer(slot.channel, it, slot.stylePan, slot.styleExpression, slot.styleReverb, slot.styleChorus) }
                                SxMixRow("PAN", slot.stylePan) { onMixer(slot.channel, slot.styleVolume, it, slot.styleExpression, slot.styleReverb, slot.styleChorus) }
                                SxMixRow("EXP", slot.styleExpression) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, it, slot.styleReverb, slot.styleChorus) }
                                SxMixRow("REV", slot.styleReverb) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, slot.styleExpression, it, slot.styleChorus) }
                                SxMixRow("CHO", slot.styleChorus) { onMixer(slot.channel, slot.styleVolume, slot.stylePan, slot.styleExpression, slot.styleReverb, it) }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SxMixRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = SxDim, fontSize = 7.sp, modifier = Modifier.width(30.dp))
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..127f, modifier = Modifier.weight(1f))
        Text(value.toString(), color = Color.White, fontSize = 7.sp, modifier = Modifier.width(28.dp))
    }
}
@Composable
private fun SxSf2ManagerDialog(files: List<Pair<Uri, String>>, currentName: String, onDismiss: () -> Unit, onSelect: (Uri, String) -> Unit, onRefresh: () -> Unit, onImport: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SxPanel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(.9f).fillMaxHeight(.75f)) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SF2 MANAGER", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                Text("Current: " + currentName.ifBlank { "None" }, color = SxBlue, fontSize = 9.sp)
                Spacer(Modifier.height(6.dp))
                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No SF2 files found. Use IMPORT to add one.", color = SxDim, fontSize = 10.sp) }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(files, key = { it.second }) { item ->
                            val active = item.second == currentName
                            Surface(color = if (active) SxBlueDark else SxPanel2, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(item.first, item.second) }) {
                                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (active) "✓" else "○", color = if (active) SxGreen else SxDim, fontSize = 12.sp)
                                    Spacer(Modifier.width(7.dp))
                                    Text(item.second, color = Color.White, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("REFRESH", fontSize = 8.sp) }
                    Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("IMPORT", fontSize = 8.sp) }
                }
            }
        }
    }
}

@Composable
private fun SxHeader(state: MainUiState, height: Dp, onPickSoundFont: () -> Unit) {
    Surface(color = Color(0xFF080A0C), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF333A42), RoundedCornerShape(5.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YAMAHA", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.width(9.dp)); Text("PSR-SX900", color = SxDim, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.width(8.dp)); Text("YamahaArranger", color = Color(0xFF6EA7D2), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("● MIDI", color = if (state.midiStatus.startsWith("No")) SxDim else SxGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Surface(color = if (state.soundFontName == "None" || state.soundFontName == "Load failed") Color(0xFF252B31) else SxBlueDark,
                shape = RoundedCornerShape(3.dp), modifier = Modifier.clickable(onClick = onPickSoundFont).border(1.dp, Color(0xFF39434B), RoundedCornerShape(3.dp))) {
                Text(if (state.soundFontName == "None") "LOAD SF2" else "SF2 ${state.soundFontName.take(16)}", color = Color.White, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun SxNavBar(height: Dp, onMixer: () -> Unit, onUtility: () -> Unit) {
    val tabs = listOf("HOME", "STYLE", "VOICE", "SONG", "MULTI PAD", "REGIST", "MIXER", "UTILITY")
    Row(Modifier.fillMaxWidth().height(height).background(Color(0xFF0D1115), RoundedCornerShape(5.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        tabs.forEachIndexed { i, label ->
            val modifier = Modifier.weight(1f).fillMaxHeight().then(
                when (label) {
                    "MIXER" -> Modifier.clickable(onClick = onMixer)
                    "UTILITY" -> Modifier.clickable(onClick = onUtility)
                    else -> Modifier
                }
            )
            Surface(color = if (i == 0) Color(0xFF073E82) else Color(0xFF1C2228), shape = RoundedCornerShape(3.dp), modifier = modifier) {
                Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SxEngineLogDialog(onDismiss: () -> Unit, onSaveAllLog: () -> Unit) {
    var logs by remember { mutableStateOf(listOf<String>()) }
    var filter by remember { mutableStateOf("ALL") }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            logs = DebugLog.getAll()
            delay(250)
        }
    }

    val filteredLogs = when (filter) {
        "AUDIO" -> logs.filter { it.contains("🔊 AUDIO") }
        "MIDI" -> logs.filter { it.contains("🎹 IN") || it.contains("📤 MIDI OUT") }
        "CASM" -> logs.filter { it.contains("CASM") || it.contains("RTR") || it.contains("RETARGET") }
        "STRING" -> logs.filter { it.contains("STRING") || it.contains("dst=13") || it.contains("dst13") }
        "ERROR" -> logs.filter { it.contains("❌") || it.contains("FAILED") || it.contains("error", ignoreCase = true) }
        else -> logs
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("UTILITY  •  ENGINE LOG") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                Text("Live engine/MIDI diagnostics. Full history is saved with SAVE ALL LOG.", color = SxDim, fontSize = 9.sp)
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("ALL", "AUDIO", "MIDI", "CASM", "STRING", "ERROR").forEach { f ->
                        TextButton(
                            onClick = { filter = f },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text(f, fontSize = 7.sp, color = if (filter == f) SxGreen else SxDim) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Surface(color = Color(0xFF080B0E), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 430.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                        filteredLogs.forEach { line -> Text(line, color = Color(0xFFB8C0C8), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 8.sp) }
                        if (filteredLogs.isEmpty()) Text("(no log yet)", color = SxDim, fontSize = 8.sp)
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { DebugLog.clear(); logs = emptyList() }) { Text("CLEAR") }
                TextButton(onClick = {
                    onSaveAllLog()
                }) { Text("SAVE ALL LOG") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
private fun SxSideMenu(modifier: Modifier, compact: Boolean) {
    val labels = listOf("STYLE SELECT", "FAVORITE", "STYLE CONTROL", "OTS LINK", "SYNC START", "STYLE SETTING")
    Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            Text("STYLE", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            labels.forEach { label ->
                Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF343C45), RoundedCornerShape(3.dp))) {
                    Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun SxCenterDisplay(state: MainUiState, vm: MainViewModel, compact: Boolean, onPickStyle: () -> Unit, modifier: Modifier) {
    Surface(color = Color(0xFF101419), shape = RoundedCornerShape(6.dp), modifier = modifier.border(2.dp, Color(0xFF39424C), RoundedCornerShape(6.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 7.dp)) {
            Row(Modifier.fillMaxWidth().height(if (compact) 26.dp else 30.dp).background(Color(0xFF080B0E), RoundedCornerShape(2.dp)), verticalAlignment = Alignment.CenterVertically) {
                Text("HOME", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp)); Text("STYLE", color = Color(0xFF55A9E6), fontSize = 8.sp)
                Spacer(Modifier.weight(1f)); Text("44.1 kHz", color = SxDim, fontSize = 7.sp, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.height(if (compact) 4.dp else 5.dp))
            Column(Modifier.fillMaxSize().weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                    SxStyleCard(state, onPickStyle, Modifier.weight(1.25f)); SxMultiPad(Modifier.weight(.75f))
                }
                Row(Modifier.fillMaxWidth().weight(1.15f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                    val v = state.voiceAssignments
                    SxVoiceCard("RIGHT 1", state.voiceName, true, Modifier.weight(1f)); SxVoiceCard("RIGHT 2", state.right2Name, false, Modifier.weight(1f))
                    SxVoiceCard("RIGHT 3", v.getOrNull(2)?.displayName() ?: "OFF", false, Modifier.weight(1f)); SxVoiceCard("LEFT", v.getOrNull(3)?.displayName() ?: "OFF", false, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().weight(.55f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SxValue("CHORD", state.detectedChordLabel.ifBlank { "—" }, Modifier.weight(1f), null, null, compact)
                    SxValue("TEMPO", state.tempoBpm.toString(), Modifier.weight(.8f), vm::onTempoDown, vm::onTempoUp, compact)
                    SxValue("TRANSPOSE", if (state.transpose >= 0) "+${state.transpose}" else state.transpose.toString(), Modifier.weight(.9f), vm::onTransposeDown, vm::onTransposeUp, compact)
                    SxValue("SPLIT", state.splitPoint, Modifier.weight(.8f), null, null, compact)
                    Surface(color = Color(0xFF172029), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1.25f).fillMaxHeight().border(1.dp, Color(0xFF34404C), RoundedCornerShape(3.dp))) {
                        Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("PART", color = SxDim, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                            listOf("A", "B", "C", "D").forEach { part -> Box(Modifier.weight(1f).fillMaxHeight().padding(start = 2.dp).background(if (state.activeSection.endsWith(" $part")) SxOrangeBright else Color(0xFF26313B), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) { Text(part, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SxStyleCard(state: MainUiState, onClick: () -> Unit, modifier: Modifier) {
    Surface(color = SxOrange, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().clickable(onClick = onClick).border(1.dp, Color(0xFFFF9B32), RoundedCornerShape(4.dp))) {
        Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Text("STYLE", color = Color(0xFFFFD1A5), fontSize = 7.sp, fontWeight = FontWeight.Bold); Text(state.styleName.take(25), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("4/4   ♪ ${state.tempoBpm}", color = Color.White, fontSize = 8.sp); Text(state.activeSection, color = Color(0xFFFFC27A), fontSize = 7.sp) }
            Text("LOAD", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp))
        }
    }
}
@Composable private fun SxMultiPad(modifier: Modifier) { Surface(color = SxBlueDark, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().border(1.dp, Color(0xFF146CB3), RoundedCornerShape(4.dp))) { Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.Center) { Text("MULTI PAD", color = Color(0xFFA8D8FF), fontSize = 7.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Ready", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("Shaker & Tamb   1/4", color = SxDim, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
@Composable private fun SxVoiceCard(label: String, name: String, active: Boolean, modifier: Modifier) { Surface(color = if (active) Color(0xFF063F85) else Color(0xFF24292F), shape = RoundedCornerShape(3.dp), modifier = modifier.fillMaxHeight().border(1.dp, if (active) Color(0xFF2C91FF) else Color(0xFF3A424B), RoundedCornerShape(3.dp))) { Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) { Text(label, color = if (active) Color(0xFFA8D8FF) else SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold); Text(name.ifBlank { "OFF" }.take(18), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (active) "ON" else "OFF", color = if (active) Color(0xFF58D8FF) else SxDim, fontSize = 6.sp) } } }
@Composable private fun SxValue(label: String, value: String, modifier: Modifier, minus: (() -> Unit)?, plus: (() -> Unit)?, compact: Boolean) { Surface(color = SxPanel2, shape = RoundedCornerShape(3.dp), modifier = modifier.fillMaxHeight().border(1.dp, Color(0xFF343B44), RoundedCornerShape(3.dp))) { Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = SxDim, fontSize = if (compact) 5.sp else 6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 3.dp), maxLines = 1); if (minus != null && plus != null) SmallKey("−", minus, compact); Text(value, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); if (minus != null && plus != null) SmallKey("+", plus, compact) } } }

@Composable private fun SxRightPanel(state: MainUiState, modifier: Modifier, compact: Boolean) { Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) { Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) { Text("VOICE SELECT", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold); val cats = listOf("PIANO", "ORGAN", "GUITAR", "STRINGS", "BRASS", "SAX/WOODWIND", "SYNTH", "CHOIR/PAD", "BASS", "PERCUSSION", "WORLD", "USER"); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { cats.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { row.forEach { c -> Surface(color = if (c == "PIANO") SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF35404A), RoundedCornerShape(3.dp))) { Box(contentAlignment = Alignment.Center) { Text(c, color = Color.White, fontSize = if (c.length > 8) 5.sp else 6.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } } } }; Text("PART ON/OFF", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { listOf("R1", "R2", "R3", "L").forEach { p -> Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) { Box(contentAlignment = Alignment.Center) { Text(p, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) } } } }; Text("ONE TOUCH SETTING", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { (1..4).forEach { n -> Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) { Box(contentAlignment = Alignment.Center) { Text(n.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } } } } }

@Composable private fun SxStyleControls(state: MainUiState, vm: MainViewModel, height: Dp, compact: Boolean) { Surface(color = Color(0xFF111519), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF303841), RoundedCornerShape(5.dp))) { Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("STYLE CONTROL", color = SxOrangeBright, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF6D3600))) }; Spacer(Modifier.height(if (compact) 4.dp else 6.dp)); Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) { SxSectionGroup("INTRO", listOf("Intro 1", "Intro 2", "Intro 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact); SxSectionGroup("MAIN VARIATION", listOf("Main A", "Main B", "Main C", "Main D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f), compact); SxStartStop(state.isPlaying, vm::onStartStop, Modifier.weight(1.35f), compact); SxSectionGroup("ENDING", listOf("Ending 1", "Ending 2", "Ending 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact); Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) { SxAutoFill(state.autoFill, vm::toggleAutoFill, Modifier.fillMaxWidth().weight(1f), compact); SxAction("SYNC START", false, vm::onSyncStart, compact); SxAction("TAP TEMPO", false, vm::onTapTempo, compact) } } } } }
@Composable private fun SxStartStop(playing: Boolean, onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Column(modifier.fillMaxHeight()) {
        Text("TRANSPORT", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        Surface(
            color = if (playing) Color(0xFF155B3C) else Color(0xFF242A31),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(onClick = onClick)
                .border(2.dp, if (playing) SxGreen else Color(0xFF4A5560), RoundedCornerShape(4.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (playing) "STOP" else "START / STOP",
                    color = Color.White,
                    fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun SxAutoFill(enabled: Boolean, onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Column(modifier.fillMaxHeight()) {
        Text("AUTO FILL", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        Surface(
            color = if (enabled) Color(0xFF155B3C) else Color(0xFF242A31),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(onClick = onClick)
                .border(1.dp, if (enabled) SxGreen else Color(0xFF3A424C), RoundedCornerShape(3.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (enabled) "ON" else "OFF", color = Color.White,
                        fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
                    Text("Main A–D", color = if (enabled) Color(0xFF9BE7C2) else SxDim,
                        fontSize = if (compact) 6.sp else 7.sp)
                }
            }
        }
    }
}

@Composable private fun SxSectionGroup(title: String, labels: List<String>, active: String, onClick: (String) -> Unit, modifier: Modifier, compact: Boolean) { Column(modifier.fillMaxHeight()) { Text(title, color = SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)); Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { labels.forEach { label -> val selected = active.equals(label, true); Surface(color = if (selected) SxOrange else Color(0xFF242A31), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().clickable { onClick(label) }.border(1.dp, if (selected) Color(0xFFFFB25A) else Color(0xFF3A424C), RoundedCornerShape(3.dp))) { Box(contentAlignment = Alignment.Center) { Text(label.substringAfterLast(" "), color = Color.White, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold) } } } } } }
@Composable private fun SxAction(label: String, active: Boolean, onClick: () -> Unit, compact: Boolean) { Surface(color = if (active) Color(0xFF155B3C) else Color(0xFF20262C), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().height(if (compact) 30.dp else 35.dp).clickable(onClick = onClick).border(1.dp, if (active) SxGreen else Color(0xFF39414A), RoundedCornerShape(3.dp))) { Box(contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = if (compact) 7.sp else 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }

@Composable private fun SxRegistration(state: MainUiState, vm: MainViewModel, height: Dp, compact: Boolean) {
    val chordNames = listOf("C", "Dm", "Em", "F", "G", "Am", "Bdim", "C7")
    Surface(color = Color(0xFF111419), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF303740), RoundedCornerShape(5.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CHORD TEST", color = Color(0xFF55A9E6), fontSize = if (compact) 7.sp else 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp), maxLines = 1)
            (1..8).forEach { slot ->
                val selected = slot - 1 == state.activeRegSlot
                Surface(color = if (selected) SxBlue else Color(0xFF20252B), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp).clickable { vm.onRegSlotTap(slot - 1) }) {
                    Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(slot.toString(), color = Color.White, fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Bold); Text(chordNames[slot - 1], color = if (selected) Color.White else SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold) } }
                }
            }
            Spacer(Modifier.width(4.dp)); Text("TEST", color = SxOrangeBright, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable private fun SxMidiBar(state: MainUiState, vm: MainViewModel, height: Dp, compact: Boolean) { Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) { Text("YamahaArranger v0.1.0   |   MIDI: ${state.midiStatus}", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); OutlinedButton(onClick = vm::refreshMidiConnection, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("CONNECT", fontSize = if (compact) 6.sp else 7.sp) }; Spacer(Modifier.width(4.dp)); Button(onClick = vm::toggleMidiOut, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.midiOutEnabled) SxBlue else SxPanel2)) { Text("MIDI OUT", fontSize = if (compact) 6.sp else 7.sp) } } }
@Composable private fun SmallKey(text: String, onClick: () -> Unit, compact: Boolean = false) { Button(onClick = onClick, modifier = Modifier.size(if (compact) 23.dp else 25.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39434B)), shape = RoundedCornerShape(2.dp)) { Text(text, fontSize = if (compact) 10.sp else 12.sp) } }

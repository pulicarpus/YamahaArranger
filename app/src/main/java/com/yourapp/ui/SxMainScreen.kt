package com.yourapp.yamahaarranger.ui

import com.yourapp.yamahaarranger.audio.KeyboardPart
import com.yourapp.yamahaarranger.audio.KeyboardVoiceBrowserState
import com.yourapp.yamahaarranger.audio.SoundFontPresetCatalog
import com.yourapp.yamahaarranger.audio.AudioEngineManager

import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
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
private val SxMixerBlue = Color(0xFF58B8FF)
private val SxMixerMaster = Color(0xFFFFC857)
private val SxGreen = Color(0xFF27D887)
private val SxDim = Color(0xFF9AA3AD)

@Composable
fun SxMainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onOpenInspector: () -> Unit = {},
    onMixerVisibilityChanged: (Boolean) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var voiceSelectionError by remember { mutableStateOf<String?>(null) }
    var releaseProbeBusy by remember { mutableStateOf(false) }
    var releaseWav by remember { mutableStateOf<ByteArray?>(null) }
    var releaseWavPart by remember { mutableStateOf<KeyboardPart?>(null) }
    val stylePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onStyleFilePicked) }
    val soundFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::onSoundFontFilePicked) }
    val context = LocalContext.current
    val saveReleaseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) releaseWav?.let { wav ->
            runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(wav) } }
                .onSuccess { Toast.makeText(context, "Release PCM WAV saved: 0, 64, 127", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, "WAV save failed", Toast.LENGTH_SHORT).show() }
        }
    }
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
    var navigation by remember { mutableStateOf(LiveNavigation()) }
    val showMixer = navigation.page == LivePage.MIXER
    var showSf2Manager by remember { mutableStateOf(false) }
    var rightVoicePickerLayer by remember { mutableStateOf<Int?>(null) }
    var voiceBrowser by remember { mutableStateOf(KeyboardVoiceBrowserState()) }
    val selectedVoiceCategory = voiceBrowser.category
    var lcdPage by remember { mutableStateOf("HOME") }
    val selectedVoiceLayer = voiceBrowser.part.channel
    var selectedStyleUri by remember { mutableStateOf<Uri?>(null) }
    var selectedStyleFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDataParameter by remember { mutableStateOf("TEMPO") }

    androidx.activity.compose.BackHandler(enabled = navigation.page != LivePage.HOME) { navigation = navigation.back() }
    LaunchedEffect(navigation.page) { onMixerVisibilityChanged(navigation.page != LivePage.HOME) }

    if (navigation.page == LivePage.HOME) BoxWithConstraints(Modifier.fillMaxSize().background(SxBlack)) {
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
            SxNavBar(
                navH,
                onStyle = { selectedStyleUri = null; selectedStyleFolderUri = null; viewModel.refreshStyleList(); lcdPage = "STYLE" },
                onVoice = {
                    voiceBrowser = voiceBrowser.selectCategory(null)
                    rightVoicePickerLayer = null
                    lcdPage = "VOICE"
                },
                onMixer = { navigation = navigation.mixer() },
                onLive = { navigation = navigation.live() },
                onUtility = { viewModel.logKeyboardVoiceSnapshot(); showUtilityLog = true },
                onInspector = onOpenInspector
            )
            Spacer(Modifier.height(gap))
            // Arranger-style hardware panel: the center is a dedicated LCD,
            // visually separated from the physical-style controls on both sides.
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
            ) {
                SxSideMenu(
                    Modifier.weight(.18f).fillMaxHeight(),
                    compact = compact,
                    selectedParameter = selectedDataParameter,
                    parameterValue = when (selectedDataParameter) {
                        "TEMPO" -> state.tempoBpm.toString()
                        "TRANSPOSE" -> if (state.transpose >= 0) "+" + state.transpose else state.transpose.toString()
                        "RELEASE" -> state.releaseTime.toString()
                        else -> "—"
                    },
                    onStep = { delta ->
                        when (selectedDataParameter) {
                            "TEMPO" -> if (delta > 0) viewModel.onTempoUp() else viewModel.onTempoDown()
                            "TRANSPOSE" -> if (delta > 0) viewModel.onTransposeUp() else viewModel.onTransposeDown()
                            "RELEASE" -> if (delta > 0) viewModel.onReleaseUp() else viewModel.onReleaseDown()
                        }
                    },
                    onPickStyle = { stylePicker.launch(arrayOf("audio/*", "application/octet-stream", "*/*")) }
                )

                SxLcdFrame(
                    modifier = Modifier.weight(.64f).fillMaxHeight(),
                    compact = compact
                ) {
                    SxCenterDisplay(
                        state,
                        viewModel,
                        compact,
                        onPickStyle = { selectedStyleUri = null; selectedStyleFolderUri = null; viewModel.refreshStyleList(); lcdPage = "STYLE" },
                        onPickRightVoice = {
                            voiceBrowser = voiceBrowser.selectPart(KeyboardPart.fromChannel(it))
                            rightVoicePickerLayer = null
                            lcdPage = "VOICE"
                        },
                        lcdPage = lcdPage,
                        selectedDataParameter = selectedDataParameter,
                        onDataParameterSelect = { selectedDataParameter = it },
                        styleFolders = state.styleFolders,
                        styleFiles = state.styleFiles,
                        selectedStyleUri = selectedStyleUri,
                        selectedStyleFolderUri = selectedStyleFolderUri,
                        onStyleFolderSelect = { folder -> selectedStyleFolderUri = folder },
                        onStyleSelect = { uri -> selectedStyleUri = uri; viewModel.onStyleFilePicked(uri); selectedStyleUri = null; lcdPage = "HOME" },
                        onStyleLoad = { uri -> viewModel.onStyleFilePicked(uri); selectedStyleUri = null; lcdPage = "HOME" },
                        voicePresets = state.sf2Presets,
                        voiceCategory = selectedVoiceCategory,
                        selectedVoiceLayer = selectedVoiceLayer,
                        onVoiceSelect = { part, preset ->
                            viewModel.setKeyboardVoice(part, preset) { ok ->
                                if (ok) {
                                    if (voiceBrowser.part == part) { lcdPage = "HOME"; voiceBrowser = voiceBrowser.selectCategory(null) }
                                } else voiceSelectionError = "${preset.soundFontName} • ${preset.bank}/${preset.program} could not be selected. Refresh the SF2 list and try again."
                            }
                        },
                        onVoiceCategory = { category ->
                            voiceBrowser = voiceBrowser.selectCategory(category)
                            lcdPage = "VOICE"
                        },
                        onBackHome = {
                            lcdPage = "HOME"
                            voiceBrowser = voiceBrowser.selectCategory(null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                SxRightPanel(
                    state,
                    viewModel,
                    Modifier.weight(.18f).fillMaxHeight(),
                    compact,
                    lcdPage = lcdPage,
                    styleFolders = state.styleFolders,
                    selectedStyleFolderUri = selectedStyleFolderUri,
                    onPickStyleFolder = { folder -> selectedStyleFolderUri = folder; lcdPage = "STYLE" },
                    onPickVoice = { category ->
                        voiceBrowser = voiceBrowser.selectCategory(category)
                        rightVoicePickerLayer = null
                        lcdPage = "VOICE"
                    }
                )
            }
            Spacer(Modifier.height(gap)); SxStyleControls(state, viewModel, controlH, compact)
            Spacer(Modifier.height(gap)); SxRegistration(state, viewModel, regH, compact)
            Spacer(Modifier.height(gap)); SxMidiBar(state, viewModel, footerH, compact)
        }
    }

    LaunchedEffect(showMixer, state.soundFontName) {
        if (showMixer && state.soundFontName != "None" && state.sf2Presets.isEmpty()) {
            DebugLog.add("🎛 Mixer opened → loading MELODY + DRUM SF2 preset list")
            viewModel.refreshSoundFontList()
        }
    }

    if (navigation.page == LivePage.LIVE) {
        LiveControlScreen(state, viewModel,
            onArranger = { navigation = navigation.home() },
            onMixer = { navigation = navigation.mixer() })
    }
    if (showMixer) {
        SxMixerPage(
            state = state,
            vm = viewModel,
            voices = state.voiceAssignments,
            presets = state.sf2Presets,
            onDismiss = { navigation = navigation.back() },
            returnLabel = if (navigation.mixerReturn == LivePage.LIVE) "LIVE" else "ARRANGER",
            onMixer = viewModel::setStyleChannelMixer,
            onMute = viewModel::toggleStyleChannelMute,
        )
    }

    // Large Yamaha SF2 preset enumeration is deferred until the LCD VOICE page is opened.
    LaunchedEffect(lcdPage, state.soundFontName) {
        if (lcdPage == "VOICE" && state.soundFontName != "None" && state.sf2Presets.isEmpty()) {
            DebugLog.add("🎹 LCD VOICE opened → loading SF2 preset list")
            viewModel.refreshSoundFontList()
        }
    }

    voiceSelectionError?.let { message ->
        AlertDialog(onDismissRequest = { voiceSelectionError = null },
            title = { Text("Voice unavailable") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { voiceSelectionError = null }) { Text("CLOSE") } })
    }
    rightVoicePickerLayer?.let { layer ->
        val slot = state.rightVoices.getOrNull(layer)
        if (slot != null) {
            SxKeyboardVoiceDialog(
                slot = slot,
                presets = state.sf2Presets,
                category = selectedVoiceCategory,
                onDismiss = {
                    rightVoicePickerLayer = null
                    voiceBrowser = voiceBrowser.selectCategory(null)
                },
                onSelect = { preset ->
                    viewModel.setKeyboardVoice(KeyboardPart.fromChannel(layer), preset) { ok ->
                        if (ok) { rightVoicePickerLayer = null; voiceBrowser = voiceBrowser.selectCategory(null) }
                        else voiceSelectionError = "${preset.soundFontName} • ${preset.bank}/${preset.program} could not be selected. Refresh the SF2 list and try again."
                    }
                },
                onToggle = { viewModel.toggleRightVoice(layer) }
            )
        }
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
        SxEngineLogDialog(onDismiss = { showUtilityLog = false },
            onSaveAllLog = { saveLogLauncher.launch("YamahaArranger_AllLog_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt") },
            probePart = voiceBrowser.part,
            probeEnabled = !state.isPlaying && !releaseProbeBusy && voiceBrowser.part != KeyboardPart.LEFT,
            probeBusy = releaseProbeBusy,
            onProbe = {
                releaseProbeBusy = true
                releaseWav = null
                val part = voiceBrowser.part
                viewModel.runKeyboardReleaseProbe(part) { wav ->
                    releaseWav = wav.takeIf { it.isNotEmpty() }; releaseWavPart = part; releaseProbeBusy = false
                }
            },
            hasWav = releaseWav != null,
            onSaveWav = { saveReleaseLauncher.launch("YamahaArranger_Release_${releaseWavPart?.name}_0_64_127.wav") })
    }
}

@Composable
private fun SxMixerPage(state: MainUiState, vm: MainViewModel, voices: List<VoiceSlot>, presets: List<AudioEngineManager.SfPreset>, onDismiss: () -> Unit, returnLabel: String = "ARRANGER", onMixer: (Int, Int, Int, Int, Int, Int) -> Unit, onMute: (Int) -> Unit) {
    var voicePickerSlot by remember { mutableStateOf<VoiceSlot?>(null) }
    var keyboardPicker by remember { mutableStateOf<Int?>(null) }
    var detailChannel by remember { mutableStateOf<Int?>(null) }
    var busDetail by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val focus by vm.mixerConsole.collectAsState()
    val keyboardDetails by vm.keyboardMixerDetails.collectAsState()
    val keyboardVolumes = listOf(state.right1Volume, state.right2Volume, state.right3Volume, state.leftVolume)
    val keyboardVolumeCallbacks: List<(Int) -> Unit> = listOf(vm::onRight1VolumeChange, vm::onRight2VolumeChange, vm::onRight3VolumeChange, vm::onLeftVolumeChange)
    LaunchedEffect(voices.map { it.channel }) { vm.reconcileMixerChannels() }
    // CPV: engine binding per style channel, refreshed when the section or a voice changes.
    var bindings by remember { mutableStateOf<Map<Int, com.yourapp.yamahaarranger.audio.ChannelBinding>>(emptyMap()) }
    LaunchedEffect(state.activeSection, voices) { bindings = vm.channelBindings() }
    DisposableEffect(vm) { onDispose { vm.clearMixerSolo() } }
    val hostContext = LocalContext.current
    val activity = generateSequence(hostContext) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val insets = window?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it.decorView) }
        val visibleTypes = listOf(androidx.core.view.WindowInsetsCompat.Type.statusBars(),
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()).fold(0) { mask, type ->
            if (insets?.isVisible(type) != false) mask or type else mask
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { if (visibleTypes != 0) controller?.show(visibleTypes) }
    }
    Surface(color = SxBlack, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stripWidth = compactMixerStripWidth(maxWidth.value).dp
            Column(Modifier.fillMaxSize().padding(6.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MIXER", color = SxGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${voices.size} STYLE CHANNELS  •  SWIPE →", color = SxDim, fontSize = 10.sp)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(returnLabel, color = SxGreen) }
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Compact strips retain full-width 48dp touch targets.
                    // Six fixed strips never participate in the style scroll container.
                    Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.rightVoices.forEach { slot ->
                            val ch = slot.layer
                            SxConsoleStrip(slot.label, slot.displayName(), keyboardVolumes[ch], focus.muted(ch), ch in focus.solo,
                                keyboardDetails[ch].pan, keyboardVolumeCallbacks[ch],
                                { vm.toggleMixerKeyboardMute(ch) }, { vm.toggleMixerSolo(ch) }, { detailChannel = ch },
                                accent = SxMixerBlue, stripWidth = stripWidth)
                        }
                        SxConsoleBus("STYLE", state.styleVolume, vm::onStyleVolumeChange, SxGreen, stripWidth) { busDetail = "STYLE" }
                        SxConsoleBus("MASTER", state.masterVolume, vm::onMasterVolumeChange, SxMixerMaster, stripWidth) { busDetail = "MASTER" }
                    }
                    Box(Modifier.width(2.dp).fillMaxHeight().background(SxMixerBlue.copy(alpha = 0.6f)))
                    if (voices.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text("No used style channels", color = SxDim, fontSize = 11.sp)
                        }
                    } else Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        voices.forEach { slot ->
                            val badge = when {
                                slot.overrideStatus == "ACTIVE" -> "OVR"
                                slot.overrideStatus == "STALE" -> "STALE"
                                slot.overrideStatus == "UNVERIFIED" -> "OVR?"
                                bindings[slot.channel]?.status == "UNRESOLVED" -> "UNRES"
                                else -> null
                            }
                            SxConsoleStrip(slot.label.substringAfter(") ", slot.label), slot.overridePin?.presetName ?: slot.sf2Name ?: slot.displayName(),
                                slot.styleVolume, focus.muted(slot.channel, slot.styleMuted), slot.channel in focus.solo,
                                slot.stylePan, { vm.setStyleChannelVolume(slot.channel, it) },
                                { onMute(slot.channel) }, { vm.toggleMixerSolo(slot.channel) }, { detailChannel = slot.channel }, stripWidth = stripWidth,
                                badge = badge)
                        }
                    }
                }
            }
        }
    }
    busDetail?.let { bus ->
        AlertDialog(onDismissRequest = { busDetail = null }, title = { Text("$bus VOLUME") },
            text = { Text(if (bus == "MASTER")
                "100 = unity; 0 = silence. Android uses the existing master gain. MIDI OUT trims CC11 only on RIGHT1/2/3, LEFT and used style channels, preserving their balance. Values above 100 stay at unity."
                else "STYLE trims accompaniment expression while preserving each channel's own volume and expression, including style controller changes. Keyboard parts are unaffected.") },
            confirmButton = { TextButton(onClick = { busDetail = null }) { Text("CLOSE") } })
    }
    detailChannel?.let { ch ->
        val slot = voices.firstOrNull { it.channel == ch }
        val keyboard = ch in 0..3
        val d = keyboardDetails.getOrNull(ch)
        AlertDialog(onDismissRequest = { detailChannel = null }, title = { Text(if (keyboard) KeyboardPart.fromChannel(ch).label else slot?.label ?: "CHANNEL $ch") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (keyboard && d != null) {
                        TextButton(onClick = { detailChannel = null; keyboardPicker = ch }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("VOICE: ${state.rightVoices[ch].displayName()}") }
                        SxMixRow("PAN", d.pan, { vm.setKeyboardMixerDetail(ch, d.copy(pan = it)) })
                        // Keyboard volume already uses CC11: EXP edits that same binding.
                        SxMixRow("EXP", keyboardVolumes[ch], keyboardVolumeCallbacks[ch])
                        SxMixRow("REV", d.reverb, { vm.setKeyboardMixerDetail(ch, d.copy(reverb = it)) })
                        SxMixRow("CHO", d.chorus, { vm.setKeyboardMixerDetail(ch, d.copy(chorus = it)) })
                    } else if (slot != null) {
                        TextButton(onClick = { detailChannel = null; voicePickerSlot = slot }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("VOICE: " + (slot.overridePin?.let { "${it.presetName} [${slot.overrideStatus}]" } ?: slot.displayName()))
                        }
                        val binding = bindings[ch]
                        Text("ENGINE: " + (binding?.let { "${it.status} ${it.source} ${it.summary()}" } ?: "-"),
                            color = if (binding?.resolved == true) SxGreen else SxDim, fontSize = 10.sp)
                        SxMixRow("PAN", slot.stylePan, { onMixer(ch, slot.styleVolume, it, slot.styleExpression, slot.styleReverb, slot.styleChorus) })
                        SxMixRow("EXP", slot.styleExpression, { onMixer(ch, slot.styleVolume, slot.stylePan, it, slot.styleReverb, slot.styleChorus) })
                        SxMixRow("REV", slot.styleReverb, { onMixer(ch, slot.styleVolume, slot.stylePan, slot.styleExpression, it, slot.styleChorus) })
                        SxMixRow("CHO", slot.styleChorus, { onMixer(ch, slot.styleVolume, slot.stylePan, slot.styleExpression, slot.styleReverb, it) })
                    }
                }
            }, confirmButton = { TextButton(onClick = { detailChannel = null }) { Text("CLOSE") } })
    }
    voicePickerSlot?.let { pickerSlot ->
        val slot = voices.firstOrNull { it.channel == pickerSlot.channel } ?: pickerSlot
        StyleVoicePickerDialog(slot = slot, presets = presets, onRefresh = vm::refreshLoadedPresets,
            onDismiss = { voicePickerSlot = null },
            onSelect = { preset ->
                vm.pickStyleChannelVoice(slot.channel, preset) { ok ->
                    bindings = vm.channelBindings()
                    if (ok) voicePickerSlot = null
                    else selectionError = "Preset not verified: ${preset.soundFontName} ${preset.bank}:${preset.program}. Saved as override; channel reports UNVERIFIED."
                }
            },
            onReset = { vm.resetStyleChannelVoice(slot.channel); voicePickerSlot = null })
    }
    keyboardPicker?.let { ch ->
        state.rightVoices.getOrNull(ch)?.let { slot ->
            SxKeyboardVoiceDialog(slot, presets, onDismiss = { keyboardPicker = null }, onSelect = { preset ->
                vm.setKeyboardVoice(KeyboardPart.fromChannel(ch), preset) { ok ->
                    if (ok) keyboardPicker = null else selectionError = "Preset unavailable: ${preset.soundFontName} ${preset.bank}/${preset.program}"
                }
            }, onToggle = { vm.toggleRightVoice(ch) })
        }
    }
    selectionError?.let { message ->
        AlertDialog(onDismissRequest = { selectionError = null }, title = { Text("Voice unavailable") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { selectionError = null }) { Text("CLOSE") } })
    }
}

@Composable
private fun SxConsoleStrip(name: String, voice: String, volume: Int, muted: Boolean, solo: Boolean, pan: Int,
    onVolume: (Int) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, onDetail: () -> Unit,
    accent: Color = SxGreen, stripWidth: Dp = 56.dp, badge: String? = null) {
    Surface(color = SxPanel, shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.24f)), modifier = Modifier.width(stripWidth).fillMaxHeight()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onDetail, modifier = Modifier.fillMaxWidth().height(48.dp)
                .semantics { contentDescription = "$name details and voice selection: $voice, pan $pan" }, contentPadding = PaddingValues(0.dp)) {
                Text("DETAIL", color = accent, fontSize = 10.sp)
            }
            SxConsoleSwitch("MUTE", muted, onMute, Modifier.fillMaxWidth().semantics { contentDescription = "$name mute" }, accent)
            SxConsoleSwitch("SOLO", solo, onSolo, Modifier.fillMaxWidth().semantics { contentDescription = "$name solo" }, accent)
            Text(volume.toString(), color = if (muted) SxDim else accent, fontSize = 13.sp)
            SxVerticalFader(name, volume, onVolume, Modifier.weight(1f).fillMaxWidth(), accent)
            if (badge != null) Text(badge, color = if (badge == "OVR") SxGreen else Color(0xFFFFB347), fontSize = 9.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(compactMixerLabel(name), color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                maxLines = 1, modifier = Modifier.height(32.dp).padding(top = 4.dp))
        }
    }
}

@Composable
private fun SxConsoleSwitch(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, accent: Color) {
    TextButton(onClick = onClick, modifier = modifier.height(48.dp).background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent), contentPadding = PaddingValues(0.dp)) {
        Text(label, color = if (selected) accent else SxDim, fontSize = 10.sp)
    }
}

@Composable
private fun SxConsoleBus(name: String, volume: Int, onVolume: (Int) -> Unit, accent: Color, stripWidth: Dp, onDetail: () -> Unit) {
    Surface(color = SxPanel, shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.24f)), modifier = Modifier.width(stripWidth).fillMaxHeight()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onDetail, modifier = Modifier.fillMaxWidth().height(48.dp)
                .semantics { contentDescription = "$name volume details" }, contentPadding = PaddingValues(0.dp)) {
                Text("DETAIL", color = accent, fontSize = 10.sp)
            }
            Text(volume.toString(), color = accent, fontSize = 13.sp)
            SxVerticalFader(name, volume, onVolume, Modifier.weight(1f).fillMaxWidth(), accent)
            Text(compactMixerLabel(name), color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, modifier = Modifier.height(32.dp).padding(top = 4.dp))
        }
    }
}

@Composable
private fun SxVerticalFader(name: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier, accent: Color = SxGreen) {
    val update by rememberUpdatedState(onChange)
    androidx.compose.foundation.Canvas(modifier
        .semantics {
            contentDescription = "$name volume"
            progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.toFloat(), 0f..127f, 126)
            setProgress { update(it.roundToInt().coerceIn(0, 127)); true }
        }
        .pointerInput(Unit) {
            detectTapGestures { p -> update(com.yourapp.yamahaarranger.audio.mixerFaderValue(p.y - 16.dp.toPx(), size.height - 32.dp.toPx())) }
        }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { p -> update(com.yourapp.yamahaarranger.audio.mixerFaderValue(p.y - 16.dp.toPx(), size.height - 32.dp.toPx())) }) { change, _ ->
                change.consume()
                update(com.yourapp.yamahaarranger.audio.mixerFaderValue(change.position.y - 16.dp.toPx(), size.height - 32.dp.toPx()))
            }
        }) {
        val pad = 16.dp.toPx()
        val bottom = (size.height - pad).coerceAtLeast(pad)
        val x = size.width / 2f
        drawLine(SxBlack, androidx.compose.ui.geometry.Offset(x, pad), androidx.compose.ui.geometry.Offset(x, bottom), 4.dp.toPx())
        for (tick in 0..8) {
            val y = pad + (bottom - pad) * tick / 8f
            drawLine(SxDim.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(x - 22.dp.toPx(), y), androidx.compose.ui.geometry.Offset(x - 14.dp.toPx(), y), 1.dp.toPx())
        }
        val y = bottom - (bottom - pad) * value.coerceIn(0, 127) / 127f
        drawRoundRect(accent, androidx.compose.ui.geometry.Offset(x - 14.dp.toPx(), y - 8.dp.toPx()),
            androidx.compose.ui.geometry.Size(28.dp.toPx(), 16.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawLine(SxBlack, androidx.compose.ui.geometry.Offset(x - 10.dp.toPx(), y), androidx.compose.ui.geometry.Offset(x + 10.dp.toPx(), y), 2.dp.toPx())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
// Mixer: volume is compact by default; long-press opens channel details.
private fun SxMixRow(label: String, value: Int, onChange: (Int) -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().combinedClickable(
        onClick = { },
        onLongClick = onLongPress,
        onLongClickLabel = if (onLongPress != null) "Open channel details" else null
    )) {
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
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No SF2 files found. Use IMPORT to add one.", color = SxDim, fontSize = 10.sp) }
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
private fun SxNavBar(
    height: Dp,
    onStyle: () -> Unit,
    onVoice: () -> Unit,
    onMixer: () -> Unit,
    onLive: () -> Unit,
    onUtility: () -> Unit,
    onInspector: () -> Unit
) {
    val tabs = listOf("HOME", "STYLE", "VOICE", "SONG", "MULTI PAD", "REGIST", "MIXER", "LIVE", "UTILITY")
    Row(
        Modifier.fillMaxWidth().height(height).background(Color(0xFF0D1115), RoundedCornerShape(5.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            val modifier = Modifier.weight(1f).fillMaxHeight().then(
                when (label) {
                    "STYLE" -> Modifier.clickable(onClick = onStyle)
                    "VOICE" -> Modifier.clickable(onClick = onVoice)
                    "MIXER" -> Modifier.clickable(onClick = onMixer)
                    "LIVE" -> Modifier.clickable(onClick = onLive)
                    "UTILITY" -> Modifier.clickable(onClick = onUtility)
                    else -> Modifier
                }
            )
            Surface(
                color = if (i == 0) Color(0xFF073E82) else Color(0xFF1C2228),
                shape = RoundedCornerShape(3.dp),
                modifier = modifier
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Surface(
            color = Color(0xFF5A2A92),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.width(48.dp).fillMaxHeight().clickable(onClick = onInspector)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("🔍", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SxEngineLogDialog(
    onDismiss: () -> Unit, onSaveAllLog: () -> Unit, probePart: KeyboardPart,
    probeEnabled: Boolean, probeBusy: Boolean, onProbe: () -> Unit,
    hasWav: Boolean, onSaveWav: () -> Unit
) {
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
        "AUDIO" -> logs.filter { it.contains("🔊 AUDIO") || it.contains("KEYBOARD VOICE") || it.contains("RELEASE PCM") ||
            it.contains("RELEASE LIVE") || it.contains("RELEASE SF2") || it.contains("RELEASE DIAGNOSIS") || it.contains("RELEASE CONTEXT") }
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
                Text("Stop style. PCM test uses the selected part at C4, velocity 100; WAV order 0 / 64 / 127.", color = SxDim, fontSize = 9.sp)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(enabled = probeEnabled, onClick = onProbe) {
                        Text(if (probeBusy) "MEASURING PCM…" else "RELEASE PCM • ${probePart.label}", fontSize = 9.sp)
                    }
                    TextButton(enabled = hasWav && !probeBusy, onClick = onSaveWav) { Text("SAVE WAV", fontSize = 9.sp) }
                }
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
private fun SxSideMenu(
    modifier: Modifier,
    compact: Boolean,
    selectedParameter: String,
    parameterValue: String,
    onStep: (Int) -> Unit,
    onPickStyle: () -> Unit
) {
    Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) {
        Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DATA CONTROL", color = SxBlue, fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Text("TAP VALUE • DRAG", color = SxDim, fontSize = if (compact) 5.sp else 6.sp, maxLines = 1)
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            val dialSize = if (compact) 104.dp else 132.dp
            Surface(
                color = Color(0xFF080C10),
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(dialSize)
                    .border(if (compact) 3.dp else 4.dp, SxBlue, androidx.compose.foundation.shape.CircleShape)
                    .draggable(
                        state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                            if (kotlin.math.abs(delta) >= 1.5f) {
                                onStep(if (delta < 0f) 1 else -1)
                            }
                        },
                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedParameter, color = SxDim, fontSize = if (compact) 7.sp else 8.sp, fontWeight = FontWeight.Bold)
                        Text(parameterValue, color = Color.White, fontSize = if (compact) 24.sp else 30.sp, fontWeight = FontWeight.Bold)
                        Text("DATA", color = SxBlue, fontSize = if (compact) 6.sp else 7.sp, letterSpacing = 1.sp)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onStep(-1) }, modifier = Modifier.weight(1f).height(if (compact) 31.dp else 36.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252D35))) { Text("−", fontSize = if (compact) 15.sp else 18.sp) }
                Button(onClick = { onStep(1) }, modifier = Modifier.weight(1f).height(if (compact) 31.dp else 36.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252D35))) { Text("+", fontSize = if (compact) 15.sp else 18.sp) }
            }
            Spacer(Modifier.height(if (compact) 5.dp else 7.dp))
            Text("SELECTED", color = SxDim, fontSize = 5.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().height(if (compact) 35.dp else 42.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("TEMPO", "TRANSPOSE", "RELEASE").forEach { item ->
                    val active = selectedParameter == item
                    Surface(color = if (active) SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxHeight().weight(1f).border(1.dp, if (active) SxGreen else Color(0xFF35404A), RoundedCornerShape(3.dp))) {
                        Box(contentAlignment = Alignment.Center) { Text(item, color = Color.White, fontSize = if (compact) 5.sp else 6.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp).clickable(onClick = onPickStyle).border(1.dp, Color(0xFF343C45), RoundedCornerShape(3.dp))) {
                Box(contentAlignment = Alignment.Center) { Text("STYLE SELECT", color = Color.White, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SxLcdFrame(
    modifier: Modifier,
    compact: Boolean,
    content: @Composable () -> Unit
) {
    // Thick dark bezel + inner highlight deliberately make this look like a
    // physical arranger LCD rather than a normal Android card.
    Surface(
        color = Color(0xFF050607),
        shape = RoundedCornerShape(if (compact) 6.dp else 8.dp),
        modifier = modifier.border(
            width = if (compact) 2.dp else 3.dp,
            color = Color(0xFF2B3035),
            shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
        )
    ) {
        Surface(
            color = Color(0xFF0B0E11),
            shape = RoundedCornerShape(if (compact) 4.dp else 6.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 3.dp else 5.dp)
                .border(
                    1.dp,
                    Color(0xFF444A50),
                    RoundedCornerShape(if (compact) 4.dp else 6.dp)
                )
        ) {
            content()
        }
    }
}

@Composable
private fun SxCenterDisplay(
    state: MainUiState,
    vm: MainViewModel,
    compact: Boolean,
    onPickStyle: () -> Unit,
    onPickRightVoice: (Int) -> Unit,
    lcdPage: String,
    selectedDataParameter: String,
    onDataParameterSelect: (String) -> Unit,
    styleFolders: List<StyleFolder>,
    styleFiles: List<Pair<Uri, String>>,
    selectedStyleUri: Uri?,
    selectedStyleFolderUri: Uri?,
    onStyleFolderSelect: (Uri) -> Unit,
    onStyleSelect: (Uri) -> Unit,
    onStyleLoad: (Uri) -> Unit,
    voicePresets: List<AudioEngineManager.SfPreset>,
    voiceCategory: String?,
    selectedVoiceLayer: Int,
    onVoiceSelect: (KeyboardPart, AudioEngineManager.SfPreset) -> Unit,
    onVoiceCategory: (String) -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier
) {
    Surface(color = Color(0xFF101419), shape = RoundedCornerShape(6.dp), modifier = modifier.border(2.dp, Color(0xFF39424C), RoundedCornerShape(6.dp))) {
        if (lcdPage == "STYLE") {
            SxStyleLcdPage(
                state = state,
                styleFolders = styleFolders,
                styleFiles = styleFiles,
                selectedStyleUri = selectedStyleUri,
                selectedStyleFolderUri = selectedStyleFolderUri,
                compact = compact,
                onFolderSelect = onStyleFolderSelect,
                onSelect = onStyleSelect,
                onLoad = onStyleLoad,
                onBrowse = onPickStyle,
                onBack = onBackHome
            )
        } else if (lcdPage == "VOICE") {
            SxVoiceLcdPage(
                state = state,
                presets = voicePresets,
                category = voiceCategory,
                selectedLayer = selectedVoiceLayer,
                compact = compact,
                onCategory = onVoiceCategory,
                onSelect = onVoiceSelect,
                onBack = onBackHome
            )
        } else {
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
                    val rv = state.rightVoices
                    rv.getOrNull(0)?.let { v -> SxVoiceCard("RIGHT 1", v.displayName(), v.enabled, Modifier.weight(1f).clickable { onPickRightVoice(0) }) }
                    rv.getOrNull(1)?.let { v -> SxVoiceCard("RIGHT 2", v.displayName(), v.enabled, Modifier.weight(1f).clickable { onPickRightVoice(1) }) }
                    rv.getOrNull(2)?.let { v -> SxVoiceCard("RIGHT 3", v.displayName(), v.enabled, Modifier.weight(1f).clickable { onPickRightVoice(2) }) }
                    rv.getOrNull(3)?.let { v -> SxVoiceCard("LEFT", v.displayName(), state.leftVoiceEnabled, Modifier.weight(1f).clickable { onPickRightVoice(3) }) }
                }
                Row(
                    Modifier.fillMaxWidth().height(if (compact) 48.dp else 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SxValue("CHORD", state.detectedChordLabel.ifBlank { "—" }, Modifier.weight(1f), null, null, compact)
                    SxValue("TEMPO", state.tempoBpm.toString(), Modifier.weight(.8f), vm::onTempoDown, vm::onTempoUp, compact, selectedDataParameter == "TEMPO") { onDataParameterSelect("TEMPO") }
                    SxValue("TRANSPOSE", if (state.transpose >= 0) "+" + state.transpose else state.transpose.toString(), Modifier.weight(.9f), vm::onTransposeDown, vm::onTransposeUp, compact, selectedDataParameter == "TRANSPOSE") { onDataParameterSelect("TRANSPOSE") }
                    SxValue("RELEASE", state.releaseTime.toString(), Modifier.weight(.8f), vm::onReleaseDown, vm::onReleaseUp, compact, selectedDataParameter == "RELEASE") { onDataParameterSelect("RELEASE") }
                    TextButton(onClick = vm::toggleRelease, modifier = Modifier.weight(.7f).fillMaxHeight(), contentPadding = PaddingValues(1.dp)) {
                        Text(if (state.releaseEnabled) "RELEASE\nON" else "RELEASE\nOFF · 64", color = if (state.releaseEnabled) SxGreen else SxDim, fontSize = 8.sp)
                    }
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
}

@Composable
private fun SxStyleLcdPage(
    state: MainUiState,
    styleFolders: List<StyleFolder>,
    styleFiles: List<Pair<Uri, String>>,
    selectedStyleUri: Uri?,
    selectedStyleFolderUri: Uri?,
    compact: Boolean,
    onFolderSelect: (Uri) -> Unit,
    onSelect: (Uri) -> Unit,
    onLoad: (Uri) -> Unit,
    onBrowse: () -> Unit,
    onBack: () -> Unit
) {
    val visibleStyleFiles = remember(selectedStyleFolderUri, styleFiles) {
        val folderPath = selectedStyleFolderUri?.path?.let(Uri::decode)?.trimEnd('/')
        if (folderPath == null) emptyList()
        else styleFiles.filter { (uri, _) ->
            val p = uri.path?.let(Uri::decode)?.trimEnd('/') ?: return@filter false
            p.startsWith(folderPath + "/")
        }
    }
    var page by remember(selectedStyleFolderUri) { mutableStateOf(0) }
    val pageSize = 16
    val pageCount = maxOf(1, (visibleStyleFiles.size + pageSize - 1) / pageSize)
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageItems = visibleStyleFiles.drop(safePage * pageSize).take(pageSize)

    Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 7.dp)) {
        Row(Modifier.fillMaxWidth().height(if (compact) 27.dp else 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STYLE", color = SxOrangeBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text(
                styleFolders.firstOrNull { it.uri == selectedStyleFolderUri }?.name ?: "STYLE SELECT",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text("${visibleStyleFiles.size} STYLES", color = SxDim, fontSize = 7.sp)
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("HOME", color = SxGreen, fontSize = 8.sp)
            }
        }

        when {
            selectedStyleFolderUri == null -> Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("SELECT A STYLE FOLDER", color = SxDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            visibleStyleFiles.isEmpty() -> Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("NO STYLE FILES IN THIS FOLDER", color = SxDim, fontSize = 9.sp)
                }
            }
            else -> Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .border(1.dp, Color(0xFF38424C), RoundedCornerShape(4.dp))
            ) {
                Column(
                    Modifier.fillMaxSize().padding(if (compact) 4.dp else 5.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                ) {
                    pageItems.chunked(4).forEachIndexed { rowIndex, rowItems ->
                        Row(
                            Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                        ) {
                            rowItems.forEachIndexed { colIndex, item ->
                                val selected = selectedStyleUri == item.first
                                Surface(
                                    color = if (selected) SxBlue else Color(0xFF20262D),
                                    shape = RoundedCornerShape(3.dp),
                                    modifier = Modifier.weight(1f).fillMaxHeight()
                                        .clickable { onSelect(item.first) }
                                        .border(
                                            1.dp,
                                            if (selected) Color(0xFF58A9FF) else Color(0xFF38434E),
                                            RoundedCornerShape(3.dp)
                                        )
                                ) {
                                    Column(
                                        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            item.second.substringBeforeLast(".").take(22),
                                            color = Color.White,
                                            fontSize = if (compact) 8.sp else 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "STYLE " + (safePage * pageSize + rowIndex * 4 + colIndex + 1),
                                            color = if (selected) Color(0xFFC9E5FF) else SxDim,
                                            fontSize = 5.sp, maxLines = 1
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().height(if (compact) 34.dp else 39.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            (0..5).forEach { index ->
                val enabled = index < pageCount
                Surface(
                    color = if (safePage == index) SxOrange else if (enabled) Color(0xFF303840) else Color(0xFF171B20),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clickable(enabled = enabled) { page = index }
                        .border(
                            1.dp,
                            if (safePage == index) Color(0xFFFFB25A) else Color(0xFF444D56),
                            RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("P${index + 1}", color = if (enabled) Color.White else SxDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SxVoiceLcdPage(
    state: MainUiState,
    presets: List<AudioEngineManager.SfPreset>,
    category: String?,
    selectedLayer: Int,
    compact: Boolean,
    onCategory: (String) -> Unit,
    onSelect: (KeyboardPart, AudioEngineManager.SfPreset) -> Unit,
    onBack: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var fontId by remember { mutableStateOf<Long?>(null) }
    var allCategories by remember(category) { mutableStateOf(category == null || category == "USER") }
    val filtered = SoundFontPresetCatalog.filter(presets, search, fontId)
        .filter { allCategories || if (category == "PERCUSSION") it.isDrum else voiceCategoryMatches(it.program, category) }
    var page by remember(category, selectedLayer, search, fontId, allCategories, presets) { mutableStateOf(0) }
    val pageSize = 8
    val pageCount = maxOf(1, (filtered.size + pageSize - 1) / pageSize)
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageItems = filtered.drop(safePage * pageSize).take(pageSize)

    Column(Modifier.fillMaxSize().padding(if (compact) 5.dp else 7.dp)) {
        Row(Modifier.fillMaxWidth().height(if (compact) 27.dp else 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("VOICE", color = SxBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text(if (selectedLayer == 3) "LEFT" else "RIGHT " + (selectedLayer + 1), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${filtered.size} VOICES", color = SxDim, fontSize = 7.sp)
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("HOME", color = SxGreen, fontSize = 8.sp)
            }
        }

        SxPresetFilters(presets, search, { search = it }, fontId, { fontId = it }, compact)
        if (category != null && category != "USER") {
            TextButton(onClick = { allCategories = !allCategories }, contentPadding = PaddingValues(0.dp)) {
                Text(if (allCategories) "ALL PRESETS" else category, color = SxDim, fontSize = 8.sp)
            }
        }
        if (state.soundFontName == "None") {
            Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("LOAD SF2 FIRST", color = SxOrangeBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else if (presets.isEmpty()) {
            Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("No available SF2 presets", color = SxDim, fontSize = 12.sp)
                }
            }
        } else {
            Surface(
                color = Color(0xFF151A20), shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .border(1.dp, Color(0xFF38424C), RoundedCornerShape(4.dp))
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(if (compact) 4.dp else 5.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                ) {
                    pageItems.chunked(4).forEachIndexed { rowIndex, rowItems ->
                        item(key = "voice-row-$safePage-$rowIndex") {
                            Row(
                                Modifier.fillMaxWidth().height(if (compact) 48.dp else 58.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
                            ) {
                                rowItems.forEach { item ->
                                    val selected = item.program == (state.rightVoices.getOrNull(selectedLayer)?.program ?: -1) &&
                                        item.bank == (state.rightVoices.getOrNull(selectedLayer)?.bank ?: -1) &&
                                        item.soundFontId == state.rightVoices.getOrNull(selectedLayer)?.soundFontId
                                    Surface(
                                        color = if (selected) SxBlue else Color(0xFF20262D),
                                        shape = RoundedCornerShape(3.dp),
                                        modifier = Modifier.weight(1f).fillMaxHeight()
                                            .clickable { onSelect(KeyboardPart.fromChannel(selectedLayer), item) }
                                            .border(
                                                1.dp,
                                                if (selected) Color(0xFF58A9FF) else Color(0xFF38434E),
                                                RoundedCornerShape(3.dp)
                                            )
                                    ) {
                                        Column(
                                            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                item.name.take(24),
                                                color = Color.White,
                                                fontSize = if (compact) 9.sp else 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                item.soundFontName + " • " + item.bank + ":" + item.program,
                                                color = if (selected) Color(0xFFC9E5FF) else SxDim,
                                                fontSize = 6.sp, maxLines = 1
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 39.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = safePage > 0, onClick = { page = safePage - 1 }) { Text("◀") }
            Text("${safePage + 1} / $pageCount", color = SxDim, fontSize = 10.sp)
            TextButton(enabled = safePage + 1 < pageCount, onClick = { page = safePage + 1 }) { Text("▶") }
        }

    }
}

@Composable private fun SxStyleCard(state: MainUiState, onClick: () -> Unit, modifier: Modifier) {
    Surface(color = SxOrange, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().clickable(onClick = onClick).border(1.dp, Color(0xFFFF9B32), RoundedCornerShape(4.dp))) {
        Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Text("STYLE", color = Color(0xFFFFD1A5), fontSize = 7.sp, fontWeight = FontWeight.Bold); Text(state.styleName.substringBeforeLast(".").take(25), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("4/4   ♪ ${state.tempoBpm}", color = Color.White, fontSize = 8.sp); Text(state.activeSection, color = Color(0xFFFFC27A), fontSize = 7.sp) }
            Text("LOAD", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp))
        }
    }
}
@Composable private fun SxMultiPad(modifier: Modifier) { Surface(color = SxBlueDark, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxHeight().border(1.dp, Color(0xFF146CB3), RoundedCornerShape(4.dp))) { Column(Modifier.fillMaxSize().padding(7.dp), verticalArrangement = Arrangement.Center) { Text("MULTI PAD", color = Color(0xFFA8D8FF), fontSize = 7.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("Ready", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("Shaker & Tamb   1/4", color = SxDim, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
@Composable private fun SxVoiceCard(label: String, name: String, active: Boolean, modifier: Modifier) { Surface(color = if (active) Color(0xFF063F85) else Color(0xFF24292F), shape = RoundedCornerShape(3.dp), modifier = modifier.fillMaxHeight().border(1.dp, if (active) Color(0xFF2C91FF) else Color(0xFF3A424B), RoundedCornerShape(3.dp))) { Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.SpaceBetween) { Text(label, color = if (active) Color(0xFFA8D8FF) else SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold); Text(name.ifBlank { "OFF" }.take(18), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (active) "ON" else "OFF", color = if (active) Color(0xFF58D8FF) else SxDim, fontSize = 6.sp) } } }
@Composable private fun SxToggleValue(label: String, value: String, active: Boolean, onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Surface(color = if (active) Color(0xFF155B3C) else SxPanel2, shape = RoundedCornerShape(3.dp),
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick)
            .border(1.dp, if (active) SxGreen else Color(0xFF343B44), RoundedCornerShape(3.dp))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Text(label, color = if (active) Color(0xFF9BE7C2) else SxDim, fontSize = if (compact) 5.sp else 6.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text(value, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun SxValue(
    label: String,
    value: String,
    modifier: Modifier,
    minus: (() -> Unit)?,
    plus: (() -> Unit)?,
    compact: Boolean,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null
) {
    Surface(
        color = if (selected) Color(0xFF263D52) else SxPanel2,
        shape = RoundedCornerShape(3.dp),
        modifier = modifier.fillMaxHeight()
            .clickable(enabled = onSelect != null) { onSelect?.invoke() }
            .border(if (selected) 2.dp else 1.dp, if (selected) SxBlue else Color(0xFF343B44), RoundedCornerShape(3.dp))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, color = if (selected) Color.White else SxDim, fontSize = if (compact) 5.sp else 6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), maxLines = 1)
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (minus != null && plus != null) SmallKey("−", minus, compact)
                Text(value, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (minus != null && plus != null) SmallKey("+", plus, compact)
            }
        }
    }
}

@Composable private fun SxRightPanel(
    state: MainUiState,
    vm: MainViewModel,
    modifier: Modifier,
    compact: Boolean,
    lcdPage: String,
    styleFolders: List<StyleFolder>,
    selectedStyleFolderUri: Uri?,
    onPickStyleFolder: (Uri) -> Unit,
    onPickVoice: (String) -> Unit
) {
    Surface(color = SxPanel, shape = RoundedCornerShape(5.dp), modifier = modifier.border(1.dp, Color(0xFF303942), RoundedCornerShape(5.dp))) {
        if (lcdPage == "STYLE") {
            Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                Text("STYLE SELECT", color = SxOrangeBright, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("STYLE FOLDER", color = SxDim, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(styleFolders) { folder ->
                        val selected = folder.uri == selectedStyleFolderUri
                        Row(Modifier.fillMaxWidth().clickable { onPickStyleFolder(folder.uri) }.background(if (selected) SxBlue else Color(0xFF1D2329), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 9.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(folder.name, color = Color.White, fontSize = if (compact) 6.sp else 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(folder.styleCount.toString(), color = SxDim, fontSize = 6.sp)
                        }
                    }
                }
                Text("Folder → styles on LCD", color = SxDim, fontSize = 6.sp)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                Text("VOICE SELECT", color = Color(0xFF55A9E6), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                val cats = listOf("PIANO", "ORGAN", "GUITAR", "STRINGS", "BRASS", "SAX/WOODWIND", "SYNTH", "CHOIR/PAD", "BASS", "PERCUSSION", "WORLD", "USER")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    cats.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEach { cat ->
                                Surface(color = if (cat == "PIANO") SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().clickable { onPickVoice(cat) }.border(1.dp, Color(0xFF35404A), RoundedCornerShape(3.dp))) {
                                    Box(contentAlignment = Alignment.Center) { Text(cat, color = Color.White, fontSize = if (cat.length > 8) 5.sp else 6.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
                Text("PART ON/OFF", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(0, 1, 2).forEach { layer ->
                        val enabled = state.rightVoices.getOrNull(layer)?.enabled == true
                        Surface(color = if (enabled) SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().clickable { vm.toggleRightVoice(layer) }) { Box(contentAlignment = Alignment.Center) { Text("R" + (layer + 1), color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) } }
                    }
                    val leftOn = state.leftVoiceEnabled
                    Surface(color = if (leftOn) SxBlue else Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight().clickable { vm.toggleLeftVoice() }) { Box(contentAlignment = Alignment.Center) { Text("L", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) } }
                }
                Text("ONE TOUCH SETTING", color = Color(0xFF55A9E6), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().height(if (compact) 34.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) { (1..4).forEach { n -> Surface(color = Color(0xFF1D2329), shape = RoundedCornerShape(3.dp), modifier = Modifier.weight(1f).fillMaxHeight()) { Box(contentAlignment = Alignment.Center) { Text(n.toString(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } }
            }
        }
    }
}

@Composable private fun SxStyleControls(state: MainUiState, vm: MainViewModel, height: Dp, compact: Boolean) { Surface(color = Color(0xFF111519), shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(height).border(1.dp, Color(0xFF303841), RoundedCornerShape(5.dp))) { Column(Modifier.fillMaxSize().padding(if (compact) 6.dp else 8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("STYLE CONTROL", color = SxOrangeBright, fontSize = if (compact) 9.sp else 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.width(8.dp)); Box(Modifier.weight(1f).height(1.dp).background(Color(0xFF6D3600))) }; Spacer(Modifier.height(if (compact) 4.dp else 6.dp)); Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) { SxSectionGroup("INTRO", listOf("Intro 1", "Intro 2", "Intro 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact); SxSectionGroup("MAIN VARIATION", listOf("Main A", "Main B", "Main C", "Main D"), state.activeSection, vm::onSectionSelected, Modifier.weight(1.35f), compact); SxStartStop(state.isPlaying, state.startReady, state.readinessReason, vm::onStartStop, Modifier.weight(1.35f), compact); SxSectionGroup("ENDING", listOf("Ending 1", "Ending 2", "Ending 3"), state.activeSection, vm::onSectionSelected, Modifier.weight(1f), compact); Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) { SxAutoFill(state.autoFill, vm::toggleAutoFill, Modifier.fillMaxWidth().weight(1f), compact); SxAction(if (state.acmpEnabled) "ACMP ON" else "ACMP OFF", state.acmpEnabled, vm::toggleAcmp, compact); SxAction(if (state.leftVoiceEnabled) "LEFT ON" else "LEFT OFF", state.leftVoiceEnabled, vm::toggleLeftVoice, compact); SxAction("SYNC START", false, vm::onSyncStart, compact); SxAction("TAP TEMPO", false, vm::onTapTempo, compact) } } } } }
@Composable private fun SxStartStop(playing: Boolean, ready: Boolean, reason: String, onClick: () -> Unit, modifier: Modifier, compact: Boolean) {
    Column(modifier.fillMaxHeight()) {
        Text("TRANSPORT", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
        Surface(
            color = if (playing) Color(0xFF155B3C) else Color(0xFF242A31),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(enabled = playing || ready, onClick = onClick)
                .border(2.dp, if (playing) SxGreen else Color(0xFF4A5560), RoundedCornerShape(4.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (playing) "STOP" else if (ready) "START" else reason,
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

@Composable private fun SxMidiBar(state: MainUiState, vm: MainViewModel, height: Dp, compact: Boolean) { Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) { Text("YamahaArranger v0.1.0   |   MIDI: ${state.midiStatus}", color = SxDim, fontSize = if (compact) 6.sp else 7.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); SxToolButton("\uD83C\uDFB9 KEYBOARD", ToolPanels.keyboard, height, compact, ToolPanels::toggleKeyboard); Spacer(Modifier.width(4.dp)); SxToolButton("LATENCY", ToolPanels.latency, height, compact, ToolPanels::toggleLatency); Spacer(Modifier.width(4.dp)); SxToolButton("RIGHT DIAG", ToolPanels.diag, height, compact, ToolPanels::toggleDiag); Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = vm::refreshMidiConnection, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("CONNECT", fontSize = if (compact) 6.sp else 7.sp) }; Spacer(Modifier.width(4.dp)); Button(onClick = vm::toggleMidiOut, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = if (state.midiOutEnabled) SxBlue else SxPanel2)) { Text("MIDI OUT", fontSize = if (compact) 6.sp else 7.sp) } } }
@Composable private fun SmallKey(text: String, onClick: () -> Unit, compact: Boolean = false) { Button(onClick = onClick, modifier = Modifier.size(if (compact) 23.dp else 25.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39434B)), shape = RoundedCornerShape(2.dp)) { Text(text, fontSize = if (compact) 10.sp else 12.sp) } }

private fun voiceCategoryMatches(program: Int, category: String?): Boolean {
    if (category == null || category == "USER") return true
    return when (category) {
        "PIANO" -> program in 0..15
        "ORGAN" -> program in 16..23
        "GUITAR" -> program in 24..31
        "BASS" -> program in 32..39
        "STRINGS" -> program in 40..55
        "CHOIR/PAD" -> program in 52..55 || program in 88..95
        "BRASS" -> program in 56..63
        "SAX/WOODWIND" -> program in 64..79
        "SYNTH" -> program in 80..103
        "WORLD" -> program in 104..127
        "PERCUSSION" -> false
        else -> true
    }
}

@Composable
private fun SxPresetFilters(
    presets: List<AudioEngineManager.SfPreset>, search: String, onSearch: (String) -> Unit,
    fontId: Long?, onFont: (Long?) -> Unit, compact: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val sources = presets.distinctBy { it.soundFontId }
    LaunchedEffect(sources.map { it.soundFontId }, fontId) {
        if (fontId != null && sources.none { it.soundFontId == fontId }) onFont(null)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = search, onValueChange = onSearch,
            modifier = Modifier.weight(1f), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (compact) 10.sp else 12.sp),
            placeholder = { Text("Search preset…", fontSize = 10.sp) })
        Box(Modifier.widthIn(max = 150.dp)) {
            TextButton(onClick = { expanded = true }) {
                Text(sources.firstOrNull { it.soundFontId == fontId }?.soundFontName ?: "ALL SF2",
                    fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("ALL SF2") }, onClick = { onFont(null); expanded = false })
                sources.forEach { source ->
                    DropdownMenuItem(text = { Text("${source.soundFontName} • ${source.role} #${source.soundFontId}") },
                        onClick = { onFont(source.soundFontId); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SxKeyboardVoiceDialog(
    slot: KeyboardVoiceSlot,
    presets: List<AudioEngineManager.SfPreset>,
    category: String? = null,
    onDismiss: () -> Unit,
    onSelect: (AudioEngineManager.SfPreset) -> Unit,
    onToggle: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var fontId by remember { mutableStateOf<Long?>(null) }
    var allCategories by remember(category) { mutableStateOf(category == null || category == "USER") }
    val filtered = SoundFontPresetCatalog.filter(presets, search, fontId)
        .filter { allCategories || if (category == "PERCUSSION") it.isDrum else voiceCategoryMatches(it.program, category) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(slot.label, fontWeight = FontWeight.Bold)
                    Text(slot.displayName(), color = SxDim, fontSize = 11.sp)
                }
                TextButton(onClick = onToggle) { Text(if (slot.enabled) "ON" else "OFF") }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                SxPresetFilters(presets, search, { search = it }, fontId, { fontId = it })
                if (category != null && category != "USER") {
                    TextButton(onClick = { allCategories = !allCategories }) {
                        Text(if (allCategories) "ALL PRESETS" else category)
                    }
                }
                Text("${filtered.size} PRESETS • bank / program (0-based)", color = SxDim, fontSize = 9.sp)
                if (filtered.isEmpty()) Text("No available preset", color = SxOrangeBright, fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                    items(filtered, key = { it.identity }) { item ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(7.dp)) {
                            Text(item.name, color = Color.White, fontSize = 12.sp,
                                fontWeight = if (item.soundFontId == slot.soundFontId && item.bank == slot.bank && item.program == slot.program)
                                    FontWeight.Bold else FontWeight.Normal)
                            Text("${item.soundFontName} • ${item.role} #${item.soundFontId} • ${item.bank}/${item.program}",
                                color = SxDim, fontSize = 9.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable private fun SxToolButton(label: String, active: Boolean, height: Dp, compact: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(height), contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) SxBlue else Color.Transparent)) {
        Text(label, fontSize = if (compact) 6.sp else 7.sp, maxLines = 1)
    }
}

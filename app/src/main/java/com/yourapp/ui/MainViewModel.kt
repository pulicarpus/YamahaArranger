package com.yourapp.yamahaarranger.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.StyleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

object DebugLog {
    private val _lines = ConcurrentLinkedQueue<String>()
    private const val MAX_LINES = 30
    @JvmStatic fun add(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _lines.add("[$ts] $msg")
        while (_lines.size > MAX_LINES) _lines.poll()
    }
    fun getAll(): List<String> = _lines.toList()
    fun clear() = _lines.clear()
}

data class VoiceSlot(val channel: Int, val label: String, val program: Int, val bank: Int = 0, val locked: Boolean = false) {
    fun displayName(): String = if (bank == 128) "DRUM KIT" else GM_VOICES.firstOrNull { it.second == program }?.first ?: "prog$program"
    fun isDrum(): Boolean = bank == 128
}

val GM_VOICES: List<Pair<String, Int>> = listOf(
    "Piano" to 0, "Bright Piano" to 1, "E.Piano 1" to 4, "Drawbar Organ" to 16,
    "Nylon Guitar" to 24, "Clean Guitar" to 27, "Acoustic Bass" to 32, "Finger Bass" to 33,
    "Violin" to 40, "Strings Ensemble 1" to 48, "Synth Strings 1" to 50, "Choir Aahs" to 52,
    "Trumpet" to 56, "Brass Section" to 61, "Soprano Sax" to 64, "Alto Sax" to 65,
    "Tenor Sax" to 66, "Flute" to 73, "Synth Lead (Square)" to 80, "Synth Lead (Saw)" to 81,
    "Synth Pad (New Age)" to 88, "Synth Pad (Warm)" to 89, "FX (Rain)" to 96, "Sitar" to 104,
    "Kalimba" to 108, "Tinkle Bell" to 112, "Taiko Drum" to 116, "Synth Drum" to 118
)

fun defaultVoices(): List<VoiceSlot> = (0..15).map { ch ->
    when (ch) {
        9 -> VoiceSlot(9, "Ch9 (Drum)", 0, 128)
        10 -> VoiceSlot(10, "Ch10 (Bass)", 33)
        11 -> VoiceSlot(11, "Ch11 (Chord1)", 0)
        12 -> VoiceSlot(12, "Ch12 (Chord2)", 24)
        13 -> VoiceSlot(13, "Ch13 (Pad)", 48)
        14 -> VoiceSlot(14, "Ch14 (Phrase1)", 56)
        15 -> VoiceSlot(15, "Ch15 (Phrase2)", 65)
        else -> VoiceSlot(ch, "Ch$ch", 0)
    }
}

data class MainUiState(
    val styleName: String = "No Style Loaded", val tempoBpm: Int = 120, val transpose: Int = 0,
    val isPlaying: Boolean = false, val activeSection: String = "Main A", val detectedChordLabel: String = "",
    val midiStatus: String = "No MIDI device", val midiOutEnabled: Boolean = false, val soundFontName: String = "None",
    val styleVolume: Int = 100, val voiceVolume: Int = 100, val masterVolume: Int = 110,
    val activeBank: Int = 1, val activeRegSlot: Int = 0, val voiceName: String = "GrandPiano",
    val right2Name: String = "OFF", val splitPoint: String = "C4", val voiceAssignments: List<VoiceSlot> = defaultVoices()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val arrangerBrain: ArrangerBrain,
    private val styleRepository: StyleRepository,
    private val contentResolver: ContentResolverProvider,
    private val midiInputManager: MidiInputManager,
    private val audioEngine: AudioEngineManager
) : ViewModel() {
    private val _styleName = MutableStateFlow("No Style Loaded")
    private val _midiStatus = MutableStateFlow("No MIDI device")
    private val _midiOutEnabled = MutableStateFlow(false)
    private val _transpose = MutableStateFlow(0)
    private val _soundFontName = MutableStateFlow("None")
    private val _styleVolume = MutableStateFlow(100)
    private val _voiceVolume = MutableStateFlow(100)
    private val _masterVolume = MutableStateFlow(110)
    private val _activeBank = MutableStateFlow(1)
    private val _activeRegSlot = MutableStateFlow(0)
    private val _voiceAssignments = MutableStateFlow(defaultVoices())
    private var activeChordNotes: List<Int> = emptyList()

    val uiState: StateFlow<MainUiState> = combine(
        arrangerBrain.state,
        combine(_styleName, _midiStatus) { s, m -> s to m },
        combine(_transpose, _soundFontName) { t, sf -> t to sf },
        combine(_voiceVolume, _masterVolume) { vv, mv -> vv to mv },
        combine(_activeBank, _activeRegSlot, _voiceAssignments) { b, r, v -> Triple(b, r, v) }
    ) { arranger, (styleName, midi), (transpose, sfName), (voiceVol, masterVol), (bank, regSlot, voices) ->
        MainUiState(styleName = styleName, tempoBpm = arranger.tempoBpm, transpose = transpose,
            isPlaying = arranger.isPlaying, activeSection = displayLabelFor(arranger.currentSection),
            detectedChordLabel = arranger.currentChordLabel, midiStatus = midi, midiOutEnabled = _midiOutEnabled.value,
            soundFontName = sfName, voiceVolume = voiceVol, masterVolume = masterVol,
            activeBank = bank, activeRegSlot = regSlot, voiceAssignments = voices)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        arrangerBrain.attachScope(viewModelScope)
        audioEngine.start()
        DebugLog.add("🎵 ViewModel init")
        midiInputManager.onNoteOn = { note, velocity -> arrangerBrain.onKeyboardNoteOn(note, velocity / 127f) }
        midiInputManager.onNoteOff = { note -> arrangerBrain.onKeyboardNoteOff(note) }
    }

    fun connectFirstAvailableMidiDevice() {
        viewModelScope.launch {
            repeat(5) {
                if (midiInputManager.connectFirstAvailableDevice()) {
                    _midiStatus.value = midiInputManager.connectedDeviceName ?: "MIDI connected"
                    DebugLog.add("✅ MIDI: ${_midiStatus.value}")
                    return@launch
                }
                delay(1500L)
            }
            _midiStatus.value = "No MIDI device"
        }
    }
    fun refreshMidiConnection() { _midiStatus.value = "Connecting…"; connectFirstAvailableMidiDevice() }
    fun toggleMidiOut() { val v = !_midiOutEnabled.value; _midiOutEnabled.value = v; midiInputManager.midiOutEnabled = v; DebugLog.add(if (v) "📤 MIDI OUT: ON" else "📤 MIDI OUT: OFF") }
    override fun onCleared() { midiInputManager.close(); audioEngine.stop(); super.onCleared() }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) = arrangerBrain.onKeyboardNoteOn(midiNote, velocity)
    fun onKeyboardNoteOff(midiNote: Int) = arrangerBrain.onKeyboardNoteOff(midiNote)

    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) arrangerBrain.selectMainVariation(section) else arrangerBrain.selectSection(section)
    }
    fun onSyncStart() { }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { }
    fun onTempoDown() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm - 5).coerceIn(20, 280)) }
    fun onTempoUp() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm + 5).coerceIn(20, 280)) }
    fun onTransposeDown() { _transpose.value = (_transpose.value - 1).coerceIn(-12, 12) }
    fun onTransposeUp() { _transpose.value = (_transpose.value + 1).coerceIn(-12, 12) }
    fun onStyleVolumeChange(value: Int) { _styleVolume.value = value }
    fun onVoiceVolumeChange(value: Int) { _voiceVolume.value = value }
    fun onMasterVolumeChange(value: Int) { _masterVolume.value = value }
    fun onBankChange(bank: Int) { _activeBank.value = bank.coerceIn(1, 8) }

    // Temporary style-test chord pads. Registration buttons 1-8 are now fixed test chords.
    // Tap another pad to release the previous chord and hold the new chord.
    fun onRegSlotTap(slot: Int) {
        val chords = listOf(
            "C" to intArrayOf(48, 52, 55),
            "Dm" to intArrayOf(50, 53, 57),
            "Em" to intArrayOf(52, 55, 59),
            "F" to intArrayOf(53, 57, 60),
            "G" to intArrayOf(55, 59, 62),
            "Am" to intArrayOf(57, 60, 64),
            "Bdim" to intArrayOf(59, 62, 65),
            "C7" to intArrayOf(60, 64, 67, 70)
        )
        val index = slot.coerceIn(0, 7)
        activeChordNotes.forEach(arrangerBrain::onKeyboardNoteOff)
        activeChordNotes = chords[index].second.toList()
        activeChordNotes.forEach { arrangerBrain.onKeyboardNoteOn(it, 0.82f) }
        _activeRegSlot.value = index
        DebugLog.add("🎹 CHORD PAD ${index + 1}: ${chords[index].first} (${activeChordNotes.joinToString()})")
    }
    fun onRegSlotSave(slot: Int) { Timber.i("Save reg bank=${_activeBank.value} slot=$slot") }

    fun setChannelVoice(channel: Int, program: Int, bank: Int) {
        _voiceAssignments.value = _voiceAssignments.value.map { slot ->
            if (slot.channel == channel) {
                audioEngine.setChannelProgram(channel, program, bank)
                midiInputManager.sendProgramChange(channel, program, bank)
                DebugLog.add("🎼 Ch$channel → prog$program (bank$bank)")
                slot.copy(program = program, bank = bank)
            } else slot
        }
    }
    fun toggleChannelLock(channel: Int) {
        val updated = _voiceAssignments.value.map { slot -> if (slot.channel == channel) slot.copy(locked = !slot.locked) else slot }
        _voiceAssignments.value = updated
        arrangerBrain.updateLockedChannels(updated.filter { it.locked }.map { it.channel }.toSet())
    }
    fun playTestTone() {
        viewModelScope.launch {
            for (note in intArrayOf(60, 64, 67)) { audioEngine.testTone(note, 0.9f); delay(400); audioEngine.noteOff(note) }
        }
    }

    fun onStyleFilePicked(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) } ?: run { DebugLog.add("❌ Cannot read style"); return@launch }
            val fileName = contentResolver.fileName(uri) ?: "style.sty"
            val parsed = withContext(Dispatchers.Default) { styleRepository.loadStyle(fileName, bytes) }
            if (parsed == null) { DebugLog.add("❌ Parse fail: $fileName"); return@launch }
            arrangerBrain.loadStyle(parsed); _styleName.value = fileName; DebugLog.add("✅ Loaded: $fileName")
        }
    }

    fun onSoundFontFilePicked(uri: Uri) {
        viewModelScope.launch {
            DebugLog.add("📂 SF2 picker…")
            val fileName = contentResolver.fileName(uri) ?: "font.sf2"
            val destFile = File(contentResolver.getFilesDir(), "user.sf2")
            val copied = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } } ?: return@withContext false
                    destFile.length() > 0
                } catch (e: Exception) { Timber.e(e, "Copy SF2 failed"); false }
            }
            if (!copied) { DebugLog.add("❌ Copy SF2 failed"); return@launch }
            DebugLog.add("📂 SF2 copied: ${destFile.length() / 1024 / 1024} MB")
            val ok = withContext(Dispatchers.Default) { audioEngine.loadSoundFont(destFile.absolutePath) }
            _soundFontName.value = if (ok) fileName else "Load failed"
            DebugLog.add(if (ok) "✅ SF2: $fileName" else "❌ SF2 load failed")
        }
    }

    companion object {
        private val SECTION_BUTTON_MAP = mapOf(
            "Intro 1" to ArrangerSection.IntroA, "Intro 2" to ArrangerSection.IntroB, "Intro 3" to ArrangerSection.IntroC,
            "Main A" to ArrangerSection.MainA, "Main B" to ArrangerSection.MainB, "Main C" to ArrangerSection.MainC, "Main D" to ArrangerSection.MainD,
            "Fill A" to ArrangerSection.FillAA, "Fill B" to ArrangerSection.FillBB, "Fill C" to ArrangerSection.FillCC, "Fill D" to ArrangerSection.FillDD,
            "Ending 1" to ArrangerSection.EndingA, "Ending 2" to ArrangerSection.EndingB, "Ending 3" to ArrangerSection.EndingC)
        private val MAIN_VARIATIONS = setOf(ArrangerSection.MainA, ArrangerSection.MainB, ArrangerSection.MainC, ArrangerSection.MainD)
        private fun displayLabelFor(section: ArrangerSection): String = SECTION_BUTTON_MAP.entries.firstOrNull { it.value == section }?.key ?: section.name
    }
}
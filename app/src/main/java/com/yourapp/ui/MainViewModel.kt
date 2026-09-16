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

    @JvmStatic
    fun add(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _lines.add("[$ts] $msg")
        while (_lines.size > MAX_LINES) _lines.poll()
    }

    fun getAll(): List<String> = _lines.toList()
    fun clear() = _lines.clear()
}

data class VoiceSlot(val channel: Int, val label: String, val program: Int) {
    fun displayName(): String =
        GM_VOICES.firstOrNull { it.second == program }?.first ?: "prog$program"
}

val GM_VOICES: List<Pair<String, Int>> = listOf(
    "Piano" to 0, "Bright Piano" to 1, "E.Piano" to 4, "Harpsichord" to 6,
    "Organ" to 16, "Church Organ" to 19, "Accordion" to 21,
    "Nylon Guitar" to 24, "Steel Guitar" to 25, "Jazz Guitar" to 26,
    "Clean Guitar" to 27, "Overdrive Gt" to 29, "Distortion Gt" to 30,
    "Finger Bass" to 33, "Pick Bass" to 34, "Slap Bass" to 36, "Synth Bass" to 38,
    "Violin" to 40, "Viola" to 41, "Cello" to 42,
    "Strings" to 48, "Slow Strings" to 51, "Choir" to 52,
    "Trumpet" to 56, "Trombone" to 57, "Tuba" to 58, "Brass" to 61,
    "Soprano Sax" to 64, "Alto Sax" to 65, "Tenor Sax" to 66,
    "Oboe" to 68, "English Horn" to 69, "Bassoon" to 70,
    "Clarinet" to 71, "Flute" to 73, "Pan Flute" to 75,
    "Synth Lead" to 80, "Synth Pad" to 89, "FX" to 96
)

fun defaultVoices(): List<VoiceSlot> = listOf(
    VoiceSlot(2, "Bass", 33), VoiceSlot(3, "Chord1", 0),
    VoiceSlot(4, "Chord2", 24), VoiceSlot(5, "Pad", 48),
    VoiceSlot(6, "Phrase1", 56), VoiceSlot(7, "Phrase2", 65)
)

data class MainUiState(
    val styleName: String = "No Style Loaded",
    val tempoBpm: Int = 120,
    val transpose: Int = 0,
    val isPlaying: Boolean = false,
    val activeSection: String = "Main A",
    val detectedChordLabel: String = "",
    val midiStatus: String = "No MIDI device",
    val midiOutEnabled: Boolean = false,        // ← BARU
    val soundFontName: String = "None",
    val styleVolume: Int = 100,
    val voiceVolume: Int = 100,
    val masterVolume: Int = 110,
    val activeBank: Int = 1,
    val activeRegSlot: Int = 0,
    val voiceName: String = "GrandPiano",
    val right2Name: String = "OFF",
    val splitPoint: String = "C4",
    val voiceAssignments: List<VoiceSlot> = defaultVoices()
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
    private val _midiOutEnabled = MutableStateFlow(false)   // ← BARU
    private val _transpose = MutableStateFlow(0)
    private val _soundFontName = MutableStateFlow("None")
    private val _styleVolume = MutableStateFlow(100)
    private val _voiceVolume = MutableStateFlow(100)
    private val _masterVolume = MutableStateFlow(110)
    private val _activeBank = MutableStateFlow(1)
    private val _activeRegSlot = MutableStateFlow(0)
    private val _voiceAssignments = MutableStateFlow(defaultVoices())

    val uiState: StateFlow<MainUiState> =
        combine(
            arrangerBrain.state,
            combine(_styleName, _midiStatus) { s, m -> s to m },
            combine(_transpose, _soundFontName) { t, sf -> t to sf },
            combine(_voiceVolume, _masterVolume) { vv, mv -> vv to mv },
            combine(_activeBank, _activeRegSlot, _voiceAssignments) { b, r, v ->
                Triple(b, r, v)
            }
        ) { arranger, (styleName, midi), (transpose, sfName),
            (voiceVol, masterVol), (bank, regSlot, voices) ->
            MainUiState(
                styleName = styleName,
                tempoBpm = arranger.tempoBpm,
                transpose = transpose,
                isPlaying = arranger.isPlaying,
                activeSection = displayLabelFor(arranger.currentSection),
                detectedChordLabel = arranger.currentChordLabel,
                midiStatus = midi,
                midiOutEnabled = _midiOutEnabled.value,
                soundFontName = sfName,
                voiceVolume = voiceVol,
                masterVolume = masterVol,
                activeBank = bank,
                activeRegSlot = regSlot,
                voiceAssignments = voices
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        arrangerBrain.attachScope(viewModelScope)
        audioEngine.start()
        DebugLog.add("🎵 ViewModel init")

        midiInputManager.onNoteOn = { note, velocity ->
            arrangerBrain.onKeyboardNoteOn(note, velocity / 127f)
        }
        midiInputManager.onNoteOff = { note ->
            arrangerBrain.onKeyboardNoteOff(note)
        }
    }

    // MIDI
    fun connectFirstAvailableMidiDevice() {
        viewModelScope.launch {
            repeat(5) { attempt ->
                val ok = midiInputManager.connectFirstAvailableDevice()
                if (ok) {
                    _midiStatus.value = midiInputManager.connectedDeviceName ?: "MIDI connected"
                    DebugLog.add("✅ MIDI: ${_midiStatus.value}")
                    return@launch
                }
                delay(1500L)
            }
            _midiStatus.value = "No MIDI device"
        }
    }

    fun refreshMidiConnection() {
        _midiStatus.value = "Connecting…"
        connectFirstAvailableMidiDevice()
    }

    /** Toggle MIDI OUT on/off — kalau ON, style dikirim ke E343. */
    fun toggleMidiOut() {
        val newVal = !_midiOutEnabled.value
        _midiOutEnabled.value = newVal
        midiInputManager.midiOutEnabled = newVal
        DebugLog.add(if (newVal) "📤 MIDI OUT: ON" else "📤 MIDI OUT: OFF")
    }

    override fun onCleared() {
        midiInputManager.close()
        audioEngine.stop()
        super.onCleared()
    }

    // KEYBOARD
    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) =
        arrangerBrain.onKeyboardNoteOn(midiNote, velocity)

    fun onKeyboardNoteOff(midiNote: Int) =
        arrangerBrain.onKeyboardNoteOff(midiNote)

    // SECTION
    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) {
            arrangerBrain.selectMainVariation(section)
        } else {
            arrangerBrain.selectSection(section)
        }
    }

    // TRANSPORT
    fun onSyncStart() { }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { }

    // TEMPO
    fun onTempoDown() {
        val newTempo = (arrangerBrain.state.value.tempoBpm - 5).coerceIn(20, 280)
        arrangerBrain.setTempo(newTempo)
    }
    fun onTempoUp() {
        val newTempo = (arrangerBrain.state.value.tempoBpm + 5).coerceIn(20, 280)
        arrangerBrain.setTempo(newTempo)
    }

    // TRANSPOSE
    fun onTransposeDown() { _transpose.value = (_transpose.value - 1).coerceIn(-12, 12) }
    fun onTransposeUp() { _transpose.value = (_transpose.value + 1).coerceIn(-12, 12) }

    // VOLUME
    fun onStyleVolumeChange(value: Int) { _styleVolume.value = value }
    fun onVoiceVolumeChange(value: Int) { _voiceVolume.value = value }
    fun onMasterVolumeChange(value: Int) { _masterVolume.value = value }

    // REGISTRATION
    fun onBankChange(bank: Int) { _activeBank.value = bank.coerceIn(1, 8) }
    fun onRegSlotTap(slot: Int) { _activeRegSlot.value = slot }
    fun onRegSlotSave(slot: Int) { Timber.i("Save reg bank=${_activeBank.value} slot=$slot") }

    // VOICE ASSIGN
    fun cycleVoice(channel: Int) {
        val current = _voiceAssignments.value
        val updated = current.map { slot ->
            if (slot.channel == channel) {
                val currIdx = GM_VOICES.indexOfFirst { it.second == slot.program }
                val nextIdx = if (currIdx < 0) 0 else (currIdx + 1) % GM_VOICES.size
                val newProg = GM_VOICES[nextIdx].second
                val newName = GM_VOICES[nextIdx].first
                audioEngine.setChannelProgram(channel, newProg, 0)
                midiInputManager.sendProgramChange(channel, newProg, 0)
                DebugLog.add("🎼 ${slot.label} ch$channel → $newName")
                slot.copy(program = newProg)
            } else slot
        }
        _voiceAssignments.value = updated
    }

    // TEST TONE
    fun playTestTone() {
        viewModelScope.launch {
            DebugLog.add("🔊 TEST TONE")
            for (note in intArrayOf(60, 64, 67)) {
                audioEngine.testTone(note, 0.9f)
                delay(400)
                audioEngine.noteOff(note)
            }
        }
    }

    // STYLE PICKER
    fun onStyleFilePicked(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) }
            if (bytes == null) {
                DebugLog.add("❌ Cannot read style")
                return@launch
            }
            val fileName = contentResolver.fileName(uri) ?: "style.sty"
            val parsed = withContext(Dispatchers.Default) {
                styleRepository.loadStyle(fileName, bytes)
            }
            if (parsed == null) {
                DebugLog.add("❌ Parse fail: $fileName")
                return@launch
            }
            arrangerBrain.loadStyle(parsed)
            _styleName.value = fileName
            DebugLog.add("✅ Loaded: $fileName")
        }
    }

    // SOUNDFONT PICKER
    fun onSoundFontFilePicked(uri: Uri) {
        viewModelScope.launch {
            DebugLog.add("📂 SF2 picker…")
            val fileName = contentResolver.fileName(uri) ?: "font.sf2"

            val destFile = File(contentResolver.getFilesDir(), "user.sf2")
            val copied = withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri) ?: return@withContext false
                    val output = FileOutputStream(destFile)
                    input.use { inp -> output.use { out -> inp.copyTo(out) } }
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Copy SF2 failed")
                    false
                }
            }
            if (!copied) {
                DebugLog.add("❌ Copy SF2 failed")
                return@launch
            }
            DebugLog.add("📂 SF2 copied: ${destFile.length() / 1024 / 1024} MB")

            val ok = withContext(Dispatchers.Default) {
                audioEngine.loadSoundFont(destFile.absolutePath)
            }
            _soundFontName.value = if (ok) fileName else "Load failed"
            DebugLog.add(if (ok) "✅ SF2: $fileName" else "❌ SF2 load failed")
        }
    }

    companion object {
        private val SECTION_BUTTON_MAP = mapOf(
            "Intro 1" to ArrangerSection.IntroA,
            "Intro 2" to ArrangerSection.IntroB,
            "Intro 3" to ArrangerSection.IntroC,
            "Main A" to ArrangerSection.MainA,
            "Main B" to ArrangerSection.MainB,
            "Main C" to ArrangerSection.MainC,
            "Main D" to ArrangerSection.MainD,
            "Fill A" to ArrangerSection.FillAA,
            "Fill B" to ArrangerSection.FillBB,
            "Fill C" to ArrangerSection.FillCC,
            "Fill D" to ArrangerSection.FillDD,
            "Ending 1" to ArrangerSection.EndingA,
            "Ending 2" to ArrangerSection.EndingB,
            "Ending 3" to ArrangerSection.EndingC
        )
        private val MAIN_VARIATIONS = setOf(
            ArrangerSection.MainA, ArrangerSection.MainB,
            ArrangerSection.MainC, ArrangerSection.MainD
        )

        private fun displayLabelFor(section: ArrangerSection): String =
            SECTION_BUTTON_MAP.entries.firstOrNull { it.value == section }?.key ?: section.name
    }
}
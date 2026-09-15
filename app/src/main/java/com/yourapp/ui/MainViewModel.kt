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
import javax.inject.Inject

/** Global log buffer — diakses dari UI & StyleSequencer. */
object DebugLog {
    private val _lines = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_LINES = 30

    fun add(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _lines.add("[$ts] $msg")
        while (_lines.size > MAX_LINES) _lines.poll()
    }

    fun getAll(): List<String> = _lines.toList()
    fun clear() = _lines.clear()
}

data class MainUiState(
    val styleName: String = "No Style Loaded",
    val tempoBpm: Int = 120,
    val transpose: Int = 0,
    val isPlaying: Boolean = false,
    val activeSection: String = "Main A",
    val detectedChordLabel: String = "",
    val midiStatus: String = "No MIDI device",
    // Volume (0-127)
    val styleVolume: Int = 100,
    val voiceVolume: Int = 100,
    val masterVolume: Int = 110,
    // Registration
    val activeBank: Int = 1,
    val activeRegSlot: Int = 0,
    // Voice info (stub — nanti diisi dari Voice Browser)
    val voiceName: String = "GrandPiano",
    val right2Name: String = "OFF",
    val splitPoint: String = "C4"
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
    private val _transpose = MutableStateFlow(0)
    private val _styleVolume = MutableStateFlow(100)
    private val _voiceVolume = MutableStateFlow(100)
    private val _masterVolume = MutableStateFlow(110)
    private val _activeBank = MutableStateFlow(1)
    private val _activeRegSlot = MutableStateFlow(0)
    val uiState: StateFlow<MainUiState> =
        combine(
            arrangerBrain.state,
            combine(_styleName, _midiStatus) { s, m -> s to m },
            combine(_transpose, _styleVolume) { t, sv -> t to sv },
            combine(_voiceVolume, _masterVolume) { vv, mv -> vv to mv },
            combine(_activeBank, _activeRegSlot) { b, r -> b to r }
        ) { arranger, (styleName, midi), (transpose, styleVol), (voiceVol, masterVol), (bank, regSlot) ->
            MainUiState(
                styleName = styleName,
                tempoBpm = arranger.tempoBpm,
                transpose = transpose,
                isPlaying = arranger.isPlaying,
                activeSection = displayLabelFor(arranger.currentSection),
                detectedChordLabel = arranger.currentChordLabel,
                midiStatus = midi,
                styleVolume = styleVol,
                voiceVolume = voiceVol,
                masterVolume = masterVol,
                activeBank = bank,
                activeRegSlot = regSlot
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    // ═══════════════════════════════════════════════
    // MIDI
    // ═══════════════════════════════════════════════
    fun connectFirstAvailableMidiDevice() {
        viewModelScope.launch {
            repeat(5) { attempt ->
                val ok = midiInputManager.connectFirstAvailableDevice()
                if (ok) {
                    _midiStatus.value = midiInputManager.connectedDeviceName ?: "MIDI connected"
                    Timber.i("MIDI connected attempt ${attempt + 1}: ${_midiStatus.value}")
                    return@launch
                }
                Timber.w("MIDI attempt ${attempt + 1} failed, retrying…")
                delay(1500L)
            }
            _midiStatus.value = "No MIDI device"
        }
    }

    fun refreshMidiConnection() {
        _midiStatus.value = "Connecting…"
        connectFirstAvailableMidiDevice()
    }

    override fun onCleared() {
        midiInputManager.close()
        audioEngine.stop()
        super.onCleared()
    }

    // ═══════════════════════════════════════════════
    // KEYBOARD (dari E343 atau virtual)
    // ═══════════════════════════════════════════════
    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) =
        arrangerBrain.onKeyboardNoteOn(midiNote, velocity)

    fun onKeyboardNoteOff(midiNote: Int) =
        arrangerBrain.onKeyboardNoteOff(midiNote)

    // ═══════════════════════════════════════════════
    // SECTION
    // ═══════════════════════════════════════════════
    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) {
            arrangerBrain.selectMainVariation(section)
        } else {
            arrangerBrain.selectSection(section)
        }
    }

    // ═══════════════════════════════════════════════
    // TRANSPORT
    // ═══════════════════════════════════════════════
    fun onSyncStart() { /* TODO Sprint B */ }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { /* TODO Sprint B */ }

        /** Test tone: play C-E-G arpeggio 3× — untuk diagnosa audio engine. */
    fun playTestTone() {
        viewModelScope.launch {
            DebugLog.add("🔊 TEST TONE: C-E-G × 3")
            val notes = intArrayOf(60, 64, 67)
            repeat(3) {
                for (note in notes) {
                    audioEngine.noteOn(note, 0.8f)
                    delay(300)
                    audioEngine.noteOff(note)
                }
                delay(200)
            }
            DebugLog.add("🔊 TEST TONE: done")
        }
    }
    // ═══════════════════════════════════════════════
    // TEMPO
    // ═══════════════════════════════════════════════
    fun onTempoDown() {
        val newTempo = (arrangerBrain.state.value.tempoBpm - 5).coerceIn(20, 280)
        arrangerBrain.setTempo(newTempo)
    }

    fun onTempoUp() {
        val newTempo = (arrangerBrain.state.value.tempoBpm + 5).coerceIn(20, 280)
        arrangerBrain.setTempo(newTempo)
    }

    // ═══════════════════════════════════════════════
    // TRANSPOSE
    // ═══════════════════════════════════════════════
    fun onTransposeDown() {
        _transpose.value = (_transpose.value - 1).coerceIn(-12, 12)
        // TODO Sprint B: arrangerBrain.setTranspose(_transpose.value)
    }

    fun onTransposeUp() {
        _transpose.value = (_transpose.value + 1).coerceIn(-12, 12)
        // TODO Sprint B: arrangerBrain.setTranspose(_transpose.value)
    }

    // ═══════════════════════════════════════════════
    // VOLUME
    // ═══════════════════════════════════════════════
    fun onStyleVolumeChange(value: Int) {
        _styleVolume.value = value
        // TODO Sprint C: audioEngine.setChannelVolume(VOICE_STYLE, value)
    }

    fun onVoiceVolumeChange(value: Int) {
        _voiceVolume.value = value
        // TODO Sprint C: audioEngine.setChannelVolume(VOICE_RIGHT1, value)
    }

    fun onMasterVolumeChange(value: Int) {
        _masterVolume.value = value
        // TODO Sprint C: audioEngine.setMasterVolume(value)
    }

    // ═══════════════════════════════════════════════
    // REGISTRATION
    // ═══════════════════════════════════════════════
    fun onBankChange(bank: Int) {
        _activeBank.value = bank.coerceIn(1, 8)
    }

    fun onRegSlotTap(slot: Int) {
        _activeRegSlot.value = slot
        // TODO Sprint F: load registration dari Room DB
    }

    fun onRegSlotSave(slot: Int) {
        // TODO Sprint F: save registration ke Room DB
        Timber.i("Save registration bank=${_activeBank.value} slot=$slot")
    }

    // ═══════════════════════════════════════════════
    // STYLE
    // ═══════════════════════════════════════════════
    fun onStyleFilePicked(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) }
            if (bytes == null) {
                Timber.e("Could not read style file at $uri")
                return@launch
            }
            val fileName = contentResolver.fileName(uri) ?: "style.sty"
            val parsed = withContext(Dispatchers.Default) {
                styleRepository.loadStyle(fileName, bytes)
            }
            if (parsed == null) {
                Timber.e("Could not parse style file: $fileName")
                return@launch
            }
            arrangerBrain.loadStyle(parsed)
            _styleName.value = fileName
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
            // TODO Sprint B: tambah BreakDown ke enum ArrangerSection
            // "Break" to ArrangerSection.BreakDown,
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
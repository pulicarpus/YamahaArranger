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

data class MainUiState(
    val styleName: String = "No Style Loaded",
    val tempoBpm: Int = 120,
    val isPlaying: Boolean = false,
    val activeSection: String = "Main A",
    val detectedChordLabel: String = "",
    val midiStatus: String = "No MIDI"
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
    private val _midiStatus = MutableStateFlow("No MIDI")

    val uiState: StateFlow<MainUiState> =
        combine(arrangerBrain.state, _styleName, _midiStatus) { arranger, styleName, midi ->
            MainUiState(
                styleName = styleName,
                tempoBpm = arranger.tempoBpm,
                isPlaying = arranger.isPlaying,
                activeSection = displayLabelFor(arranger.currentSection),
                detectedChordLabel = arranger.currentChordLabel,
                midiStatus = midi
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        arrangerBrain.attachScope(viewModelScope)
        audioEngine.start()

        midiInputManager.onNoteOn = { note, velocity ->
            arrangerBrain.onKeyboardNoteOn(note, velocity / 127f)
        }
        midiInputManager.onNoteOff = { note ->
            arrangerBrain.onKeyboardNoteOff(note)
        }
    }

    /** Auto-connect dengan retry. USB MIDI butuh 1-3 detik untuk enumerate. */
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
            Timber.e("MIDI connect failed after 5 attempts")
        }
    }

    /** Panggil dari tombol "Connect MIDI" — scan ulang + connect. */
    fun refreshMidiConnection() {
        _midiStatus.value = "Connecting…"
        connectFirstAvailableMidiDevice()
    }

    override fun onCleared() {
        midiInputManager.close()
        audioEngine.stop()
        super.onCleared()
    }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) =
        arrangerBrain.onKeyboardNoteOn(midiNote, velocity)

    fun onKeyboardNoteOff(midiNote: Int) =
        arrangerBrain.onKeyboardNoteOff(midiNote)

    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) {
            arrangerBrain.selectMainVariation(section)
        } else {
            arrangerBrain.selectSection(section)
        }
    }

    fun onSyncStart() { /* Phase 2b */ }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { /* Phase 2b */ }

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
            "Intro" to ArrangerSection.IntroA,
            "Main A" to ArrangerSection.MainA,
            "Main B" to ArrangerSection.MainB,
            "Fill" to ArrangerSection.FillAA,
            "Ending" to ArrangerSection.EndingA
        )
        private val MAIN_VARIATIONS = setOf(
            ArrangerSection.MainA, ArrangerSection.MainB,
            ArrangerSection.MainC, ArrangerSection.MainD
        )

        private fun displayLabelFor(section: ArrangerSection): String =
            SECTION_BUTTON_MAP.entries.firstOrNull { it.value == section }?.key ?: section.name
    }
}
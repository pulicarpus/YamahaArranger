package com.yourapp.yamahaarranger.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.StyleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    val detectedChordLabel: String = ""
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

    val uiState: StateFlow<MainUiState> =
        combine(arrangerBrain.state, _styleName) { arranger, styleName ->
            MainUiState(
                styleName = styleName,
                tempoBpm = arranger.tempoBpm,
                isPlaying = arranger.isPlaying,
                activeSection = displayLabelFor(arranger.currentSection),
                detectedChordLabel = arranger.currentChordLabel
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        arrangerBrain.attachScope(viewModelScope)

        // ✅ START AUDIO ENGINE — tanpa ini tidak akan ada suara!
        audioEngine.start()

        // External USB/Bluetooth MIDI keyboards feed the same chord
        // detection + audio path as the on-screen keyboard.
        midiInputManager.onNoteOn = { note, velocity -> arrangerBrain.onKeyboardNoteOn(note, velocity / 127f) }
        midiInputManager.onNoteOff = { note -> arrangerBrain.onKeyboardNoteOff(note) }
    }

    /** Exposed so MainActivity/MainScreen can show a device picker (Phase 2b). */
    fun connectFirstAvailableMidiDevice() {
        midiInputManager.listAvailableDevices().firstOrNull()?.let(midiInputManager::connect)
    }

    override fun onCleared() {
        midiInputManager.close()
        audioEngine.stop()                    // ✅ Stop audio engine
        super.onCleared()
    }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) = arrangerBrain.onKeyboardNoteOn(midiNote, velocity)
    fun onKeyboardNoteOff(midiNote: Int) = arrangerBrain.onKeyboardNoteOff(midiNote)

    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) {
            arrangerBrain.selectMainVariation(section)
        } else {
            arrangerBrain.selectSection(section)
        }
    }

    fun onSyncStart() { /* Phase 2b: arm on next chord instead of playing immediately */ }

    fun onStartStop() = arrangerBrain.startStop()

    fun onTapTempo() { /* Phase 2b: average inter-tap interval -> tempoBpm */ }

    /** Called from MainActivity's document-picker callback with the .sty
     * file's Uri. Reading + parsing happens off the main thread since a
     * style file can be a few hundred KB and parsing walks every event. */
    fun onStyleFilePicked(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) }
            if (bytes == null) {
                Timber.e("Could not read style file at $uri")
                return@launch
            }
            val fileName = contentResolver.fileName(uri) ?: "style.sty"
            val parsed = withContext(Dispatchers.Default) { styleRepository.loadStyle(fileName, bytes) }
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

        /** Inverse of SECTION_BUTTON_MAP, for driving SectionButtonRow's
         * highlight from ArrangerBrain's current enum state. */
        private fun displayLabelFor(section: ArrangerSection): String =
            SECTION_BUTTON_MAP.entries.firstOrNull { it.value == section }?.key ?: section.name
    }
}
package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class ArrangerSection(val styleName: String) {
    IntroA("IntroA"), IntroB("IntroB"), IntroC("IntroC"),
    MainA("MainA"), MainB("MainB"), MainC("MainC"), MainD("MainD"),
    FillAA("FillAA"), FillBB("FillBB"), FillCC("FillCC"), FillDD("FillDD"),
    EndingA("EndingA"), EndingB("EndingB"), EndingC("EndingC")
}

data class ArrangerState(
    val isPlaying: Boolean = false,
    val currentSection: ArrangerSection = ArrangerSection.MainA,
    val tempoBpm: Int = 120,
    val currentChordLabel: String = ""
)

@Singleton
class ArrangerBrain @Inject constructor(
    private val audioEngine: AudioEngineManager,
    private val chordDetector: ChordDetector,
    private val midiInputManager: MidiInputManager
) {
    private lateinit var sequencer: StyleSequencer
    private var loadedStyle: ParsedStyle? = null
    private var externalScope: CoroutineScope? = null

    private val _state = MutableStateFlow(ArrangerState())
    val state: StateFlow<ArrangerState> = _state.asStateFlow()

    private val mainVariations = setOf(
        ArrangerSection.MainA, ArrangerSection.MainB,
        ArrangerSection.MainC, ArrangerSection.MainD
    )

    fun attachScope(scope: CoroutineScope) {
        externalScope = scope
        sequencer = StyleSequencer(audioEngine, midiInputManager, scope)
        Timber.i("ArrangerBrain: scope attached")
    }

    private fun ensureSequencer() {
        if (!::sequencer.isInitialized) {
            val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
            sequencer = StyleSequencer(audioEngine, midiInputManager, scope)
        }
    }

    fun loadStyle(style: ParsedStyle) {
        loadedStyle = style
        ensureSequencer()
        sequencer.tempoBpm = style.defaultTempoBpm
        sequencer.setVoiceMap(style.voiceMap)
        _state.update { it.copy(tempoBpm = style.defaultTempoBpm) }
        Timber.i("Style loaded: ${style.fileName}")
    }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) {
        audioEngine.noteOn(midiNote, velocity)
        chordDetector.noteOn(midiNote)?.let(::onChordChanged)
    }

    fun onKeyboardNoteOff(midiNote: Int) {
        audioEngine.noteOff(midiNote)
        chordDetector.noteOff(midiNote)?.let(::onChordChanged) ?: run {
            _state.update { it.copy(currentChordLabel = "") }
        }
    }

    private fun onChordChanged(chord: DetectedChord) {
        ensureSequencer()
        sequencer.currentChord = chord
        _state.update { it.copy(currentChordLabel = chord.label()) }
    }

    fun startStop() {
        ensureSequencer()
        val style = loadedStyle ?: run {
            Timber.w("startStop with no style")
            return
        }
        if (_state.value.isPlaying) {
            sequencer.stop()
            midiInputManager.allNotesOff()
            _state.update { it.copy(isPlaying = false) }
        } else {
            playSection(_state.value.currentSection)
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun selectMainVariation(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        val previous = _state.value.currentSection
        _state.update { it.copy(currentSection = target) }

        if (!wasPlaying) return

        val previousWasMain = previous in mainVariations
        val fill = fillFor(target)

        if (previousWasMain && previous != target && fill != null && sectionExists(fill)) {
            DebugLog.add("🎼 Main→Main: play fill $fill then $target")
            playSection(fill, thenPlay = target)
        } else {
            playSection(target)
        }
    }

    fun selectSection(target: ArrangerSection) {
        ensureSequencer()
        _state.update { it.copy(currentSection = target) }
        if (_state.value.isPlaying) playSection(target)
    }

    fun setTempo(bpm: Int) {
        ensureSequencer()
        val clamped = bpm.coerceIn(20, 280)
        sequencer.tempoBpm = clamped
        _state.update { it.copy(tempoBpm = clamped) }
    }

    private fun playSection(section: ArrangerSection, thenPlay: ArrangerSection? = null) {
        ensureSequencer()
        val style = loadedStyle ?: return
        val model = style.sections[section.styleName]
        if (model == null) {
            Timber.w("Style has no ${section.styleName}")
            return
        }
        sequencer.play(model, style.ppq)
    }

    private fun sectionExists(section: ArrangerSection): Boolean =
        loadedStyle?.sections?.containsKey(section.styleName) == true

    private fun fillFor(target: ArrangerSection): ArrangerSection? = when (target) {
        ArrangerSection.MainA -> ArrangerSection.FillAA
        ArrangerSection.MainB -> ArrangerSection.FillBB
        ArrangerSection.MainC -> ArrangerSection.FillCC
        ArrangerSection.MainD -> ArrangerSection.FillDD
        else -> null
    }
}

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun DetectedChord.label(): String {
    val rootName = NOTE_NAMES[rootNote]
    return if (bassNote != rootNote) "$rootName/${NOTE_NAMES[bassNote]}" else rootName
}
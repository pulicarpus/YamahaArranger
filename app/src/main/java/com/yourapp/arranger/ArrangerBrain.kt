package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.ParsedStyle
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
    private val chordDetector: ChordDetector
) {
    private lateinit var sequencer: StyleSequencer
    private var loadedStyle: ParsedStyle? = null
    private var externalScope: CoroutineScope? = null

    private val _state = MutableStateFlow(ArrangerState())
    val state: StateFlow<ArrangerState> = _state.asStateFlow()

    // ═════════════════════════════════════════════════════
    // INIT — dipanggil dari MainViewModel
    // ═════════════════════════════════════════════════════
    fun attachScope(scope: CoroutineScope) {
        externalScope = scope
        sequencer = StyleSequencer(audioEngine, scope)
        Timber.i("ArrangerBrain: scope attached, sequencer initialized")
    }

    /** Safe check: buat sequencer kalau belum ada (fallback). */
    private fun ensureSequencer() {
        if (!::sequencer.isInitialized) {
            Timber.w("Sequencer not initialized — creating fallback")
            val scope = externalScope ?: CoroutineScope(
                Dispatchers.Main + SupervisorJob()
            )
            sequencer = StyleSequencer(audioEngine, scope)
        }
    }

    // ═════════════════════════════════════════════════════
    // STYLE LOADING
    // ═════════════════════════════════════════════════════
    fun loadStyle(style: ParsedStyle) {
        loadedStyle = style
        ensureSequencer()
        // TODO Sprint B: baca defaultTempoBpm dari ParsedStyle kalau field ada
        val defaultBpm = 120
        sequencer.tempoBpm = defaultBpm
        _state.update { it.copy(tempoBpm = defaultBpm) }
        Timber.i("Style loaded: ${style.fileName} (ppq=${style.ppq}, sections=${style.sections.size})")
    }

    // ═════════════════════════════════════════════════════
    // KEYBOARD EVENTS (dari E343 atau virtual keyboard)
    // ═════════════════════════════════════════════════════
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

    // ═════════════════════════════════════════════════════
    // TRANSPORT (Start / Stop)
    // ═════════════════════════════════════════════════════
    fun startStop() {
        ensureSequencer()
        val style = loadedStyle
        if (style == null) {
            Timber.w("startStop() called with no style loaded")
            return
        }
        if (_state.value.isPlaying) {
            sequencer.stop()
            _state.update { it.copy(isPlaying = false) }
        } else {
            playSection(_state.value.currentSection)
            _state.update { it.copy(isPlaying = true) }
        }
    }

    // ═════════════════════════════════════════════════════
    // SECTION SELECTION
    // ═════════════════════════════════════════════════════
    fun selectMainVariation(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        val previous = _state.value.currentSection
        _state.update { it.copy(currentSection = target) }

        if (!wasPlaying) return

        val fill = fillFor(target)
        if (previous != target && fill != null && sectionExists(fill)) {
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
            Timber.w("Style has no ${section.styleName} section, ignoring")
            return
        }
        sequencer.play(model, style.ppq)
        if (thenPlay != null) {
            Timber.d("TODO: chain to ${thenPlay.styleName} after this fill completes")
        }
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
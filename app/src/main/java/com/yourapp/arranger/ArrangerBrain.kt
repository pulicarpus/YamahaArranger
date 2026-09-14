package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.ParsedStyle
import kotlinx.coroutines.CoroutineScope
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

/**
 * MODUL 5 from the spec: ties ChordDetector + StyleSequencer + section
 * management together. This is the piece that turns "a parsed style file"
 * and "chords the user is holding" into actual auto-accompaniment.
 *
 * Auto-fill behaviour: switching Main A<->B<->C<->D plays that target
 * variation's Fill (FillAA/BB/CC/DD) for one bar first, then falls
 * through to the Main itself — mirrors how real Yamaha arrangers behave
 * ("press Main B mid-song -> hear a fill, then Main B kicks in").
 */
@Singleton
class ArrangerBrain @Inject constructor(
    private val audioEngine: AudioEngineManager,
    private val chordDetector: ChordDetector
) {
    private lateinit var sequencer: StyleSequencer
    private var loadedStyle: ParsedStyle? = null

    private val _state = MutableStateFlow(ArrangerState())
    val state: StateFlow<ArrangerState> = _state.asStateFlow()

    fun attachScope(scope: CoroutineScope) {
        sequencer = StyleSequencer(audioEngine, scope)
    }

    fun loadStyle(style: ParsedStyle) {
        loadedStyle = style
        sequencer.tempoBpm = style.defaultTempoBpm
        _state.update { it.copy(tempoBpm = style.defaultTempoBpm) }
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
        sequencer.currentChord = chord
        _state.update { it.copy(currentChordLabel = chord.label()) }
    }

    fun startStop() {
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

    /** Called when the user taps a Main A-D button. If a different Main
     * variation is already playing, queues that variation's Fill first. */
    fun selectMainVariation(target: ArrangerSection) {
        val wasPlaying = _state.value.isPlaying
        val previous = _state.value.currentSection
        _state.update { it.copy(currentSection = target) }

        if (!wasPlaying) return // just remember the selection for next Start

        val fill = fillFor(target)
        if (previous != target && fill != null && sectionExists(fill)) {
            playSection(fill, thenPlay = target)
        } else {
            playSection(target)
        }
    }

    fun selectSection(target: ArrangerSection) {
        _state.update { it.copy(currentSection = target) }
        if (_state.value.isPlaying) playSection(target)
    }

    fun setTempo(bpm: Int) {
        val clamped = bpm.coerceIn(20, 280)
        sequencer.tempoBpm = clamped
        _state.update { it.copy(tempoBpm = clamped) }
    }

    private fun playSection(section: ArrangerSection, thenPlay: ArrangerSection? = null) {
        val style = loadedStyle ?: return
        val model = style.sections[section.styleName]
        if (model == null) {
            Timber.w("Style has no ${section.styleName} section, ignoring")
            return
        }
        sequencer.play(model, style.ppq)
        // TODO(Phase 2b): `thenPlay` (auto-fill-then-main) needs the
        // sequencer to report "loop finished" so ArrangerBrain can chain
        // to the next section instead of relying on the fill's own length —
        // wire that once StyleSequencer exposes a completion callback/Flow.
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

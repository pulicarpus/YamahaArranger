package com.yourapp.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.ParsedStyle
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

    // UI currently exposes Split: C4. Notes below C4 are arranger/chord input;
    // notes at/above C4 are the live right-hand voice. This prevents the
    // chord hand from being doubled by the GrandPiano preview voice.
    private val splitNote = 60

    private val _state = MutableStateFlow(ArrangerState())
    val state: StateFlow<ArrangerState> = _state.asStateFlow()

    private val mainVariations = setOf(
        ArrangerSection.MainA, ArrangerSection.MainB,
        ArrangerSection.MainC, ArrangerSection.MainD
    )
    private val fillVariations = setOf(
        ArrangerSection.FillAA, ArrangerSection.FillBB,
        ArrangerSection.FillCC, ArrangerSection.FillDD
    )

    fun attachScope(scope: CoroutineScope) {
        externalScope = scope
        if (::sequencer.isInitialized) sequencer.stop()
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
        Timber.i("Style loaded: ${style.fileName}, voices=${style.voiceMap.size}")
    }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) {
        if (midiNote >= splitNote) audioEngine.noteOn(midiNote, velocity)
        chordDetector.noteOn(midiNote)?.let(::onChordChanged)
    }

    fun onKeyboardNoteOff(midiNote: Int) {
        if (midiNote >= splitNote) audioEngine.noteOff(midiNote)
        val chord = chordDetector.noteOff(midiNote)
        if (chord != null) {
            onChordChanged(chord)
        } else {
            // Do not leave the sequencer holding the last chord forever.
            // Yamaha-style accompaniment needs an explicit "no chord" state
            // after the last chord-zone key is released; otherwise the next
            // generated note can still be converted against the stale chord.
            ensureSequencer()
            sequencer.currentChord = null
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
        val style = loadedStyle
        if (style == null) {
            Timber.w("startStop with no style loaded")
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

    fun updateLockedChannels(lockedChannels: Set<Int>) {
        ensureSequencer()
        sequencer.setLockedChannels(lockedChannels)
    }

    private fun playSection(section: ArrangerSection, thenPlay: ArrangerSection? = null) {
        ensureSequencer()
        val style = loadedStyle ?: return
        val model = style.sections[section.styleName]
        if (model == null) {
            Timber.w("Style has no ${section.styleName} section, ignoring")
            return
        }

        if (thenPlay != null) {
            sequencer.play(model, style.ppq, loopLimit = 1) {
                DebugLog.add("🎼 Fill selesai, lanjut ke ${thenPlay.styleName}")
                playSection(thenPlay)
                _state.update { it.copy(currentSection = thenPlay) }
            }
        } else {
            sequencer.play(model, style.ppq)
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

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
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

    // PSR-E343 default split point is F#2 (MIDI 54). Keys at or below it are
    // the ACMP/chord area; keys above it are the right-hand performance area.
    private var splitNote = 54

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
        if (midiNote > splitNote) {
            audioEngine.noteOn(midiNote, velocity)
            return
        }
        // ACMP area: do not send these chord-control notes directly to the
        // keyboard sound engine; they only determine the accompaniment chord.
        chordDetector.noteOn(midiNote)?.let(::onChordChanged)
    }

    fun onKeyboardNoteOff(midiNote: Int) {
        if (midiNote > splitNote) {
            audioEngine.noteOff(midiNote)
            return
        }
        val chord = chordDetector.noteOff(midiNote)
        if (chord != null) {
            onChordChanged(chord)
        } else {
            // Releasing the last chord key is NOT the same as Synchro Stop.
            // Keep the last detected chord active until a new chord arrives.
            DebugLog.add("🎹 Chord release: keep last chord")
        }
    }

    /** E343-compatible default split point, exposed for future UI control. */
    fun setSplitPoint(midiNote: Int) {
        splitNote = midiNote.coerceIn(24, 96)
        chordDetector.reset()
        _state.update { it.copy(currentChordLabel = "") }
        DebugLog.add("🎹 Split Point: $splitNote")
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

private val NOTE_NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

private fun DetectedChord.label(): String {
    if (displayName.isNotBlank()) return displayName
    val rootName = NOTE_NAMES[rootNote]
    val qualityName = when (quality) {
        ChordQuality.MAJOR -> ""
        ChordQuality.MINOR -> "m"
        ChordQuality.SUS4 -> "sus4"
        ChordQuality.SUS2 -> "sus2"
        ChordQuality.DOM7 -> "7"
        ChordQuality.MIN7 -> "m7"
        ChordQuality.MAJ7 -> "M7"
        ChordQuality.SIX -> "6"
        ChordQuality.MIN6 -> "m6"
        ChordQuality.DIM -> "dim"
        ChordQuality.DIM7 -> "dim7"
        ChordQuality.AUG -> "aug"
        ChordQuality.MIN7_FLAT5 -> "m7b5"
        ChordQuality.DOM7_FLAT5 -> "7b5"
        ChordQuality.SIX9 -> "6(9)"
        ChordQuality.ADD9 -> "add9"
        ChordQuality.DOM7_SUS4 -> "7sus4"
        ChordQuality.MIN7_11 -> "m7(11)"
        ChordQuality.POWER5 -> "5"
    }
    return if (bassNote != rootNote) "$rootName$qualityName/${NOTE_NAMES[bassNote]}" else "$rootName$qualityName"
}

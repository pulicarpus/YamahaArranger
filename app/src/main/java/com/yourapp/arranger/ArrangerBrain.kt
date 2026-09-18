package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val currentChordLabel: String = "",
    val autoFill: Boolean = true
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
    private var appliedChord: DetectedChord? = null
    private var pendingChord: DetectedChord? = null
    private var pendingChordJob: Job? = null
    private var pendingTransitionJob: Job? = null
    private var transportStartedAtNanos: Long = 0L
    // Monotonic anchor for the currently playing style section. Section changes
    // are quantized from the actual section start, not from app Start/Stop time.
    private var sectionStartedAtNanos: Long = 0L
    private var activeSection: ArrangerSection = ArrangerSection.MainA

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
        appliedChord = null
        _state.update { it.copy(currentChordLabel = "") }
        DebugLog.add("🎹 Split Point: $splitNote")
    }

    private fun onChordChanged(chord: DetectedChord) {
        // Never retrigger the same chord, including Note-Off callbacks.
        if (appliedChord?.sameChordAs(chord) == true) return
        if (pendingChord?.sameChordAs(chord) == true) return
        ensureSequencer()
        if (!_state.value.isPlaying) {
            applyChordNow(chord)
            return
        }
        // Chord recognition is already serialized by the MIDI callback. Apply it
        // immediately so the E343 never waits for a beat/grid before retargeting.
        pendingChordJob?.cancel()
        pendingChord = null
        applyChordNow(chord)
    }

    private fun applyChordNow(chord: DetectedChord) {
        ensureSequencer()
        pendingChord = null
        appliedChord = chord
        sequencer.currentChord = chord
        _state.update { it.copy(currentChordLabel = chord.label()) }
        DebugLog.add("🎼 ACMP chord → ${chord.label()}")
    }

    fun startStop() {
        ensureSequencer()
        val style = loadedStyle
        if (style == null) {
            Timber.w("startStop with no style loaded")
            return
        }
        if (_state.value.isPlaying) {
            pendingTransitionJob?.cancel()
            pendingChordJob?.cancel()
            pendingChord = null
            sequencer.stop()
            transportStartedAtNanos = 0L
            sectionStartedAtNanos = 0L
            _state.update { it.copy(isPlaying = false) }
        } else {
            pendingTransitionJob?.cancel()
            pendingChordJob?.cancel()
            pendingChord = null
            transportStartedAtNanos = System.nanoTime()
            activeSection = _state.value.currentSection
            playSection(activeSection)
            _state.update { it.copy(isPlaying = true) }
        }
    }

    fun selectMainVariation(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        val previous = activeSection
        _state.update { it.copy(currentSection = target) }
        if (!wasPlaying) return
        val previousWasMain = previous in mainVariations
        val fill = fillFor(target)
        if (_state.value.autoFill && previousWasMain && previous != target && fill != null && sectionExists(fill)) {
            DebugLog.add("🎼 Main→Main: queue fill $fill then $target at next bar")
            scheduleSectionChange(fill, thenPlay = target)
        } else {
            scheduleSectionChange(target)
        }
    }

    fun selectSection(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        val previous = activeSection
        _state.update { it.copy(currentSection = target) }
        if (!wasPlaying) return

        when {
            target in setOf(ArrangerSection.IntroA, ArrangerSection.IntroB, ArrangerSection.IntroC) -> {
                val returnMain = previous.takeIf { it in mainVariations } ?: ArrangerSection.MainA
                DebugLog.add("🎼 INTRO $target: one-shot → $returnMain (next bar)")
                scheduleSectionChange(target, thenPlay = returnMain)
            }
            target in setOf(ArrangerSection.EndingA, ArrangerSection.EndingB, ArrangerSection.EndingC) -> {
                DebugLog.add("🎼 ENDING $target: one-shot → STOP (next bar)")
                scheduleSectionChange(target, thenStop = true)
            }
            target in fillVariations -> {
                val returnMain = previous.takeIf { it in mainVariations } ?: ArrangerSection.MainA
                DebugLog.add("🎼 FILL $target: one-shot → $returnMain (next bar)")
                scheduleSectionChange(target, thenPlay = returnMain)
            }
            else -> scheduleSectionChange(target)
        }
    }

    private fun scheduleSectionChange(
        section: ArrangerSection,
        thenPlay: ArrangerSection? = null,
        thenStop: Boolean = false
    ) {
        pendingTransitionJob?.cancel()
        val waitMs = delayToNextBar()
        DebugLog.add("⏱ SECTION QUANTIZE ${section.styleName} to next bar in ${waitMs}ms")
        val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
        pendingTransitionJob = scope.launch {
            if (waitMs > 0) delay(waitMs)
            if (!_state.value.isPlaying) return@launch
            pendingTransitionJob = null
            playSection(section, thenPlay, thenStop)
        }
    }

    /**
     * Quantize Main/Intro/Ending/Fill transitions to the next musical bar.
     *
     * The old implementation measured from Start/Stop. That allowed the
     * arranger clock and the actual StyleSequencer loop to drift apart, so a
     * button pressed mid-bar could restart a section slightly early/late.
     * Anchor the phase to the moment the current section is started instead.
     * Yamaha styles in this phase are 4/4, so one bar = four quarter notes.
     */
    private fun delayToNextBar(): Long {
        val anchor = sectionStartedAtNanos
        if (anchor == 0L) return 0L
        val bpm = _state.value.tempoBpm.coerceIn(20, 280)
        val barMs = (4.0 * 60_000.0 / bpm).toLong().coerceAtLeast(1L)
        val elapsedMs = (System.nanoTime() - anchor) / 1_000_000L
        val remainder = elapsedMs % barMs
        val wait = if (remainder == 0L) 0L else barMs - remainder
        // Do not introduce a whole-bar wait because of scheduler jitter right
        // on the boundary.
        return if (wait <= 25L) 0L else wait
    }

    fun setAutoFill(enabled: Boolean) {
        _state.update { it.copy(autoFill = enabled) }
        DebugLog.add(if (enabled) "🎼 AUTO FILL: ON" else "🎼 AUTO FILL: OFF")
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

    fun setStyleChannelOverride(channel: Int, override: StyleChannelOverride) {
        ensureSequencer()
        sequencer.setChannelOverride(channel, override)
    }

    fun setStyleChannelVolume(channel: Int, volume: Int) {
        ensureSequencer()
        sequencer.setChannelVolume(channel, volume)
    }

    fun setStyleChannelMute(channel: Int, muted: Boolean) {
        ensureSequencer()
        sequencer.setChannelMute(channel, muted)
    }

    fun setStyleChannelProgram(channel: Int, program: Int, bank: Int) {
        ensureSequencer()
        sequencer.setChannelProgramOverride(channel, program, bank)
    }

    private fun playSection(section: ArrangerSection, thenPlay: ArrangerSection? = null, thenStop: Boolean = false) {
        ensureSequencer()
        activeSection = section
        val style = loadedStyle ?: return
        val model = style.sections[section.styleName]
        if (model == null) {
            Timber.w("Style has no ${section.styleName} section, ignoring")
            return
        }
        // This is the phase anchor used by delayToNextBar(). Set it immediately
        // before handing control to the sequencer so the next requested change
        // lands on a real bar boundary instead of an app-time boundary.
        sectionStartedAtNanos = System.nanoTime()
        if (thenPlay != null || thenStop) {
            sequencer.play(model, style.ppq, loopLimit = 1) {
                when {
                    thenPlay != null -> {
                        DebugLog.add("🎼 ${section.styleName} selesai → ${thenPlay.styleName}")
                        playSection(thenPlay)
                        _state.update { it.copy(currentSection = thenPlay, isPlaying = true) }
                    }
                    thenStop -> {
                        DebugLog.add("🎼 ${section.styleName} selesai → STOP")
                        sequencer.stop()
                        _state.update { it.copy(isPlaying = false) }
                    }
                }
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

private fun DetectedChord.sameChordAs(other: DetectedChord): Boolean =
    rootNote == other.rootNote && bassNote == other.bassNote && quality == other.quality

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

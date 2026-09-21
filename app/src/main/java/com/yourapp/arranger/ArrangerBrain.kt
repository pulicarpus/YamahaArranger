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
    FillAA("FillAA"), FillAB("FillAB"), FillAC("FillAC"), FillAD("FillAD"),
    FillBA("FillBA"), FillBB("FillBB"), FillBC("FillBC"), FillBD("FillBD"),
    FillCA("FillCA"), FillCB("FillCB"), FillCC("FillCC"), FillCD("FillCD"),
    FillDA("FillDA"), FillDB("FillDB"), FillDC("FillDC"), FillDD("FillDD"),
    EndingA("EndingA"), EndingB("EndingB"), EndingC("EndingC")
}

data class ArrangerState(
    val isPlaying: Boolean = false,
    val currentSection: ArrangerSection = ArrangerSection.MainA,
    val tempoBpm: Int = 120,
    val currentChordLabel: String = "",
    val autoFill: Boolean = true,
    val acmpEnabled: Boolean = true,
    val leftVoiceEnabled: Boolean = true
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

    // Yamaha-style upper keyboard layers: RIGHT 1/2/3 use synth channels 0/1/2.
    // RIGHT 1 is on by default; RIGHT 2 and RIGHT 3 are independently switchable.
    private val rightVoiceEnabled = booleanArrayOf(true, false, false)
    private var acmpEnabled = true
    private var leftVoiceEnabled = true
    private val leftVoiceChannel = 3

    private var appliedChord: DetectedChord? = null
    private var pendingChord: DetectedChord? = null
    private var pendingChordJob: Job? = null
    private var pendingTransitionJob: Job? = null
    // MIDI keyboards commonly deliver the fingers of one chord a few
    // milliseconds apart. Settle the note-on burst before retargeting CASM.
    private val chordSettleMs = 15L
    // Monotonic anchor for the currently playing style section. Section changes
    // are quantized from the actual section start, not from app Start/Stop time.
    private var activeSection: ArrangerSection = ArrangerSection.MainA

    private val _state = MutableStateFlow(ArrangerState())
    val state: StateFlow<ArrangerState> = _state.asStateFlow()

    private val mainVariations = setOf(
        ArrangerSection.MainA, ArrangerSection.MainB,
        ArrangerSection.MainC, ArrangerSection.MainD
    )
    private val fillVariations = setOf(
        ArrangerSection.FillAA, ArrangerSection.FillAB, ArrangerSection.FillAC, ArrangerSection.FillAD,
        ArrangerSection.FillBA, ArrangerSection.FillBB, ArrangerSection.FillBC, ArrangerSection.FillBD,
        ArrangerSection.FillCA, ArrangerSection.FillCB, ArrangerSection.FillCC, ArrangerSection.FillCD,
        ArrangerSection.FillDA, ArrangerSection.FillDB, ArrangerSection.FillDC, ArrangerSection.FillDD
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
        val velocity127 = (velocity * 127f).toInt().coerceIn(0, 127)
        if (midiNote > splitNote) {
            DebugLog.add("🎹 RIGHT IN note=$midiNote vel=$velocity127 → R1/R2/R3")
            for (channel in 0..2) {
                if (!rightVoiceEnabled[channel]) continue
                // RIGHT 1 deliberately keeps the exact legacy channel-0 audio path that the
                // on-screen keyboard already uses successfully. RIGHT 2/3 use
                // their dedicated FluidSynth channels.
                if (channel == 0) audioEngine.noteOn(midiNote, velocity)
                else audioEngine.noteOnChannel(channel, midiNote, velocity)
                // Mirror the upper-keyboard note to the external E343 when MIDI OUT is enabled.
                // Keep the internal SF2 path above so the app can still audition the RIGHT layer.
                midiInputManager.sendNoteOn(channel, midiNote, velocity127)
            }
            return
        }
        if (acmpEnabled) {
            DebugLog.add("🎹 LEFT IN note=$midiNote vel=$velocity127 → CHORD")
            chordDetector.noteOn(midiNote)?.let(::onChordChanged)
        } else if (leftVoiceEnabled) {
            DebugLog.add("🎹 LEFT IN note=$midiNote vel=$velocity127 → LEFT VOICE")
            audioEngine.noteOnChannel(leftVoiceChannel, midiNote, velocity)
            midiInputManager.sendNoteOn(leftVoiceChannel, midiNote, velocity127)
        } else {
            DebugLog.add("🎹 LEFT IN note=$midiNote vel=$velocity127 → R1/R2/R3 (LEFT OFF)")
            for (channel in 0..2) {
                if (!rightVoiceEnabled[channel]) continue
                if (channel == 0) audioEngine.noteOn(midiNote, velocity)
                else audioEngine.noteOnChannel(channel, midiNote, velocity)
                midiInputManager.sendNoteOn(channel, midiNote, velocity127)
            }
        }
    }

    fun onKeyboardNoteOff(midiNote: Int) {
        if (midiNote > splitNote) {
            DebugLog.add("🎹 RIGHT OFF note=$midiNote → R1/R2/R3 OFF")
            // Send NoteOff to all three channels so a layer switched OFF while
            // a key is held cannot leave a hanging note in FluidSynth.
            for (channel in 0..2) {
                if (channel == 0) audioEngine.noteOff(midiNote)
                else audioEngine.noteOffChannel(channel, midiNote)
                // Always release the corresponding external E343 channel too.
                midiInputManager.sendNoteOff(channel, midiNote)
            }
            return
        }
        if (acmpEnabled) {
            DebugLog.add("🎹 LEFT OFF note=$midiNote → CHORD")
            val chord = chordDetector.noteOff(midiNote)
            if (chord != null) onChordChanged(chord)
            else DebugLog.add("🎹 Chord release: keep last chord")
        } else if (leftVoiceEnabled) {
            DebugLog.add("🎹 LEFT OFF note=$midiNote → LEFT VOICE OFF")
            audioEngine.noteOffChannel(leftVoiceChannel, midiNote)
            midiInputManager.sendNoteOff(leftVoiceChannel, midiNote)
        } else {
            DebugLog.add("🎹 LEFT OFF note=$midiNote → R1/R2/R3 OFF (LEFT OFF)")
            for (channel in 0..2) {
                if (!rightVoiceEnabled[channel]) continue
                if (channel == 0) audioEngine.noteOff(midiNote)
                else audioEngine.noteOffChannel(channel, midiNote)
                midiInputManager.sendNoteOff(channel, midiNote)
            }
        }
    }

    fun setAcmpEnabled(enabled: Boolean) {
        acmpEnabled = enabled
        pendingChordJob?.cancel()
        pendingChord = null
        if (!enabled) {
            chordDetector.reset()
            appliedChord = null
            _state.update { it.copy(currentChordLabel = "", acmpEnabled = false) }
        } else _state.update { it.copy(acmpEnabled = true) }
        DebugLog.add(if (enabled) "🎹 ACMP: ON (LEFT = CHORD)" else "🎹 ACMP: OFF")
    }

    fun setLeftVoiceEnabled(enabled: Boolean) {
        leftVoiceEnabled = enabled
        _state.update { it.copy(leftVoiceEnabled = enabled) }
        DebugLog.add(if (enabled) "🎹 LEFT VOICE: ON" else "🎹 LEFT VOICE: OFF (LEFT → RIGHT 1/2/3)")
    }

    fun toggleAcmp() = setAcmpEnabled(!acmpEnabled)
    fun toggleLeftVoice() = setLeftVoiceEnabled(!leftVoiceEnabled)

    fun setRightVoiceEnabled(layer: Int, enabled: Boolean) {
        if (layer !in 0..2) return
        rightVoiceEnabled[layer] = enabled
        DebugLog.add("🎹 RIGHT " + (layer + 1) + ": " + if (enabled) "ON" else "OFF")
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

        // E343 sends the fingers of one chord as separate MIDI NoteOn messages.
        // Coalesce that short burst so CASM/RTR is retargeted only once.
        pendingChordJob?.cancel()
        pendingChord = chord
        DebugLog.add("🎹 CHORD SETTLE " + chord.label() + " (" + chordSettleMs + "ms)")
        val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
        pendingChordJob = scope.launch {
            delay(chordSettleMs)
            val settled = pendingChord
            pendingChord = null
            pendingChordJob = null
            if (settled != null && appliedChord?.sameChordAs(settled) != true) {
                applyChordNow(settled)
            }
        }
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
            _state.update { it.copy(isPlaying = false) }
        } else {
            pendingTransitionJob?.cancel()
            pendingChordJob?.cancel()
            pendingChord = null
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
        val fill = fillForTransition(previous, target)
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
        val style = loadedStyle
        val waitMs = if (style != null) sequencer.millisToNextBar(style.ppq, style.beatsPerBar) else 0L
        DebugLog.add("⏱ SECTION QUANTIZE ${section.styleName} to next bar in ${waitMs}ms")
        val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
        pendingTransitionJob = scope.launch {
            if (waitMs > 0) delay(waitMs)
            if (!_state.value.isPlaying) return@launch
            pendingTransitionJob = null
            playSection(section, thenPlay, thenStop)
        }
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

    fun setStyleChannelMixer(
        channel: Int,
        volume: Int,
        pan: Int,
        expression: Int,
        reverbSend: Int,
        chorusSend: Int
    ) {
        ensureSequencer()
        sequencer.setChannelMixer(channel, volume, pan, expression, reverbSend, chorusSend)
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
        if (thenPlay != null || thenStop) {
            sequencer.playSeamless(model, style.ppq, loopLimit = 1) {
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
            if (_state.value.isPlaying) sequencer.playSeamless(model, style.ppq) else sequencer.play(model, style.ppq)
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

    /** Prefer a true directional Yamaha fill when the style contains one; otherwise use the target fill present in the file. */
    private fun fillForTransition(from: ArrangerSection, to: ArrangerSection): ArrangerSection? {
        if (from !in mainVariations || to !in mainVariations) return fillFor(to)
        val name = "Fill" + from.styleName.removePrefix("Main") + to.styleName.removePrefix("Main")
        val directional = ArrangerSection.values().firstOrNull { it.styleName == name }
        if (directional != null && sectionExists(directional)) return directional
        return fillFor(to)?.takeIf { sectionExists(it) }
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

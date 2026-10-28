package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.RightVelocityMonitor
import com.yourapp.yamahaarranger.audio.VirtualKeyboardSink
import com.yourapp.yamahaarranger.audio.VirtualRightVoices
import com.yourapp.yamahaarranger.audio.RightVelocityTest
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.chord.transposedBy
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val leftVoiceEnabled: Boolean = true,
    /** Transpose that RIGHT/LEFT/ACMP currently use. */
    val activeTranspose: Int = 0,
    /** Requested transpose waiting for the next ACMP chord onset (Style running + ACMP ON); null when nothing is pending. */
    val pendingTranspose: Int? = null,
    /** RIGHT 1/2/3 octave shift (-1/0/+1). */
    val rightOctaves: List<Int> = listOf(0, 0, 0)
)

@Singleton
class ArrangerBrain @Inject constructor(
    private val audioEngine: AudioEngineManager,
    private val chordDetector: ChordDetector,
    private val midiInputManager: MidiInputManager
) {
    private lateinit var sequencer: StyleSequencer
    @Volatile private var loadedStyle: ParsedStyle? = null
    private var externalScope: CoroutineScope? = null

    // PSR-E343 default split point is F#2 (MIDI 54). Keys at or below it are
    // the ACMP/chord area; keys above it are the right-hand performance area.
    private var splitNote = 54

    // Yamaha-style upper keyboard layers: RIGHT 1/2/3 use synth channels 0/1/2.
    // RIGHT 1 is on by default; RIGHT 2 and RIGHT 3 are independently switchable.
    private val rightVoiceEnabled = booleanArrayOf(true, false, false)
    // Serialize keyboard UI changes and MIDI callbacks; never used by style/render.
    private val keyboardLock = Any()
    private val rightNoteOwners = mutableMapOf<Int, MutableMap<Int, Int>>()
    private var acmpEnabled = true
    private var leftVoiceEnabled = true
    private val leftVoiceChannel = 3
    // Master Transpose is three values, never one:
    //  requested = what the user dialled in (also what Registration stores);
    //  active    = what RIGHT/LEFT NOTE_ON and the ACMP chord target use right now;
    //  pending   = requested value that must wait for the next chord onset because Style harmony is sounding.
    @Volatile private var requestedTranspose = 0
    @Volatile private var activeTranspose = 0
    private var pendingTranspose: Int? = null
    // RIGHT 1/2/3 octave (-1/0/+1), applied only to the upper-keyboard output pitch.
    private val rightOctave = intArrayOf(0, 0, 0)
    // Per-key ownership of the lower (ACMP/LEFT) area: a toggle while a key is held can never orphan its NOTE_OFF.
    private val chordHeld = mutableSetOf<Int>()
    private val leftOwners = mutableMapOf<Int, Int>()
    // The raw chord the player last played (display + re-derivation when transpose commits without a new chord).
    private var lastRawChord: DetectedChord? = null
    private var keyboardSustain = false
    private var keyboardReleaseTime = 64
    // Kotlin-side safety ledger: panel SUSTAIN delays keyboard NOTE_OFF for
    // RIGHT 1/2/3 and LEFT. ACMP/chord notes are excluded so arranger chord
    // changes are never sustain-held.
    private val sustainHeldNotes = mutableSetOf<Pair<Int, Int>>()
    // Sustain is native for RIGHT 1/2/3; LEFT uses the ledger above.
    // Notes currently sounding through the dedicated LEFT VOICE channel.
    // Mode changes must release them even if the key-up arrives after ACMP changes.
    private val leftVoiceNotes = mutableMapOf<Int, Int>()
    private var appliedChord: DetectedChord? = null
    private var pendingChord: DetectedChord? = null
    private var pendingChordJob: Job? = null
    private var pendingTransitionJob: Job? = null
    // MIDI keyboards commonly deliver the fingers of one chord a few
    // milliseconds apart. Settle the note-on burst before retargeting CASM.
    private val chordSettleMs = 15L
    // UI/transition target and the section that is actually sounding are
    // deliberately separate. During Auto Fill, activeSection must not be used
    // as the "currently playing" section because it already points at the
    // future Main target.
    @Volatile private var activeSection: ArrangerSection = ArrangerSection.MainA
    @Volatile private var currentPlayingSection: ArrangerSection = ArrangerSection.MainA
    @Volatile private var pendingMainTarget: ArrangerSection? = null

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
        sequencer.setMasterTranspose(activeTranspose)
        sequencer.setAcmpHarmony(acmpEnabled)
        Timber.i("ArrangerBrain: scope attached")
    }

    private fun ensureSequencer() {
        if (!::sequencer.isInitialized) {
            val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
            sequencer = StyleSequencer(audioEngine, midiInputManager, scope)
            sequencer.setMasterTranspose(activeTranspose)
            sequencer.setAcmpHarmony(acmpEnabled)
        }
    }

    fun loadStyle(style: ParsedStyle) {
        loadedStyle = style
        ensureSequencer()
        sequencer.tempoBpm = style.defaultTempoBpm
        // CPV: manual voices belong to one style; the caller restores the new style's own pins.
        sequencer.clearVoicePins()
        sequencer.setVoiceMap(style.voiceMap)
        _state.update { it.copy(tempoBpm = style.defaultTempoBpm) }
        Timber.i("Style loaded: ${style.fileName}, voices=${style.voiceMap.size}")
    }

    // METRONOME: read-only views of the Style's musical clock (primitives only, so no other module needs the metronome types).
    fun clockRunning(): Boolean = ::sequencer.isInitialized && _state.value.isPlaying && sequencer.clockRunning()
    fun clockMeterNumerator(): Int = loadedStyle?.meter?.numerator ?: 4
    fun clockMeterDenominator(): Int = loadedStyle?.meter?.denominator ?: 4
    fun clockPpq(): Int = loadedStyle?.ppq ?: 480
    fun clockLoopOriginTick(): Long = if (::sequencer.isInitialized) sequencer.loopOriginTick else 0L
    /** CLOCK_MONOTONIC ns at which `tick` is due, or -1 when no clock is running. */
    fun clockTickToNanos(tick: Long): Long = if (::sequencer.isInitialized) (sequencer.tickToNanos(tick, clockPpq()) ?: -1L) else -1L
    fun clockTickNow(): Long = if (::sequencer.isInitialized) sequencer.masterTickNow(clockPpq()) else 0L
    fun clockTempoRevision(): Long = if (::sequencer.isInitialized) sequencer.clockTempoRevision() else 0L

    fun setKeyboardReleaseTime(value: Int) {
        keyboardReleaseTime = value.coerceIn(0, 127)
        DebugLog.add("🎹 RELEASE TIME = $keyboardReleaseTime")
        audioEngine.setKeyboardReleaseTime(keyboardReleaseTime)
    }

    fun setKeyboardSustain(enabled: Boolean) {
        synchronized(keyboardLock) {
            if (keyboardSustain == enabled) return
            keyboardSustain = enabled
            DebugLog.add("🎹 SUSTAIN = " + if (enabled) "ON" else "OFF")
            audioEngine.setKeyboardSustain(enabled)
            if (!enabled) flushSustainedNotes()
        }
    }

    private fun engineNoteOn(channel: Int, note: Int, velocity: Float) {
        if (channel == 0) audioEngine.noteOn(note, velocity)
        else audioEngine.noteOnChannel(channel, note, velocity)
    }

    private fun engineNoteOff(channel: Int, note: Int) {
        // A USB release must not cut a key the on-screen keyboard is still holding.
        if (virtualRight.owns(channel, note)) return
        rawNoteOff(channel, note)
    }

    private fun rawNoteOff(channel: Int, note: Int) {
        if (channel == 0) audioEngine.noteOff(note)
        else audioEngine.noteOffChannel(channel, note)
    }

    // Virtual keyboard (on-screen) RIGHT 1/2/3 path: separate ownership ledger, engine only.
    // It never reaches MIDI OUT, chord detection, style playback or the USB owner map.
    private val virtualRight = VirtualRightVoices(
        engineOn = { channel, pitch, velocity01 ->
            if (channel == 0) audioEngine.noteOn(pitch, velocity01) else audioEngine.noteOnChannel(channel, pitch, velocity01)
        },
        engineOff = { channel, pitch -> rawNoteOff(channel, pitch) },
        rightEnabled = { channel -> rightVoiceEnabled[channel] },
        externallyHeld = { channel, pitch ->
            rightNoteOwners.values.any { it[channel] == pitch } || sustainHeldNotes.contains(channel to pitch)
        }
    )

    val virtualKeyboardSink: VirtualKeyboardSink = object : VirtualKeyboardSink {
        override fun noteOn(pitch: Int, velocity127: Int) { synchronized(keyboardLock) { virtualRight.noteOn(pitch, velocity127) } }
        override fun noteOff(pitch: Int) { synchronized(keyboardLock) { virtualRight.noteOff(pitch) } }
        override fun setSustain(enabled: Boolean) { synchronized(keyboardLock) { virtualRight.setSustain(enabled) } }
        override fun releaseAll() { synchronized(keyboardLock) { virtualRight.releaseAll() } }
    }

    private fun releaseKeyboardNote(channel: Int, note: Int) {
        if (keyboardSustain) {
            sustainHeldNotes.add(channel to note)
            return
        }
        engineNoteOff(channel, note)
        midiInputManager.sendNoteOff(channel, note)
    }

    private fun stopSustainedNote(channel: Int, note: Int) {
        if (sustainHeldNotes.remove(channel to note)) {
            engineNoteOff(channel, note)
            midiInputManager.sendNoteOff(channel, note)
        }
    }

    private fun flushSustainedNotes() {
        if (sustainHeldNotes.isEmpty()) return
        val notes = sustainHeldNotes.toList()
        sustainHeldNotes.clear()
        notes.forEach { (channel, note) ->
            engineNoteOff(channel, note)
            midiInputManager.sendNoteOff(channel, note)
        }
        DebugLog.add("🎹 SUSTAIN RELEASE: ${notes.size} held note(s)")
    }

    /**
     * RIGHT 1/2/3 NOTE_ON. Each layer's pitch is sourceNote + activeTranspose + 12*octave(layer); a pitch outside 0..127 is
     * DROPPED for that layer (no clamp, no octave wrap, no illegal MIDI note). The pitch is stored in the owner map, so the
     * NOTE_OFF always pairs with the exact pitch/channel of its NOTE_ON, whatever transpose/octave/routing does afterwards.
     */
    private fun startRightNote(sourceNote: Int, velocity: Float, velocity127: Int, source: com.yourapp.yamahaarranger.audio.RightInputSource = com.yourapp.yamahaarranger.audio.RightInputSource.OTHER) {
        com.yourapp.yamahaarranger.audio.LatencyProbe.rightRoutingStart() // LATENCY_PROBE
        rightNoteOwners.remove(sourceNote)?.forEach { (channel, pitch) ->
            engineNoteOff(channel, pitch)
            midiInputManager.sendNoteOff(channel, pitch)
        }
        val owners = mutableMapOf<Int, Int>()
        rightNoteOwners[sourceNote] = owners
        com.yourapp.yamahaarranger.audio.RightInputProbe.routed(source, sourceNote, velocity127)
        // Touch Response: physical keyboard (USB) only. Original = identity. MIDI OUT below keeps the raw velocity.
        val engineVelocity127 = if (source == com.yourapp.yamahaarranger.audio.RightInputSource.USB) com.yourapp.yamahaarranger.audio.RightUsbTouch.response.map(velocity127) else velocity127
        val engineVelocity = if (engineVelocity127 == velocity127) velocity else engineVelocity127 / 127f
        val transpose = activeTranspose
        for (channel in 0..2) {
            if (!rightVoiceEnabled[channel]) continue
            val outputNote = sourceNote + transpose + rightOctave[channel] * 12
            if (outputNote !in 0..127) {
                DebugLog.add("🎹 RIGHT ${channel + 1} DROP note=$sourceNote → $outputNote (outside 0..127)")
                continue
            }
            stopSustainedNote(channel, outputNote)
            // Diagnostic: monitor the keyboard velocity; the A/B test alters ONLY the internal synth velocity.
            RightVelocityMonitor.shared.record(channel, velocity127)
            com.yourapp.yamahaarranger.audio.RightInputProbe.forwarded(source, engineVelocity127)
            engineNoteOn(channel, outputNote, RightVelocityTest.internalVelocity(engineVelocity, engineVelocity127))
            midiInputManager.sendNoteOn(channel, outputNote, RightVelocityTest.midiOutVelocity127(velocity127))
            owners[channel] = outputNote
        }
        com.yourapp.yamahaarranger.audio.LatencyProbe.rightHandedOff() // LATENCY_PROBE
    }

    private fun releaseRightNote(sourceNote: Int) {
        rightNoteOwners.remove(sourceNote)?.forEach { (channel, pitch) ->
            releaseKeyboardNote(channel, pitch)
        }
    }

    /**
     * Master Transpose request (-12..12). While Style harmony is sounding (Style running, ACMP ON, a chord applied) the value
     * only becomes PENDING and commits at the next valid chord onset ([onChordOnset]); in every other situation nothing
     * harmonic can change under the player, so it is active immediately. A later request replaces an earlier pending one.
     */
    fun setKeyboardTranspose(semitones: Int) {
        var rederive = false
        synchronized(keyboardLock) {
            val value = semitones.coerceIn(-12, 12)
            requestedTranspose = value
            when {
                value == activeTranspose -> pendingTranspose = null
                mustDeferTranspose() -> pendingTranspose = value
                else -> { commitTranspose(value, "immediate"); rederive = true }
            }
            publishTranspose()
            DebugLog.add("🎹 TRANSPOSE request=$value active=$activeTranspose pending=${pendingTranspose ?: "-"}")
        }
        if (rederive) reapplyChordForTranspose()
    }

    private fun mustDeferTranspose(): Boolean =
        ::sequencer.isInitialized && _state.value.isPlaying && acmpEnabled && appliedChord != null

    /** Commit [value] as the active transpose. Caller holds [keyboardLock]; held RIGHT/LEFT notes keep their own pitch. */
    private fun commitTranspose(value: Int, why: String) {
        activeTranspose = value
        pendingTranspose = null
        if (::sequencer.isInitialized) sequencer.setMasterTranspose(value)
        DebugLog.add("🎹 TRANSPOSE ACTIVE=$value ($why)")
    }

    private fun publishTranspose() {
        _state.update { it.copy(activeTranspose = activeTranspose, pendingTranspose = pendingTranspose) }
    }

    /** No chord onset is involved (Style stopped / immediate): re-derive the target chord once so Style and keys agree. */
    private fun reapplyChordForTranspose() {
        val raw: DetectedChord
        val eff: DetectedChord
        synchronized(keyboardLock) {
            if (!acmpEnabled) return
            raw = lastRawChord ?: return
            eff = raw.transposedBy(activeTranspose)
            if (appliedChord?.sameChordAs(eff) == true) return
            pendingChordJob?.cancel(); pendingChord = null
            appliedChord = eff
        }
        ensureSequencer()
        sequencer.currentChord = eff
    }

    fun setRightOctave(layer: Int, octave: Int) {
        synchronized(keyboardLock) {
            if (layer !in 0..2) return
            val value = octave.coerceIn(-1, 1)
            if (rightOctave[layer] == value) return
            rightOctave[layer] = value
            _state.update { it.copy(rightOctaves = rightOctave.toList()) }
            DebugLog.add("🎹 RIGHT ${layer + 1} OCTAVE = $value (held notes keep their pitch)")
        }
    }

    fun rightOctaveOf(layer: Int): Int = rightOctave[layer.coerceIn(0, 2)]

    /**
     * Lower keyboard area. ACMP and LEFT are independent: ACMP ON feeds the chord detector, LEFT ON sounds the LEFT voice,
     * both ON does both for the same key, both OFF turns the whole keyboard into RIGHT 1/2/3.
     */
    fun onKeyboardNoteOn(midiNote: Int, velocity: Float, source: com.yourapp.yamahaarranger.audio.RightInputSource = com.yourapp.yamahaarranger.audio.RightInputSource.OTHER) {
        val probeRequestedNs = System.nanoTime() // LATENCY_PROBE
        synchronized(keyboardLock) {
            com.yourapp.yamahaarranger.audio.LatencyProbe.keyboardLockAcquired(probeRequestedNs) // LATENCY_PROBE
            val velocity127 = (velocity * 127f).toInt().coerceIn(0, 127)
            if (midiNote > splitNote) {
                if (!com.yourapp.yamahaarranger.audio.NoteLogTuning.skip()) DebugLog.add("🎹 RIGHT IN note=$midiNote vel=$velocity127")
                startRightNote(midiNote, velocity, velocity127, source)
                return
            }
            if (!acmpEnabled && !leftVoiceEnabled) {
                DebugLog.add("🎹 LEFT IN note=$midiNote vel=$velocity127 → R1/R2/R3 (ACMP/L OFF)")
                startRightNote(midiNote, velocity, velocity127, source)
                return
            }
            if (acmpEnabled) {
                // ACMP/chord notes are deliberately not sustain-held.
                DebugLog.add("🎹 LEFT IN note=$midiNote vel=$velocity127 → CHORD")
                com.yourapp.yamahaarranger.audio.LatencyProbe.chordNoteParsed(); com.yourapp.yamahaarranger.audio.LatencyProbe.chordKeyDown() // LATENCY_PROBE
                chordHeld.add(midiNote)
                chordDetector.noteOn(midiNote)?.let { onChordChanged(it, onset = true) }
            }
            if (leftVoiceEnabled) startLeftNote(midiNote, velocity, velocity127)
        }
    }

    private fun startLeftNote(midiNote: Int, velocity: Float, velocity127: Int) {
        val outputNote = midiNote + activeTranspose
        if (outputNote !in 0..127) {
            DebugLog.add("🎹 LEFT DROP note=$midiNote → $outputNote (outside 0..127)")
            return
        }
        leftOwners.remove(midiNote)?.let { releaseLeftNote(it, honourSustain = false) }
        DebugLog.add("🎹 LEFT IN note=$midiNote → pitch=$outputNote vel=$velocity127 → LEFT VOICE")
        stopSustainedNote(leftVoiceChannel, outputNote)
        audioEngine.noteOnChannel(leftVoiceChannel, outputNote, velocity)
        midiInputManager.sendNoteOn(leftVoiceChannel, outputNote, velocity127)
        leftOwners[midiNote] = outputNote
        leftVoiceNotes.merge(outputNote, 1, Int::plus)
    }

    private fun releaseLeftNote(outputNote: Int, honourSustain: Boolean) {
        if (honourSustain && keyboardSustain) {
            sustainHeldNotes.add(leftVoiceChannel to outputNote)
            return
        }
        audioEngine.noteOffChannel(leftVoiceChannel, outputNote)
        midiInputManager.sendNoteOff(leftVoiceChannel, outputNote)
        val left = (leftVoiceNotes[outputNote] ?: 1) - 1
        if (left <= 0) leftVoiceNotes.remove(outputNote) else leftVoiceNotes[outputNote] = left
    }

    fun onKeyboardNoteOff(midiNote: Int) {
        synchronized(keyboardLock) {
            // NOTE_ON owns the part/pitch even after split, transpose, octave or routing changes.
            // Empty entries retain physical keys whose owners were cleared by RIGHT OFF.
            if (rightNoteOwners.containsKey(midiNote)) {
                releaseRightNote(midiNote)
                return
            }
            if (chordHeld.remove(midiNote)) {
                // ACMP owns chord release; sustain never intercepts this path.
                DebugLog.add("🎹 LEFT OFF note=$midiNote → CHORD")
                val chord = chordDetector.noteOff(midiNote)
                if (chord != null) onChordChanged(chord, onset = false)
                else DebugLog.add("🎹 Chord release: keep last chord")
            }
            leftOwners.remove(midiNote)?.let { pitch ->
                DebugLog.add("🎹 LEFT OFF note=$midiNote → pitch=$pitch → LEFT VOICE OFF" + if (keyboardSustain) " (held by sustain)" else "")
                releaseLeftNote(pitch, honourSustain = true)
            }
        }
    }

    /**
     * ACMP ON/OFF only concerns the chord detector and Style harmony; it never touches LEFT or RIGHT notes.
     * OFF: Style keeps Rhythm only (harmonic parts released through the sequencer's own note ledger), a pending transpose
     * commits (nothing harmonic is left to protect), chord context is dropped. ON: Style harmony re-enters only after the next
     * valid chord (the old chord is never reused).
     */
    fun setAcmpEnabled(enabled: Boolean) {
        var changed = false
        synchronized(keyboardLock) {
            if (acmpEnabled != enabled) {
                changed = true
                acmpEnabled = enabled
                pendingChordJob?.cancel()
                pendingChord = null
                chordDetector.reset()
                chordHeld.clear()
                appliedChord = null
                lastRawChord = null
                if (!enabled && pendingTranspose != null) commitTranspose(requestedTranspose, "ACMP OFF")
                publishTranspose()
                _state.update { it.copy(currentChordLabel = "", acmpEnabled = enabled) }
                DebugLog.add(if (enabled) "🎹 ACMP: ON (chord detection; harmony re-enters after next chord)" else "🎹 ACMP: OFF (Style = drums only)")
            }
        }
        if (changed) {
            ensureSequencer()
            sequencer.setMasterTranspose(activeTranspose)
            sequencer.setAcmpHarmony(enabled)
        }
    }

    /** LEFT ON/OFF only concerns the LEFT voice; it never changes ACMP. Turning it OFF releases its own notes only. */
    fun setLeftVoiceEnabled(enabled: Boolean) {
        synchronized(keyboardLock) {
            if (leftVoiceEnabled && !enabled) releaseLeftVoiceNotes("LEFT VOICE OFF")
            leftVoiceEnabled = enabled
            _state.update { it.copy(leftVoiceEnabled = enabled) }
            DebugLog.add(if (enabled) "🎹 LEFT VOICE: ON" else "🎹 LEFT VOICE: OFF")
        }
    }

    private fun releaseLeftVoiceNotes(reason: String) {
        sustainHeldNotes.removeAll { it.first == leftVoiceChannel }
        leftOwners.clear()
        if (leftVoiceNotes.isEmpty()) return
        val notes = leftVoiceNotes.keys.toList()
        val instances = leftVoiceNotes.toMap()
        notes.forEach { note ->
            repeat(instances[note] ?: 1) {
                audioEngine.noteOffChannel(leftVoiceChannel, note)
                midiInputManager.sendNoteOff(leftVoiceChannel, note)
            }
        }
        leftVoiceNotes.clear()
        DebugLog.add("🎹 LEFT VOICE RELEASE: $reason notes=${notes.joinToString(",")}")
    }

    fun toggleAcmp() = setAcmpEnabled(!acmpEnabled)
    fun toggleLeftVoice() = setLeftVoiceEnabled(!leftVoiceEnabled)

    fun setRightVoiceEnabled(layer: Int, enabled: Boolean) {
        synchronized(keyboardLock) {
            if (layer !in 0..2) return
            if (rightVoiceEnabled[layer] == enabled) return
            rightVoiceEnabled[layer] = enabled
            if (!enabled) {
                val owned = rightNoteOwners.values.mapNotNull { it.remove(layer) }.toSet()
                val held = sustainHeldNotes.filter { it.first == layer }.map { it.second }.toSet()
                sustainHeldNotes.removeAll { it.first == layer }
                for (pitch in owned + held) {
                    // Part OFF bypasses sustain; normal key-up still uses RELEASE.
                    engineNoteOff(layer, pitch)
                    midiInputManager.sendNoteOff(layer, pitch)
                }
                // Covers release-stage voices as well as held notes, on this part only.
                audioEngine.stopRightPart(layer)
                virtualRight.forgetPart(layer)
                midiInputManager.sendRightPartSoundOff(layer)
            }
            DebugLog.add("🎹 RIGHT " + (layer + 1) + ": " + if (enabled) "ON" else "OFF")
        }
    }

    /** E343-compatible default split point, exposed for future UI control. */
    fun setSplitPoint(midiNote: Int) {
        synchronized(keyboardLock) {
            splitNote = midiNote.coerceIn(24, 96)
            chordDetector.reset()
            chordHeld.clear()
            appliedChord = null
            lastRawChord = null
            // The chord context is dropped, so a pending transpose has nothing left to wait for.
            if (pendingTranspose != null) commitTranspose(requestedTranspose, "split change")
            publishTranspose()
            _state.update { it.copy(currentChordLabel = "") }
            DebugLog.add("🎹 Split Point: $splitNote")
        }
    }

    /**
     * Chord callback from the detector. [onset] is true only for a NOTE_ON that produced a valid recognition; NOTE_OFF
     * callbacks (which just repeat the last chord) are never onsets. A pending transpose commits here, in the same
     * transaction (under [keyboardLock]) that derives the target chord, so transpose and chord context never disagree.
     */
    private fun onChordChanged(chord: DetectedChord, onset: Boolean) {
        if (onset && pendingTranspose != null) {
            commitTranspose(pendingTranspose!!, "chord onset ${chord.label()}")
            publishTranspose()
        }
        lastRawChord = chord
        val target = chord.transposedBy(activeTranspose)
        // Never retrigger the same target chord, including Note-Off callbacks.
        if (appliedChord?.sameChordAs(target) == true) return
        if (pendingChord?.sameChordAs(target) == true) return
        ensureSequencer()

        // E343 sends the fingers of one chord as separate MIDI NoteOn messages.
        // Coalesce that short burst so CASM/RTR is retargeted only once.
        pendingChordJob?.cancel()
        pendingChord = target
        com.yourapp.yamahaarranger.audio.LatencyProbe.chordRecognized() // LATENCY_PROBE
        DebugLog.add("🎹 CHORD SETTLE " + chord.label() + (if (activeTranspose != 0) " → " + target.label() else "") + " (" + chordSettleMs + "ms)")
        val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
        pendingChordJob = scope.launch {
            delay(chordSettleMs)
            val settled = synchronized(keyboardLock) {
                val c = pendingChord
                pendingChord = null
                pendingChordJob = null
                if (c != null && acmpEnabled && appliedChord?.sameChordAs(c) != true) c else null
            }
            if (settled != null) applyChordNow(settled)
        }
    }

    private fun applyChordNow(target: DetectedChord) {
        ensureSequencer()
        val label = synchronized(keyboardLock) {
            pendingChord = null
            appliedChord = target
            (lastRawChord ?: target).label()
        }
        val probeStart = com.yourapp.yamahaarranger.audio.LatencyProbe.chordApplyStart() // LATENCY_PROBE
        sequencer.currentChord = target
        com.yourapp.yamahaarranger.audio.LatencyProbe.chordApplyEnd(probeStart) // LATENCY_PROBE
        _state.update { it.copy(currentChordLabel = label) }
        DebugLog.add("🎼 ACMP chord → ${target.label()}")
    }

    fun setDrumABMode(resolved:Boolean):Boolean {
        if(_state.value.isPlaying || pendingTransitionJob?.isActive==true || pendingChordJob?.isActive==true)return false
        ensureSequencer()
        return sequencer.setDrumABMode(resolved)
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
            // Nothing harmonic sounds any more: a pending transpose becomes active now and the chord context follows it.
            val commit = synchronized(keyboardLock) {
                val p = pendingTranspose
                if (p != null) { commitTranspose(p, "STOP"); publishTranspose() }
                p != null
            }
            if (commit) reapplyChordForTranspose()
        } else {
            audioEngine.readiness.admit(activeSectionName(), style) {
                if (_state.value.isPlaying) return@admit
                pendingTransitionJob?.cancel()
                pendingChordJob?.cancel()
                pendingChord = null
                activeSection = _state.value.currentSection
                currentPlayingSection = activeSection
                pendingMainTarget = null
                playSection(activeSection)
                _state.update { it.copy(isPlaying = true) }
            }
        }
    }

    private fun activeSectionName(): String = _state.value.currentSection.styleName

    fun selectMainVariation(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        _state.update { it.copy(currentSection = target) }
        if (!wasPlaying) return
        com.yourapp.yamahaarranger.audio.LatencyProbe.sectionRequested() // LATENCY_PROBE

        // If a Fill is already sounding, do NOT create a new transition job.
        // Keep the current Fill intact; replace only the pending tail with the
        // newest requested directional Fill + final Main target.
        if (currentPlayingSection in fillVariations && pendingMainTarget != null) {
            val fromMain = pendingMainTarget!!
            val nextFill = fillForTransition(fromMain, target)
            val style = loadedStyle
            val fillModel = nextFill?.let { style?.sections?.get(it.styleName) }
            val targetModel = style?.sections?.get(target.styleName)
            if (_state.value.autoFill && nextFill != null && fillModel != null && targetModel != null) {
                pendingMainTarget = target
                pendingTransitionJob?.cancel()
                pendingTransitionJob = null
                DebugLog.add(
                    "🎼 ACTIVE FILL RETARGET: " +
                        currentPlayingSection.styleName + " → " +
                        nextFill.styleName + " → " + target.styleName
                )
                sequencer.queueAfterCurrentSection(
                    listOf(fillModel to 1, targetModel to -1),
                    style!!.ppq
                ) {
                    currentPlayingSection = target
                    activeSection = target
                    pendingMainTarget = null
                    DebugLog.add("🎼 FINAL MAIN STARTED: ${target.styleName}")
                }
                return
            }
        }

        val previous = currentPlayingSection
        val previousWasMain = previous in mainVariations
        val fill = fillForTransition(previous, target)
        if (_state.value.autoFill && previousWasMain && previous != target && fill != null && sectionExists(fill)) {
            pendingMainTarget = target
            DebugLog.add("🎼 Main→Main: queue fill $fill then $target at next bar")
            scheduleSectionChange(fill, thenPlay = target, quantizeToNextBar = true)
        } else {
            pendingMainTarget = null
            scheduleSectionChange(target)
        }
    }

    fun selectSection(target: ArrangerSection) {
        ensureSequencer()
        val wasPlaying = _state.value.isPlaying
        val previous = activeSection
        _state.update { it.copy(currentSection = target) }
        if (!wasPlaying) return
        com.yourapp.yamahaarranger.audio.LatencyProbe.sectionRequested() // LATENCY_PROBE

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
        thenStop: Boolean = false,
        quantizeToNextBar: Boolean = true
    ) {
        pendingTransitionJob?.cancel()
        val style = loadedStyle ?: return
        val barTicks = style.ppq.toLong() * 4L * style.meter.numerator /
            style.meter.denominator.coerceAtLeast(1)
        val sounding = style.sections[currentPlayingSection.styleName]
        if (quantizeToNextBar && !_state.value.autoFill && thenPlay == null && !thenStop &&
            section in mainVariations && currentPlayingSection in mainVariations &&
            sounding != null && sounding.lengthTicks.toLong() > barTicks &&
            sequencer.queueNextBarMain(style.sections[section.styleName] ?: return,
                style.ppq, style.meter.numerator, style.meter.denominator) {
                currentPlayingSection = section
                activeSection = section
                pendingMainTarget = null
            }) return
        val tempoRevision = sequencer.clockTempoRevision()
        val targetTick = if (quantizeToNextBar) {
            sequencer.nextBarTargetTick(style.ppq, style.meter.numerator, style.meter.denominator)
        } else null
        // Preserve non-Main request policies; their Yamaha interruption
        // contracts are not established. Main admissions use the
        // target already computed by the existing musical clock/window rule.
        if (targetTick != null && currentPlayingSection in mainVariations) {
            val firstModel = style.sections[section.styleName]
            val finalModel = thenPlay?.let { style.sections[it.styleName] }
            if (firstModel != null && (thenPlay == null || finalModel != null)) {
                val sequence = if (finalModel != null) listOf(firstModel to 1, finalModel to -1)
                    else listOf(firstModel to if (thenStop) 1 else -1)
                val admitted = sequencer.queueScheduledTransition(sequence, style.ppq, targetTick,
                    onFirstSectionStarted = {
                        currentPlayingSection = section
                        activeSection = section
                        if (section in mainVariations) pendingMainTarget = null
                    },
                    onFinalSectionStarted = if (thenPlay == null) null else ({
                        currentPlayingSection = thenPlay
                        activeSection = thenPlay
                        pendingMainTarget = null
                        _state.update { it.copy(currentSection = thenPlay) }
                    }),
                    onComplete = if (!thenStop) null else ({
                        sequencer.stop()
                        _state.update { it.copy(isPlaying = false) }
                    }))
                if (admitted) { pendingTransitionJob = null; return }
            }
        }
        val waitMs = if (targetTick != null) {
            sequencer.millisToNextBar(style.ppq, style.meter.numerator, style.meter.denominator)
        } else 0L
        DebugLog.add(
            if (quantizeToNextBar)
                "⏱ SECTION QUANTIZE " + section.styleName + " to next bar in " + waitMs + "ms"
            else
                "⏱ SECTION IMMEDIATE " + section.styleName + " at current master beat"
        )
        val scope = externalScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
        pendingTransitionJob = scope.launch {
            if (targetTick != null) sequencer.awaitMusicalTick(targetTick, style.ppq, tempoRevision)
            if (!_state.value.isPlaying) return@launch
            pendingTransitionJob = null

            if (thenPlay != null) {
                val firstModel = style.sections[section.styleName]
                val targetModel = style.sections[thenPlay.styleName]
                if (firstModel != null && targetModel != null) {
                    // Auto Fill is one musical sequence on one clock:
                    // current Main continues to the next bar boundary,
                    // Fill starts on that exact tick, then target Main starts
                    // immediately after the Fill. No playback job is cancelled.
                    DebugLog.add(
                        "🎼 MASTER TRANSITION " + section.styleName + " → " + thenPlay.styleName + " (same clock)"
                    )
                    currentPlayingSection = section
                    sequencer.queueSeamlessTransition(
                        listOf(firstModel to 1, targetModel to -1),
                        style.ppq,
                        style.meter.numerator,
                        style.meter.denominator
                    ) {
                        currentPlayingSection = thenPlay
                        activeSection = thenPlay
                        pendingMainTarget = null
                        DebugLog.add("🎼 FINAL MAIN STARTED: ${thenPlay.styleName}")
                    }
                    activeSection = thenPlay
                } else {
                    DebugLog.add(
                        "⚠ Transition section missing: " + section.styleName + " / " + thenPlay.styleName
                    )
                }
            } else {
                playSection(section, null, thenStop)
            }
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

    fun setStyleBusTrim(value: Int) { ensureSequencer(); sequencer.setStyleBusTrim(value) }

    fun setStyleChannelVolume(channel: Int, volume: Int) {
        ensureSequencer()
        sequencer.setChannelVolume(channel, volume)
    }

    fun armChordDiagnostic() { if (::sequencer.isInitialized) sequencer.armChordDiagnostic() }
    fun stopChordDiagnostic() { if (::sequencer.isInitialized) sequencer.stopChordDiagnostic() }
    // Same authoritative style reference used by startStop/playSection, not a
    // ViewModel-local diagnostic cache that is lost when the UI is recreated.
    fun diagnosticActiveStyle(): ParsedStyle? = loadedStyle
    fun partPresenceReport(style:ParsedStyle):String = if (::sequencer.isInitialized) sequencer.partPresenceReport(style) else "PART PRESENCE unavailable: sequencer not initialized"
    suspend fun compactPartPresenceReport(): String {
        if (state.value.isPlaying) return "PART PRESENCE unavailable: STOP before export\n"
        val style = diagnosticActiveStyle()
            ?: return "PART PRESENCE unavailable: load a style first\n"
        return withContext(Dispatchers.Default) {
            val scheduler = partPresenceReport(style)
            val native = audioEngine.noteZoneReport().substringBefore("=== ACTUAL BASS / STRINGS NOTE ZONES ===")
            if (diagnosticActiveStyle() !== style || state.value.isPlaying)
                return@withContext "PART PRESENCE unavailable: style/playback changed during export; STOP and retry\n"
            com.yourapp.yamahaarranger.arranger.ChordReportBounds.lines(
                "PART PRESENCE: scheduler=since_style_load; native=process_lifetime; fresh app/session recommended.\nNo playback/controller writes; NOTE_ON acceptance does not prove audible PCM.",
                (native + scheduler).lines()
            )
        }
    }

    fun compactChordDiagnosticReport(): String = if (::sequencer.isInitialized) sequencer.compactChordDiagnosticReport() else "CHORD STYLE unavailable: load style first"
    fun chordDiagnosticReport(): String = if (::sequencer.isInitialized) sequencer.chordDiagnosticReport() else "CHORD STYLE unavailable: load style first"

    fun setStyleChannelMute(channel: Int, muted: Boolean) {
        ensureSequencer()
        sequencer.setChannelMute(channel, muted)
    }

    fun setStyleChannelMixer(
        channel: Int,
        volume: Int,
        pan: Int?,
        expression: Int,
        reverbSend: Int?,
        chorusSend: Int?
    ) {
        ensureSequencer()
        sequencer.setChannelMixer(channel, volume, pan, expression, reverbSend, chorusSend)
    }

    /** CPV manual voice for a style channel (null releases it). Internal sound only. */
    fun setStyleVoicePin(channel: Int, pin: com.yourapp.yamahaarranger.style.StyleVoicePin?): Boolean {
        ensureSequencer()
        return sequencer.setVoicePin(channel, pin)
    }
    /** Style Voice Editor: internal voice for one section only. */
    fun setStyleSectionVoicePin(section: String, channel: Int, pin: com.yourapp.yamahaarranger.style.StyleVoicePin?): Boolean {
        ensureSequencer()
        return sequencer.setSectionVoicePin(section, channel, pin)
    }
    /** Style Voice Editor: Voice sent to MIDI OUT for a melodic channel (section null = every section). */
    fun setStyleExternalVoice(section: String?, channel: Int, voice: com.yourapp.yamahaarranger.style.ExternalVoice?): Boolean {
        ensureSequencer()
        return sequencer.setExternalVoice(section, channel, voice)
    }
    /** Style Voice Editor audition (Style stopped only); see StyleSequencer.auditionBegin. */
    fun auditionBegin(channel: Int, pin: com.yourapp.yamahaarranger.style.StyleVoicePin?, original: Triple<Int, Int, String?>?,
        external: com.yourapp.yamahaarranger.style.ExternalVoice?): String? { ensureSequencer(); return sequencer.auditionBegin(channel, pin, original, external) }
    fun auditionNote(channel: Int, note: Int, on: Boolean) { if (::sequencer.isInitialized) sequencer.auditionNote(channel, note, on) }
    fun auditionEnd(channel: Int) { if (::sequencer.isInitialized) sequencer.auditionEnd(channel) }
    /** Style Voice Editor V2: octave shift of a melodic part (section null = every section). */
    fun setStyleOctaveShift(section: String?, channel: Int, octaves: Int?): Boolean { ensureSequencer(); return sequencer.setOctaveShift(section, channel, octaves) }
    /** Style Voice Editor V2: drum note map of Rhythm 1/2 for one output (0 internal, 1 MIDI OUT). */
    fun setStyleDrumMap(section: String?, channel: Int, output: Int, notes: Map<Int, Int>?): Boolean { ensureSequencer(); return sequencer.setDrumMap(section, channel, output, notes) }
    fun invalidateAppliedVoices() { if (::sequencer.isInitialized) sequencer.invalidateAppliedVoices() }
    fun styleVoicePin(channel: Int): com.yourapp.yamahaarranger.style.StyleVoicePin? =
        if (::sequencer.isInitialized) sequencer.voicePin(channel) else null

    fun setStyleChannelProgram(channel: Int, program: Int, bank: Int) {
        ensureSequencer()
        sequencer.setChannelProgramOverride(channel, program, bank)
    }

    private fun playSection(section: ArrangerSection, thenPlay: ArrangerSection? = null, thenStop: Boolean = false) {
        ensureSequencer()
        currentPlayingSection = section
        activeSection = section
        if (section in mainVariations) pendingMainTarget = null
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


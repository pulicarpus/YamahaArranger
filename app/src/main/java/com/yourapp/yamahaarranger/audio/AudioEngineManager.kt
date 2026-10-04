package com.yourapp.yamahaarranger.audio

import com.yourapp.yamahaarranger.ui.DebugLog
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

@Singleton
class AudioEngineManager @Inject constructor(
    private val bridge: NativeAudioBridge,
    private val sampleProvider: SampleProvider
) {
    private var started = false
    private var soundFontLoaded = false
    private var nextSoundFontRole = 0 // 0=melody, 1=drum
    // Serialize SF2 operations: startup auto-load and Import must never race
    // while stopping/restarting the native Oboe stream.
    private val soundFontOperationMutex = Mutex()

    fun start() {
        if (started) return
        DebugLog.add("🎵 AudioEngine.start()…")
        bridge.nativeInitLogger()
        DebugLog.add("📋 Native logger initialized")
        started = bridge.nativeStart()
        DebugLog.traceAudio("START result=$started sf2Loaded=$soundFontLoaded")
        DebugLog.add(if (started) "✅ AudioEngine OK" else "❌ AudioEngine FAILED")
    }

    fun stop() {
        if (!started) return
        DebugLog.traceAudio("STOP begin")
        bridge.nativeStop()
        started = false
        DebugLog.traceAudio("STOP complete")
        DebugLog.add("🛑 AudioEngine stopped")
    }

    /**
     * Compatibility API: first successful SF2 load is assigned to MELODY,
     * second successful load to DRUM. The native FluidSynth instance keeps
     * both SoundFonts loaded simultaneously.
     */
    fun loadSoundFont(filePath: String): Boolean {
        val role = if (nextSoundFontRole == 0) "MELODY" else "DRUM"
        DebugLog.add("🎼 Loading $role SF2…")
        DebugLog.traceAudio("SF2 LOAD role=$role path=$filePath")
        val ok = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeLoadSoundFont(filePath) } } }
        if (ok) {
            soundFontLoaded = true
            nextSoundFontRole = 1 - nextSoundFontRole
            DebugLog.add("✅ $role SF2 OK (multi-SF2)")
        } else {
            DebugLog.add("❌ $role SF2 FAILED")
        }
        return ok
    }

    /**
     * FluidSynth owns the live synth used by the Oboe render callback.
     * Never mutate/unload its SoundFont stack while the callback can render.
     * Pause the stream for the load, then resume it even when loading fails.
     */
    private fun <T> withAudioStreamPausedUnsafe(block: () -> T): T {
        val resume = started
        if (resume) {
            DebugLog.traceAudio("PAUSE for SF2 operation")
            bridge.nativeStop()
            started = false
        }
        return try {
            block()
        } finally {
            if (resume) {
                bridge.nativeInitLogger()
                started = bridge.nativeStart()
                DebugLog.traceAudio("RESUME after SF2 operation started=$started")
            }
        }
    }

    fun loadMelodySoundFont(filePath: String): Boolean {
        val ok = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeLoadMelodySoundFont(filePath) } } }
        soundFontLoaded = soundFontLoaded || ok
        if (ok) DebugLog.add("✅ MELODY SF2 OK") else DebugLog.add("❌ MELODY SF2 FAILED")
        return ok
    }

    fun loadDrumSoundFont(filePath: String): Boolean {
        val ok = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeLoadDrumSoundFont(filePath) } } }
        soundFontLoaded = soundFontLoaded || ok
        if (ok) DebugLog.add("✅ DRUM SF2 OK") else DebugLog.add("❌ DRUM SF2 FAILED")
        return ok
    }

    /**
     * Load melody + drum as one transaction. Pause once and enumerate
     * presets only after both native loads have completed.
     */
    fun loadSoundFontPair(melodyPath: String, drumPath: String): Boolean {
        val result = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe {
            val melodyOk = bridge.nativeLoadMelodySoundFont(melodyPath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val drumOk = bridge.nativeLoadDrumSoundFont(drumPath)
            melodyOk && drumOk
        } } }
        soundFontLoaded = soundFontLoaded || result
        if (result) DebugLog.add("✅ MELODY + DRUM SF2 OK (atomic pair)")
        else DebugLog.add("❌ MELODY + DRUM SF2 FAILED")
        return result
    }

    /**
     * Establish the core melody/drum pair, then attach at most two optional
     * melodic sources (Colombo and Tyros) for one family-gated candidate pool.
     */
    fun loadSoundFontPairWithFallback(
        melodyPath: String,
        fallbackMelodyPath: String?,
        drumPath: String,
        additionalMelodyPath: String? = null
    ): Boolean {
        val result = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe {
            // Establish the essential Yamaha pair first. The previous order
            // loaded the large secondary melody font before the drum font; if
            // that consumed too much native/SF2 memory, the subsequent drum
            // load could fail and the whole startup transaction was reported
            // failed even though fallback itself is optional.
            val melodyOk = bridge.nativeLoadMelodySoundFont(melodyPath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val drumOk = bridge.nativeLoadDrumSoundFont(drumPath)
            if (!drumOk) return@withAudioStreamPausedUnsafe false

            DebugLog.add("✅ Core MELODY + DRUM SF2 established; attaching secondary melody")
            val fallbackOk = fallbackMelodyPath.isNullOrBlank() ||
                bridge.nativeLoadMelodyFallbackSoundFont(fallbackMelodyPath)
            if (!fallbackOk) {
                // Fallback is deliberately non-fatal. BassMidiPlayer rolls a
                // failed fallback mapping back without discarding the already
                // valid primary+drum pair.
                DebugLog.add("⚠️ Secondary melody SF2 failed; core Yamaha pair remains active")
            } else if (!fallbackMelodyPath.isNullOrBlank()) {
                DebugLog.add("✅ Secondary melody SF2 attached")
            }
            if (!additionalMelodyPath.isNullOrBlank()) {
                val additionalOk = bridge.nativeLoadMelodyFallbackSoundFont(additionalMelodyPath)
                DebugLog.add(if (additionalOk) "✅ Yamaha alternate SF2 attached"
                    else "⚠️ Yamaha alternate failed; established SF2 pool remains active")
            }
            true
        } } }
        soundFontLoaded = soundFontLoaded || result
        if (result) DebugLog.add("✅ MELODY + FALLBACK + DRUM SF2 OK")
        else DebugLog.add("❌ MELODY + FALLBACK + DRUM SF2 FAILED")
        return result
    }



    /**
     * Load one SF2 as the shared source for both melodic and drum channels.
     * This is the correct mode for a style that ships with one custom SF2:
     * the same font remains available on normal channels and on Yamaha rhythm
     * channels 8/9. The operation is atomic with respect to the live audio
     * stream so the render callback never sees a half-updated font stack.
     */
    fun loadSingleSoundFont(filePath: String): Boolean {
        val result = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe {
            val melodyOk = bridge.nativeLoadMelodySoundFont(filePath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val drumOk = bridge.nativeLoadDrumSoundFont(filePath)
            if (!drumOk) {
                // Roll back the partial dual-role load rather than leaving an
                // apparently successful but incomplete single-font state.
                bridge.nativeUnloadSoundFont()
                false
            } else {
                true
            }
        } } }
        soundFontLoaded = soundFontLoaded || result
        if (result) DebugLog.add("✅ SINGLE SF2 loaded as MELODY + DRUM")
        else DebugLog.add("❌ SINGLE SF2 load failed")
        return result
    }

    fun isSoundFontLoaded(): Boolean = soundFontLoaded

    fun unloadSoundFont() {
        DebugLog.traceAudio("SF2 UNLOAD")
        runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeUnloadSoundFont() } } }
        soundFontLoaded = false
        nextSoundFontRole = 0
        DebugLog.add("🗑️ All SF2 unloaded")
    }

    fun noteOn(midiNote: Int, velocity01: Float) {
        DebugLog.traceAudio("NOTE_ON ch=legacy note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOnChannel(0, midiNote, velocity01)
        else {
            val sample = sampleProvider.sampleForNote(midiNote) ?: return
            bridge.nativeNoteOn(midiNote, sample.rootNote, velocity01, sample.buffer, sample.frameCount, sample.sampleRateHz)
        }
    }

    fun noteOff(midiNote: Int) {
        DebugLog.traceAudio("NOTE_OFF ch=legacy note=$midiNote sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOffChannel(0, midiNote) else bridge.nativeNoteOff(midiNote)
    }

    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {
        DebugLog.traceAudio("NOTE_ON ch=$channel note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)
        else noteOn(midiNote, velocity01)
    }

    fun noteOnStyleChannel(channel: Int, note: Int, velocity: Float, sourceChannel: Int,
        sourceNote: Int, styleBank: Int, tick: Long, id: Long, sampled: Boolean, chordId: Long = 0L, operation: Int = 0) {
        if (soundFontLoaded) bridge.nativeSfNoteOnStyleChannel(channel, note, velocity,
            sourceChannel, sourceNote, styleBank, tick, id, sampled, chordId, operation)
        else {
            if (sampled || chordId != 0L) DebugLog.add("AUDIO BRIDGE id=$id ch=$channel NOTE_ON_NATIVE=0 reason=sf2_not_loaded existing_sample_fallback=1")
            noteOnChannel(channel, note, velocity)
        }
    }

    fun armChordDiagnostic() = bridge.nativeArmChordDiagnostic()
    fun markChordDiagnostic(id: Long) = bridge.nativeMarkChordDiagnostic(id)
    fun compactChordDiagnosticReport(): String = bridge.nativeCompactChordDiagnosticReport()
    fun stopChordDiagnostic() = bridge.nativeStopChordDiagnostic()
    fun chordDiagnosticReport(): String = bridge.nativeChordDiagnosticReport()
    fun noteOffStyleChannel(channel: Int, note: Int, sourceChannel: Int, sourceNote: Int,
        bank: Int, tick: Long, id: Long, chordId: Long, operation: Int) {
        if (soundFontLoaded) bridge.nativeSfNoteOffStyleChannel(channel, note,
            sourceChannel, sourceNote, bank, tick, id, chordId, operation)
        else noteOffChannel(channel, note)
    }

    fun noteOffChannel(channel: Int, midiNote: Int) {
        DebugLog.traceAudio("NOTE_OFF ch=$channel note=$midiNote sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOffChannel(channel, midiNote)
        else noteOff(midiNote)
    }

    fun setChannelMixer(channel: Int, volume: Int = 127, pan: Int = 64, expression: Int = 127, reverbSend: Int = 40, chorusSend: Int = 0) {
        bridge.nativeSetChannelMixer(channel, volume.coerceIn(0,127), pan.coerceIn(0,127), expression.coerceIn(0,127), reverbSend.coerceIn(0,127), chorusSend.coerceIn(0,127))
    }

    fun setChannelVolume(channel: Int, volume: Int) = setChannelMixer(channel, volume=volume)
    fun setChannelExpression(channel: Int, expression: Int) = bridge.nativeSetChannelExpression(channel, expression.coerceIn(0, 127))
    fun shadowDrumSnapshot(): String = bridge.nativeShadowDrumSnapshot()

    fun diagnosticDrumWav(bank: Int, pc: Int, key: Int, velocity: Int): ByteArray = bridge.nativeDiagnosticDrumWav(bank, pc, key, velocity)
    fun noteZoneReport(): String = bridge.nativeGetNoteZoneReport()
    fun drumKitCoverage(histogram: IntArray): String = bridge.nativeGetDrumKitCoverage(histogram)
    fun drumCompatibilityReport(histogram: IntArray): String = bridge.nativeGetDrumCompatibilityReport(histogram)
    fun drumCompatibilityComparison(histogram: IntArray, kits: IntArray): String = bridge.nativeGetDrumCompatibilityComparison(histogram, kits)
    fun setKeyboardSustain(enabled: Boolean) {
        DebugLog.add(if (enabled) "🎹 SUSTAIN: ON" else "🎹 SUSTAIN: OFF")
        bridge.nativeSetKeyboardSustain(enabled)
    }

    fun setKeyboardReleaseTime(releaseTime: Int) {
        val value = releaseTime.coerceIn(0, 127)
        DebugLog.add("🎹 RELEASE TIME = $value (CC72) R1/R2/R3")
        bridge.nativeSetKeyboardReleaseTime(value)
    }

    fun setMasterVolume(volume: Int) {
        val v = volume.coerceIn(0, 127)
        // Keep 100 as unity; 0 is silent and 127 gives modest headroom above unity.
        bridge.nativeSetMasterGain((v / 100f).coerceIn(0f, 1.27f))
    }


    data class SfPreset(val role: String, val bank: Int, val program: Int, val name: String)

    fun loadedSoundFontPresets(): List<SfPreset> {
        val raw = runBlocking {
            soundFontOperationMutex.withLock {
                withAudioStreamPausedUnsafe { bridge.nativeGetSoundFontPresets() }
            }
        }

        // Current native contract uses ASCII control separators:
        // RECORD=0x1E, FIELD=0x1F. Keep newline/semicolon/pipe parsing for
        // older APKs so a mixed native/Java deployment cannot lose presets.
        return raw
            .split('\u001e')
            .asSequence()
            .flatMap { record ->
                if (record.contains('\u001f')) sequenceOf(record)
                else record.lineSequence().flatMap { line ->
                    if (line.contains('|')) sequenceOf(line)
                    else line.split(';').asSequence()
                }
            }
            .mapNotNull { entry ->
                val p = if (entry.contains('\u001f')) {
                    entry.split('\u001f', limit = 4)
                } else {
                    entry.split('|', limit = 4)
                }

                if (p.size == 4) {
                    val role = p[0].trim()
                    val bank = p[1].trim().toIntOrNull()
                    val program = p[2].trim().toIntOrNull()
                    if (bank != null && program != null && role.isNotEmpty()) {
                        SfPreset(role, bank, program, p[3].trim())
                    } else null
                } else {
                    val legacy = entry.split(':', limit = 3)
                    if (legacy.size == 3) {
                        val bank = legacy[0].trim().toIntOrNull()
                        val program = legacy[1].trim().toIntOrNull()
                        if (bank != null && program != null) {
                            SfPreset("MELODY", bank, program, legacy[2].trim())
                        } else null
                    } else null
                }
            }
            .toList()
    }

    // Named overload carries the Yamaha/CASM voice identity into BASSMIDI resolution.
    fun setChannelProgram(channel: Int, program: Int, bank: Int = 0, voiceName: String? = null) {
        DebugLog.traceAudio("PROGRAM ch=$channel bank=$bank program=$program voice=${voiceName ?: ""}")
        if (voiceName.isNullOrBlank()) {
            bridge.nativeSetChannelPreset(channel, bank, program)
        } else {
            bridge.nativeSetChannelPresetWithName(channel, bank, program, voiceName)
        }
        DebugLog.add("🎼 Ch$channel → prog=$program bank=$bank voice=${voiceName ?: "AUTO"}")
    }

    fun allNotesOff() { DebugLog.traceAudio("ALL_NOTES_OFF"); bridge.nativeAllNotesOff() }

    fun testTone(note: Int, velocity: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOnChannel(0, note, velocity)
            return
        }
        val sample = sampleProvider.sampleForNote(note) ?: return
        bridge.nativeNoteOn(note, sample.rootNote, velocity, sample.buffer, sample.frameCount, sample.sampleRateHz)
    }
}


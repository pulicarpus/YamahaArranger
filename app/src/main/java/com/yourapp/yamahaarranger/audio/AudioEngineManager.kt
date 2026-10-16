package com.yourapp.yamahaarranger.audio

import com.yourapp.yamahaarranger.ui.DebugLog
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton
import com.yourapp.yamahaarranger.lifecycle.ReadyToPlayGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class AudioEngineManager @Inject constructor(
    private val bridge: NativeAudioBridge,
    private val sampleProvider: SampleProvider
) {
    @Volatile private var started = false
    @Volatile private var keyboardReleaseTime = 64
    val readiness = ReadyToPlayGate()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var soundFontLoaded = false
    private var nextSoundFontRole = 0 // 0=melody, 1=drum
    // Serialize SF2 operations: startup auto-load and Import must never race
    // while stopping/restarting the native Oboe stream.
    private val soundFontOperationMutex = Mutex()

    fun start(): Boolean {
        val epoch = readiness.beginOutput()
        return runBlocking { readiness.operations.withLock {
            if (!readiness.wantsOutput(epoch)) return@withLock false
            if (!started) {
                DebugLog.add("🎵 AudioEngine.start()…")
                bridge.nativeInitLogger()
                DebugLog.add("📋 Native logger initialized")
                started = bridge.nativeStart()
                if (started) bridge.nativeSetKeyboardReleaseTime(keyboardReleaseTime)
                DebugLog.traceAudio("START result=$started sf2Loaded=$soundFontLoaded")
                DebugLog.add(if (started) "✅ AudioEngine OK" else "❌ AudioEngine FAILED")
            }
            readiness.outputResult(epoch, started)
            started
        } }
    }

    fun stop() {
        val epoch = readiness.invalidateOutput()
        // Never wait on an SF2 load on the UI thread. A later explicit startup
        // supersedes this shutdown; an old load cannot restart stopped output.
        lifecycleScope.launch { readiness.operations.withLock {
            if (readiness.outputToken() != epoch || !started) return@withLock
            DebugLog.traceAudio("STOP begin")
            bridge.nativeStop()
            started = false
            DebugLog.traceAudio("STOP complete")
            DebugLog.add("🛑 AudioEngine stopped")
        } }
    }

    private fun fontOperation(request: ReadyToPlayGate.Request?, melody: String?, drum: String?, block: () -> Boolean): Boolean {
        val token = request ?: readiness.beginFonts()
        var ok = false
        try {
            ok = runBlocking { readiness.operations.withLock {
                if (!readiness.current(token)) return@withLock false
                val result = try { block() } catch (error: Exception) {
                    readiness.clearFonts()
                    throw error
                }
                readiness.installedFonts(token, result, melody, drum)
                result
            } }
            return ok
        } finally { if (request == null) readiness.finishFonts(token, ok) }
    }

    /**
     * Compatibility API: first successful SF2 load is assigned to MELODY,
     * second successful load to DRUM. The native FluidSynth instance keeps
     * both SoundFonts loaded simultaneously.
     */
    fun loadSoundFont(filePath: String, request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, null, null) {
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
        ok
    }

    /**
     * FluidSynth owns the live synth used by the Oboe render callback.
     * Never mutate/unload its SoundFont stack while the callback can render.
     * Pause the stream for the load, then resume it even when loading fails.
     */
    private fun <T> withAudioStreamPausedUnsafe(block: () -> T): T {
        val resume = started
        val outputEpoch = readiness.outputToken()
        readiness.paused(outputEpoch)
        if (resume) {
            DebugLog.traceAudio("PAUSE for SF2 operation")
            bridge.nativeStop()
            started = false
        }
        return try {
            block()
        } finally {
            if (resume && readiness.wantsOutput(outputEpoch)) {
                bridge.nativeInitLogger()
                started = bridge.nativeStart()
                if (started) bridge.nativeSetKeyboardReleaseTime(keyboardReleaseTime)
                readiness.outputResult(outputEpoch, started)
                DebugLog.traceAudio("RESUME after SF2 operation started=$started")
            }
        }
    }

    fun loadMelodySoundFont(filePath: String, request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, filePath, null) {
        val ok = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeLoadMelodySoundFont(filePath) } } }
        soundFontLoaded = soundFontLoaded || ok
        if (ok) DebugLog.add("✅ MELODY SF2 OK") else DebugLog.add("❌ MELODY SF2 FAILED")
        ok
    }

    fun loadDrumSoundFont(filePath: String, request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, null, filePath) {
        val ok = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeLoadDrumSoundFont(filePath) } } }
        soundFontLoaded = soundFontLoaded || ok
        if (ok) DebugLog.add("✅ DRUM SF2 OK") else DebugLog.add("❌ DRUM SF2 FAILED")
        ok
    }

    /**
     * Load melody + drum as one transaction. Pause once and enumerate
     * presets only after both native loads have completed.
     */
    fun loadSoundFontPair(melodyPath: String, drumPath: String, request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, melodyPath, drumPath) {
        val result = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe {
            val melodyOk = bridge.nativeLoadMelodySoundFont(melodyPath)
            if (!melodyOk) return@withAudioStreamPausedUnsafe false
            val drumOk = bridge.nativeLoadDrumSoundFont(drumPath)
            melodyOk && drumOk
        } } }
        soundFontLoaded = soundFontLoaded || result
        if (result) DebugLog.add("✅ MELODY + DRUM SF2 OK (atomic pair)")
        else DebugLog.add("❌ MELODY + DRUM SF2 FAILED")
        result
    }

    /**
     * Establish the core melody/drum pair, then attach at most two optional
     * melodic sources (Colombo and Tyros) for one family-gated candidate pool.
     */
    fun loadSoundFontPairWithFallback(
        melodyPath: String,
        fallbackMelodyPath: String?,
        drumPath: String,
        additionalMelodyPath: String? = null,
        request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, melodyPath, drumPath) {
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
        result
    }



    /**
     * Load one SF2 as the shared source for both melodic and drum channels.
     * This is the correct mode for a style that ships with one custom SF2:
     * the same font remains available on normal channels and on Yamaha rhythm
     * channels 8/9. The operation is atomic with respect to the live audio
     * stream so the render callback never sees a half-updated font stack.
     */
    fun loadSingleSoundFont(filePath: String, request: ReadyToPlayGate.Request? = null): Boolean = fontOperation(request, filePath, filePath) {
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
        result
    }

    fun isSoundFontLoaded(): Boolean = soundFontLoaded

    fun unloadSoundFont() = runBlocking { readiness.operations.withLock {
        DebugLog.traceAudio("SF2 UNLOAD")
        runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe { bridge.nativeUnloadSoundFont() } } }
        soundFontLoaded = false
        nextSoundFontRole = 0
        DebugLog.add("🗑️ All SF2 unloaded")

        readiness.clearFonts()
    } }

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

    fun stopRightPart(channel: Int): Boolean {
        if (channel !in 0..2) return false
        val stopped = bridge.nativeStopKeyboardPart(channel)
        DebugLog.add("RIGHT PART OFF channel=$channel nativeStopped=$stopped")
        return stopped
    }

    // Serialize controller snapshots with their native write. This lock is not
    // used by render, SF2 loading, note ownership or the scheduler clock.
    private val mixerRoutingLock = Any()
    @Volatile var onMixerControllers: ((Int, IntArray) -> Unit)? = null
    @Volatile var onMixerMasterVolume: ((Int) -> Unit)? = null
    private var auxiliaryStyleTrim = 127
    private val auxiliaryExpression = IntArray(4) { 127 }
    private val auxiliaryObserved = BooleanArray(4)
    private fun effectiveMixerExpression(channel: Int, expression: Int): Int {
        val value = expression.coerceIn(0, 127)
        if (channel !in 4..7) return value
        auxiliaryExpression[channel - 4] = value
        auxiliaryObserved[channel - 4] = true
        return (value * auxiliaryStyleTrim + 63) / 127
    }
    /** Existing sequencer trims 8..15; cover compatible style destinations 4..7 once. */
    fun setAuxiliaryStyleBusTrim(value: Int) = synchronized(mixerRoutingLock) {
        auxiliaryStyleTrim = value.coerceIn(0, 127)
        for (channel in 4..7) if (auxiliaryObserved[channel - 4]) {
            val effective = (auxiliaryExpression[channel - 4] * auxiliaryStyleTrim + 63) / 127
            bridge.nativeSetChannelExpression(channel, effective)
            onMixerControllers?.invoke(channel, intArrayOf(11, effective))
        }
    }
    fun setChannelMixer(channel: Int, volume: Int = 127, pan: Int = 64, expression: Int = 127, reverbSend: Int = 40, chorusSend: Int = 0) = synchronized(mixerRoutingLock) {
        require(channel in 0..15)
        val v = volume.coerceIn(0, 127); val p = pan.coerceIn(0, 127)
        val e = effectiveMixerExpression(channel, expression)
        val r = reverbSend.coerceIn(0, 127); val c = chorusSend.coerceIn(0, 127)
        bridge.nativeSetChannelMixer(channel, v, p, e, r, c)
        onMixerControllers?.invoke(channel, intArrayOf(7, v, 10, p, 11, e, 91, r, 93, c))
        Unit
    }
    fun setChannelVolume(channel: Int, volume: Int) = setChannelMixer(channel, volume=volume)
    fun setChannelExpression(channel: Int, expression: Int) = synchronized(mixerRoutingLock) {
        require(channel in 0..15)
        val effective = effectiveMixerExpression(channel, expression)
        bridge.nativeSetChannelExpression(channel, effective)
        onMixerControllers?.invoke(channel, intArrayOf(11, effective))
        Unit
    }
    fun shadowDrumSnapshot(): String = bridge.nativeShadowDrumSnapshot()

    fun diagnosticDrumWav(bank: Int, pc: Int, key: Int, velocity: Int): ByteArray = bridge.nativeDiagnosticDrumWav(bank, pc, key, velocity)
    fun markPcmSession(): Long = PcmSessionControls.snapshot { bridge.nativeMarkPcmSession(it.muteMask,it.knownMask,it.changes,it.lastChangeNs) }
    fun pcmSessionReport(): String = PcmSessionControls.snapshot { bridge.nativePcmSessionReport(it.muteMask,it.knownMask,it.changes,it.lastChangeNs) }
    fun drumChannelSelection(channel: Int): String = bridge.nativeDrumChannelSelection(channel)
    var drumABResolved: Boolean = false
        private set
    fun setDrumABMode(resolved: Boolean): Boolean {
        val ok=bridge.nativeSetDrumABMode(resolved)
        if(ok)drumABResolved=resolved
        return ok
    }
    fun bindDrumAB(ch: Int, pdta: String, bank: Int, pc: Int, section: String): Boolean = bridge.nativeBindDrumAB(ch,pdta,bank,pc,section)
    fun drumABReport(): String = bridge.nativeDrumABReport()
    fun noteZoneReport(): String = bridge.nativeGetNoteZoneReport()
    fun drumKitCoverage(histogram: IntArray): String = bridge.nativeGetDrumKitCoverage(histogram)
    fun drumCompatibilityReport(histogram: IntArray): String = bridge.nativeGetDrumCompatibilityReport(histogram)
    fun drumCompatibilityComparison(histogram: IntArray, kits: IntArray): String = bridge.nativeGetDrumCompatibilityComparison(histogram, kits)
    fun setKeyboardSustain(enabled: Boolean) {
        DebugLog.add(if (enabled) "🎹 SUSTAIN: ON" else "🎹 SUSTAIN: OFF")
        bridge.nativeSetKeyboardSustain(enabled)
    }

    fun setKeyboardReleaseTime(releaseTime: Int): Boolean = runBlocking { readiness.operations.withLock {
        keyboardReleaseTime = releaseTime.coerceIn(0, 127)
        val ok = bridge.nativeSetKeyboardReleaseTime(keyboardReleaseTime)
        DebugLog.add("🎹 RELEASE TIME=$keyboardReleaseTime R1/R2/R3 acceptedAndReadback=$ok")
        ok
    } }

    fun setMasterVolume(volume: Int) {
        val v = volume.coerceIn(0, 127)
        // 100 is unity and 0 is silent; the native player saturates values above unity.
        synchronized(mixerRoutingLock) {
            bridge.nativeSetMasterGain((v / 100f).coerceIn(0f, 1.27f))
            onMixerMasterVolume?.invoke(v)
        }
    }


    data class SfPreset(
        val role: String, val bank: Int, val program: Int, val name: String,
        val soundFontId: Long = 0, val soundFontName: String = ""
    ) {
        val identity: String get() = "$soundFontId:$bank:$program"
        val isDrum: Boolean get() = bank == 127 || bank == 128
    }

    fun loadedSoundFontPresets(): List<SfPreset> = runBlocking { readiness.operations.withLock {
        // Enumeration is a cached snapshot, built at SF2 load with BASSMIDI's
        // actual inventory. It performs no SF2/sample I/O and need not pause output.
        SoundFontPresetCatalog.parse(bridge.nativeGetSoundFontPresets())
    } }

    fun selectKeyboardPreset(channel: Int, preset: SfPreset, isCurrent: () -> Boolean = { true }): Boolean = runBlocking { readiness.operations.withLock {
        if (channel !in 0..3 || preset.soundFontId <= 0 || !isCurrent()) return@withLock false
        val ok = bridge.nativeSetKeyboardPreset(channel, preset.soundFontId, preset.bank, preset.program)
        DebugLog.add("🎹 VOICE ch=$channel sf2=${preset.soundFontName} id=${preset.soundFontId} " +
            "bank=${preset.bank} pc=${preset.program} verified=$ok")
        ok
    } }

    data class KeyboardReleaseProbe(val wav: ByteArray, val report: String)

    fun keyboardVoiceSnapshot(): String = bridge.nativeKeyboardVoiceSnapshot()

    fun diagnosticKeyboardRelease(part: KeyboardPart, allowed: () -> Boolean): KeyboardReleaseProbe =
        runBlocking { readiness.operations.withLock {
            if (part == KeyboardPart.LEFT || !allowed()) return@withLock KeyboardReleaseProbe(
                byteArrayOf(), "RELEASE PCM blocked: stop style and select a RIGHT part first")
            withAudioStreamPausedUnsafe {
                val wav = bridge.nativeDiagnosticKeyboardReleaseWav(part.channel, 60, 100)
                KeyboardReleaseProbe(wav, bridge.nativeKeyboardReleaseProbeReport())
            }
        } }

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


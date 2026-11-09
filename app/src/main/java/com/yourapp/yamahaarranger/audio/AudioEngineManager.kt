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
                if (started) bridge.nativeSetInternalGainDb(internalGainDb)
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
     * CPV stack: Colombo is the primary melodic source, Tyros the optional
     * fallback. No dedicated drum SF2 is loaded; Rhythm channels use the R2-3
     * verified Colombo/Tyros kit binding, and an unverified kit stays silent.
     * The primary path also satisfies the readiness drum slot because it is
     * the drum source.
     */
    fun loadColomboPrimaryStack(primaryPath: String, fallbackPath: String?, request: ReadyToPlayGate.Request? = null, manualPaths: List<String> = emptyList()): Boolean =
        fontOperation(request, primaryPath, primaryPath) {
            val result = runBlocking { soundFontOperationMutex.withLock { withAudioStreamPausedUnsafe {
                // Drop any previous stack, including a dedicated drum font.
                bridge.nativeUnloadSoundFont()
                bridge.nativeSetSourceTiered(true)
                if (!bridge.nativeLoadMelodySoundFont(primaryPath)) return@withAudioStreamPausedUnsafe false
                if (!fallbackPath.isNullOrBlank()) {
                    val ok = bridge.nativeLoadMelodyFallbackSoundFont(fallbackPath)
                    DebugLog.add(if (ok) "✅ CPV fallback SF2 attached" else "⚠️ CPV fallback SF2 failed; primary remains active")
                }
                // Manual-only SoundFonts (SX Prime Melody/Drum, custom): selectable by hand, invisible to the automatic
                // resolver. One at a time, each non-fatal; the file list was already limited by ManagedSf2Policy.
                for (manualPath in manualPaths) {
                    val ok = bridge.nativeLoadMelodyManualSoundFont(manualPath)
                    DebugLog.add(if (ok) "✅ CPV manual-only SF2 attached: ${manualPath.substringAfterLast('/')}"
                        else "⚠️ CPV manual-only SF2 failed: ${manualPath.substringAfterLast('/')}; established stack remains active")
                }
                val drums = bridge.nativeSetDrumABMode(true)
                if (drums) drumABResolved = true
                DebugLog.add("CPV drum route=R23_RESOLVED ok=$drums")
                true
            } } }
            soundFontLoaded = result
            DebugLog.add(if (result) "✅ CPV SF2 stack: primary=$primaryPath fallback=${fallbackPath ?: "-"}"
                else "❌ CPV SF2 stack failed")
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

    // ---- MIDI RECORDER (V1B): one tap for what the keyboard parts really sound. Called AFTER the native call; null = not recording. ----
    interface NoteTap {
        fun noteOn(channel: Int, note: Int, velocity01: Float)
        fun noteOff(channel: Int, note: Int)
        fun partStopped(channel: Int)
        fun allStopped()
        fun sustain(on: Boolean)
        fun keyboardVoice(channel: Int, bank: Int, program: Int, name: String, sf2: String)
        // V1C (FULL ARRANGER): the Style parts' final voice and mixer (defaults keep V1B adapters valid)
        fun styleVoice(channel: Int, bank: Int, program: Int, name: String, sf2: String, bank14: Boolean) {}
        fun controllers(channel: Int, pairs: IntArray) {}
    }
    @Volatile var noteTap: NoteTap? = null
    /** Last voice set per keyboard part (bank, program, name, sf2), for the start of a recording. */
    private val keyboardVoices = arrayOfNulls<Array<Any>>(4)
    fun keyboardVoiceIdentity(channel: Int): Array<Any>? = keyboardVoices.getOrNull(channel)
    /** Last voice (bank, program, name, sf2, bank14) and mixer controllers per channel 4..15, for the start of a FULL ARRANGER take. */
    private val styleVoices = arrayOfNulls<Array<Any>>(16)
    private val channelControllers = arrayOfNulls<IntArray>(16)
    fun styleVoiceIdentity(channel: Int): Array<Any>? = styleVoices.getOrNull(channel)
    fun channelControllerSnapshot(channel: Int): IntArray? = channelControllers.getOrNull(channel)?.copyOf()

    fun noteOn(midiNote: Int, velocity01: Float) {
        noteOnLegacy(midiNote, velocity01)
        noteTap?.noteOn(0, midiNote, velocity01)
    }

    private fun noteOnLegacy(midiNote: Int, velocity01: Float) {
        val probeEntry = System.nanoTime() // LATENCY_PROBE
        if (!NoteLogTuning.skip()) DebugLog.traceAudio("NOTE_ON ch=legacy note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) {
            val probeStart = System.nanoTime() // LATENCY_PROBE
            LatencyProbe.preJniLogging(probeEntry, probeStart)
            bridge.nativeSfNoteOnChannel(0, midiNote, velocity01)
            LatencyProbe.jniKeyboardNote(probeStart)
        } else {
            val sample = sampleProvider.sampleForNote(midiNote) ?: return
            bridge.nativeNoteOn(midiNote, sample.rootNote, velocity01, sample.buffer, sample.frameCount, sample.sampleRateHz)
        }
    }

    fun noteOff(midiNote: Int) {
        if (!NoteLogTuning.skip()) DebugLog.traceAudio("NOTE_OFF ch=legacy note=$midiNote sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOffChannel(0, midiNote) else bridge.nativeNoteOff(midiNote)
        noteTap?.noteOff(0, midiNote)
    }

    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {
        pbRole() // P-B0.1
        val probeEntry = System.nanoTime() // LATENCY_PROBE
        if (!NoteLogTuning.skip()) DebugLog.traceAudio("NOTE_ON ch=$channel note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) {
            val probeStart = System.nanoTime() // LATENCY_PROBE
            if (channel < 4) LatencyProbe.preJniLogging(probeEntry, probeStart)
            val pb2 = Pb02.t0() // P-B0.2
            bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)
            Pb02.jni(pb2) // P-B0.2
            if (channel < 4) LatencyProbe.jniKeyboardNote(probeStart)
            noteTap?.noteOn(channel, midiNote, velocity01)
        } else noteOn(midiNote, velocity01)
    }

    fun noteOnStyleChannel(channel: Int, note: Int, velocity: Float, sourceChannel: Int,
        sourceNote: Int, styleBank: Int, tick: Long, id: Long, sampled: Boolean, chordId: Long = 0L, operation: Int = 0) {
        pbRole() // P-B0.1
        val pb2 = Pb02.t0() // P-B0.2
        if (soundFontLoaded) bridge.nativeSfNoteOnStyleChannel(channel, note, velocity,
            sourceChannel, sourceNote, styleBank, tick, id, sampled, chordId, operation)
        else {
            if (sampled || chordId != 0L) DebugLog.add("AUDIO BRIDGE id=$id ch=$channel NOTE_ON_NATIVE=0 reason=sf2_not_loaded existing_sample_fallback=1")
            noteOnChannel(channel, note, velocity)
        }
        Pb02.jni(pb2) // P-B0.2
        if (soundFontLoaded) noteTap?.noteOn(channel, note, velocity)
    }

    fun armChordDiagnostic() = bridge.nativeArmChordDiagnostic()
    fun markChordDiagnostic(id: Long) = bridge.nativeMarkChordDiagnostic(id)
    fun compactChordDiagnosticReport(): String = bridge.nativeCompactChordDiagnosticReport()
    fun stopChordDiagnostic() = bridge.nativeStopChordDiagnostic()
    fun chordDiagnosticReport(): String = bridge.nativeChordDiagnosticReport()
    fun noteOffStyleChannel(channel: Int, note: Int, sourceChannel: Int, sourceNote: Int,
        bank: Int, tick: Long, id: Long, chordId: Long, operation: Int) {
        pbRole() // P-B0.1
        val pb2 = Pb02.t0() // P-B0.2
        if (soundFontLoaded) bridge.nativeSfNoteOffStyleChannel(channel, note,
            sourceChannel, sourceNote, bank, tick, id, chordId, operation)
        else noteOffChannel(channel, note)
        Pb02.jni(pb2) // P-B0.2
        if (soundFontLoaded) noteTap?.noteOff(channel, note)
    }

    fun noteOffChannel(channel: Int, midiNote: Int) {
        pbRole() // P-B0.1
        if (!NoteLogTuning.skip()) DebugLog.traceAudio("NOTE_OFF ch=$channel note=$midiNote sf2=$soundFontLoaded")
        val pb2 = Pb02.t0() // P-B0.2 (after the NOTE log line: logging is Kotlin work, not JNI)
        if (soundFontLoaded) bridge.nativeSfNoteOffChannel(channel, midiNote)
        else noteOff(midiNote)
        Pb02.jni(pb2) // P-B0.2
        if (soundFontLoaded) noteTap?.noteOff(channel, midiNote)
    }

    fun stopRightPart(channel: Int): Boolean {
        if (channel !in 0..2) return false
        val stopped = bridge.nativeStopKeyboardPart(channel)
        DebugLog.add("RIGHT PART OFF channel=$channel nativeStopped=$stopped")
        noteTap?.partStopped(channel)
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
        pbRole() // P-B0.1
        val v = volume.coerceIn(0, 127); val p = pan.coerceIn(0, 127)
        val e = effectiveMixerExpression(channel, expression)
        val r = reverbSend.coerceIn(0, 127); val c = chorusSend.coerceIn(0, 127)
        bridge.nativeSetChannelMixer(channel, v, p, e, r, c)
        onMixerControllers?.invoke(channel, intArrayOf(7, v, 10, p, 11, e, 91, r, 93, c))
        channelControllers[channel] = intArrayOf(7, v, 10, p, 11, e, 91, r, 93, c)
        noteTap?.controllers(channel, intArrayOf(7, v, 10, p, 11, e, 91, r, 93, c))
        Unit
    }
    fun setChannelVolume(channel: Int, volume: Int) = setChannelMixer(channel, volume=volume)
    fun setChannelExpression(channel: Int, expression: Int) = synchronized(mixerRoutingLock) {
        require(channel in 0..15)
        val effective = effectiveMixerExpression(channel, expression)
        bridge.nativeSetChannelExpression(channel, effective)
        onMixerControllers?.invoke(channel, intArrayOf(11, effective))
        channelControllers[channel]?.let { a -> a[5] = effective }
        noteTap?.controllers(channel, intArrayOf(11, effective))
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
    /** LATENCY_PROBE report (Kotlin + native + Oboe stream). Call outside realtime paths. */
    // METRONOME: thin forwarders; the clock logic lives in MetronomeController / metronome_engine.h.
    fun metronomeConfigure(enabled: Boolean, mode: Int, running: Boolean, bpmX100: Int, numerator: Int, denominator: Int, volumePermille: Int) =
        bridge.nativeMetronomeConfigure(enabled, mode, running, bpmX100, numerator, denominator, volumePermille)
    fun metronomePush(targetNs: Long, level: Int, generation: Int): Boolean = bridge.nativeMetronomePush(targetNs, level, generation)
    fun metronomeGeneration(): Int = bridge.nativeMetronomeGeneration()
    fun metronomeBumpGeneration(): Int = bridge.nativeMetronomeBumpGeneration()
    fun metronomeBeat(): Long = bridge.nativeMetronomeBeat()

    fun latencyReport(): String = LatencyProbe.report() + bridge.nativeLatencyReport() + bridge.nativeMetronomeReport()
    fun latencyReset() { LatencyProbe.reset(); bridge.nativeLatencyReset() }
    /** P-A EXPERIMENT (diagnostic A/B): 0 = LEGACY (default), 1 = OPTIMIZED. Gates native per-note diagnostics only; never an event. */
    fun setPaDiagnosticMode(mode: Int) { bridge.nativeSetPaDiagnosticMode(if (mode == 1) 1 else 0); DebugLog.add("P-A AUDIO DIAGNOSTIC MODE " + paDiagnosticModeLabel()) }
    fun paDiagnosticMode(): Int = bridge.nativePaDiagnosticMode()
    fun paReset() = bridge.nativePaReset()
    // ---- P-B0.1 TARGETED PROBES (observer only; native pb01_probe.h). Never changes an event, its order, a preset or a lock. ----
    private val pbRoleSet = ThreadLocal<Boolean>()
    /** Tells the native probe, once per OS thread, which role this thread plays: 1 scheduler (coroutine Default pool), 2 main/UI, 3 other. */
    private fun pbRole() {
        if (pbRoleSet.get() == true) return
        pbRoleSet.set(true)
        val n = Thread.currentThread().name
        bridge.nativePbSetThreadRole(if (n == "main") 2 else if (n.startsWith("DefaultDispatcher-worker")) 1 else 3)
    }
    init {
        LatencyProbe.pbVoiceWindow = { start, ns -> pbRole(); bridge.nativePbVoiceWindow(start, ns) }
        LatencyProbe.pbChordWindow = { start, ns, replacements -> pbRole(); bridge.nativePbChordWindow(start, ns, replacements) }
    }
    fun setPbProbesEnabled(enabled: Boolean) { bridge.nativePbSetEnabled(enabled); DebugLog.add("P-B0.1 PROBES " + if (enabled) "ON" else "OFF") }
    fun pbProbesEnabled(): Boolean = bridge.nativePbEnabled()
    /** P-B1a EXPERIMENT: skip identical, still-verified Drum AB rebinds at section voice apply (default OFF = legacy). */
    fun setPb1aDrumBindCache(enabled: Boolean) { bridge.nativePb1aSetDrumBindCache(enabled); DebugLog.add("P-B1a DRUM BIND CACHE " + if (enabled) "ON" else "OFF") }
    fun pb1aDrumBindCache(): Boolean = bridge.nativePb1aDrumBindCache()
    /** P-B0.2 EXPERIMENT: scheduler lateness split (Kotlin observer, default OFF). */
    fun setPb02Enabled(enabled: Boolean) { Pb02.setEnabled(enabled); DebugLog.add("P-B0.2 SCHED SPLIT " + if (enabled) "ON" else "OFF") }
    fun pb02Enabled(): Boolean = Pb02.enabled
    fun paDiagnosticModeLabel(): String = if (paDiagnosticMode() == 1) "OPTIMIZED" else "LEGACY"
    fun channelBinding(channel: Int): ChannelBinding? = ChannelBinding.parse(bridge.nativeChannelBinding(channel))
    /** SF2 preset-data fingerprint of a loaded font ("" when unavailable); identifies content, not just the file name. */
    fun soundFontPdta(soundFontId: Long): String = if (soundFontId > 0) bridge.nativeFontPdta(soundFontId) else ""

    /**
     * Preset-data fingerprint per loaded SF2 file, read from the cached native snapshot WITHOUT the readiness lock.
     * Safe to call while [ReadyToPlayGate.operations] is held (a Style load does); [loadedSoundFontPresets] is not: it takes that
     * non-reentrant Mutex inside runBlocking and would block the calling thread forever.
     */
    fun soundFontPdtaByFile(): Map<String, String> =
        SoundFontPresetCatalog.parse(bridge.nativeGetSoundFontPresets()).distinctBy { it.soundFontName }
            .associate { it.soundFontName to soundFontPdta(it.soundFontId) }

    /** Loaded preset for a saved pin. Reads the cached native snapshot without the readiness lock. */
    fun findLoadedPreset(pin: com.yourapp.yamahaarranger.style.StyleVoicePin): SfPreset? =
        SoundFontPresetCatalog.parse(bridge.nativeGetSoundFontPresets()).firstOrNull {
            it.soundFontName == pin.soundFontName && it.bank == pin.bank && it.program == pin.program
        }

    /**
     * CPV manual override for a style channel; internal sound only (no MIDI OUT).
     * Melodic channels use an exact native pin; Rhythm 8/9 use the R2-3 verified kit
     * binding. Returns false when the preset is not loaded or readback fails, and the
     * channel then reports UNRESOLVED/UNVERIFIED instead of a substitute.
     */
    fun pinStyleChannel(channel: Int, pin: com.yourapp.yamahaarranger.style.StyleVoicePin, section: String = "OVERRIDE"): Boolean {
        if (channel !in 4..15) return false
        val preset = findLoadedPreset(pin)
        val drum = channel == 8 || channel == 9
        val ok = when {
            preset == null -> false
            // Reviewed Colombo/Tyros kits keep the original exact-namespace route; any other loaded SoundFont
            // (SX Prime Drum, custom) binds by exact font id + raw bank + program after the user chose it.
            drum -> drumABResolved && pin.drum &&
                (bridge.nativeBindDrumAB(channel, bridge.nativeFontPdta(preset.soundFontId), pin.bank, pin.program, section) ||
                    bridge.nativeBindDrumManual(channel, preset.soundFontId, pin.bank, pin.program, section))
            else -> !pin.drum && bridge.nativePinChannelPreset(channel, preset.soundFontId, pin.bank, pin.program)
        }
        DebugLog.add("CPV OVERRIDE ch=$channel sf2=${pin.soundFontName} bank=${pin.bank} pc=${pin.program} " +
            "name='${pin.presetName}' loaded=${preset != null} verified=$ok")
        if (ok) {
            styleVoices[channel] = arrayOf(pin.bank, pin.program, pin.presetName, pin.soundFontName, false)
            noteTap?.styleVoice(channel, pin.bank, pin.program, pin.presetName, pin.soundFontName, false)
        }
        return ok
    }

    /** Remove a melodic pin; Rhythm channels are rebound by the sequencer from the style request. */
    fun unpinStyleChannel(channel: Int): Boolean =
        if (channel == 8 || channel == 9) true else bridge.nativePinChannelPreset(channel, 0L, 0, 0)
    fun noteZoneReport(): String = bridge.nativeGetNoteZoneReport()
    fun drumKitCoverage(histogram: IntArray): String = bridge.nativeGetDrumKitCoverage(histogram)
    fun drumCompatibilityReport(histogram: IntArray): String = bridge.nativeGetDrumCompatibilityReport(histogram)
    fun drumCompatibilityComparison(histogram: IntArray, kits: IntArray): String = bridge.nativeGetDrumCompatibilityComparison(histogram, kits)
    fun setKeyboardSustain(enabled: Boolean) {
        DebugLog.add(if (enabled) "🎹 SUSTAIN: ON" else "🎹 SUSTAIN: OFF")
        bridge.nativeSetKeyboardSustain(enabled)
        noteTap?.sustain(enabled)
    }

    fun setKeyboardReleaseTime(releaseTime: Int): Boolean = runBlocking { readiness.operations.withLock {
        keyboardReleaseTime = releaseTime.coerceIn(0, 127)
        val ok = bridge.nativeSetKeyboardReleaseTime(keyboardReleaseTime)
        DebugLog.add("🎹 RELEASE TIME=$keyboardReleaseTime R1/R2/R3 acceptedAndReadback=$ok")
        ok
    } }

    /** INTERNAL OUTPUT GAIN (0..12 dB) on the BASSMIDI mix only; 0 = legacy path. Never reaches MIDI OUT. */
    @Volatile var internalGainDb: Float = 0f
        private set
    fun setInternalGainDb(db: Float) { internalGainDb = db.coerceIn(0f, 12f); bridge.nativeSetInternalGainDb(internalGainDb) }
    fun internalGainReport(): String = bridge.nativeInternalGainReport()

    // ---- DEVICE ROUTING V1 (where BASSMIDI's stream plays; never the synth, SoundFonts, resolver or balance) ----
    /** Wanted Oboe output device (Android AudioDeviceInfo id, 0 = System Default) and the device the stream really opened on. */
    @Volatile var wantedOutputDevice: Int = 0
        private set
    @Volatile var lastOutputFallback: Boolean = false
        private set
    fun actualOutputDevice(): Int = bridge.nativeActualOutputDevice()
    fun outputDisconnected(): Boolean = bridge.nativeOutputDisconnected()

    /**
     * Choose the output device and (if the stream runs) reopen it there: same lock order and stop/start as an SF2 load
     * (readiness.operations, then the SF2 mutex), so a font load and a device switch never overlap. BASSMIDI keeps every note;
     * only the Oboe stream is recreated. If the device cannot be opened, the stream falls back to System Default (reported).
     */
    fun setOutputDevice(deviceId: Int, force: Boolean = false): Boolean = runBlocking { readiness.operations.withLock { soundFontOperationMutex.withLock {
        val id = deviceId.coerceAtLeast(0)
        if (!force && id == wantedOutputDevice && !(started && bridge.nativeOutputDisconnected())) return@withLock started
        wantedOutputDevice = id
        bridge.nativeSetPreferredOutputDevice(id)
        lastOutputFallback = false
        if (!started) { DebugLog.add("🔈 AUDIO OUTPUT device=$id (applied when the engine starts)"); return@withLock false }
        val epoch = readiness.outputToken()
        readiness.paused(epoch)
        if (started) { bridge.nativeStop(); started = false }
        bridge.nativeInitLogger()
        started = bridge.nativeStart()
        if (!started && id != 0) {
            DebugLog.add("⚠ AUDIO OUTPUT device=$id failed to open: falling back to System Default")
            bridge.nativeSetPreferredOutputDevice(0)
            started = bridge.nativeStart()
            lastOutputFallback = started
        }
        if (started) bridge.nativeSetKeyboardReleaseTime(keyboardReleaseTime)
        readiness.outputResult(epoch, started)
        DebugLog.add("🔈 AUDIO OUTPUT wanted=$id actual=${bridge.nativeActualOutputDevice()} started=$started fallback=$lastOutputFallback")
        started
    } } }

    /** OUTPUT MODE: true = BASSMIDI renders but its signal is silenced (mode MIDI OUT). Notes and controllers keep running. */
    @Volatile var internalMuted: Boolean = false
        private set
    fun setInternalMuted(muted: Boolean) { internalMuted = muted; bridge.nativeSetInternalMuted(muted); DebugLog.add(if (muted) "🔇 INTERNAL (BASSMIDI) OFF" else "🔊 INTERNAL (BASSMIDI) ON") }
    fun resetInternalGainStats() = bridge.nativeInternalGainReset()
    /** Real output level for the Mixer's master meter: peak of the last audio block, 0..1 (linear). Per-part audio levels do not exist (one mixed BASSMIDI stream). */
    fun masterLevel(): Float = bridge.nativeMasterLevel()

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
        if (ok) {
            keyboardVoices[channel] = arrayOf(preset.bank, preset.program, preset.name, preset.soundFontName)
            noteTap?.keyboardVoice(channel, preset.bank, preset.program, preset.name, preset.soundFontName)
        }
        ok
    } }

    data class KeyboardReleaseProbe(val wav: ByteArray, val report: String)

    fun keyboardVoiceSnapshot(): String = bridge.nativeKeyboardVoiceSnapshot()

    /** Read-only RIGHT diagnostic (backend readback + forwarded velocity histogram). */
    fun rightVoiceDiagnostic(): String = bridge.nativeRightVoiceDiagnostic()
    fun resetRightVelocityStats() = bridge.nativeResetRightVelocityStats()

    // ---- Output-buffer A/B and onset probe (control thread only; see docs/latency/RIGHT_LATENCY_AB.md) ----
    fun setBufferMode(mode: Int): Int = bridge.nativeSetBufferMode(mode)
    fun bufferStatus(): String = bridge.nativeBufferStatus()
    fun bufferTick() = bridge.nativeBufferTick()

    /** Path A vs B: arm a capture of the live render of the next RIGHT note (style must be stopped). */
    fun armLiveOnset(allowed: () -> Boolean): Boolean = allowed() && bridge.nativeArmLiveOnset()
    fun liveOnsetStatus(): String = bridge.nativeLiveOnsetStatus()
    /** Builds the live-vs-isolated comparison; output is paused while the isolated decodes run. */
    fun liveOnsetReport(): String = runBlocking { readiness.operations.withLock {
        withAudioStreamPausedUnsafe { bridge.nativeLiveOnsetReport() }
    } }

    /** Offline onset of the selected RIGHT preset. Needs a stopped style; output is paused while it decodes. */
    fun rightOnsetReport(channel: Int, key: Int, allowed: () -> Boolean): String = runBlocking { readiness.operations.withLock {
        if (channel !in 0..2 || !allowed()) return@withLock "ONSET blocked: stop the style first"
        withAudioStreamPausedUnsafe { bridge.nativeRightOnsetReport(channel, key) }
    } }

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
        pbRole() // P-B0.1
        DebugLog.traceAudio("PROGRAM ch=$channel bank=$bank program=$program voice=${voiceName ?: ""}")
        if (voiceName.isNullOrBlank()) {
            bridge.nativeSetChannelPreset(channel, bank, program)
        } else {
            bridge.nativeSetChannelPresetWithName(channel, bank, program, voiceName)
        }
        DebugLog.add("🎼 Ch$channel → prog=$program bank=$bank voice=${voiceName ?: "AUTO"}")
        if (channel in 0..3) {
            keyboardVoices[channel] = arrayOf(bank, program, voiceName ?: "", "")
            noteTap?.keyboardVoice(channel, bank, program, voiceName ?: "", "")
        }
        if (channel in 4..15) {
            styleVoices[channel] = arrayOf(bank, program, voiceName ?: "", "", true)
            noteTap?.styleVoice(channel, bank, program, voiceName ?: "", "", true)
        }
    }

    fun allNotesOff() { DebugLog.traceAudio("ALL_NOTES_OFF"); bridge.nativeAllNotesOff(); noteTap?.allStopped() }
    // ---- MIDI SONG (V1A): the Song's own BASSMIDI stream. Never the keyboard/Style stream; no global All Notes Off. ----
    fun songEvents(bytes: ByteArray): Boolean = try { bridge.nativeSongEvents(bytes, bytes.size) } catch (e: Throwable) { false }
    fun songSysEx(bytes: ByteArray): Boolean = try { bridge.nativeSongSysEx(bytes, bytes.size) } catch (e: Throwable) { false }
    /** SONG MIXER: -1 not found, 0 mapped, 1 verified, 2 cleared (fontId 0). Song stream only. */
    fun songSetChannelVoice(channel: Int, fontId: Long, bank: Int, program: Int): Int =
        try { bridge.nativeSongSetChannelVoice(channel, fontId, bank, program) } catch (e: Throwable) { -1 }
    fun songChannelReport(): String = try { bridge.nativeSongChannelReport() } catch (e: Throwable) { "" }
    /** V1C fix: a recorded take's voice route on the Song stream (channel < 0 clears all). */
    fun songSetVoiceRoute(channel: Int, msb: Int, lsb: Int, program: Int, fontId: Long, bank: Int, sourceProgram: Int): Boolean =
        try { bridge.nativeSongSetVoiceRoute(channel, msb, lsb, program, fontId, bank, sourceProgram) } catch (e: Throwable) { false }
    /** The loaded SF2 preset the keyboard/Style stream really plays on [channel]: [fontId, sf2, bank, program, name] or null. */
    fun channelResolvedSource(channel: Int): List<String>? =
        try { bridge.nativeChannelResolvedSource(channel).takeIf { it.isNotEmpty() }?.split('\u001f')?.takeIf { it.size == 5 } } catch (e: Throwable) { null }
    fun songReset() { try { bridge.nativeSongReset() } catch (_: Throwable) {} }
    fun songSetActive(active: Boolean) { try { bridge.nativeSongSetActive(active) } catch (_: Throwable) {} }

    fun testTone(note: Int, velocity: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOnChannel(0, note, velocity)
            return
        }
        val sample = sampleProvider.sampleForNote(note) ?: return
        bridge.nativeNoteOn(note, sample.rootNote, velocity, sample.buffer, sample.frameCount, sample.sampleRateHz)
    }
}


/** What a synth channel is actually bound to, as read back from the native player. */
data class ChannelBinding(
    val channel: Int,
    /** BOUND, UNRESOLVED, UNVERIFIED or UNINITIALIZED. */
    val status: String,
    /** PRIMARY, SECONDARY, DRUM_R23, DRUM_LEGACY, KEYBOARD, PINNED or NONE. */
    val source: String,
    val soundFontName: String,
    val pdta: String,
    val bank: Int,
    val program: Int,
    val presetName: String,
    val reason: String
) {
    val resolved: Boolean get() = status == "BOUND"

    fun summary(): String = if (resolved) "$soundFontName $bank:$program '$presetName'"
        else "$status${if (reason.isNotBlank()) " ($reason)" else ""}"

    companion object {
        fun parse(raw: String): ChannelBinding? {
            val f = raw.split('\t')
            if (f.size < 9) return null
            return ChannelBinding(
                channel = f[0].toIntOrNull() ?: return null,
                status = f[1], source = f[2], soundFontName = f[3], pdta = f[4],
                bank = f[5].toIntOrNull() ?: -1, program = f[6].toIntOrNull() ?: -1,
                presetName = f[7], reason = f[8]
            )
        }
    }
}

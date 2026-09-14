package com.yourapp.yamahaarranger.audio

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point the rest of the app (ChordEngine, ArrangerBrain,
 * on-screen keyboard) talks to. Wraps NativeAudioBridge so nothing outside
 * this package needs to know JNI details.
 */
@Singleton
class AudioEngineManager @Inject constructor(
    private val bridge: NativeAudioBridge,
    private val sampleProvider: SampleProvider
) {
    private var started = false

    fun start() {
        if (started) return
        started = bridge.nativeStart()
        if (!started) Timber.e("AudioEngine failed to start — check Oboe/device audio config")
    }

    fun stop() {
        if (!started) return
        bridge.nativeStop()
        started = false
    }

    /** velocity01 in [0, 1]. Voice/instrument selection is Phase 1's single
     * GM piano patch; multi-timbral routing (Right1/Right2/Left splits) is
     * Phase 2 (see arranger/VoiceLayer.kt TODO). */
    fun noteOn(midiNote: Int, velocity01: Float) {
        val sample = sampleProvider.sampleForNote(midiNote) ?: return
        bridge.nativeNoteOn(
            midiNote, sample.rootNote, velocity01,
            sample.buffer, sample.frameCount, sample.sampleRateHz
        )
    }

    fun noteOff(midiNote: Int) = bridge.nativeNoteOff(midiNote)
    fun allNotesOff() = bridge.nativeAllNotesOff()
}

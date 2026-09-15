package com.yourapp.yamahaarranger.audio

import com.yourapp.yamahaarranger.ui.DebugLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioEngineManager @Inject constructor(
    private val bridge: NativeAudioBridge,
    private val sampleProvider: SampleProvider
) {
    private var started = false

    fun start() {
        if (started) {
            DebugLog.add("🎵 AudioEngine already started")
            return
        }
        DebugLog.add("🎵 AudioEngine.start() called…")
        val ok = bridge.nativeStart()
        started = ok
        if (!ok) {
            DebugLog.add("❌ AudioEngine FAILED to start — check RECORD_AUDIO permission or Oboe config")
        } else {
            DebugLog.add("✅ AudioEngine started OK")
        }
    }

    fun stop() {
        if (!started) return
        bridge.nativeStop()
        started = false
        DebugLog.add("🛑 AudioEngine stopped")
    }

    /** Test tone helper — bypass sample provider, pakai sample bawaan. */
    fun testTone(note: Int, velocity: Float) {
        val sample = sampleProvider.sampleForNote(note)
        if (sample == null) {
            DebugLog.add("❌ sampleForNote($note) returned NULL")
            return
        }
        DebugLog.add("🎵 testTone note=$note, sample.frames=${sample.frameCount}, " +
                "sampleRate=${sample.sampleRateHz}, rootNote=${sample.rootNote}")
        bridge.nativeNoteOn(
            note, sample.rootNote, velocity,
            sample.buffer, sample.frameCount, sample.sampleRateHz
        )
    }

    fun noteOn(midiNote: Int, velocity01: Float) {
        val sample = sampleProvider.sampleForNote(midiNote)
        if (sample == null) {
            Timber.w("sampleForNote($midiNote) returned null")
            return
        }
        bridge.nativeNoteOn(
            midiNote, sample.rootNote, velocity01,
            sample.buffer, sample.frameCount, sample.sampleRateHz
        )
    }

    fun noteOff(midiNote: Int) = bridge.nativeNoteOff(midiNote)
    fun allNotesOff() = bridge.nativeAllNotesOff()
}
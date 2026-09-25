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
    private var soundFontLoaded = false

    fun start() {
        if (started) return
        DebugLog.add("🎵 AudioEngine.start()…")

        bridge.nativeInitLogger()
        DebugLog.add("📋 Native logger initialized")

        started = bridge.nativeStart()
        DebugLog.add(if (started) "✅ AudioEngine OK" else "❌ AudioEngine FAILED")
    }

    fun stop() {
        if (!started) return
        bridge.nativeStop()
        started = false
        DebugLog.add("🛑 AudioEngine stopped")
    }

    fun addSoundFont(filePath: String): Boolean {
        DebugLog.add("🎼 Adding SF2…")
        val ok = bridge.nativeAddSoundFont(filePath)
        soundFontLoaded = soundFontLoaded || ok
        DebugLog.add(if (ok) "✅ Additional SF2 OK" else "❌ Additional SF2 FAILED")
        return ok
    }

    fun loadSoundFont(filePath: String): Boolean {
        DebugLog.add("🎼 Loading SF2…")
        val ok = bridge.nativeLoadSoundFont(filePath)
        soundFontLoaded = ok
        DebugLog.add(if (ok) "✅ SF2 OK" else "❌ SF2 FAILED")
        return ok
    }

    fun isSoundFontLoaded(): Boolean = soundFontLoaded

    fun unloadSoundFont() {
        bridge.nativeUnloadSoundFont()
        soundFontLoaded = false
    }

    fun noteOn(midiNote: Int, velocity01: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOn(midiNote, velocity01)
        } else {
            val sample = sampleProvider.sampleForNote(midiNote) ?: return
            bridge.nativeNoteOn(
                midiNote, sample.rootNote, velocity01,
                sample.buffer, sample.frameCount, sample.sampleRateHz
            )
        }
    }

    fun noteOff(midiNote: Int) {
        if (soundFontLoaded) bridge.nativeSfNoteOff(midiNote)
        else bridge.nativeNoteOff(midiNote)
    }

    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)
        } else {
            noteOn(midiNote, velocity01)
        }
    }

    fun noteOffChannel(channel: Int, midiNote: Int) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOffChannel(channel, midiNote)
        } else {
            noteOff(midiNote)
        }
    }

    fun setChannelProgram(channel: Int, program: Int, bank: Int = 0) {
        bridge.nativeSetChannelPreset(channel, bank, program)
        DebugLog.add("🎼 Ch$channel → prog=$program bank=$bank")
    }

    fun allNotesOff() = bridge.nativeAllNotesOff()

    fun testTone(note: Int, velocity: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOn(note, velocity)
            return
        }
        val sample = sampleProvider.sampleForNote(note) ?: return
        bridge.nativeNoteOn(
            note, sample.rootNote, velocity,
            sample.buffer, sample.frameCount, sample.sampleRateHz
        )
    }
}
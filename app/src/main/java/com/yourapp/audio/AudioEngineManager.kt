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
        DebugLog.add("🎵 AudioEngine.start() called…")
        started = bridge.nativeStart()
        if (!started) {
            DebugLog.add("❌ AudioEngine FAILED — check RECORD_AUDIO permission")
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

    /** Load SoundFont dari absolute path file di internal storage. */
    fun loadSoundFont(filePath: String): Boolean {
        DebugLog.add("🎼 Loading SF2: $filePath")
        val ok = bridge.nativeLoadSoundFont(filePath)
        soundFontLoaded = ok
        if (ok) {
            DebugLog.add("✅ SF2 loaded successfully")
        } else {
            DebugLog.add("❌ SF2 load failed")
        }
        return ok
    }

    fun isSoundFontLoaded(): Boolean = soundFontLoaded

    fun unloadSoundFont() {
        bridge.nativeUnloadSoundFont()
        soundFontLoaded = false
        DebugLog.add("🎼 SF2 unloaded")
    }

    /** Note on default channel 0 (piano). */
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
        if (soundFontLoaded) {
            bridge.nativeSfNoteOff(midiNote)
        } else {
            bridge.nativeNoteOff(midiNote)
        }
    }

    /** Kirim note ke channel spesifik (0-15). Ch 9 = drum. */
    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)
        } else {
            // Fallback: pakai sample mono (channel diabaikan)
            noteOn(midiNote, velocity01)
        }
    }

    /** Note-off dari channel spesifik. */
    fun noteOffChannel(channel: Int, midiNote: Int) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOffChannel(channel, midiNote)
        } else {
            noteOff(midiNote)
        }
    }

    fun allNotesOff() = bridge.nativeAllNotesOff()

    /** Test tone untuk diagnosa. */
    fun testTone(note: Int, velocity: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOn(note, velocity)
            DebugLog.add("🎵 SF TestTone note=$note")
            return
        }
        val sample = sampleProvider.sampleForNote(note)
        if (sample == null) {
            DebugLog.add("❌ sampleForNote($note) NULL")
            return
        }
        bridge.nativeNoteOn(
            note, sample.rootNote, velocity,
            sample.buffer, sample.frameCount, sample.sampleRateHz
        )
    }
}
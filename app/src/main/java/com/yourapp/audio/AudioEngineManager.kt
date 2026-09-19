package com.yourapp.yamahaarranger.audio

import com.yourapp.yamahaarranger.ui.DebugLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioEngineManager @Inject constructor(
    private val bridge: NativeAudioBridge,
    private val sampleProvider: SampleProvider
) {
    private var started = false
    private var soundFontLoaded = false
    private var nextSoundFontRole = 0 // 0=melody, 1=drum

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
        val ok = bridge.nativeLoadSoundFont(filePath)
        if (ok) {
            soundFontLoaded = true
            nextSoundFontRole = 1 - nextSoundFontRole
            DebugLog.add("✅ $role SF2 OK (multi-SF2)")
        } else {
            DebugLog.add("❌ $role SF2 FAILED")
        }
        return ok
    }

    fun isSoundFontLoaded(): Boolean = soundFontLoaded

    fun unloadSoundFont() {
        DebugLog.traceAudio("SF2 UNLOAD")
        bridge.nativeUnloadSoundFont()
        soundFontLoaded = false
        nextSoundFontRole = 0
        DebugLog.add("🗑️ All SF2 unloaded")
    }

    fun noteOn(midiNote: Int, velocity01: Float) {
        DebugLog.traceAudio("NOTE_ON ch=legacy note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOn(midiNote, velocity01)
        else {
            val sample = sampleProvider.sampleForNote(midiNote) ?: return
            bridge.nativeNoteOn(midiNote, sample.rootNote, velocity01, sample.buffer, sample.frameCount, sample.sampleRateHz)
        }
    }

    fun noteOff(midiNote: Int) {
        DebugLog.traceAudio("NOTE_OFF ch=legacy note=$midiNote sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOff(midiNote) else bridge.nativeNoteOff(midiNote)
    }

    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {
        DebugLog.traceAudio("NOTE_ON ch=$channel note=$midiNote vel=${"%.3f".format(java.util.Locale.US, velocity01)} sf2=$soundFontLoaded")
        if (soundFontLoaded) bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)
        else noteOn(midiNote, velocity01)
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

    fun setChannelProgram(channel: Int, program: Int, bank: Int = 0) {
        DebugLog.traceAudio("PROGRAM ch=$channel bank=$bank program=$program")
        bridge.nativeSetChannelPreset(channel, bank, program)
        DebugLog.add("🎼 Ch$channel → prog=$program bank=$bank")
    }

    fun allNotesOff() { DebugLog.traceAudio("ALL_NOTES_OFF"); bridge.nativeAllNotesOff() }

    fun testTone(note: Int, velocity: Float) {
        if (soundFontLoaded) {
            bridge.nativeSfNoteOn(note, velocity)
            return
        }
        val sample = sampleProvider.sampleForNote(note) ?: return
        bridge.nativeNoteOn(note, sample.rootNote, velocity, sample.buffer, sample.frameCount, sample.sampleRateHz)
    }
}
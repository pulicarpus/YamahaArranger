package com.yourapp.yamahaarranger.audio

import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioBridge @Inject constructor() {
    companion object {
        init { System.loadLibrary("yamaha_arranger_native") }
    }

    external fun nativeDrumChannelSelection(channel: Int): String
    external fun nativeSetDrumABMode(resolved: Boolean): Boolean
    external fun nativeBindDrumAB(channel: Int, pdta: String, bank: Int, pc: Int, section: String): Boolean
    /** Manual Rhythm 1/2 kit from any loaded SoundFont: exact font id + raw drum bank + program. */
    external fun nativeBindDrumManual(channel: Int, fontId: Long, bank: Int, pc: Int, section: String): Boolean
    external fun nativeDrumABReport(): String
    external fun nativeChannelBinding(channel: Int): String
    external fun nativeSetSourceTiered(tiered: Boolean)
    external fun nativeLatencyReport(): String
    external fun nativeLatencyReset()
    external fun nativePinChannelPreset(channel: Int, fontId: Long, bank: Int, program: Int): Boolean
    external fun nativeFontPdta(fontId: Long): String
    external fun nativeShadowDrumSnapshot(): String
    external fun nativeInitLogger()
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeNoteOn(midiNote: Int, rootNote: Int, velocity: Float, sampleBuffer: ByteBuffer, sampleFrames: Int, sampleRateHz: Int)
    external fun nativeNoteOff(midiNote: Int)
    external fun nativeAllNotesOff()
    external fun nativeLoadSoundFont(path: String): Boolean
    external fun nativeLoadMelodySoundFont(path: String): Boolean
    external fun nativeLoadMelodyFallbackSoundFont(path: String): Boolean
    /** Attach an SF2 for explicit selection only; the automatic resolver never uses it. */
    external fun nativeLoadMelodyManualSoundFont(path: String): Boolean
    external fun nativeLoadDrumSoundFont(path: String): Boolean
    external fun nativeIsSoundFontLoaded(): Boolean
    external fun nativeUnloadSoundFont()
    external fun nativeSfNoteOn(midiNote: Int, velocity: Float)
    external fun nativeSfNoteOff(midiNote: Int)
    external fun nativeSfNoteOnChannel(channel: Int, midiNote: Int, velocity: Float)
    external fun nativeSfNoteOnStyleChannel(channel: Int, midiNote: Int, velocity: Float,
        sourceChannel: Int, sourceNote: Int, styleBank: Int, tick: Long, id: Long, sampled: Boolean, chordId: Long, operation: Int)
    external fun nativeArmChordDiagnostic()
    external fun nativeMarkChordDiagnostic(id: Long)
    external fun nativeCompactChordDiagnosticReport(): String
    external fun nativeStopChordDiagnostic()
    external fun nativeChordDiagnosticReport(): String
    external fun nativeSfNoteOffStyleChannel(channel: Int, note: Int, sourceChannel: Int,
        sourceNote: Int, bank: Int, tick: Long, id: Long, chordId: Long, operation: Int)
    external fun nativeSfNoteOffChannel(channel: Int, midiNote: Int)
    external fun nativeStopKeyboardPart(channel: Int): Boolean
    external fun nativeSetKeyboardPreset(channel: Int, fontId: Long, bank: Int, program: Int): Boolean
    external fun nativeSetChannelPreset(channel: Int, bank: Int, program: Int)
    external fun nativeSetChannelPresetWithName(channel: Int, bank: Int, program: Int, voiceName: String)
    external fun nativeSetChannelMixer(channel: Int, volume: Int, pan: Int, expression: Int, reverbSend: Int, chorusSend: Int)
    external fun nativeSetChannelExpression(channel: Int, expression: Int)
    external fun nativeSetKeyboardSustain(enabled: Boolean)
    external fun nativeSetKeyboardReleaseTime(releaseTime: Int): Boolean
    external fun nativeSetMasterGain(gain: Float)
    external fun nativeSetInternalGainDb(db: Float)
    external fun nativeInternalGainReport(): String
    external fun nativeInternalGainReset()
    external fun nativeGetSoundFontPresets(): String
    external fun nativeKeyboardVoiceSnapshot(): String
    external fun nativeRightVoiceDiagnostic(): String
    external fun nativeSetBufferMode(mode: Int): Int
    external fun nativeBufferStatus(): String
    external fun nativeBufferTick()
    external fun nativeRightOnsetReport(channel: Int, key: Int): String
    external fun nativeArmLiveOnset(): Boolean
    external fun nativeLiveOnsetStatus(): String
    external fun nativeLiveOnsetReport(): String
    external fun nativeResetRightVelocityStats()
    external fun nativeDiagnosticKeyboardReleaseWav(channel: Int, key: Int, velocity: Int): ByteArray
    external fun nativeKeyboardReleaseProbeReport(): String
    external fun nativeDiagnosticDrumWav(bank: Int, pc: Int, key: Int, velocity: Int): ByteArray
    external fun nativeMarkPcmSession(muteMask: Int, knownMask: Int, changes: Long, lastChangeNs: Long): Long
    external fun nativePcmSessionReport(muteMask: Int, knownMask: Int, changes: Long, lastChangeNs: Long): String
    external fun nativeGetNoteZoneReport(): String
    external fun nativeGetDrumKitCoverage(histogram: IntArray): String
    external fun nativeGetDrumCompatibilityReport(histogram: IntArray): String
    external fun nativeGetDrumCompatibilityComparison(histogram: IntArray, kits: IntArray): String

    external fun nativeMetronomeConfigure(enabled: Boolean, mode: Int, running: Boolean, bpmX100: Int, numerator: Int, denominator: Int, volumePermille: Int)
    external fun nativeMetronomePush(targetNs: Long, level: Int, generation: Int): Boolean
    external fun nativeMetronomeGeneration(): Int
    external fun nativeMetronomeBumpGeneration(): Int
    external fun nativeMetronomeBeat(): Long
    external fun nativeMetronomeReport(): String
}

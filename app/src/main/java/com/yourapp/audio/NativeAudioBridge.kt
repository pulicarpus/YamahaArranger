package com.yourapp.yamahaarranger.audio

import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioBridge @Inject constructor() {
    companion object {
        init { System.loadLibrary("yamaha_arranger_native") }
    }

    external fun nativeInitLogger()
    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeNoteOn(midiNote: Int, rootNote: Int, velocity: Float, sampleBuffer: ByteBuffer, sampleFrames: Int, sampleRateHz: Int)
    external fun nativeNoteOff(midiNote: Int)
    external fun nativeAllNotesOff()
    external fun nativeLoadSoundFont(path: String): Boolean
    external fun nativeIsSoundFontLoaded(): Boolean
    external fun nativeUnloadSoundFont()
    external fun nativeSfNoteOn(midiNote: Int, velocity: Float)
    external fun nativeSfNoteOff(midiNote: Int)
    external fun nativeSfNoteOnChannel(channel: Int, midiNote: Int, velocity: Float)
    external fun nativeSfNoteOffChannel(channel: Int, midiNote: Int)
    external fun nativeSetChannelPreset(channel: Int, bank: Int, program: Int)
}
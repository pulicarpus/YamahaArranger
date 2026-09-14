package com.yourapp.yamahaarranger.audio

import java.nio.ByteBuffer

/**
 * Thin JNI wrapper around AudioEngine (native/audio_engine.cpp).
 * Every call here must stay cheap — noteOn/noteOff are on the input
 * hot path (on-screen keyboard taps, MIDI-in events).
 */
class NativeAudioBridge {

    companion object {
        init {
            System.loadLibrary("yamaha_arranger_native")
        }
    }

    external fun nativeStart(): Boolean
    external fun nativeStop()

    /**
     * @param sampleByteBuffer a *direct* FloatBuffer-backed ByteBuffer of the
     *   mono sample data, kept alive by the caller (e.g. SoundFontManager)
     *   for as long as notes might reference it.
     */
    external fun nativeNoteOn(
        midiNote: Int,
        rootNote: Int,
        velocity: Float,
        sampleByteBuffer: ByteBuffer,
        sampleFrames: Int,
        sampleRateHz: Int
    )

    external fun nativeNoteOff(midiNote: Int)
    external fun nativeAllNotesOff()
}

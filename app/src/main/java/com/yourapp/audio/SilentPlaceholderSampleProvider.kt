package com.yourapp.yamahaarranger.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates a single one-second sine-wave "sample" at C4 (MIDI 60, 261.6Hz)
 * and lets Voice's pitch-shifting resample it across the keyboard. This is
 * ONLY here to prove the JNI -> Oboe -> speaker path works before a real
 * SoundFont player exists — replace via AppModule once SF2/SFZ loading
 * (TinySoundFont) lands in Phase 2.
 */
class SilentPlaceholderSampleProvider : SampleProvider {

    private val rootNote = 60
    private val sampleRateHz = 44100
    private val buffer: ByteBuffer
    private val frameCount: Int

    init {
        val seconds = 1.0
        frameCount = (sampleRateHz * seconds).toInt()
        buffer = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.nativeOrder())
        val freqHz = 261.626 // C4
        for (i in 0 until frameCount) {
            // Simple decaying sine so held/pitched notes don't drone forever
            // even before the ADSR release stage kicks in on noteOff.
            val t = i / sampleRateHz.toDouble()
            val decay = Math.exp(-t * 1.2)
            val value = (sin(2 * PI * freqHz * t) * decay).toFloat()
            buffer.putFloat(i * 4, value)
        }
    }

    override fun sampleForNote(midiNote: Int): NoteSample {
        return NoteSample(buffer, frameCount, sampleRateHz, rootNote)
    }
}

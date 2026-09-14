package com.yourapp.yamahaarranger.audio

import java.nio.ByteBuffer

data class NoteSample(
    val buffer: ByteBuffer, // direct, float32, mono
    val frameCount: Int,
    val sampleRateHz: Int,
    val rootNote: Int
)

/**
 * Phase 1: a single hard-coded sample (e.g. one piano note) pitch-shifted
 * across the keyboard via Voice's resampling, just to validate the audio
 * path end-to-end.
 *
 * Phase 2 TODO: replace with a real SoundFont-backed implementation
 * (wrapping TinySoundFont or FluidSynth) that picks the correct
 * sample/zone per MIDI note + velocity + selected GM/XG program, so
 * quality moves from "one stretched sample" to proper multi-sampled
 * instruments.
 */
interface SampleProvider {
    fun sampleForNote(midiNote: Int): NoteSample?
}

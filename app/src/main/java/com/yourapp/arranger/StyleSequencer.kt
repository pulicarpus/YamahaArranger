package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives playback of one style section: walks its note events in tick
 * order, sleeping between them based on the current tempo, looping back to
 * tick 0 at `lengthTicks`, and re-transposing every note through
 * [NoteTransposer] against whatever chord is currently held.
 *
 * IMPORTANT (Phase 2 known limitation): this scheduler runs on a Kotlin
 * coroutine using `delay()`, which is NOT sample-accurate — timer jitter
 * from the OS scheduler means bar timing can drift a few ms, acceptable
 * for now but not the "real-time MIDI processing" bar the full spec asks
 * for. Phase 2b/3 should move bar-accurate scheduling into the native
 * audio callback (sample-clock-driven, like a real sequencer) instead of
 * a coroutine loop — flagged here rather than glossed over.
 *
 * Parts named "Rhythm" (drums) are played back un-transposed since drum
 * channels map hit type to note number, not pitch.
 */
class StyleSequencer(
    private val audioEngine: AudioEngineManager,
    private val scope: CoroutineScope
) {
    private var playbackJob: Job? = null
    var tempoBpm: Int = 120
    var currentChord: DetectedChord? = null

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        playbackJob = scope.launch {
            while (true) {
                playOnce(section, ppq)
            }
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioEngine.allNotesOff()
    }

    /** Swap the section that plays on the *next* loop boundary, called by
     * ArrangerBrain when the user taps a new section button — avoids
     * cutting off mid-bar, per the "quantize to bar" requirement. */
    fun queueNextSection(section: StyleSectionModel, ppq: Int) {
        play(section, ppq) // Phase 2 simplification: restarts immediately.
        // TODO(Phase 2b): don't cancel the running job; instead swap the
        // section reference and let the current loop finish its bar first.
    }

    private suspend fun playOnce(section: StyleSectionModel, ppq: Int) {
        if (section.lengthTicks <= 0) {
            Timber.w("Section ${section.name} has zero length, skipping")
            return
        }

        // Merge all parts into one time-ordered event stream; Rhythm
        // parts skip transposition, melodic parts go through NoteTransposer.
        data class ScheduledEvent(val tick: Int, val event: StyleNoteEvent, val transpose: Boolean)
        val merged = section.parts.flatMap { part ->
            val shouldTranspose = !part.name.contains("rhythm", ignoreCase = true) &&
                                   !part.name.contains("drum", ignoreCase = true)
            part.events.map { ScheduledEvent(it.tick, it, shouldTranspose) }
        }.sortedBy { it.tick }

        var lastTick = 0
        for (scheduled in merged) {
            val deltaTicks = scheduled.tick - lastTick
            if (deltaTicks > 0) {
                delay(ticksToMillis(deltaTicks, ppq, tempoBpm))
            }
            lastTick = scheduled.tick

            val note = if (scheduled.transpose) {
                currentChord?.let { NoteTransposer.transpose(scheduled.event.note, it) }
                    ?: scheduled.event.note
            } else {
                scheduled.event.note
            }

            if (scheduled.event.isNoteOn) {
                audioEngine.noteOn(note, scheduled.event.velocity / 127f)
            } else {
                audioEngine.noteOff(note)
            }
        }

        // Wait out any remaining silence to the end of the bar before looping.
        val remaining = section.lengthTicks - lastTick
        if (remaining > 0) delay(ticksToMillis(remaining, ppq, tempoBpm))
    }

    private fun ticksToMillis(ticks: Int, ppq: Int, bpm: Int): Long {
        val msPerBeat = 60_000.0 / bpm
        val msPerTick = msPerBeat / ppq
        return (ticks * msPerTick).toLong().coerceAtLeast(0)
    }
}

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class StyleSequencer(
    private val audioEngine: AudioEngineManager,
    private val scope: CoroutineScope
) {
    private var playbackJob: Job? = null
    var tempoBpm: Int = 120
    var currentChord: DetectedChord? = null
    private var loopCount = 0

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        loopCount = 0
        val totalEvents = section.parts.sumOf { it.events.size }
        DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=$totalEvents, len=${section.lengthTicks} ticks, ppq=$ppq, bpm=$tempoBpm")
        section.parts.forEach { part ->
            DebugLog.add("  · Part '${part.name}': ${part.events.size} events")
        }

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
        DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) {
        play(section, ppq)
    }

    private suspend fun playOnce(section: StyleSectionModel, ppq: Int) {
        loopCount++
        if (section.lengthTicks <= 0) {
            DebugLog.add("⚠ Section ${section.name} length=0, skip")
            delay(500)
            return
        }

        data class ScheduledEvent(
            val tick: Int,
            val event: StyleNoteEvent,
            val transpose: Boolean,
            val isDrum: Boolean
        )

        val merged = section.parts.flatMap { part ->
            val isDrum = part.name.contains("rhythm", ignoreCase = true) ||
                          part.name.contains("drum", ignoreCase = true)
            val shouldTranspose = !isDrum
            part.events.map { ScheduledEvent(it.tick, it, shouldTranspose, isDrum) }
        }.sortedBy { it.tick }

        if (merged.isEmpty()) {
            DebugLog.add("⚠ Loop $loopCount: NO EVENTS → test tone")
            playTestTone()
            return
        }

        if (loopCount <= 2) {
            DebugLog.add("🔄 Loop $loopCount: ${merged.size} events, tick ${merged.first().tick}→${merged.last().tick}")
        }

        var lastTick = 0
        var noteOnCount = 0
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

            // Detect part: rhythm/drum → channel 9, else channel 0
            val channel = if (scheduled.isDrum) 9 else 0

            if (scheduled.event.isNoteOn) {
                audioEngine.noteOnChannel(channel, note, scheduled.event.velocity / 127f)
                noteOnCount++
                if (loopCount <= 2 && noteOnCount <= 5) {
                    DebugLog.add("  ♪ On ch=$channel n=$note v=${scheduled.event.velocity}")
                }
            } else {
                audioEngine.noteOffChannel(channel, note)
            }
        }

        if (loopCount <= 2) {
            DebugLog.add("✅ Loop $loopCount done: $noteOnCount noteOn sent")
        }

        val remaining = section.lengthTicks - lastTick
        if (remaining > 0) delay(ticksToMillis(remaining, ppq, tempoBpm))
    }

    private suspend fun playTestTone() {
        val notes = intArrayOf(60, 64, 67)
        repeat(4) {
            for (note in notes) {
                audioEngine.noteOnChannel(0, note, 0.8f)
                delay(120)
                audioEngine.noteOffChannel(0, note)
            }
            delay(200)
        }
    }

    private fun ticksToMillis(ticks: Int, ppq: Int, bpm: Int): Long {
        if (ppq <= 0 || bpm <= 0) return 0
        val msPerBeat = 60_000.0 / bpm
        val msPerTick = msPerBeat / ppq
        return (ticks * msPerTick).toLong().coerceAtLeast(0)
    }
}
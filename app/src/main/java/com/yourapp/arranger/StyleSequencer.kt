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

    /** Map part name → channel. Rhythm/drum selalu ch 9. */
    private fun channelForPart(partName: String): Int {
        val n = partName.lowercase()
        return when {
            n.contains("rhythm") || n.contains("drum") -> 9
            n.contains("bass") -> 2
            n.contains("chord1") || (n.contains("chord") && !n.contains("2")) -> 3
            n.contains("chord2") -> 4
            n.contains("pad") -> 5
            n.contains("phrase1") || (n.contains("phrase") && !n.contains("2")) -> 6
            n.contains("phrase2") -> 7
            else -> 0
        }
    }

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        loopCount = 0
        val totalEvents = section.parts.sumOf { it.events.size }
        DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=$totalEvents")
        section.parts.forEach { part ->
            val ch = channelForPart(part.name)
            DebugLog.add("  · ${part.name} → ch$ch (${part.events.size} ev)")
        }
        playbackJob = scope.launch {
            while (true) playOnce(section, ppq)
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioEngine.allNotesOff()
        DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) = play(section, ppq)

    private suspend fun playOnce(section: StyleSectionModel, ppq: Int) {
        loopCount++
        if (section.lengthTicks <= 0) {
            delay(500)
            return
        }

        data class ScheduledEvent(
            val tick: Int,
            val event: StyleNoteEvent,
            val transpose: Boolean,
            val channel: Int
        )

        val merged = section.parts.flatMap { part ->
            val ch = channelForPart(part.name)
            val isDrum = ch == 9
            val shouldTranspose = !isDrum
            part.events.map { ScheduledEvent(it.tick, it, shouldTranspose, ch) }
        }.sortedBy { it.tick }

        if (merged.isEmpty()) {
            DebugLog.add("⚠ Loop $loopCount: NO EVENTS")
            delay(500)
            return
        }

        var lastTick = 0
        var noteOnCount = 0
        for (sched in merged) {
            val delta = sched.tick - lastTick
            if (delta > 0) delay(ticksToMillis(delta, ppq, tempoBpm))
            lastTick = sched.tick

            val note = if (sched.transpose) {
                currentChord?.let { NoteTransposer.transpose(sched.event.note, it) }
                    ?: sched.event.note
            } else {
                sched.event.note
            }

            if (sched.event.isNoteOn) {
                audioEngine.noteOnChannel(sched.channel, note, sched.event.velocity / 127f)
                noteOnCount++
                if (loopCount <= 1 && noteOnCount <= 6) {
                    DebugLog.add("  ♪ ch${sched.channel} n=$note v=${sched.event.velocity}")
                }
            } else {
                audioEngine.noteOffChannel(sched.channel, note)
            }
        }
        if (loopCount <= 1) DebugLog.add("✅ Loop1: $noteOnCount noteOn")

        val rem = section.lengthTicks - lastTick
        if (rem > 0) delay(ticksToMillis(rem, ppq, tempoBpm))
    }

    private fun ticksToMillis(ticks: Int, ppq: Int, bpm: Int): Long {
        if (ppq <= 0 || bpm <= 0) return 0
        return ((ticks * (60_000.0 / bpm)) / ppq).toLong().coerceAtLeast(0)
    }
}
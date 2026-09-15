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

class StyleSequencer(
    private val audioEngine: AudioEngineManager,
    private val scope: CoroutineScope
) {
    private var playbackJob: Job? = null
    var tempoBpm: Int = 120
    var currentChord: DetectedChord? = null
    private var loopCount = 0

    private var voiceMap: Map<Int, String> = emptyMap()
    private var lastAppliedSection: String = ""

    fun setVoiceMap(vm: Map<Int, String>) {
        voiceMap = vm
        lastAppliedSection = ""
    }

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        loopCount = 0

        if (lastAppliedSection != section.name) {
            applyAutoVoicesForSection(section)
            lastAppliedSection = section.name
        }

        val totalEvents = section.parts.sumOf { it.events.size }
        DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=$totalEvents")

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

    /** Auto-detect part type dari note pattern, assign GM program. */
    private fun applyAutoVoicesForSection(section: StyleSectionModel) {
        DebugLog.add("🎼 Auto voice for ${section.name}:")

        // Group events by original channel
        val channelEvents = mutableMapOf<Int, MutableList<StyleNoteEvent>>()
        section.parts.forEach { part ->
            part.events.filter { it.isNoteOn }.forEach { ev ->
                channelEvents.getOrPut(ev.channel) { mutableListOf() }.add(ev)
            }
        }

        channelEvents.forEach { (ch, events) ->
            if (events.isEmpty()) return@forEach

            val notes = events.map { it.note }
            val avgNote = notes.average().toInt()
            val minNote = notes.minOrNull() ?: 0
            val maxNote = notes.maxOrNull() ?: 0
            val noteSpan = maxNote - minNote

            // Detect drum: banyak note di range drum dengan pola khas
            val drumHits = notes.count { it in intArrayOf(35, 36, 38, 40, 42, 44, 46, 49, 51, 57, 59) }
            val isDrumChannel = events.size > 20 && drumHits.toFloat() / events.size > 0.4f

            val (program, bank, type) = when {
                isDrumChannel -> Triple(0, 128, "DRUM")
                avgNote < 46 -> Triple(33, 0, "BASS")
                avgNote < 60 -> Triple(0, 0, "PIANO/CHORD")
                avgNote < 68 -> Triple(24, 0, "GUITAR")
                else -> Triple(56, 0, "MELODY")
            }

            // Keep original channel, apply program
            audioEngine.setChannelProgram(ch, program, bank)
            DebugLog.add("  · ch$ch ($type): avg=$avgNote span=$noteSpan range=$minNote-$maxNote n=${events.size} → prog$program bank$bank")
        }
    }

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

        // Pakai channel ASLI dari file, tidak remap
        val merged = section.parts.flatMap { part ->
            part.events.map { ev ->
                val isDrum = ev.channel == 9
                val shouldTranspose = !isDrum
                ScheduledEvent(ev.tick, ev, shouldTranspose, ev.channel)
            }
        }.sortedBy { it.tick }

        if (merged.isEmpty()) {
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
                if (loopCount <= 1 && noteOnCount <= 8) {
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
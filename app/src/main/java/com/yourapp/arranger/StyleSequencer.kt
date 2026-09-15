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

    // Mapping partNum (1-8) → target channel (0-index Yamaha standard)
    private val targetChannelForPart = intArrayOf(
        -1,  // 0 (unused)
        8,   // 1 → Rhythm 1
        9,   // 2 → Rhythm 2
        10,  // 3 → Bass
        11,  // 4 → Chord 1
        12,  // 5 → Chord 2
        13,  // 6 → Pad
        14,  // 7 → Phrase 1
        15   // 8 → Phrase 2
    )

    fun setVoiceMap(vm: Map<Int, String>) {
        voiceMap = vm
        lastAppliedSection = ""
        DebugLog.add("🎼 VoiceMap set: ${vm.size} entries")
    }

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        loopCount = 0

        if (lastAppliedSection != section.name) {
            applyVoicesForSection(section)
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

    private fun applyVoicesForSection(section: StyleSectionModel) {
        DebugLog.add("🎼 Apply voices for ${section.name}:")

        section.parts.forEachIndexed { idx, part ->
            val partNum = idx + 1
            val voiceName = voiceMap[partNum] ?: return@forEachIndexed
            val targetCh = if (partNum in 1..8) targetChannelForPart[partNum] else return@forEachIndexed

            val program = guessProgramFromVoiceName(voiceName)
            if (program < 0) {
                DebugLog.add("  · part$partNum ch$targetCh: $voiceName (unknown)")
                return@forEachIndexed
            }

            val bank = if (isDrumVoice(voiceName)) 128 else 0
            audioEngine.setChannelProgram(targetCh, program, bank)
            DebugLog.add("  · part$partNum → ch$targetCh: $voiceName → prog$program (bank$bank)")
        }
    }

    private fun guessProgramFromVoiceName(name: String): Int {
        val n = name.lowercase()

        val trailingDigits = n.takeLastWhile { it.isDigit() }
        if (trailingDigits.isNotEmpty()) {
            val num = trailingDigits.toIntOrNull()
            if (num != null && num in 0..127) return num
        }

        return when {
            n.contains("piano") -> 0
            n.contains("ep") -> 4
            n.contains("organ") -> 16
            n.contains("accordion") -> 21
            n.contains("guitar") || n.contains("gtr") -> 24
            n.contains("bass") -> 33
            n.contains("violin") -> 40
            n.contains("cello") -> 42
            n.contains("strg") || n.contains("str") -> 48
            n.contains("choir") -> 52
            n.contains("trumpet") -> 56
            n.contains("trombone") -> 57
            n.contains("brass") -> 61
            n.contains("sax") -> 65
            n.contains("oboe") -> 68
            n.contains("clarinet") -> 71
            n.contains("flute") -> 73
            n.contains("dr") || n.contains("kit") -> 0
            else -> -1
        }
    }

    private fun isDrumVoice(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("dr") || n.contains("drum") || n.contains("kit")
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
            val targetChannel: Int
        )

        // Remap: source channel → target channel berdasarkan partNum
        val merged = section.parts.flatMapIndexed { idx, part ->
            val partNum = idx + 1
            val targetCh = if (partNum in 1..8) targetChannelForPart[partNum] else 0
            val isDrum = targetCh == 8 || targetCh == 9
            val shouldTranspose = !isDrum

            part.events.map { ev ->
                ScheduledEvent(ev.tick, ev, shouldTranspose, targetCh)
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
                audioEngine.noteOnChannel(sched.targetChannel, note, sched.event.velocity / 127f)
                noteOnCount++
                if (loopCount <= 1 && noteOnCount <= 8) {
                    DebugLog.add("  ♪ ch${sched.targetChannel} n=$note v=${sched.event.velocity}")
                }
            } else {
                audioEngine.noteOffChannel(sched.targetChannel, note)
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
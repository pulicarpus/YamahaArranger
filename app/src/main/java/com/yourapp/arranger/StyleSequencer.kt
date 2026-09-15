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
            // Debug log awal
            val totalEvents = section.parts.sumOf { it.events.size }
            Timber.i("▶ StyleSequencer.play: ${section.name} " +
                    "parts=${section.parts.size} events=$totalEvents " +
                    "lengthTicks=${section.lengthTicks} ppq=$ppq bpm=$tempoBpm")

            // DEBUG: log tiap part + jumlah event
            section.parts.forEach { part ->
                Timber.d("  Part '${part.name}': ${part.events.size} events")
            }

            while (true) {
                playOnce(section, ppq)
            }
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioEngine.allNotesOff()
        Timber.i("⏹ StyleSequencer.stop")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) {
        play(section, ppq)
    }

    private suspend fun playOnce(section: StyleSectionModel, ppq: Int) {
        if (section.lengthTicks <= 0) {
            Timber.w("Section ${section.name} has zero length, skipping")
            delay(500)
            return
        }

        data class ScheduledEvent(val tick: Int, val event: StyleNoteEvent, val transpose: Boolean)

        val merged = section.parts.flatMap { part ->
            val shouldTranspose = !part.name.contains("rhythm", ignoreCase = true) &&
                                   !part.name.contains("drum", ignoreCase = true)
            part.events.map { ScheduledEvent(it.tick, it, shouldTranspose) }
        }.sortedBy { it.tick }

        // ═══════════════════════════════════════════════════
        // DEBUG: kalau tidak ada events, main test tone
        // ═══════════════════════════════════════════════════
        if (merged.isEmpty()) {
            Timber.w("⚠ No note events in '${section.name}' — playing TEST TONE")
            playTestTone()
            return
        }

        Timber.d("Looping ${merged.size} events (first tick=${merged.first().tick}, " +
                "last tick=${merged.last().tick}, lengthTicks=${section.lengthTicks})")

        // Hitung total loop duration
        val loopDurationMs = ticksToMillis(section.lengthTicks, ppq, tempoBpm)
        val startTime = System.currentTimeMillis()

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
                Timber.v("  ♪ NoteOn  tick=${scheduled.tick} note=$note " +
                        "vel=${scheduled.event.velocity}")
            } else {
                audioEngine.noteOff(note)
                Timber.v("  ♪ NoteOff tick=${scheduled.tick} note=$note")
            }
        }

        // ═══════════════════════════════════════════════════
        // DEBUG: pastikan total waktu sesuai (kalau drift > 100ms, warn)
        // ═══════════════════════════════════════════════════
        val elapsed = System.currentTimeMillis() - startTime
        val drift = elapsed - loopDurationMs
        if (drift > 100) {
            Timber.w("Loop drift: expected ${loopDurationMs}ms, actual ${elapsed}ms (drift=${drift}ms)")
        }

        // Tunggu sisa bar
        val remaining = section.lengthTicks - lastTick
        if (remaining > 0) delay(ticksToMillis(remaining, ppq, tempoBpm))
    }

    /** Test tone: C-E-G arpeggio, 4× per detik, untuk konfirmasi audio engine hidup. */
    private suspend fun playTestTone() {
        val notes = intArrayOf(60, 64, 67) // C major
        repeat(8) {
            for (note in notes) {
                audioEngine.noteOn(note, 0.8f)
                delay(120)
                audioEngine.noteOff(note)
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
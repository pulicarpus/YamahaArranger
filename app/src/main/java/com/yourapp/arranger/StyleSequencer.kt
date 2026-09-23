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
    private var currentChordState: DetectedChord? = null
    private var loopCount = 0

    private var voiceMap: Map<Int, String> = emptyMap()
    private var lastAppliedSection: String = ""

    // FIX (menyertai ArrangerBrain.updateLockedChannels yang tadinya
    // Unresolved reference): channel yang di-lock user lewat UI TIDAK
    // boleh ditimpa oleh applyVoicesFromCasm() saat ganti section.
    private var lockedChannels: Set<Int> = emptySet()

    fun setLockedChannels(channels: Set<Int>) {
        lockedChannels = channels
        DebugLog.add("🔒 Locked channels updated: $channels")
    }

    // BUGFIX (note-off mismatch / "sumbang"): key = "channel:originalNote",
    // value = the note actually sent to noteOnChannel(). currentChord can
    // change (user re-fingers a chord) *between* a note-on and its matching
    // note-off — if note-off recomputes the transpose against whatever
    // currentChord is *now*, it sends an off for the WRONG pitch, so the
    // original transposed note never gets a note-off and hangs. Every
    // subsequent chord change piles on another stuck note, which is what
    // produces the "berantakan/sumbang" (chaotic/dissonant) sound over
    // time. Fix: compute the transpose once at note-on, remember it here,
    // and reuse that exact value for the matching note-off.
    private data class ActiveVoice(
        val channel: Int,
        val sourceNote: Int,
        var soundingNote: Int,
        val velocity: Float,
        val policy: com.yourapp.yamahaarranger.style.YamahaCasmPolicy?
    )

    private val activeVoices = mutableMapOf<String, ActiveVoice>()

    /**
     * Apply a live chord change to sustaining accompaniment notes.
     * Yamaha uses CASM RTR to decide whether a note stops, pitch-shifts, or
     * retriggers. FluidSynth pitch-bend is channel-wide, so for safety we
     * revoice individual notes rather than bending an entire channel.
     */
    fun setCurrentChord(chord: DetectedChord?) {
        val previous = currentChordState
        currentChordState = chord
        if (chord == null || previous == chord) return

        val snapshot = activeVoices.toList()
        for ((key, voice) in snapshot) {
            val rtr = voice.policy?.rtr ?: 3
            if (rtr == 0) {
                audioEngine.noteOffChannel(voice.channel, voice.soundingNote)
                activeVoices.remove(key)
                continue
            }

            val newNote = NoteTransposer.transpose(
                voice.sourceNote,
                chord,
                voice.channel,
                voice.policy
            )
            if (newNote == voice.soundingNote) continue

            audioEngine.noteOffChannel(voice.channel, voice.soundingNote)
            audioEngine.noteOnChannel(voice.channel, newNote, voice.velocity)
            activeVoices[key] = voice.copy(soundingNote = newNote)
        }
        DebugLog.add("🎹 CASM RTR revoice: ${activeVoices.size} active notes")
    }

    fun setVoiceMap(vm: Map<Int, String>) {
        voiceMap = vm
        lastAppliedSection = ""
        DebugLog.add("🎼 VoiceMap set: ${vm.size} entries")
    }

    /**
     * @param loopLimit -1 = loop selamanya (dipakai untuk Main/Intro/Ending).
     *   Angka positif = main sejumlah itu loop, lalu STOP dan panggil
     *   [onComplete] — dipakai untuk Fill, yang harus main sekali lalu
     *   pindah ke Main target.
     *
     * BUGFIX ("klik Main A-D jadi Fill semua"): sebelumnya tidak ada cara
     * untuk membatasi jumlah loop, jadi rantai fill->main di ArrangerBrain
     * (`thenPlay`) cuma jadi TODO yang tidak pernah benar-benar jalan —
     * Fill yang sudah mulai loop selamanya, dan klik Main berikutnya cuma
     * memicu Fill baru lagi, bukan pernah benar-benar sampai ke Main.
     */
    fun play(
        section: StyleSectionModel,
        ppq: Int,
        loopLimit: Int = -1,
        onComplete: (() -> Unit)? = null
    ) {
        stop()
        loopCount = 0

        // Apply CASM voice per part saat ganti section
        if (lastAppliedSection != section.name) {
            applyVoicesFromCasm(section)
            lastAppliedSection = section.name
        }

        val totalEvents = section.parts.sumOf { it.events.size }
        DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=$totalEvents, loopLimit=$loopLimit")

        playbackJob = scope.launch {
            var loopsPlayed = 0
            while (loopLimit < 0 || loopsPlayed < loopLimit) {
                playOnce(section, ppq)
                loopsPlayed++
            }
            DebugLog.add("↪ ${section.name} selesai ($loopsPlayed loop), chaining...")
            onComplete?.invoke()
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioEngine.allNotesOff()
        // BUGFIX: must clear alongside allNotesOff(), otherwise stale
        // entries here would make the *next* section's note-offs reuse
        // pitches from a section that's no longer playing.
        activeVoices.clear()
        DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) = play(section, ppq)

    /**
     * Apply CASM voice per part.
     * Pakai channel ASLI dari file (tidak remap).
     */
    private fun applyVoicesFromCasm(section: StyleSectionModel) {
        if (voiceMap.isEmpty()) {
            DebugLog.add("⚠ No voice map available, using SF2 defaults")
            return
        }

        DebugLog.add("🎼 Apply CASM voices for ${section.name}:")
        section.parts.forEachIndexed { idx, part ->
            val partNum = idx + 1
            val voiceName = voiceMap[partNum] ?: return@forEachIndexed

            // Ambil channel asli dari file
            val ch = part.events.firstOrNull()?.channel ?: return@forEachIndexed

            // FIX: channel yang di-lock user tidak boleh ditimpa CASM.
            if (ch in lockedChannels) {
                DebugLog.add("  · part$partNum ch$ch: SKIP (locked by user)")
                return@forEachIndexed
            }

            val prog = guessProgramFromVoiceName(voiceName)
            if (prog < 0) {
                DebugLog.add("  · part$partNum ch$ch: $voiceName (unknown)")
                return@forEachIndexed
            }

            val bank = if (isDrumVoice(voiceName)) 128 else 0
            audioEngine.setChannelProgram(ch, prog, bank)
            DebugLog.add("  · part$partNum ch$ch: $voiceName → prog$prog (bank$bank)")
        }
    }

    /** Extract GM program dari nama voice CASM.
     *
     * KNOWN LIMITATION (not fixed here — separate from the note-off bug):
     * the trailing-digit shortcut below assumes a voice name's trailing
     * number IS a GM program number. In real CASM data that number is
     * usually Yamaha's own internal voice ID, which does not line up with
     * GM program numbers except by coincidence. This can pick the wrong
     * *timbre* (e.g. wrong kind of bass/guitar), but does not affect
     * pitch/timing the way the note-off bug did. Worth revisiting once
     * you're chasing "wrong instrument sound" rather than "wrong pitch".
     */
    private fun guessProgramFromVoiceName(name: String): Int {
        val n = name.lowercase()

        // 1) Coba extract digit di akhir (misal "bass33" → 33)
        val trailingDigits = n.takeLastWhile { it.isDigit() }
        if (trailingDigits.isNotEmpty()) {
            val num = trailingDigits.toIntOrNull()
            if (num != null && num in 0..127) return num
        }

        // 2) Keyword-based fallback
        return when {
            n.contains("piano") -> 0
            n.contains("e.piano") || n.contains("ep") -> 4
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
            n.contains("dr") || n.contains("kit") || n.contains("drum") -> 0
            else -> -1
        }
    }

    private fun isDrumVoice(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("add-dr") ||
               n.contains("drum") ||
               n.contains("kit") ||
               n.contains("rhythm") ||
               n.startsWith("dr")
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

        // Pakai channel ASLI dari file — TIDAK remap
        val merged = section.parts.flatMap { part ->
            part.events.map { ev ->
                val isDrum = ev.channel == 9
                val shouldTranspose = !isDrum
                ScheduledEvent(ev.tick, ev, shouldTranspose, ev.channel)
            }
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

            // BUGFIX: key identifies "this physical note slot" (channel +
            // the note number as written in the style file) so note-on and
            // its matching note-off agree on which *actual sounding pitch*
            // they're talking about, even if currentChord changes in
            // between.
            val key = "${sched.channel}:${sched.event.note}"

            if (sched.event.isNoteOn) {
                // Compute the transpose ONCE, here, and remember it.
                val note = if (sched.transpose) {
                    currentChordState?.let { chord ->
                        val partPolicy = section.parts.firstOrNull { it.channel == sched.channel }?.casmPolicy
                        NoteTransposer.transpose(sched.event.note, chord, sched.channel, partPolicy)
                    } ?: sched.event.note
                } else {
                    sched.event.note
                }
                val velocity01 = sched.event.velocity / 127f
                activeVoices[key] = ActiveVoice(
                    channel = sched.channel,
                    sourceNote = sched.event.note,
                    soundingNote = note,
                    velocity = velocity01,
                    policy = section.parts.firstOrNull { it.channel == sched.channel }?.casmPolicy
                )
                audioEngine.noteOnChannel(sched.channel, note, velocity01)
                noteOnCount++
                if (loopCount <= 1 && noteOnCount <= 8) {
                    DebugLog.add("  ♪ ch${sched.channel} n=$note v=${sched.event.velocity}")
                }
            } else {
                // Reuse the exact pitch that was actually turned on for
                // this slot — NOT a fresh transpose against whatever chord
                // happens to be held right now. Falls back to the raw
                // event note only if we somehow never saw the matching
                // note-on (shouldn't normally happen, but keeps this from
                // throwing instead of silently degrading).
                val voice = activeVoices.remove(key)
                audioEngine.noteOffChannel(sched.channel, voice?.soundingNote ?: sched.event.note)
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

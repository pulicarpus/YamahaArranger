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
            applySectionMidiSetup(section)
            applyVoicesFromCasmFallback(section)
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
     * Apply the actual MIDI setup embedded in this Yamaha section.
     *
     * This takes precedence over the old voice-name heuristic. Factory
     * styles can change bank/program/volume/pan/reverb/chorus per section;
     * ignoring those events makes an otherwise correct SF2 sound like the
     * wrong instrument.
     */
    private fun applySectionMidiSetup(section: StyleSectionModel) {
        if (section.channelSetups.isEmpty()) return
        DebugLog.add("🎛 Apply MIDI setup for " + section.name)
        for ((ch, setup) in section.channelSetups) {
            if (ch in lockedChannels) {
                DebugLog.add("  · ch$ch: SKIP setup (locked by user)")
                continue
            }

            val bank = setup.bank14
            val program = setup.program
            if (program != null) {
                audioEngine.setChannelProgram(ch, program, bank ?: 0)
                DebugLog.add(
                    "  · ch$ch SELECT bank=" + (bank ?: 0) + " prog=$program"
                )
            } else if (bank != null) {
                DebugLog.add("  · ch$ch bank=$bank (no program change in section)")
            }

            for ((controller, value) in setup.cc) {
                audioEngine.controlChange(ch, controller, value)
            }
        }
    }

    /**
     * Compatibility fallback for styles that do not carry usable section
     * program-change data. It is deliberately never allowed to overwrite an
     * explicit section setup.
     */
    private fun applyVoicesFromCasmFallback(section: StyleSectionModel) {
        if (voiceMap.isEmpty()) return

        section.parts.forEachIndexed { idx, part ->
            val partNum = idx + 1
            val voiceName = voiceMap[partNum] ?: return@forEachIndexed
            val ch = part.events.firstOrNull()?.channel ?: return@forEachIndexed
            if (ch in lockedChannels) return@forEachIndexed

            // If Yamaha explicitly selected a program for this section, that
            // selection is authoritative.
            if (section.channelSetups[ch]?.program != null) return@forEachIndexed

            val prog = guessProgramFromVoiceName(voiceName)
            if (prog < 0) return@forEachIndexed
            val bank = if (isDrumVoice(voiceName)) 128 else 0
            audioEngine.setChannelProgram(ch, prog, bank)
            DebugLog.add("  · fallback ch$ch: $voiceName → prog$prog bank$bank")
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

package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StyleSequencer(
    private val audioEngine: AudioEngineManager,
    private val midiInputManager: MidiInputManager,
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
        DebugLog.add("🎼 VoiceMap set: ${vm.size} entries")
    }

    fun play(section: StyleSectionModel, ppq: Int) {
        stop()
        loopCount = 0

        if (lastAppliedSection != section.name) {
            applyVoicesFromCasm(section)
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
        midiInputManager.allNotesOff()
        DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) = play(section, ppq)

    private fun applyVoicesFromCasm(section: StyleSectionModel) {
        if (voiceMap.isEmpty()) {
            DebugLog.add("⚠ No voice map, using SF2 defaults")
            return
        }

        DebugLog.add("🎼 Apply CASM voices for ${section.name}:")

        val allChannels = section.parts
            .flatMap { it.events }
            .map { it.channel }
            .distinct()

        if (allChannels.contains(9)) {
            audioEngine.setChannelProgram(9, 0, 128)
            midiInputManager.sendProgramChange(9, 0, 128)
            DebugLog.add("  · ch9: DRUM (bank 128) — locked")
        }

        section.parts.forEachIndexed { idx, part ->
            val partNum = idx + 1
            val voiceName = voiceMap[partNum] ?: return@forEachIndexed
            val prog = guessProgramFromVoiceName(voiceName)
            if (prog < 0) {
                DebugLog.add("  · part$partNum: $voiceName (unknown)")
                return@forEachIndexed
            }
            val bank = if (isDrumVoice(voiceName)) 128 else 0

            val channels = part.events.map { it.channel }.distinct()
            channels.forEach { ch ->
                if (ch == 9) return@forEach
                audioEngine.setChannelProgram(ch, prog, bank)
                midiInputManager.sendProgramChange(ch, prog, bank)
                DebugLog.add("  · part$partNum ch$ch: $voiceName → prog$prog (bank$bank)")
            }
        }
    }

    private fun guessProgramFromVoiceName(name: String): Int {
        val n = name.lowercase()
            .replace(".", "")
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

        val trailingDigits = n.takeLastWhile { it.isDigit() }
        if (trailingDigits.isNotEmpty()) {
            val num = trailingDigits.toIntOrNull()
            if (num != null && num in 0..127) return num
        }

        return when {
            n.contains("piano") || n.startsWith("pno") -> 0
            n.contains("epiano") || n.startsWith("ep") -> 4
            n.contains("organ") || n.contains("org") -> 16
            n.contains("accordion") || n.contains("accrd") -> 21

            n.contains("distgtr") || n.contains("disgtr") ||
                n.contains("distortion") -> 30
            n.contains("odgtr") || n.contains("overdrive") -> 29
            n.contains("egt") || n.contains("egtr") ||
                n.contains("electricgt") -> 27
            n.contains("mutedgtr") -> 28
            n.contains("jazzgtr") -> 26
            n.contains("steelgtr") || n.contains("steelgt") -> 25
            n.contains("nylongtr") || n.contains("nylongt") -> 24
            n.contains("gtr") || n.contains("guitar") -> 24

            n.contains("bass") || n.startsWith("bs") -> 33
            n.contains("slapbass") -> 36
            n.contains("synthbass") -> 38

            n.contains("violin") || n.contains("vln") -> 40
            n.contains("viola") -> 41
            n.contains("cello") -> 42
            n.contains("strings") || n.contains("strg") ||
                n.contains("str") || n.contains("strgs") -> 48

            n.contains("choir") || n.contains("voice") || n.contains("vocal") -> 52

            n.contains("trumpet") || n.startsWith("tpt") -> 56
            n.contains("trombone") || n.startsWith("tbn") -> 57
            n.contains("tuba") -> 58
            n.contains("frenchhorn") || n.contains("frhorn") -> 60
            n.contains("brass") -> 61

            n.contains("soprano") -> 64
            n.contains("altosax") || n.contains("asax") -> 65
            n.contains("tenorsax") || n.contains("tsax") -> 66
            n.contains("barisax") || n.contains("bsax") -> 67
            n.contains("sax") -> 65

            n.contains("oboe") -> 68
            n.contains("englishhorn") -> 69
            n.contains("bassoon") -> 70
            n.contains("clarinet") || n.startsWith("clr") -> 71
            n.contains("flute") || n.startsWith("flt") -> 73
            n.contains("piccolo") -> 72

            n.contains("pad") || n.contains("synthpad") -> 89
            n.contains("synthlead") -> 80
            n.contains("synth") -> 80

            n.contains("drum") || n.contains("kit") ||
                n.contains("adddr") || n.contains("maindr") ||
                n.startsWith("dr") || n.contains("perc") -> 0

            n.contains("fx") || n.contains("effect") -> 96

            else -> -1
        }
    }

    private fun isDrumVoice(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("add-dr") ||
               n.contains("drum") ||
               n.contains("kit") ||
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

            // Detect apakah channel ini bass (ch 10-11) atau chord (ch 11-13)
            val isBass = sched.channel == 10 || sched.channel == 11

            val note = if (sched.transpose) {
                currentChord?.let {
                    NoteTransposer.transpose(
                        patternNote = sched.event.note,
                        chord = it,
                        isBassPart = isBass
                    )
                } ?: sched.event.note
            } else {
                sched.event.note
            }
            if (sched.event.isNoteOn) {
                // Internal SF2 audio
                audioEngine.noteOnChannel(sched.channel, note, sched.event.velocity / 127f)
                // MIDI OUT ke E343 (jika enabled)
                midiInputManager.sendNoteOn(sched.channel, note, sched.event.velocity)

                noteOnCount++
                if (loopCount <= 1 && noteOnCount <= 8) {
                    DebugLog.add("  ♪ ch${sched.channel} n=$note v=${sched.event.velocity}")
                }
            } else {
                audioEngine.noteOffChannel(sched.channel, note)
                midiInputManager.sendNoteOff(sched.channel, note)
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
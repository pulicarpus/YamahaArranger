package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
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
        set(value) {
            val old = field
            field = value
            when {
                old != null && value != null && old != value -> handleChordChange(value)
                old != null && value == null -> handleNoChord()
            }
        }

    private var loopCount = 0
    private var voiceMap: Map<Int, String> = emptyMap()
    private var lastAppliedSection = ""
    private var lockedChannels: Set<Int> = emptySet()

    private data class ActiveTransposedNote(
        val sourceChannel: Int,
        val sourceNote: Int,
        val destinationChannel: Int,
        var outputNote: Int,
        val velocity: Int,
        val policy: CasmPolicyModel
    )

    private val activeTransposedNotes = mutableMapOf<String, ActiveTransposedNote>()

    fun setLockedChannels(channels: Set<Int>) {
        lockedChannels = channels
        DebugLog.add("🔒 Locked channels updated: $channels")
    }

    fun setVoiceMap(vm: Map<Int, String>) {
        voiceMap = vm
        lastAppliedSection = ""
        DebugLog.add("🎼 Legacy CASM VoiceMap received: ${vm.size}; actual MIDI setup takes precedence")
    }

    fun play(section: StyleSectionModel, ppq: Int, loopLimit: Int = -1, onComplete: (() -> Unit)? = null) {
        stop()
        loopCount = 0
        if (lastAppliedSection != section.name) {
            applyVoicesFromCasm(section)
            lastAppliedSection = section.name
        }
        val noteCount = section.parts.sumOf { part -> part.events.count { isNoteEvent(it) } }
        DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=${section.parts.sumOf { it.events.size }}, noteEvents=$noteCount, loopLimit=$loopLimit")
        playbackJob = scope.launch {
            var loops = 0
            while (loopLimit < 0 || loops < loopLimit) {
                playOnce(section, ppq)
                loops++
            }
            onComplete?.invoke()
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioEngine.allNotesOff()
        midiInputManager.allNotesOff()
        activeTransposedNotes.clear()
        DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section: StyleSectionModel, ppq: Int) = play(section, ppq)

    private fun handleNoChord() {
        val snapshot = activeTransposedNotes.values.toList()
        snapshot.forEach { releaseActive(it) }
        if (snapshot.isNotEmpty()) DebugLog.add("🎹 NO CHORD: released ${snapshot.size} held style notes")
    }

    private fun handleChordChange(newChord: DetectedChord) {
        if (activeTransposedNotes.isEmpty()) return
        val snapshot = activeTransposedNotes.values.toList()
        snapshot.forEach { active ->
            when (active.policy.rtr and 0x7f) {
                0 -> { releaseActive(active); DebugLog.add("🎹 RTR STOP src${active.sourceChannel}:${active.sourceNote}") }
                1 -> updateHeldPitch(active, newChord, rootOnly = false, retrigger = false)
                2 -> updateHeldPitch(active, newChord, rootOnly = true, retrigger = false)
                3 -> updateHeldPitch(active, newChord, rootOnly = false, retrigger = true)
                4 -> updateHeldPitch(active, newChord, rootOnly = true, retrigger = true)
                5 -> DebugLog.add("ℹ RTR NOTE GENERATOR src${active.sourceChannel}:${active.sourceNote}: deferred")
                else -> updateHeldPitch(active, newChord, rootOnly = false, retrigger = true)
            }
        }
    }

    private fun updateHeldPitch(active: ActiveTransposedNote, chord: DetectedChord, rootOnly: Boolean, retrigger: Boolean) {
        val target = if (rootOnly) rootPitchForHeld(active, chord) else CasmNoteTransformer.transform(active.sourceNote, chord, active.policy)
        if (target == null || target == active.outputNote) return
        DebugLog.add("🎹 RTR ${if (retrigger) "RETRIGGER" else "PITCH SHIFT"} src${active.sourceChannel}:${active.sourceNote} ${active.outputNote}→$target")
        audioEngine.noteOffChannel(active.destinationChannel, active.outputNote)
        midiInputManager.sendNoteOff(active.destinationChannel, active.outputNote)
        audioEngine.noteOnChannel(active.destinationChannel, target, active.velocity / 127f)
        midiInputManager.sendNoteOn(active.destinationChannel, target, active.velocity)
        active.outputNote = target
    }

    private fun rootPitchForHeld(active: ActiveTransposedNote, chord: DetectedChord): Int {
        val oldOctave = active.outputNote / 12
        return (oldOctave * 12 + chord.bassNote.coerceIn(0, 11)).coerceIn(0, 127)
    }

    private fun releaseActive(active: ActiveTransposedNote) {
        val key = "${active.sourceChannel}:${active.sourceNote}"
        audioEngine.noteOffChannel(active.destinationChannel, active.outputNote)
        midiInputManager.sendNoteOff(active.destinationChannel, active.outputNote)
        activeTransposedNotes.remove(key)
    }

    private fun applyVoicesFromCasm(section: StyleSectionModel) {
        section.parts.forEach { part ->
            val policy = part.casmPolicies.firstOrNull() ?: part.casm ?: return@forEach
            val destination = policy.destinationChannel
            if (destination in lockedChannels) return@forEach
            val program = if (part.program in 0..127) part.program else -1
            if (program < 0) {
                DebugLog.add("⚠ src${policy.sourceChannel}→dst$destination: no Program Change; keeping current voice '${policy.voiceName}'")
                return@forEach
            }
            val isDrum = destination == 9 || isDrumVoice(policy.voiceName)
            val localBank = if (isDrum) 128 else part.bankMsb * 128 + part.bankLsb
            audioEngine.setChannelProgram(destination, program, localBank)
            midiInputManager.sendProgramChangeBank(
                destination, program,
                if (isDrum && part.bankMsb == 0 && part.bankLsb == 0) 127 else part.bankMsb,
                part.bankLsb
            )
            DebugLog.add("🎼 src${policy.sourceChannel}→dst$destination: ${policy.voiceName} bank=${part.bankMsb}/${part.bankLsb} PC=$program localBank=$localBank policies=${part.casmPolicies.size}")
        }
    }

    private fun isDrumVoice(name: String): Boolean = name.lowercase().let {
        it.contains("crash") || it.contains("cymbal") || it.contains("perc") ||
            it.contains("add-dr") || it.contains("drum") || it.contains("kit") || it.startsWith("dr")
    }

    /**
     * CASM source chord is the chord used when the source pattern was recorded.
     * It is NOT a whitelist saying that a channel may play only for that chord.
     * NTR/NTT perform the conversion from that source pattern to the current
     * play chord. Filtering policies by the current chord type was therefore
     * causing whole parts (for example piano on C/G) to disappear.
     */
    private fun selectPolicy(part: StylePartModel, eventNote: Int): CasmPolicyModel? {
        val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
        if (policies.isEmpty()) return null
        val inRange = policies.filter { eventNote in it.sourceNoteLow..it.sourceNoteHigh }
        if (inRange.isEmpty()) return null
        // Prefer the narrowest matching zone. This matters for SFF2 styles that
        // split a single source channel into separate note ranges (e.g. MegaVoice).
        return inRange.minWithOrNull(compareBy<CasmPolicyModel> {
            it.sourceNoteHigh - it.sourceNoteLow
        }.thenBy { it.sourceNoteLow })
    }

    private fun isNoteEvent(event: StyleNoteEvent): Boolean {
        val hi = event.status and 0xF0
        return hi == 0x90 || hi == 0x80
    }

    private suspend fun playOnce(section: StyleSectionModel, ppq: Int) {
        loopCount++
        if (section.lengthTicks <= 0) { delay(500); return }
        data class Scheduled(val tick: Int, val event: StyleNoteEvent, val part: StylePartModel)
        val merged = section.parts.flatMap { part ->
            part.events.filter(::isNoteEvent).map { Scheduled(it.tick, it, part) }
        }.sortedWith(compareBy<Scheduled> { it.tick }.thenBy { it.event.isNoteOn.not() })
        if (merged.isEmpty()) { delay(500); return }
        var lastTick = 0
        for (s in merged) {
            val delta = s.tick - lastTick
            if (delta > 0) delay(ticksToMillis(delta, ppq, tempoBpm))
            lastTick = s.tick
            val chord = currentChord
            val policy = selectPolicy(s.part, s.event.note)
            if (!s.event.isNoteOn) {
                val key = "${s.event.channel}:${s.event.note}"
                val active = activeTransposedNotes.remove(key)
                if (active != null) {
                    audioEngine.noteOffChannel(active.destinationChannel, active.outputNote)
                    midiInputManager.sendNoteOff(active.destinationChannel, active.outputNote)
                }
                continue
            }
            val sourceChannel = s.event.channel
            val destinationChannel = policy?.destinationChannel ?: sourceChannel
            if (destinationChannel in lockedChannels) continue
            val key = "${sourceChannel}:${s.event.note}"
            val isDrumPart = destinationChannel == 9 || (policy != null && isDrumVoice(policy.voiceName))
            val transformed = if (policy != null && !isDrumPart) {
                chord?.let { CasmNoteTransformer.transform(s.event.note, it, policy) }
                    ?: s.event.note.coerceIn(0, 127)
            } else s.event.note.coerceIn(0, 127)
            val note = transformed ?: continue
            val velocity = s.event.velocity.coerceIn(1, 127)
            if (policy != null && !isDrumPart) {
                activeTransposedNotes[key] = ActiveTransposedNote(
                    sourceChannel, s.event.note, destinationChannel, note, velocity, policy
                )
            }
            audioEngine.noteOnChannel(destinationChannel, note, velocity / 127f)
            midiInputManager.sendNoteOn(destinationChannel, note, velocity)
        }
        val rem = section.lengthTicks - lastTick
        if (rem > 0) delay(ticksToMillis(rem, ppq, tempoBpm))
    }

    private fun ticksToMillis(ticks: Int, ppq: Int, bpm: Int): Long =
        if (ppq <= 0 || bpm <= 0) 0 else ((ticks * (60000.0 / bpm)) / ppq).toLong().coerceAtLeast(0)
}

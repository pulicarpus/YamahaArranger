package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicLong

class StyleSequencer(private val audioEngine: AudioEngineManager, private val midiInputManager: MidiInputManager, private val scope: CoroutineScope) {
    private var playbackJob:Job?=null
    var tempoBpm:Int=120
    var currentChord:DetectedChord?=null
        set(value){
            val old=field
            field=value
            when {
                old==null&&value!=null -> {
                    // The first detected chord must immediately retarget notes that
                    // started while the arranger was still in the no-chord state.
                    handleChordChange(value)
                    com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 FIRST CHORD: " + value.rootNote + " " + value.quality)
                }
                old!=null&&value!=null&&old!=value -> handleChordChange(value)
                old!=null&&value==null -> handleNoChord()
            }
        }
    private var loopCount=0
    @Volatile private var barClockStartedAtNanos: Long = 0L
    private var voiceMap:Map<Int,String> = emptyMap()
    private var lastAppliedSection=""
    private var lockedChannels:Set<Int> = emptySet()
    private val channelOverrides = mutableMapOf<Int, StyleChannelOverride>()
    private data class ActiveTransposedNote(
        val sourceChannel:Int,
        val sourceNote:Int,
        var destinationChannel:Int,
        var outputNote:Int,
        val velocity:Int,
        var policy:CasmPolicyModel,
        val policies:List<CasmPolicyModel>
    )
    private val activeTransposedNotes=mutableMapOf<String,ActiveTransposedNote>()

    // Long-running diagnostic recorder for String/CASM lifecycle.
    private val traceSequence = AtomicLong(0L)
    private val stringTrace = ArrayDeque<String>()
    private val stringTraceLock = Any()
    private val stringTraceMaxEntries = 12000
    @Volatile private var stringTraceEnabled = false

    private fun stringTrace(message:String){
        if(!stringTraceEnabled)return
        val n=traceSequence.incrementAndGet()
        val line="#${n.toString().padStart(5,'0')} t=${System.currentTimeMillis()} $message"
        synchronized(stringTraceLock){
            if(stringTrace.size>=stringTraceMaxEntries)stringTrace.removeFirst()
            stringTrace.addLast(line)
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 $line")
    }

    fun setStringTraceEnabled(enabled:Boolean){
        stringTraceEnabled=enabled
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 STRING TRACE ${if(enabled) "ON" else "OFF"}")
    }

    fun clearStringTrace(){
        synchronized(stringTraceLock){stringTrace.clear()}
        traceSequence.set(0L)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 STRING TRACE CLEARED")
    }

    fun dumpStringTrace(){
        val snapshot=synchronized(stringTraceLock){stringTrace.toList()}
        val fullText = buildString {
            appendLine("========== YAMAHA ARRANGER FULL STRING/CASM TRACE entries=${snapshot.size} active=${activeTransposedNotes.size} chord=${currentChord?.rootNote}/${currentChord?.quality} ==========")
            snapshot.forEach { appendLine(it) }
            appendLine("========== TRACE END ==========")
        }
        com.yourapp.yamahaarranger.ui.DebugLog.setLongText(fullText)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 FULL TRACE READY: ${snapshot.size} entries — tap SEND LOG to send the complete trace")
        com.yourapp.yamahaarranger.ui.DebugLog.add("========== STRING TRACE BEGIN entries=${snapshot.size} active=${activeTransposedNotes.size} chord=${currentChord?.rootNote}/${currentChord?.quality} ==========")
        snapshot.chunked(80).forEachIndexed{index,chunk->
            com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 TRACE CHUNK ${index+1}/${(snapshot.size+79)/80}")
            chunk.forEach{com.yourapp.yamahaarranger.ui.DebugLog.add(it)}
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add("========== STRING TRACE END entries=${snapshot.size} active=${activeTransposedNotes.size} ==========")
    }


    fun setLockedChannels(channels:Set<Int>){lockedChannels=channels;com.yourapp.yamahaarranger.ui.DebugLog.add("🔒 Locked channels updated: $channels")}
    fun setChannelOverride(channel:Int, override:StyleChannelOverride){channelOverrides[channel]=override;com.yourapp.yamahaarranger.ui.DebugLog.add("🎚 STYLE CH$channel: vol=${override.volume} prog=${override.program ?: "AUTO"} bank=${override.bank ?: "AUTO"} tr=${override.transpose} mute=${override.muted}")}
    fun channelOverride(channel:Int):StyleChannelOverride = channelOverrides[channel] ?: StyleChannelOverride()
    fun setChannelVolume(channel:Int, volume:Int){
        val v=volume.coerceIn(0,127)
        val old=channelOverride(channel)
        setChannelOverride(channel, old.copy(volume=v))
        audioEngine.setChannelVolume(channel, if(old.muted) 0 else v)
    }
    fun setChannelMute(channel:Int, muted:Boolean){
        val old=channelOverride(channel)
        setChannelOverride(channel, old.copy(muted=muted))
        audioEngine.setChannelMixer(channel, volume=if(muted) 0 else old.volume, pan=old.pan, expression=old.expression, reverbSend=old.reverbSend, chorusSend=old.chorusSend)
    }
    fun setChannelMixer(channel:Int, volume:Int, pan:Int, expression:Int, reverbSend:Int, chorusSend:Int){
        val old=channelOverride(channel)
        val v=volume.coerceIn(0,127)
        val p=pan.coerceIn(0,127)
        val e=expression.coerceIn(0,127)
        val r=reverbSend.coerceIn(0,127)
        val ch=chorusSend.coerceIn(0,127)
        val next=old.copy(volume=v, pan=p, expression=e, reverbSend=r, chorusSend=ch)
        setChannelOverride(channel,next)
        audioEngine.setChannelMixer(channel, volume=if(next.muted) 0 else v, pan=p, expression=e, reverbSend=r, chorusSend=ch)
    }
    fun setChannelProgramOverride(channel:Int, program:Int, bank:Int){
        val old=channelOverride(channel)
        // Style/UI bank values use Yamaha's 14-bit MSB*128+LSB form.
        // Keep the full range here; BassMidiPlayer splits it back into the
        // two MIDI Bank Select bytes at the native boundary.
        setChannelOverride(
            channel,
            old.copy(
                program=program.coerceIn(0,127),
                bank=bank.coerceIn(0,16383)
            )
        )
    }
    fun setVoiceMap(vm:Map<Int,String>){voiceMap=vm;lastAppliedSection="";com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 Legacy VoiceMap received: ${vm.size}; CASM policy takes precedence")}
    private data class PendingSection(
        val section: StyleSectionModel,
        val ppq: Int,
        val loopLimit: Int,
        val onComplete: (() -> Unit)?
    )

    private data class PendingTransition(
        val sections: List<PendingSection>,
        val startTick: Long
    )

    @Volatile private var pendingSection: PendingSection? = null
    @Volatile private var pendingTransition: PendingTransition? = null
    @Volatile private var masterClockStartedAtNanos: Long = 0L
    @Volatile private var masterTimelineTick: Long = 0L

    /**
     * Seamless section request.
     *
     * While Style is running, a new section is queued instead of cancelling
     * the current playback coroutine. The same musical clock therefore spans
     * Main -> Fill -> Main without an all-notes-off or clock reset.
     */
    /**
     * Queue a musical transition without stopping the current section.
     *
     * The transition starts on the next bar of the SAME master clock.
     * The current Main is allowed to finish exactly at that bar boundary,
     * then Fill and the target Main are consumed consecutively.
     */
    fun queueSeamlessTransition(
        sections: List<Pair<StyleSectionModel, Int>>,
        ppq: Int,
        numerator: Int,
        denominator: Int,
        finalOnComplete: (() -> Unit)? = null
    ) {
        if (sections.isEmpty()) return
        if (playbackJob?.isActive != true) {
            val first = sections.first()
            startPlayback(first.first, ppq, first.second, null, seamless = true)
            if (sections.size > 1) {
                pendingTransition = PendingTransition(
                    sections.drop(1).mapIndexed { index, item ->
                        val isLast = index == sections.size - 2
                        PendingSection(item.first, ppq, item.second, if (isLast) finalOnComplete else null)
                    },
                    startTick = first.first.lengthTicks.toLong().coerceAtLeast(0L)
                )
            } else if (finalOnComplete != null) {
                startPlayback(first.first, ppq, first.second, finalOnComplete, seamless = true)
            }
            return
        }

        val currentTick = currentMasterTick(ppq)

        // Immediate/phase-continuous mode: the button press becomes the
        // transition point on the SAME master clock. The fill is not restarted
        // from tick 0; it is phase-aligned to the beat where the request lands.
        val queue = sections.mapIndexed { index, item ->
            PendingSection(item.first, ppq, item.second, if (index == sections.lastIndex) finalOnComplete else null)
        }
        pendingTransition = PendingTransition(queue, currentTick)
        pendingSection = null

        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "🎼 TRANSITION QUEUED: " +
                queue.joinToString(" → ") +
                " at current masterTick=$currentTick (phase continuous)"
        )
    }

    private fun currentMasterTick(ppq: Int): Long {
        val anchor = masterClockStartedAtNanos
        if (anchor == 0L || ppq <= 0) return masterTimelineTick
        val nanosPerTick =
            60_000_000_000.0 /
                (tempoBpm.coerceIn(20, 280).toDouble() * ppq.toDouble())
        return ((System.nanoTime() - anchor) / nanosPerTick).toLong().coerceAtLeast(masterTimelineTick)
    }

    fun playSeamless(section: StyleSectionModel, ppq: Int, loopLimit: Int = -1, onComplete: (() -> Unit)? = null) {
        if (playbackJob?.isActive == true) {
            pendingSection = PendingSection(section, ppq, loopLimit, onComplete)
            com.yourapp.yamahaarranger.ui.DebugLog.add(
                "🎼 QUEUE seamless " + section.name + " (no cancel/no allNotesOff)"
            )
            return
        }
        startPlayback(section, ppq, loopLimit, onComplete, seamless = true)
    }

    private fun startPlayback(
        section: StyleSectionModel,
        ppq: Int,
        loopLimit: Int,
        onComplete: (() -> Unit)?,
        seamless: Boolean
    ) {
        if (!seamless) clearStringTrace()
        pendingSection = null
        pendingTransition = null
        masterClockStartedAtNanos = System.nanoTime()
        masterTimelineTick = 0L
        barClockStartedAtNanos = masterClockStartedAtNanos
        stringTrace("TRACE_SESSION section=" + section.name + " ppq=" + ppq + " loopLimit=" + loopLimit + " seamless=" + seamless)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 SECTION " + section.name + ": continuous master clock")
        loopCount = 0
        if (lastAppliedSection != section.name) {
            applyVoicesFromCasm(section)
            lastAppliedSection = section.name
        }
        val noteCount = section.parts.sumOf { part -> part.events.count { isNoteEvent(it) } }
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "▶ PLAY " + section.name + ": parts=" + section.parts.size + ", events=" + section.parts.sumOf { it.events.size } + ", noteEvents=" + noteCount + ", loopLimit=" + loopLimit + ", seamless=" + seamless
        )

        playbackJob = scope.launch(Dispatchers.Default) {
            var active = PendingSection(section, ppq, loopLimit, onComplete)
            var remainingLoops = loopLimit
            while (true) {
                val result = playOnce(active.section, active.ppq, masterTimelineTick, 0L)
                masterTimelineTick = result.endTick
                barClockStartedAtNanos = masterClockStartedAtNanos

                if (result.interruptedByTransition) {
                    val transition = pendingTransition
                    pendingTransition = null
                    if (transition != null) {
                        // Release only notes owned by the outgoing section. This
                        // is deliberately NOT allNotesOff(): keyboard voices and
                        // unrelated channels remain untouched.
                        val outgoing = activeTransposedNotes.values.toList()
                        outgoing.forEach { releaseActive(it) }
                        if (outgoing.isNotEmpty()) {
                            com.yourapp.yamahaarranger.ui.DebugLog.add(
                                "🎼 TRANSITION: released " + outgoing.size + " outgoing style notes (no global allNotesOff)"
                            )
                        }

                        active = transition.sections.first()
                        remainingLoops = active.loopLimit
                        // The transition start is an absolute tick. Every section
                        // in the sequence is placed immediately after the previous
                        // one; no clock reset and no global note-off.
                        val rest = transition.sections.drop(1)
                        if (rest.isNotEmpty()) {
                            pendingSection = rest.first()
                            if (rest.size > 1) {
                                pendingTransition = PendingTransition(rest.drop(1), 0L)
                            }
                        }
                        loopCount = 0
                        if (lastAppliedSection != active.section.name) {
                            applyVoicesFromCasm(active.section)
                            lastAppliedSection = active.section.name
                        }
                        com.yourapp.yamahaarranger.ui.DebugLog.add(
                            "🎼 MASTER CLOCK → " + active.section.name + " at tick=" + masterTimelineTick
                        )
                        continue
                    }
                }

                if (remainingLoops > 0) remainingLoops--

                if (remainingLoops == 0) active.onComplete?.invoke()

                val queued = pendingSection
                if (queued != null) {
                    pendingSection = null
                    active = queued
                    remainingLoops = queued.loopLimit
                    loopCount = 0
                    if (lastAppliedSection != active.section.name) {
                        applyVoicesFromCasm(active.section)
                        lastAppliedSection = active.section.name
                    }
                    com.yourapp.yamahaarranger.ui.DebugLog.add(
                        "🎼 MASTER CLOCK → " + active.section.name + " at tick=" + masterTimelineTick
                    )
                    continue
                }

                if (remainingLoops == 0) break
            }
            playbackJob = null
        }
    }

    fun play(section:StyleSectionModel,ppq:Int,loopLimit:Int=-1,onComplete:(()->Unit)?=null){
        stop()
        clearStringTrace()
        startPlayback(section, ppq, loopLimit, onComplete, seamless = false)
    }

    fun stop(){
        pendingSection = null
        pendingTransition = null
        playbackJob?.cancel()
        playbackJob=null
        masterClockStartedAtNanos=0L
        masterTimelineTick=0L
        barClockStartedAtNanos=0L
        if(stringTraceEnabled && synchronized(stringTraceLock){stringTrace.isNotEmpty()}) dumpStringTrace()
        audioEngine.allNotesOff()
        midiInputManager.allNotesOff()
        activeTransposedNotes.clear()
        com.yourapp.yamahaarranger.ui.DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section:StyleSectionModel,ppq:Int)=playSeamless(section,ppq)

    fun millisToNextBar(ppq: Int, numerator: Int = 4, denominator: Int = 4): Long {
        val anchor = masterClockStartedAtNanos
        if (anchor == 0L || ppq <= 0) return 0L
        val bpm = tempoBpm.coerceIn(20, 280)
        val barMs = (
            numerator.coerceAtLeast(1) * 4.0 / denominator.coerceAtLeast(1) *
                60_000.0 / bpm
        ).toLong().coerceAtLeast(1L)
        val elapsedMs = (System.nanoTime() - anchor) / 1_000_000L
        val remainder = elapsedMs % barMs
        val wait = if (remainder == 0L) 0L else barMs - remainder
        return if (wait <= 25L) 0L else wait
    }

    private fun handleNoChord(){
        stringTrace("NO_CHORD active="+activeTransposedNotes.size)
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{releaseActive(it)}
        if(snapshot.isNotEmpty())com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 NO CHORD: released ${snapshot.size} held style notes")
    }

    private fun handleChordChange(newChord:DetectedChord){
        stringTrace("CHORD_CHANGE new="+newChord.rootNote+"/"+newChord.quality+" active="+activeTransposedNotes.size)
        if(activeTransposedNotes.isEmpty()){
            stringTrace("CHORD_CHANGE no-active-notes")
            return
        }
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{active->
            stringTrace("ACTIVE src"+active.sourceChannel+":"+active.sourceNote+" dst="+active.destinationChannel+" note="+active.outputNote+" NTR="+(active.policy.ntr and 0x7f)+" NTT="+(active.policy.ntt and 0x7f)+" RTR="+(active.policy.rtr and 0x7f))
            val selectedPolicy=selectPolicy(active.policies,active.sourceNote,newChord)
            if(selectedPolicy==null){
                stringTrace("POLICY_MISS src"+active.sourceChannel+":"+active.sourceNote+" chord="+newChord.rootNote+"/"+newChord.quality+" -> RELEASE")
                releaseActive(active)
                com.yourapp.yamahaarranger.ui.DebugLog.add(
                    "🔇 CASM CHORD MUTE src${active.sourceChannel}:${active.sourceNote} chord=${newChord.rootNote}/${newChord.quality}"
                )
                return@forEach
            }
            if(selectedPolicy.destinationChannel!=active.destinationChannel){
                audioEngine.noteOffChannel(active.destinationChannel,active.outputNote)
                midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
                active.destinationChannel=selectedPolicy.destinationChannel
            }
            active.policy=selectedPolicy
            if(selectedPolicy.destinationChannel==13 || active.destinationChannel==13){
                stringTrace("RETARGET src"+active.sourceChannel+":"+active.sourceNote+" chord="+newChord.rootNote+"/"+newChord.quality+" dst="+selectedPolicy.destinationChannel+" NTR="+(selectedPolicy.ntr and 0x7f)+" NTT="+(selectedPolicy.ntt and 0x7f)+" RTR="+(selectedPolicy.rtr and 0x7f)+" range="+selectedPolicy.sourceNoteLow+"-"+selectedPolicy.sourceNoteHigh+" mask="+selectedPolicy.chordMuteMask)
            }
            com.yourapp.yamahaarranger.ui.DebugLog.add(
                "🎼 CASM RETARGET src${active.sourceChannel}:${active.sourceNote} chord=${newChord.rootNote}/${newChord.quality} → dst${selectedPolicy.destinationChannel} NTR=${selectedPolicy.ntr and 0x7f} NTT=${selectedPolicy.ntt and 0x7f} RTR=${selectedPolicy.rtr and 0x7f}"
            )
            val rtr=active.policy.rtr and 0x7f
            when(rtr){
                0->{releaseActive(active);com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR STOP src${active.sourceChannel}:${active.sourceNote}")}
                1->updateHeldPitch(active,newChord,rootOnly=false,retrigger=false)
                2->updateHeldPitch(active,newChord,rootOnly=true,retrigger=false)
                3->updateHeldPitch(active,newChord,rootOnly=false,retrigger=true)
                4->updateHeldPitch(active,newChord,rootOnly=true,retrigger=true)
                5->com.yourapp.yamahaarranger.ui.DebugLog.add("ℹ RTR NOTE GENERATOR src${active.sourceChannel}:${active.sourceNote}: deferred")
                else->updateHeldPitch(active,newChord,rootOnly=false,retrigger=true)
            }
        }
    }

    private fun updateHeldPitch(active:ActiveTransposedNote,chord:DetectedChord,rootOnly:Boolean,retrigger:Boolean){
        val target=if(rootOnly) rootPitchForHeld(active,chord) else CasmNoteTransformer.transform(active.sourceNote,chord,active.policy)
        if(target==null||target==active.outputNote)return
        if(!retrigger)com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR PITCH SHIFT src${active.sourceChannel}:${active.sourceNote} ${active.outputNote}→$target (note replacement)")
        else com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR RETRIGGER src${active.sourceChannel}:${active.sourceNote} ${active.outputNote}→$target")
        audioEngine.noteOffChannel(active.destinationChannel,active.outputNote)
        midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
        audioEngine.noteOnChannel(active.destinationChannel,target,active.velocity/127f)
        midiInputManager.sendNoteOn(active.destinationChannel,target,active.velocity)
        active.outputNote=target
    }

    private fun rootPitchForHeld(active:ActiveTransposedNote,chord:DetectedChord):Int{
        val oldOctave=active.outputNote/12
        return (oldOctave*12+chord.rootNote.coerceIn(0,11)).coerceIn(0,127)
    }

    private fun releaseActive(active:ActiveTransposedNote){
        val key="${active.sourceChannel}:${active.sourceNote}"
        audioEngine.noteOffChannel(active.destinationChannel,active.outputNote)
        midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
        activeTransposedNotes.remove(key)
    }

    private fun applyVoicesFromCasm(section: StyleSectionModel) {
        val startedAtNanos = System.nanoTime()
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "⏱ CASM VOICE APPLY START section=${section.name}"
        )
        val explicitByDestination = linkedMapOf<Int, StylePartModel>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in 0..3) return@forEach
            if (destination in lockedChannels) return@forEach
            if (part.program in 0..127 && !explicitByDestination.containsKey(destination)) {
                explicitByDestination[destination] = part
            }
        }

        val applied = mutableSetOf<Int>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in lockedChannels || destination in applied) return@forEach

            // Yamaha Rhythm 1/2 are destination channels 8 and 9
            // (zero-based). Treat both as percussion regardless of whether
            // the CASM voice name explicitly says "Drum".
            val drum = destination == 8 || destination == 9 || isDrumVoice(c.voiceName)
            val override = channelOverrides[destination]
            if (override?.muted == true) {
                applied += destination
                return@forEach
            }

            val explicit = explicitByDestination[destination]
            val sourcePart = explicit ?: part
            val prog = override?.program
                ?: explicit?.program?.takeIf { it in 0..127 }
                ?: guessProgramFromVoiceName(c.voiceName)
            if (prog !in 0..127) return@forEach

            // Preserve both Yamaha Bank Select bytes. FluidSynth program_select
            // accepts the resulting 14-bit bank directly.
            val styleBank = sourcePart.bankMsb.coerceIn(0, 127) * 128 +
                sourcePart.bankLsb.coerceIn(0, 127)
            val audioBank = if (drum) 128 else styleBank
            val midiMsb = if (drum) 127 else sourcePart.bankMsb.coerceIn(0, 127)
            val midiLsb = if (drum) 0 else sourcePart.bankLsb.coerceIn(0, 127)

            audioEngine.setChannelProgram(destination, prog, audioBank)

            val volume = override?.volume ?: sourcePart.volume
            val pan = override?.pan ?: sourcePart.pan
            val expression = override?.expression ?: sourcePart.expression
            val reverb = override?.reverbSend ?: sourcePart.reverbSend
            val chorus = override?.chorusSend ?: sourcePart.chorusSend
            if (volume >= 0 || pan >= 0 || expression >= 0 || reverb >= 0 || chorus >= 0) {
                audioEngine.setChannelMixer(
                    destination,
                    volume = if (volume >= 0) volume else 127,
                    pan = if (pan >= 0) pan else 64,
                    expression = if (expression >= 0) expression else 127,
                    reverbSend = if (reverb >= 0) reverb else 0,
                    chorusSend = if (chorus >= 0) chorus else 0
                )
            }

            midiInputManager.sendProgramChange(destination, prog, midiMsb, midiLsb)
            applied += destination
            val source = if (override?.program != null) "STYLE OVERRIDE"
                else if (explicit != null) "actual MIDI setup"
                else "fallback name"
            com.yourapp.yamahaarranger.ui.DebugLog.add(
                "🎼 dst" + destination + ": " + c.voiceName + " → PC=" + prog +
                    " MIDIbank=" + midiMsb + ":" + midiLsb + " SFbank=" + audioBank +
                    " mix=" + volume + "/" + pan + "/" + expression + "/" + reverb + "/" + chorus +
                    " (" + source + ")"
            )
        }
        val durationUs = (System.nanoTime() - startedAtNanos) / 1_000L
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "⏱ CASM VOICE APPLY END section=${section.name} duration_us=$durationUs"
        )
    }

    private fun guessProgramFromVoiceName(name:String):Int{val n=name.lowercase();val numeric=Regex("(?:^|\\D)(\\d{1,3})\\s*$").find(n)?.groupValues?.getOrNull(1)?.toIntOrNull();if(numeric!=null&&numeric in 0..127)return numeric;return when{n.contains("piano")->0;n.contains("e.piano")||n.contains("ep")->4;n.contains("organ")->16;n.contains("accordion")->21;n.contains("guitar")||n.contains("gtr")->24;n.contains("bass")->33;n.contains("violin")->40;n.contains("cello")->42;n.contains("strg")||n.contains("str")->48;n.contains("choir")->52;n.contains("trumpet")->56;n.contains("trombone")->57;n.contains("brass")->61;n.contains("sax")->65;n.contains("oboe")->68;n.contains("clarinet")->71;n.contains("flute")->73;n.contains("crash")||n.contains("cymbal")||n.contains("perc")||n.contains("dr")||n.contains("kit")||n.contains("drum")->0;n.contains("pad")->89;else->-1}}
    private fun isDrumVoice(name:String)=name.lowercase().let{it.contains("crash")||it.contains("cymbal")||it.contains("perc")||it.contains("add-dr")||it.contains("drum")||it.contains("kit")||it.startsWith("dr")}
    private fun yamahaChordType(chord:DetectedChord):Int = when(chord.quality){
        ChordQuality.MAJOR -> 0
        ChordQuality.SIX -> 1
        ChordQuality.MAJ7 -> 2
        ChordQuality.ADD9 -> 4
        ChordQuality.SIX9 -> 6
        ChordQuality.AUG -> 7
        ChordQuality.MINOR -> 8
        ChordQuality.MIN6 -> 9
        ChordQuality.MIN7 -> 10
        ChordQuality.MIN7_FLAT5 -> 11
        ChordQuality.MIN7_11 -> 14
        ChordQuality.DIM -> 17
        ChordQuality.DIM7 -> 18
        ChordQuality.DOM7 -> 19
        ChordQuality.DOM7_SUS4 -> 20
        ChordQuality.DOM7_FLAT5 -> 21
        ChordQuality.SUS4 -> 32
        ChordQuality.SUS2 -> 33
        ChordQuality.POWER5 -> 31
    }

    private fun policyMatchesChord(policy:CasmPolicyModel,chord:DetectedChord):Boolean {
        return policyScore(policy,0,chord)!=null
    }

    /**
     * Resolve one CASM rule for the current source note/chord.
     *
     * Important semantics:
     * - A present Chord Mute/Play mask is a hard selector.
     * - SourceChordType/SourceChordRoot describe the recorded source pattern;
     *   they are metadata, not the currently played chord.
     * - Source-note range is a hard compatibility gate. An out-of-range rule
     *   must never win merely because its metadata happens to score higher.
     * - Among compatible rules, narrower source ranges are more specific.
     * - Source-channel compatibility is a later tie-breaker.
     */
    private fun policyScore(
        policy:CasmPolicyModel,
        eventNote:Int,
        chord:DetectedChord?,
        sourceChannel:Int?=null
    ):Int?{
        var score=0

        // 1) Chord mute/play mask is the strongest selector.
        if(chord!=null && policy.chordMuteMask>=0L){
            val type=yamahaChordType(chord)
            if(type !in 0..33) return null
            if(((policy.chordMuteMask ushr type) and 1L)==0L) return null
            score+=1_000_000
        }

        // 2) Source note range is a hard compatibility gate.
        val low=policy.sourceNoteLow.coerceIn(0,127)
        val high=policy.sourceNoteHigh.coerceIn(low,127)
        if(eventNote !in low..high) return null

        // Narrower matching ranges are more specific than broad ranges.
        val width=high-low
        score+=10_000 + (127-width)*100

        // 3) Prefer source-channel compatibility after range specificity.
        if(sourceChannel!=null && policy.sourceChannel==sourceChannel) score+=10

        // 4) Source-root/type are metadata specificity only. They must not
        // be compared to the currently played chord root/type.
        if(policy.sourceChordRoot in 0..11) score+=1
        if(policy.sourceChordType in 0..34) score+=1

        return score
    }

    private fun selectPolicy(
        policies:List<CasmPolicyModel>,
        eventNote:Int,
        chord:DetectedChord?,
        sourceChannel:Int?=null
    ):CasmPolicyModel?{
        if(policies.isEmpty())return null

        // Every candidate must pass policyScore, including the source-note
        // range gate. This prevents an out-of-range policy from becoming a
        // fallback solely because it has more metadata.
        return policies.mapNotNull{policy->
            policyScore(policy,eventNote,chord,sourceChannel)?.let{score->policy to score}
        }.maxWithOrNull(
            compareBy<Pair<CasmPolicyModel,Int>>{it.second}
                .thenByDescending{it.first.sourceNoteLow}
                .thenBy{it.first.sourceNoteHigh}
                .thenBy{it.first.sourceChannel}
                .thenBy{it.first.destinationChannel}
        )?.first
    }

    private fun selectPolicy(
        part:com.yourapp.yamahaarranger.style.StylePartModel,
        eventNote:Int,
        chord:DetectedChord?
    ):CasmPolicyModel?{
        val policies=part.casmPolicies.ifEmpty{listOfNotNull(part.casm)}
        val sourceChannel=part.events.firstOrNull()?.channel
        return selectPolicy(policies,eventNote,chord,sourceChannel)
    }
    private fun sourceChordTypeFor(chord:DetectedChord):Int=when(chord.quality){ChordQuality.MINOR,ChordQuality.MIN6,ChordQuality.MIN7->10;else->2}
    private fun isNoteEvent(event:StyleNoteEvent):Boolean{val hi=event.status and 0xF0;return hi==0x90||hi==0x80}

    private fun isRhythmSource(channel:Int):Boolean = channel == 8 || channel == 9

    // LoveSong Main D contains a Yamaha MegaVoice articulation source named
    // "StrumFX" (src7). A normal GM/SF2 player cannot reproduce its Yamaha
    // articulation mapping; sending those high trigger notes as ordinary
    // guitar notes creates the audible "tinut-tinut" artifact. Keep the real
    // guitar source (src5) but suppress this articulation-only source.
    private fun isUnsupportedArticulation(policy:CasmPolicyModel?):Boolean =
        policy?.voiceName?.lowercase()?.contains("strumfx") == true

    private data class PlayOnceResult(
        val endTick: Long,
        val interruptedByTransition: Boolean
    )

    private suspend fun playOnce(section:StyleSectionModel,ppq:Int,startAbsoluteTick:Long,phaseStartTick:Long): PlayOnceResult {
        loopCount++
        if(section.lengthTicks<=0) return PlayOnceResult(startAbsoluteTick, false)

        data class Scheduled(
            val tick:Int,
            val event:StyleNoteEvent,
            val part:com.yourapp.yamahaarranger.style.StylePartModel
        )

        val sectionLength = section.lengthTicks.coerceAtLeast(1).toLong()
        val phase = phaseStartTick.mod(sectionLength)
        val merged=section.parts
            .flatMap{part->
                part.events.filter(::isNoteEvent).map{e->
                    val raw = e.tick.toLong().coerceAtLeast(0L)
                    val relative = (raw - phase + sectionLength) % sectionLength
                    Scheduled(relative.toInt(), e, part)
                }
            }
            .sortedWith(compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()})
        if(merged.isEmpty()) return PlayOnceResult(startAbsoluteTick + sectionLength, false)

        val timelineStartNanos=masterClockStartedAtNanos
        val nanosPerTick=60_000_000_000.0/(tempoBpm.coerceIn(20,280).toDouble()*ppq.coerceAtLeast(1).toDouble())

        var interruptedByTransition = false
        var lastProcessedTick = startAbsoluteTick

        for(s in merged){
            val absoluteTick=startAbsoluteTick+s.tick.toLong()
            val transition = pendingTransition
            if (transition != null && transition.startTick > 0L && absoluteTick >= transition.startTick) {
                interruptedByTransition = true
                lastProcessedTick = transition.startTick
                break
            }
            lastProcessedTick = absoluteTick
            val targetNanos=timelineStartNanos+(absoluteTick*nanosPerTick).toLong()
            val waitNanos=targetNanos-System.nanoTime()
            if(waitNanos>0L){
                val waitMs=waitNanos/1_000_000L
                if(waitMs>0L) delay(waitMs)
                if(targetNanos-System.nanoTime()>0L) Thread.yield()
            }

            val chord=currentChord
            val policy=selectPolicy(s.part,s.event.note,chord)
            if(policy==null&&chord!=null&&s.event.isNoteOn&&!isRhythmSource(s.event.channel))continue
            if(s.event.isNoteOn&&isUnsupportedArticulation(policy)){
                com.yourapp.yamahaarranger.ui.DebugLog.add("🔇 SUPPRESS " + (policy?.voiceName ?: "unknown") + " src" + s.event.channel + ":" + s.event.note + " (MegaVoice articulation unsupported by SF2)")
                continue
            }

            if(!s.event.isNoteOn){
                val key=s.event.channel.toString()+":"+s.event.note
                val active=activeTransposedNotes.remove(key)
                if(active!=null){
                    if(active.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEOFF src"+active.sourceChannel+":"+active.sourceNote+" dst13 note="+active.outputNote)
                    audioEngine.noteOffChannel(active.destinationChannel,active.outputNote)
                    midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
                }
                continue
            }

            val sourceChannel=s.event.channel
            val destinationChannel=policy?.destinationChannel?:sourceChannel
            if(destinationChannel in 0..3){
                com.yourapp.yamahaarranger.ui.DebugLog.add("  · style event src"+sourceChannel+":"+s.event.note+": SKIP dst"+destinationChannel+" (reserved for keyboard voices)")
                continue
            }
            if(destinationChannel in lockedChannels)continue
            val channelOverride=channelOverrides[destinationChannel]
            if(channelOverride?.muted==true)continue

            val key=sourceChannel.toString()+":"+s.event.note
            val previousActive=activeTransposedNotes[key]
            if(previousActive!=null){
                if(previousActive.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING REPLACE src"+previousActive.sourceChannel+":"+previousActive.sourceNote+" oldNote="+previousActive.outputNote)
                audioEngine.noteOffChannel(previousActive.destinationChannel,previousActive.outputNote)
                midiInputManager.sendNoteOff(previousActive.destinationChannel,previousActive.outputNote)
                activeTransposedNotes.remove(key)
            }

            val isDrumPart=destinationChannel==8||destinationChannel==9||(policy!=null&&isDrumVoice(policy.voiceName))
            val transformed=if(policy!=null&&!isDrumPart){
                chord?.let{CasmNoteTransformer.transform(s.event.note,it,policy)}?:s.event.note.coerceIn(0,127)
            }else s.event.note.coerceIn(0,127)
            val note=((transformed?:continue)+(channelOverride?.transpose?:0)).coerceIn(0,127)
            val velocity=s.event.velocity.coerceIn(1,127)

            if(policy!=null&&!isDrumPart){
                val policyList=s.part.casmPolicies.ifEmpty{listOfNotNull(s.part.casm)}
                activeTransposedNotes[key]=ActiveTransposedNote(sourceChannel,s.event.note,destinationChannel,note,velocity,policy,policyList)
                if(destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEON src"+sourceChannel+":"+s.event.note+" dst13 note="+note+" NTR="+(policy.ntr and 0x7f)+" NTT="+(policy.ntt and 0x7f)+" RTR="+(policy.rtr and 0x7f))
                if(destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 CASM SELECT src"+sourceChannel+":"+s.event.note+" chord="+chord?.rootNote+"/"+chord?.quality+" → dst"+destinationChannel+" NTR="+(policy.ntr and 0x7f)+" NTT="+(policy.ntt and 0x7f)+" SRC="+policy.sourceChordRoot+"/"+policy.sourceChordType+" range="+policy.sourceNoteLow+"-"+policy.sourceNoteHigh+" RTR="+(policy.rtr and 0x7f))
            }

            audioEngine.noteOnChannel(destinationChannel,note,velocity/127f)
            midiInputManager.sendNoteOn(destinationChannel,note,velocity)
        }
        val naturalEnd = startAbsoluteTick + section.lengthTicks.coerceAtLeast(0).toLong()
        return if (interruptedByTransition) {
            PlayOnceResult(lastProcessedTick, true)
        } else {
            PlayOnceResult(naturalEnd, false)
        }
    }

    private fun ticksToMillis(ticks:Int,ppq:Int,bpm:Int):Long=if(ppq<=0||bpm<=0)0 else((ticks*(60000.0/bpm))/ppq).toLong().coerceAtLeast(0)
}

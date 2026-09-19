package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    fun setLockedChannels(channels:Set<Int>){lockedChannels=channels;com.yourapp.yamahaarranger.ui.DebugLog.add("🔒 Locked channels updated: $channels")}
    fun setChannelOverride(channel:Int, override:StyleChannelOverride){channelOverrides[channel]=override;com.yourapp.yamahaarranger.ui.DebugLog.add("🎚 STYLE CH$channel: vol=${override.volume} prog=${override.program ?: "AUTO"} bank=${override.bank ?: "AUTO"} tr=${override.transpose} mute=${override.muted}")}
    fun channelOverride(channel:Int):StyleChannelOverride = channelOverrides[channel] ?: StyleChannelOverride()
    fun setChannelVolume(channel:Int, volume:Int){ val old=channelOverride(channel); setChannelOverride(channel, old.copy(volume=volume.coerceIn(0,127))) }
    fun setChannelMute(channel:Int, muted:Boolean){ val old=channelOverride(channel); setChannelOverride(channel, old.copy(muted=muted)) }
    fun setChannelProgramOverride(channel:Int, program:Int, bank:Int){ val old=channelOverride(channel); setChannelOverride(channel, old.copy(program=program.coerceIn(0,127), bank=bank.coerceIn(0,128))) }
    fun setVoiceMap(vm:Map<Int,String>){voiceMap=vm;lastAppliedSection="";com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 Legacy VoiceMap received: ${vm.size}; CASM policy takes precedence")}
    fun play(section:StyleSectionModel,ppq:Int,loopLimit:Int=-1,onComplete:(()->Unit)?=null){stop();com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 SECTION " + section.name + ": preserving current chord for immediate CASM retarget");loopCount=0;if(lastAppliedSection!=section.name){applyVoicesFromCasm(section);lastAppliedSection=section.name};val noteCount=section.parts.sumOf{part->part.events.count{isNoteEvent(it)}};com.yourapp.yamahaarranger.ui.DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=${section.parts.sumOf{it.events.size}}, noteEvents=$noteCount, loopLimit=$loopLimit");playbackJob=scope.launch{var loops=0;while(loopLimit<0||loops<loopLimit){playOnce(section,ppq);loops++};onComplete?.invoke()}}
    fun stop(){playbackJob?.cancel();playbackJob=null;barClockStartedAtNanos=0L;audioEngine.allNotesOff();midiInputManager.allNotesOff();activeTransposedNotes.clear();com.yourapp.yamahaarranger.ui.DebugLog.add("⏹ STOP")}
    fun queueNextSection(section:StyleSectionModel,ppq:Int)=play(section,ppq)
    fun millisToNextBar(ppq: Int, beatsPerBar: Int = 4): Long {
        val anchor = barClockStartedAtNanos
        if (anchor == 0L || ppq <= 0) return 0L
        val bpm = tempoBpm.coerceIn(20, 280)
        val barMs = (beatsPerBar * 60_000.0 / bpm).toLong().coerceAtLeast(1L)
        val elapsedMs = (System.nanoTime() - anchor) / 1_000_000L
        val remainder = elapsedMs % barMs
        val wait = if (remainder == 0L) 0L else barMs - remainder
        return if (wait <= 25L) 0L else wait
    }


    private fun handleNoChord(){
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{releaseActive(it)}
        if(snapshot.isNotEmpty())com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 NO CHORD: released ${snapshot.size} held style notes")
    }

    private fun handleChordChange(newChord:DetectedChord){
        if(activeTransposedNotes.isEmpty())return
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{active->
            val selectedPolicy=selectPolicy(active.policies,active.sourceNote,newChord)
            if(selectedPolicy==null){
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

    private fun applyVoicesFromCasm(section:StyleSectionModel) {
        // Several CASM source parts can share one destination MIDI channel.
        // A source without its own Program Change must never overwrite the
        // program already established by another source on that destination.
        val explicitByDestination = linkedMapOf<Int, Triple<Int, Int, Int>>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in lockedChannels) return@forEach
            if (part.program in 0..127 && !explicitByDestination.containsKey(destination)) {
                explicitByDestination[destination] = Triple(part.program, part.bankMsb, part.bankLsb)
            }
        }

        val applied = mutableSetOf<Int>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in lockedChannels || destination in applied) return@forEach

            val drum = destination == 9 || isDrumVoice(c.voiceName)
            val override = channelOverrides[destination]
            if (override?.muted == true) { applied += destination; com.yourapp.yamahaarranger.ui.DebugLog.add("🔇 dst$destination muted by style editor"); return@forEach }
            val explicit = explicitByDestination[destination]
            val prog = override?.program ?: explicit?.first ?: guessProgramFromVoiceName(c.voiceName)
            if (prog !in 0..127) {
                com.yourapp.yamahaarranger.ui.DebugLog.add("⚠ src${c.sourceChannel}→dst$destination: no usable program for '${c.voiceName}'")
                return@forEach
            }
            val midiMsb = override?.bank ?: explicit?.second ?: part.bankMsb
            val audioBank = if (drum) 128 else 0
            val midiBank = if (drum) 127 else midiMsb.coerceIn(0, 127)
            audioEngine.setChannelProgram(destination, prog, audioBank)
            midiInputManager.sendProgramChange(destination, prog, midiBank)
            applied += destination
            val source = if (override?.program != null) "STYLE OVERRIDE" else if (explicit != null) "actual MIDI setup" else "fallback name"
            com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 dst$destination: ${c.voiceName} → PC=$prog MIDIbank=$midiBank SFbank=$audioBank ($source)")
        }
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

    // Style destination 13 is the String/Pad shared destination in LoveSong.
    // The SF2 rendering was slightly too forward in the test recording, so
    // trim only the internal audio velocity while leaving external MIDI
    // velocity untouched.
    private fun audioVelocityScale(destinationChannel:Int):Float =
        if (destinationChannel == 13) 0.85f else 1.0f
    private suspend fun playOnce(section:StyleSectionModel,ppq:Int){loopCount++; barClockStartedAtNanos = System.nanoTime();if(section.lengthTicks<=0){delay(500);return};data class Scheduled(val tick:Int,val event:StyleNoteEvent,val part:com.yourapp.yamahaarranger.style.StylePartModel);val merged=section.parts.flatMap{part->part.events.filter(::isNoteEvent).map{e->Scheduled(e.tick,e,part)}}.sortedWith(compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()});if(merged.isEmpty()){delay(500);return};var lastTick=0;for(s in merged){val delta=s.tick-lastTick;if(delta>0)delay(ticksToMillis(delta,ppq,tempoBpm));lastTick=s.tick;val chord=currentChord;val policy=selectPolicy(s.part,s.event.note,chord);if(policy==null&&chord!=null&&s.event.isNoteOn&&!isRhythmSource(s.event.channel))continue;if(s.event.isNoteOn&&isUnsupportedArticulation(policy)){com.yourapp.yamahaarranger.ui.DebugLog.add("🔇 SUPPRESS ${policy?.voiceName} src${s.event.channel}:${s.event.note} (MegaVoice articulation unsupported by SF2)");continue};if(!s.event.isNoteOn){val key="${s.event.channel}:${s.event.note}";val active=activeTransposedNotes.remove(key);if(active!=null){if(active.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEOFF src${active.sourceChannel}:${active.sourceNote} dst13 note=${active.outputNote}");audioEngine.noteOffChannel(active.destinationChannel,active.outputNote);midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)};continue};val sourceChannel=s.event.channel;val destinationChannel=policy?.destinationChannel?:sourceChannel;if(destinationChannel in lockedChannels)continue;val channelOverride=channelOverrides[destinationChannel];if(channelOverride?.muted==true)continue;val key="${sourceChannel}:${s.event.note}";val previousActive=activeTransposedNotes[key];if(previousActive!=null){if(previousActive.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING REPLACE src${previousActive.sourceChannel}:${previousActive.sourceNote} oldNote=${previousActive.outputNote}");audioEngine.noteOffChannel(previousActive.destinationChannel,previousActive.outputNote);midiInputManager.sendNoteOff(previousActive.destinationChannel,previousActive.outputNote);activeTransposedNotes.remove(key)};val isDrumPart=destinationChannel==9||(policy!=null&&isDrumVoice(policy.voiceName));val transformed=if(policy!=null&&!isDrumPart){chord?.let{CasmNoteTransformer.transform(s.event.note,it,policy)}?:s.event.note.coerceIn(0,127)}else{s.event.note.coerceIn(0,127)};val note=((transformed?:continue)+ (channelOverride?.transpose ?: 0)).coerceIn(0,127);val velocity=((s.event.velocity.coerceIn(1,127) * ((channelOverride?.volume ?: 127) / 127f)).roundToInt()).coerceIn(1,127);if(policy!=null&&!isDrumPart){val policyList=s.part.casmPolicies.ifEmpty{listOfNotNull(s.part.casm)}
            activeTransposedNotes[key]=ActiveTransposedNote(sourceChannel,s.event.note,destinationChannel,note,velocity,policy,policyList);if(destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEON src${sourceChannel}:${s.event.note} dst13 note=${note} NTR=${policy.ntr and 0x7f} NTT=${policy.ntt and 0x7f} RTR=${policy.rtr and 0x7f}")
            if(destinationChannel==13){
                com.yourapp.yamahaarranger.ui.DebugLog.add(
                    "🎼 CASM SELECT src${sourceChannel}:${s.event.note} chord=${chord?.rootNote}/${chord?.quality} → dst${destinationChannel} NTR=${policy.ntr and 0x7f} NTT=${policy.ntt and 0x7f} SRC=${policy.sourceChordRoot}/${policy.sourceChordType} range=${policy.sourceNoteLow}-${policy.sourceNoteHigh} RTR=${policy.rtr and 0x7f}"
                )
            }};audioEngine.noteOnChannel(destinationChannel,note,(velocity/127f)*audioVelocityScale(destinationChannel));midiInputManager.sendNoteOn(destinationChannel,note,velocity)};val rem=section.lengthTicks-lastTick;if(rem>0)delay(ticksToMillis(rem,ppq,tempoBpm))}
    private fun ticksToMillis(ticks:Int,ppq:Int,bpm:Int):Long=if(ppq<=0||bpm<=0)0 else((ticks*(60000.0/bpm))/ppq).toLong().coerceAtLeast(0)
}

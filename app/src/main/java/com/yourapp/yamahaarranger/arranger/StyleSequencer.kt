package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var voiceMap:Map<Int,String> = emptyMap()
    private var lastAppliedSection=""
    private var lockedChannels:Set<Int> = emptySet()
    private data class ActiveTransposedNote(
        val sourceChannel:Int,
        val sourceNote:Int,
        val destinationChannel:Int,
        var outputNote:Int,
        val velocity:Int,
        val policy:CasmPolicyModel
    )
    private val activeTransposedNotes=mutableMapOf<String,ActiveTransposedNote>()

    fun setLockedChannels(channels:Set<Int>){lockedChannels=channels;com.yourapp.yamahaarranger.ui.DebugLog.add("🔒 Locked channels updated: $channels")}
    fun setVoiceMap(vm:Map<Int,String>){voiceMap=vm;lastAppliedSection="";com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 Legacy VoiceMap received: ${vm.size}; CASM policy takes precedence")}
    fun play(section:StyleSectionModel,ppq:Int,loopLimit:Int=-1,onComplete:(()->Unit)?=null){stop();com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 SECTION " + section.name + ": preserving current chord for immediate CASM retarget");loopCount=0;if(lastAppliedSection!=section.name){applyVoicesFromCasm(section);lastAppliedSection=section.name};val noteCount=section.parts.sumOf{part->part.events.count{isNoteEvent(it)}};com.yourapp.yamahaarranger.ui.DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=${section.parts.sumOf{it.events.size}}, noteEvents=$noteCount, loopLimit=$loopLimit");playbackJob=scope.launch{var loops=0;while(loopLimit<0||loops<loopLimit){playOnce(section,ppq);loops++};onComplete?.invoke()}}
    fun stop(){playbackJob?.cancel();playbackJob=null;audioEngine.allNotesOff();midiInputManager.allNotesOff();activeTransposedNotes.clear();com.yourapp.yamahaarranger.ui.DebugLog.add("⏹ STOP")}
    fun queueNextSection(section:StyleSectionModel,ppq:Int)=play(section,ppq)

    private fun handleNoChord(){
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{releaseActive(it)}
        if(snapshot.isNotEmpty())com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 NO CHORD: released ${snapshot.size} held style notes")
    }

    private fun handleChordChange(newChord:DetectedChord){
        if(activeTransposedNotes.isEmpty())return
        val snapshot=activeTransposedNotes.values.toList()
        snapshot.forEach{active->
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
            val explicit = explicitByDestination[destination]
            val prog = explicit?.first ?: guessProgramFromVoiceName(c.voiceName)
            if (prog !in 0..127) {
                com.yourapp.yamahaarranger.ui.DebugLog.add("⚠ src${c.sourceChannel}→dst$destination: no usable program for '${c.voiceName}'")
                return@forEach
            }
            val midiMsb = explicit?.second ?: part.bankMsb
            val audioBank = if (drum) 128 else 0
            val midiBank = if (drum) 127 else midiMsb.coerceIn(0, 127)
            audioEngine.setChannelProgram(destination, prog, audioBank)
            midiInputManager.sendProgramChange(destination, prog, midiBank)
            applied += destination
            val source = if (explicit != null) "actual MIDI setup" else "fallback name"
            com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 dst$destination: ${c.voiceName} → PC=$prog MIDIbank=$midiBank SFbank=$audioBank ($source)")
        }
    }
    private fun guessProgramFromVoiceName(name:String):Int{val n=name.lowercase();val numeric=Regex("(?:^|\\D)(\\d{1,3})\\s*$").find(n)?.groupValues?.getOrNull(1)?.toIntOrNull();if(numeric!=null&&numeric in 0..127)return numeric;return when{n.contains("piano")->0;n.contains("e.piano")||n.contains("ep")->4;n.contains("organ")->16;n.contains("accordion")->21;n.contains("guitar")||n.contains("gtr")->24;n.contains("bass")->33;n.contains("violin")->40;n.contains("cello")->42;n.contains("strg")||n.contains("str")->48;n.contains("choir")->52;n.contains("trumpet")->56;n.contains("trombone")->57;n.contains("brass")->61;n.contains("sax")->65;n.contains("oboe")->68;n.contains("clarinet")->71;n.contains("flute")->73;n.contains("crash")||n.contains("cymbal")||n.contains("perc")||n.contains("dr")||n.contains("kit")||n.contains("drum")->0;n.contains("pad")->89;else->-1}}
    private fun isDrumVoice(name:String)=name.lowercase().let{it.contains("crash")||it.contains("cymbal")||it.contains("perc")||it.contains("add-dr")||it.contains("drum")||it.contains("kit")||it.startsWith("dr")}
    private fun policyMatchesChord(policy:CasmPolicyModel,chord:DetectedChord):Boolean {
        // Rhythm/sub-rhythm policies are not chord-family filters.
        if (policy.destinationChannel == 8 || policy.destinationChannel == 9 || isDrumVoice(policy.voiceName)) return true
        return when(policy.sourceChordType){
            0 -> true
            2 -> chord.quality!=ChordQuality.MINOR&&chord.quality!=ChordQuality.MIN6&&chord.quality!=ChordQuality.MIN7
            10 -> chord.quality==ChordQuality.MINOR||chord.quality==ChordQuality.MIN6||chord.quality==ChordQuality.MIN7
            else -> true
        }
    }
    private fun selectPolicy(part:com.yourapp.yamahaarranger.style.StylePartModel,eventNote:Int,chord:DetectedChord?):CasmPolicyModel?{val policies=part.casmPolicies.ifEmpty{listOfNotNull(part.casm)};if(policies.isEmpty())return null;val inRange=policies.filter{eventNote in it.sourceNoteLow..it.sourceNoteHigh};if(chord==null)return inRange.firstOrNull()?:policies.first();val matching=inRange.filter{policyMatchesChord(it,chord)};if(matching.isEmpty())return null;val exactType=matching.firstOrNull{it.sourceChordType==sourceChordTypeFor(chord)};return exactType?:matching.firstOrNull{it.sourceChordType==0}?:matching.firstOrNull()}
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
    private suspend fun playOnce(section:StyleSectionModel,ppq:Int){loopCount++;if(section.lengthTicks<=0){delay(500);return};data class Scheduled(val tick:Int,val event:StyleNoteEvent,val part:com.yourapp.yamahaarranger.style.StylePartModel);val merged=section.parts.flatMap{part->part.events.filter(::isNoteEvent).map{e->Scheduled(e.tick,e,part)}}.sortedWith(compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()});if(merged.isEmpty()){delay(500);return};var lastTick=0;for(s in merged){val delta=s.tick-lastTick;if(delta>0)delay(ticksToMillis(delta,ppq,tempoBpm));lastTick=s.tick;val chord=currentChord;val policy=selectPolicy(s.part,s.event.note,chord);if(policy==null&&chord!=null&&s.event.isNoteOn&&!isRhythmSource(s.event.channel))continue;if(s.event.isNoteOn&&isUnsupportedArticulation(policy)){com.yourapp.yamahaarranger.ui.DebugLog.add("🔇 SUPPRESS ${policy?.voiceName} src${s.event.channel}:${s.event.note} (MegaVoice articulation unsupported by SF2)");continue};if(!s.event.isNoteOn){val key="${s.event.channel}:${s.event.note}";val active=activeTransposedNotes.remove(key);if(active!=null){audioEngine.noteOffChannel(active.destinationChannel,active.outputNote);midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)};continue};val sourceChannel=s.event.channel;val destinationChannel=policy?.destinationChannel?:sourceChannel;if(destinationChannel in lockedChannels)continue;val key="${sourceChannel}:${s.event.note}";val isDrumPart=destinationChannel==9||(policy!=null&&isDrumVoice(policy.voiceName));val transformed=if(policy!=null&&!isDrumPart){chord?.let{CasmNoteTransformer.transform(s.event.note,it,policy)}?:s.event.note.coerceIn(0,127)}else{s.event.note.coerceIn(0,127)};val note=transformed?:continue;val velocity=s.event.velocity.coerceIn(1,127);if(policy!=null&&!isDrumPart){activeTransposedNotes[key]=ActiveTransposedNote(sourceChannel,s.event.note,destinationChannel,note,velocity,policy)};audioEngine.noteOnChannel(destinationChannel,note,(velocity/127f)*audioVelocityScale(destinationChannel));midiInputManager.sendNoteOn(destinationChannel,note,velocity)};val rem=section.lengthTicks-lastTick;if(rem>0)delay(ticksToMillis(rem,ppq,tempoBpm))}
    private fun ticksToMillis(ticks:Int,ppq:Int,bpm:Int):Long=if(ppq<=0||bpm<=0)0 else((ticks*(60000.0/bpm))/ppq).toLong().coerceAtLeast(0)
}

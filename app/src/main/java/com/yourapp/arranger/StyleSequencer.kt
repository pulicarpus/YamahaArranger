package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StyleSequencer(private val audioEngine: AudioEngineManager, private val midiInputManager: MidiInputManager, private val scope: CoroutineScope) {
    private var playbackJob:Job?=null
    var tempoBpm:Int=120
    var currentChord:DetectedChord?=null
    private var loopCount=0
    private var voiceMap:Map<Int,String> = emptyMap()
    private var lastAppliedSection=""
    private var lockedChannels:Set<Int> = emptySet()
    private data class ActiveTransposedNote(val destinationChannel:Int,val note:Int)
    private val activeTransposedNotes=mutableMapOf<String,ActiveTransposedNote>()
    fun setLockedChannels(channels:Set<Int>){lockedChannels=channels;DebugLog.add("🔒 Locked channels updated: $channels")}
    fun setVoiceMap(vm:Map<Int,String>){voiceMap=vm;lastAppliedSection="";DebugLog.add("🎼 Legacy VoiceMap received: ${vm.size}; CASM policy takes precedence")}
    fun play(section:StyleSectionModel,ppq:Int,loopLimit:Int=-1,onComplete:(()->Unit)?=null){stop();loopCount=0;if(lastAppliedSection!=section.name){applyVoicesFromCasm(section);lastAppliedSection=section.name};val noteCount=section.parts.sumOf{part->part.events.count{isNoteEvent(it)}};DebugLog.add("▶ PLAY ${section.name}: parts=${section.parts.size}, events=${section.parts.sumOf{it.events.size}}, noteEvents=$noteCount, loopLimit=$loopLimit");playbackJob=scope.launch{var loops=0;while(loopLimit<0||loops<loopLimit){playOnce(section,ppq);loops++};onComplete?.invoke()}}
    fun stop(){playbackJob?.cancel();playbackJob=null;audioEngine.allNotesOff();midiInputManager.allNotesOff();activeTransposedNotes.clear();DebugLog.add("⏹ STOP")}
    fun queueNextSection(section:StyleSectionModel,ppq:Int)=play(section,ppq)
    private fun applyVoicesFromCasm(section:StyleSectionModel){section.parts.forEach{part->val policies=part.casmPolicies.ifEmpty{listOfNotNull(part.casm)};val c=policies.firstOrNull()?:return@forEach;val destination=c.destinationChannel;if(destination in lockedChannels)return@forEach;val prog=guessProgramFromVoiceName(c.voiceName);if(prog<0){DebugLog.add("⚠ src${c.sourceChannel}→dst$destination: unsupported '${c.voiceName}'");return@forEach};val drum=destination==9||isDrumVoice(c.voiceName);val bank=if(drum)128 else 0;audioEngine.setChannelProgram(destination,prog,bank);midiInputManager.sendProgramChange(destination,prog,bank);DebugLog.add("🎼 src${c.sourceChannel}→dst$destination: ${c.voiceName} → GM $prog bank=$bank policies=${policies.size}")}}
    private fun guessProgramFromVoiceName(name:String):Int{val n=name.lowercase();val numeric=Regex("(?:^|\\D)(\\d{1,3})\\s*$").find(n)?.groupValues?.getOrNull(1)?.toIntOrNull();if(numeric!=null&&numeric in 0..127)return numeric;return when{n.contains("piano")->0;n.contains("e.piano")||n.contains("ep")->4;n.contains("organ")->16;n.contains("accordion")->21;n.contains("guitar")||n.contains("gtr")->24;n.contains("bass")->33;n.contains("violin")->40;n.contains("cello")->42;n.contains("strg")||n.contains("str")->48;n.contains("choir")->52;n.contains("trumpet")->56;n.contains("trombone")->57;n.contains("brass")->61;n.contains("sax")->65;n.contains("oboe")->68;n.contains("clarinet")->71;n.contains("flute")->73;n.contains("crash")||n.contains("cymbal")||n.contains("perc")||n.contains("dr")||n.contains("kit")||n.contains("drum")->0;n.contains("pad")->89;else->-1}}
    private fun isDrumVoice(name:String)=name.lowercase().let{it.contains("crash")||it.contains("cymbal")||it.contains("perc")||it.contains("add-dr")||it.contains("drum")||it.contains("kit")||it.startsWith("dr")}
    private fun policyMatchesChord(policy:CasmPolicyModel,chord:DetectedChord):Boolean{return when(policy.sourceChordType){0->true;2->chord.quality!=ChordQuality.MINOR&&chord.quality!=ChordQuality.MIN6&&chord.quality!=ChordQuality.MIN7;10->chord.quality==ChordQuality.MINOR||chord.quality==ChordQuality.MIN6||chord.quality==ChordQuality.MIN7;else->true}}
    private fun selectPolicy(part:com.yourapp.yamahaarranger.style.StylePartModel,eventNote:Int,chord:DetectedChord?):CasmPolicyModel?{val policies=part.casmPolicies.ifEmpty{listOfNotNull(part.casm)};if(policies.isEmpty())return null;val inRange=policies.filter{eventNote in it.sourceNoteLow..it.sourceNoteHigh};if(chord==null)return inRange.firstOrNull()?:policies.first();return inRange.firstOrNull{policyMatchesChord(it,chord)}?:inRange.firstOrNull()}
    private fun isNoteEvent(event:StyleNoteEvent):Boolean{val hi=event.status and 0xF0;return hi==0x90||hi==0x80}
    private suspend fun playOnce(section:StyleSectionModel,ppq:Int){loopCount++;if(section.lengthTicks<=0){delay(500);return};data class Scheduled(val tick:Int,val event:StyleNoteEvent,val part:com.yourapp.yamahaarranger.style.StylePartModel);val merged=section.parts.flatMap{part->part.events.filter(::isNoteEvent).map{e->Scheduled(e.tick,e,part)}}.sortedWith(compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()});if(merged.isEmpty()){delay(500);return};var lastTick=0;for(s in merged){val delta=s.tick-lastTick;if(delta>0)delay(ticksToMillis(delta,ppq,tempoBpm));lastTick=s.tick;val chord=currentChord;val policy=selectPolicy(s.part,s.event.note,chord);if(policy!=null&&chord!=null&&!policyMatchesChord(policy,chord))continue;if(!s.event.isNoteOn){val key="${s.event.channel}:${s.event.note}";val active=activeTransposedNotes.remove(key);if(active!=null){audioEngine.noteOffChannel(active.destinationChannel,active.note);midiInputManager.sendNoteOff(active.destinationChannel,active.note)};continue};val sourceChannel=s.event.channel;val destinationChannel=policy?.destinationChannel?:sourceChannel;if(destinationChannel in lockedChannels)continue;val key="${sourceChannel}:${s.event.note}";val isDrumPart=destinationChannel==9||(policy!=null&&isDrumVoice(policy.voiceName));val transformed=if(policy!=null&&!isDrumPart){chord?.let{CasmNoteTransformer.transform(s.event.note,it,policy)}?:s.event.note.coerceIn(0,127)}else{s.event.note.coerceIn(0,127)};val note=transformed?:continue;val velocity=s.event.velocity.coerceIn(1,127);activeTransposedNotes[key]=ActiveTransposedNote(destinationChannel,note);audioEngine.noteOnChannel(destinationChannel,note,velocity/127f);midiInputManager.sendNoteOn(destinationChannel,note,velocity)};val rem=section.lengthTicks-lastTick;if(rem>0)delay(ticksToMillis(rem,ppq,tempoBpm))}
    private fun ticksToMillis(ticks:Int,ppq:Int,bpm:Int):Long=if(ppq<=0||bpm<=0)0 else((ticks*(60000.0/bpm))/ppq).toLong().coerceAtLeast(0)
}

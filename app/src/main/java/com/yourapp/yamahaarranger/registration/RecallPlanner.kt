package com.yourapp.yamahaarranger.registration

/** Engine-independent immutable snapshots; LEFT enabled must come from Brain, not UI slot 3. */
data class RecallSnapshot(val parts: List<PartSetup>, val release: ReleaseSetup, val tempoBpm: Int,
    val transpose: Int, val voiceRequests: List<Long> = List(4) { 0L })
sealed class RecallStep {
    data class Enabled(val part: Part, val value: Boolean) : RecallStep()
    data class Sf2(val part: Part, val voice: Sf2VoiceRef) : RecallStep()
    data class External(val part: Part, val voice: ExternalVoice) : RecallStep()
    data class Volume(val part: Part, val value: Int) : RecallStep()
    data class Release(val value: ReleaseSetup) : RecallStep()
    data class Transpose(val value: Int) : RecallStep()
    data class Tempo(val value: Int) : RecallStep()
}
data class RecallIssue(val part: Part, val reason: String)
data class RecallPlan(val steps: List<RecallStep>, val issues: List<RecallIssue>)
object RecallPlanner {
    fun plan(current: RecallSnapshot, target: RegistrationSlot, freeze: FreezeFlags,
        catalog: List<Sf2VoiceRef>, midiOutActive: Boolean): RecallPlan {
        require(current.parts.size == 4 && target.parts.size == 4 && current.voiceRequests.size == 4)
        val off=mutableListOf<RecallStep>(); val sf2=mutableListOf<RecallStep>(); val ext=mutableListOf<RecallStep>()
        val volume=mutableListOf<RecallStep>(); val on=mutableListOf<RecallStep>(); val issues=mutableListOf<RecallIssue>()
        for(part in Part.values()) {
            val old=current.parts[part.channel]; val new=target.parts[part.channel]
            if(!freeze.partOnOff && old.enabled != new.enabled) {
                (if(new.enabled) on else off).add(RecallStep.Enabled(part,new.enabled))
            }
            if(!freeze.voices) {
                if(target.outputMode != OutputMode.EXTERNAL && new.sf2 != null && old.sf2 != new.sf2) {
                    if(catalog.count { it == new.sf2 } == 1) sf2.add(RecallStep.Sf2(part,new.sf2))
                    else issues.add(RecallIssue(part,if(catalog.none { it == new.sf2 }) "SF2_MISSING" else "SF2_AMBIGUOUS"))
                }
                if(target.outputMode != OutputMode.INTERNAL && new.external != null) {
                    if(midiOutActive) ext.add(RecallStep.External(part,new.external))
                    else issues.add(RecallIssue(part,"SKIPPED_MIDI_OUT_OFF"))
                }
            }
            if(!freeze.volumes && old.volume != new.volume) volume.add(RecallStep.Volume(part,new.volume.coerceIn(0,127)))
        }
        val steps=off+sf2+ext+volume+
            (if(!freeze.release && current.release != target.release) listOf(RecallStep.Release(target.release)) else emptyList())+on+
            (if(!freeze.transpose && target.transpose != null && target.transpose != current.transpose) listOf(RecallStep.Transpose(target.transpose.coerceIn(-12,12))) else emptyList())+
            (if(!freeze.tempo && target.tempoBpm != null && target.tempoBpm != current.tempoBpm) listOf(RecallStep.Tempo(target.tempoBpm.coerceIn(20,280))) else emptyList())
        return RecallPlan(steps,issues)
    }
}

package com.yourapp.audio

import com.yourapp.yamahaarranger.style.ParsedStyle

/** Explicit STOP replay of RAW events through token contracts, not a realtime sequencer emulator. */
object GenericDrumShadowRehearsal {
    data class Result(val noteOns:Int,val noteOffs:Int,val unmatchedOffs:Int,val endingFlush:Int,
        val rejectedCapacity:Int,val selectedCandidateOns:Int,val traceSHA256:String,val finalOwners:Int)
    fun replay(style:ParsedStyle,plan:GenericDrumResolver.Plan,snapshot:DrumShadowPlanner.Snapshot,experimental:Boolean):Result {
        val runtime=ShadowDrumRuntime();val owners=mutableMapOf<Pair<Int,Int>,java.util.ArrayDeque<List<Long>>>()
        val rowIndex=plan.rows.withIndex().groupBy { (_,r)->listOf(r.request.section,r.request.part,r.request.sourceChannel,r.request.tick,r.request.sourceKey,r.request.velocity) }
            .mapValues { (_,rows)->rows.groupBy { it.value.request.rhythmChannel }.mapValues { (_,xs)->java.util.ArrayDeque(xs) } }
        var ons=0;var offs=0;var unmatched=0;var flushed=0;var rejected=0;var candidates=0
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun emit(c:ShadowDrumRuntime.Command) { digest.update((c.toString()+"\n").toByteArray());if(c.kind==ShadowDrumRuntime.Kind.OFF)offs++ }
        for((name,section) in style.sections.toSortedMap()) {
            val ordered=section.parts.flatMapIndexed { partId,part->part.events.mapIndexed { eventId,event->Triple(partId*1000000+eventId,part,event) } }.sortedWith(compareBy<Triple<Int,com.yourapp.yamahaarranger.style.StylePartModel,com.yourapp.yamahaarranger.style.StyleNoteEvent>> { it.third.tick }.thenBy { it.first })
            for((_,part,event) in ordered) {
                val status=event.status and 0xf0
                if(status==0x90 && event.velocity>0) {
                    val rows=rowIndex[listOf(name,part.name,event.channel,event.tick,event.note,event.velocity)].orEmpty().values.mapNotNull { if(it.isEmpty())null else it.removeFirst() }
                    val admitted=mutableListOf<Long>()
                    for((id,row) in rows) {
                        val request=row.request;val live=snapshot.production.firstOrNull { it.channel==request.rhythmChannel }
                        val legacy=ShadowDrumRuntime.Route(live?.sha256 ?: "UNKNOWN_STOP_FONT",0,snapshot.generation,live?.bank ?: -1,live?.pc ?: -1,request.sourceKey,request.rhythmChannel,legacy=true)
                        val selected=row.selected?.let { binding ->
                            val c=row.candidates.first { it.evidence.candidate==binding && it.safe };val ticket=c.ticket!!
                            ShadowDrumRuntime.Route(binding.sha256,ticket.handle,ticket.generation,binding.bank,binding.pc,binding.key,request.rhythmChannel,c.layers.map { it.ig[57] ?: 0 }.distinct().singleOrNull() ?: 0)
                        }
                        val token=id.toLong()+1
                        if(runtime.on(token,legacy,selected,request.velocity,experimental,::emit)) {
                            admitted+=token
                            ons++;if(experimental && selected!=null)candidates++
                        } else rejected++
                    }
                    if(admitted.isNotEmpty())owners.getOrPut(event.channel to event.note) { java.util.ArrayDeque() }.addLast(admitted)
                } else if(status==0x80 || status==0x90 && event.velocity==0) {
                    val q=owners[event.channel to event.note]
                    if(q!=null && q.isNotEmpty())q.removeFirst().forEach { runtime.off(it,::emit) }
                    else if(event.channel==8 || event.channel==9)unmatched++
                }
            }
            runtime.boundary(false,::emit) // held token routes survive all section/plan changes
        }
        flushed=runtime.active;runtime.boundary(true,::emit)
        return Result(ons,offs,unmatched,flushed,rejected,candidates,digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)},runtime.active)
    }
}

package com.yourapp.audio

/** Complete summary first, bounded detail next. All rows retain the legacy action on abstention. */
object GenericDrumShadowExport {
    fun export(plan:GenericDrumResolver.Plan,activationRequested:Boolean=false,rehearsal:String="NOT_RUN"):String {
        val out=StringBuilder();var bytes=0;var omitted=0;var omittedHits=0;var omittedSummaries=0
        fun add(s:String):Boolean {
            val clean=s.replace('\n',' ').replace('\r',' ');val n=clean.toByteArray().size+1
            if(bytes+n>DrumShadowPlanner.EXPORT_BYTES-1024)return false
            out.append(clean).append('\n');bytes+=n;return true
        }
        val activation=DrumExperimentalActivation.evaluate(activationRequested,plan)
        add("YAMAHAARRANGER GENERIC DRUM RESOLVER SHADOW v1 productionDispatch=UNCHANGED runtimeHooks=NONE activation=$activation")
        add("GLOBAL_INVENTORY fonts=${plan.fonts} zones=${plan.zones} candidateKeys=${plan.candidateKeys} complete=${plan.complete} digest=${plan.digest} compileNs=${plan.compileNanos} preparation=STOP_worker_only")
        add("RESOLVED_COUNTS ${plan.counts()} totalDemand=${plan.rows.size};EXACT_is_reviewed_identity_not_address_or_name")
        add("SEMANTIC_CLAIM_COUNTS ${plan.rows.groupingBy { it.semanticClaim }.eachCount()};not_safe_route_counts")
        add("LEGACY_COMPARISON unchangedFallbackDemand=${plan.rows.count { it.selected==null }} hypotheticalSafeRouteDemand=${plan.rows.count { it.selected!=null }} observedActualPerNoteDispatch=UNKNOWN;raw_source_and_STOP_snapshot_not_runtime_CASM")
        add("RANKING EXACT>COMPATIBLE>APPROXIMATION(policy)>ABSTAIN;all_runtime_gates_required;equal_safe_bindings=AMBIGUOUS_NO_TIE_WINNER;name_hints=UNKNOWN_not_admitted;unmapped_Yamaha_note_not_assumed_GM")
        add("OWNER_MODEL captured_ON_route_token_to_OFF;repeated_notes_distinct_tokens;held_owners_survive_section_plan_flag_change;choke_domain=font_handle_generation_preset_lane_exclusiveClass;offline_model_NOT_production_adapter")
        add("END_TO_END_REHEARSAL $rehearsal")
        for((context,rows) in plan.rows.groupBy { listOf(it.request.section,it.request.rhythmChannel,it.request.msb,it.request.lsb,it.request.rawPc) })
            if(!add("CONTEXT $context hits=${rows.size} outcomes=${rows.groupingBy { it.outcome }.eachCount()} semantic=${rows.groupingBy { it.semanticClaim }.eachCount()}"))omittedSummaries++
        for((key,rows) in plan.rows.groupBy { listOf(it.request.msb,it.request.lsb,it.request.rawPc,it.request.sourceKey) })
            if(!add("DEMAND $key hits=${rows.size} velocityDemand=${rows.groupingBy { it.request.velocity }.eachCount().toSortedMap()} outcomes=${rows.groupingBy { it.outcome }.eachCount()}"))omittedSummaries++
        val evaluations=plan.rows.flatMap { r -> r.candidates.map { r.request.velocity to it } }.distinct()
        for((v,c) in evaluations)if(!add("GLOBAL_CANDIDATE id=${c.evidence.evidenceId} binding=${c.evidence.candidate} velocity=$v class=${c.evidence.classification} safe=${c.safe} gates=${c.gates} reasons=${c.reasons} layerSignatures=${c.layers.map { DrumShadowPlanner.digest(it.raw.toByteArray()) }} provenance=${c.evidence.provenance};hint_order_NOT_winner_rank"))omitted++
        for((_,rows) in plan.rows.groupBy { it.request.copy(tick=0) }) {
            val row=rows.first()
            if(!add("SHADOW_GROUP request=${row.request.copy(tick=0)} hits=${rows.size} outcome=${row.outcome} semantic=${row.semanticClaim} selected=${row.selected} reasons=${row.reasons} globalHintsUNKNOWN=${row.hints}")){omitted++;omittedHits+=rows.size}
        }
        out.append("END fullDemand=${plan.rows.size} omittedDetailRows=$omitted omittedDetailHits=$omittedHits omittedSummaryRows=$omittedSummaries maxBytes=${DrumShadowPlanner.EXPORT_BYTES} omission_NOT_absent_candidate;production=UNCHANGED\n")
        return out.toString().also { check(it.toByteArray().size<=DrumShadowPlanner.EXPORT_BYTES) }
    }
}

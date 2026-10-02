package com.yourapp.audio

import com.yourapp.yamahaarranger.style.ParsedStyle

/** STOP-worker, pure observations only. These results never populate production Proof or bindings. */
object DrumEngineeringProof {
    const val VERSION = "engineering-proof-v1"
    const val MAX_EVENTS = 131072
    enum class Status { PASS, FAIL, UNKNOWN }
    enum class Eligibility { SAFE_FOR_FUTURE_STAGE3, BLOCKED, UNKNOWN, AMBIGUOUS }
    data class Gate(val status: Status, val scope: String, val detail: String)
    data class Pitch(val gate: Gate, val layers: List<String>)
    data class OwnerEvent(val section: String, val tick: Int, val order: Int, val logical: String,
        val physical: String?, val on: Boolean)
    data class Ownership(val gate: Gate, val events: Int, val retriggers: Int, val overlaps: Int,
        val unmatchedOff: Int, val unterminated: Int, val boundaryOwners: Int, val unknownOwners: Int)
    data class Candidate(val id: String, val target: String, val semantic: DrumShadowPlanner.Classification,
        val resourceReady: Gate, val pitchSafe: Gate, val chokeSafe: Gate, val ownershipSafe: Gate,
        val velocityCoverage: Gate, val ambiguity: Gate, val eligibility: Eligibility,
        val provenance: String, val details: List<String>)
    data class Report(val cacheDigest: String, val rows: List<Candidate>, val compileNanos: Long)
    private fun pass(scope: String, detail: String) = Gate(Status.PASS, scope, detail)
    private fun fail(scope: String, detail: String) = Gate(Status.FAIL, scope, detail)
    private fun unknown(scope: String, detail: String) = Gate(Status.UNKNOWN, scope, detail)

    fun generation(before: DrumShadowPlanner.Snapshot, after: DrumShadowPlanner.Snapshot): Gate {
        val a = before.generation.toLongOrNull(); val b = after.generation.toLongOrNull()
        return when {
            a == null || b == null || a <= 0 || b <= 0 -> unknown("STOP_SNAPSHOT", "GENERATION_UNAVAILABLE")
            a != b || before.raw != after.raw -> fail("STOP_SNAPSHOT", "GENERATION_OR_HANDLE_SNAPSHOT_CHANGED;before=$a;after=$b")
            else -> pass("STOP_SNAPSHOT", "GENERATION_AND_OBSERVED_HANDLES_STABLE;generation=$a;not_loaded_sample_proof")
        }
    }

    /** SF2 generator offsets are signed; root/fixed-key/scale are instrument generators.
     * Header correction is reported separately: native tuning compensation is not a key remap.
     * Runtime pitch bend, default modulators and engine overrides are deliberately not claimed here. */
    fun pitch(binding: DrumShadowPlanner.Binding, zones: List<DrumShadowPlanner.Zone>): Pitch {
        if (zones.isEmpty()) return Pitch(fail("SF2_STATIC_PITCH", "NO_ELIGIBLE_LAYERS"), emptyList())
        val details = mutableListOf<String>(); var status = Status.PASS
        fun merge(s: Status) { if (s == Status.FAIL || status == Status.PASS) status = s }
        for (z in zones) {
            val root = z.root ?: z.original
            val fixed = z.ig[46]?.takeUnless { it == -1 }
            val played = fixed ?: binding.key
            val scale = z.ig[56] ?: 100
            val coarse = (z.pg[51] ?: 0) + (z.ig[51] ?: 0)
            val fine = (z.pg[52] ?: 0) + (z.ig[52] ?: 0)
            val offset = (played - root) * scale + coarse * 100 + fine
            val generatorPitchUnknown = (z.pg.keys intersect setOf(46, 56, 58)).isNotEmpty() ||
                listOf(5, 6, 7).any { (z.pg[it] ?: 0) + (z.ig[it] ?: 0) != 0 }
            // Exported mods are source:destination:amountSource:transform:amount.
            val blocks = listOf(z.raw.substringAfter(" PG=").substringBefore(" IG="),
                z.raw.substringAfter(" IG=").substringBefore(" classification="))
            val modsKnown = blocks.all { it.contains(";modsKnown=true;mods=") }
            var modUnknown = !modsKnown
            for (block in blocks) {
                val mods = block.substringAfter(";mods=", "unknown")
                if (mods != "none") for (m in mods.split(',')) {
                    val f = m.split(':').map { it.toIntOrNull() }
                    if (f.size != 5 || f.any { it == null }) modUnknown = true
                    else if (f[1] in listOf(5, 6, 7, 46, 51, 52, 56, 58) && f[4] != 0) modUnknown = true
                }
            }
            val s = when {
                root !in 0..127 || played !in 0..127 || scale !in 0..1200 || z.correction !in -99..99 -> Status.UNKNOWN
                modUnknown || generatorPitchUnknown -> Status.UNKNOWN
                offset != 0 -> Status.FAIL
                else -> Status.PASS
            }
            merge(s)
            details += "sample=${z.sampleId};original=${z.original};rootOverride=${z.root};effectiveRoot=$root;fixedKey=$fixed;played=$played;scale=$scale;coarse=$coarse;fine=$fine;keyTuneOffsetCents=$offset;headerCorrection=${z.correction};modsKnown=$modsKnown;relevantModulationUnknown=$modUnknown;static=$s;layerSHA256=${DrumShadowPlanner.digest(z.raw.toByteArray())}"
        }
        val gate = Gate(status, "SF2_STATIC_NATIVE_PITCH_HYPOTHESIS",
            "formula=(fixedKey_or_candidateKey-root)*scale+100*coarse+fine;headerCorrection=native_compensation;default_modulators_runtime_bend_transpose_and_engine_overrides=UNKNOWN;productionPitchSafe=UNKNOWN")
        return Pitch(gate, details)
    }

    /** No assumptions about sharing a synth channel/preset/voice pool across a future exception. */
    fun choke(e: DrumShadowPlanner.Evidence, zones: List<DrumShadowPlanner.Zone>, font: DrumShadowPlanner.Font?): Gate {
        if (e.target?.family != "HI_HAT") return pass("NO_HI_HAT_FAMILY_CLAIM", "NOT_APPLICABLE_TO_REVIEWED_NON_HAT_TARGET;no_choke_group_invented")
        if (zones.isEmpty() || font == null) return fail("SF2_EXCLUSIVE_METADATA", "MISSING_CANDIDATE_LAYERS")
        val classes = zones.map { it.ig[57] ?: 0 }.toSet()
        val related = font.zones.filter { it.bank == e.candidate.bank && it.pc == e.candidate.pc }
        val sameClass = related.filter { (it.ig[57] ?: 0) > 0 && (it.ig[57] ?: 0) in classes }
        val names = related.filter { Regex("(?i)hi.?hat|hihat").containsMatchIn(it.instrument + " " + it.sample) }
        val relations=(sameClass+names).distinctBy { it.raw }
        val observed=relations.take(32).map { "keys${it.kl}:${it.kh}/vel${it.vl}:${it.vh}/instrument=${it.instrument}/sample${it.sampleId}:${it.sample}/class${it.ig[57] ?: 0}/root${it.root ?: it.original}/type${it.sampleType}/link${it.link}/sha=${DrumShadowPlanner.digest(it.raw.toByteArray())}" }
        val detail = "exclusiveClasses=$classes;samePresetRelatedKeys=${sameClass.flatMap { listOf(it.kl,it.kh) }.distinct().sorted()};nameEvidenceHatKeys=${names.flatMap { listOf(it.kl,it.kh) }.distinct().sorted()};relatedRelations=$observed;relatedRelationsTotal=${relations.size};relatedRelationsOmitted=${(relations.size-32).coerceAtLeast(0)};names_not_articulation_proof;reference=SF2_generator57_exclusiveClass;SF2_exclusiveClass_scope=same_preset_channel;BASSMIDI_cross_font_preset_lane_choke=UNPROVEN;family_runtime_colocation=UNKNOWN;no_synthetic_choke_events"
        return unknown("SF2_METADATA_NOT_RUNTIME_CHOKE", detail + if (0 in classes) ";NO_EXPLICIT_EXCLUSIVE_CLASS_ON_SOME_LAYERS" else ";STATIC_EXCLUSIVE_GROUP_PRESENT_NOT_RUNTIME_SAFETY")
    }

    /** Token simulation, NOT a replacement for the arranger's ownership rules. Same-tick part order
     * is only source order; CASM transformations, section switching and real dispatch are unknown. */
    fun ownership(events: List<OwnerEvent>, crossKey: Boolean): Ownership {
        require(events.size <= MAX_EVENTS) { "ownership timeline exceeds diagnostic budget" }
        var retriggers=0; var overlaps=0; var missing=0; var dangling=0; var boundaries=0; var unknownOwners=0
        for (section in events.groupBy { it.section }.values) {
            val active=mutableMapOf<String, java.util.ArrayDeque<String>>()
            val physical=mutableMapOf<String, Int>()
            for (event in section.sortedWith(compareBy<OwnerEvent> { it.tick }.thenBy { it.order })) {
                if (event.on) {
                    if (active[event.logical]?.isNotEmpty() == true) retriggers++
                    // ArrayDeque cannot hold null; a sentinel represents unresolved physical ownership.
                    active.getOrPut(event.logical) { java.util.ArrayDeque() }.addLast(event.physical ?: "?")
                    if (event.physical == null) unknownOwners++ else {
                        if ((physical[event.physical] ?: 0) > 0) overlaps++
                        physical[event.physical]=(physical[event.physical] ?: 0)+1
                    }
                } else {
                    val q=active[event.logical]
                    if (q == null || q.isEmpty()) missing++ else {
                        val p=q.removeFirst()
                        if (p != "?") physical[p]=(physical[p] ?: 1)-1
                    }
                }
            }
            dangling+=active.values.sumOf { it.size }; boundaries+=active.values.count { it.isNotEmpty() }
        }
        val detail="events=${events.size};retrigger=$retriggers;simultaneousPhysicalOwners=$overlaps;unmatchedOff=$missing;unterminated=$dangling;boundaryOwners=$boundaries;unknownPhysicalOwners=$unknownOwners;pairingHypothesis=FIFO_token;main_fill_and_loop_transition=UNPROVEN;raw_source_not_CASM_runtime;tokenRequired=$crossKey;production_owner_remap_implementation=ABSENT"
        val gate=when {
            overlaps>0 || retriggers>0 || missing>0 || dangling>0 -> fail("OFFLINE_PAIRING_HYPOTHESIS", detail+";counterexample_to_simple_key_rewrite_not_failure_of_current_playback")
            crossKey || unknownOwners>0 || events.isEmpty() -> unknown("OFFLINE_PAIRING_NOT_RUNTIME_OWNERSHIP", detail)
            else -> pass("OFFLINE_PAIRING_ONLY",detail+";productionOwnershipSafe=UNKNOWN")
        }
        return Ownership(gate,events.size,retriggers,overlaps,missing,dangling,boundaries,unknownOwners)
    }

    fun velocity(e: DrumShadowPlanner.Evidence, demands: Set<Int>, font: DrumShadowPlanner.Font?): Gate {
        if (demands.isEmpty()) return unknown("ACTUAL_RAW_STYLE_VELOCITY", "NO_DEMAND")
        if (font == null) return fail("SF2_VELOCITY_REGION", "FONT_ABSENT")
        val b=e.candidate; val rows=font.zones.filter { it.bank==b.bank && it.pc==b.pc && b.key in it.kl..it.kh }
        val missing=demands.filter { v -> rows.none { it.eligible(b.key,v) } || v !in e.velocityLow..e.velocityHigh }
        val changed=demands.filter { v -> rows.filter { it.eligible(b.key,v) }.map { DrumShadowPlanner.digest(it.raw.toByteArray()) }.sorted()!=e.layerHashes.sorted() }
        val observed=demands.intersect(e.observedVelocities.toSet()); val inferred=demands-observed
        val detail="actualRawDemand=${demands.sorted()};metadataMissing=$missing;auditedLayerMismatch=$changed;isolatedPCMObserved=${observed.sorted()};sameRegionLayerInference=${inferred.sorted()};layerMultiplicity_preserved=true;fixedVelocityGenerators=${rows.mapNotNull { it.ig[47] }.distinct()};velocityModulators_defaultEngineResponse=UNPROVEN;confidence_unchanged;not_PCM_proof_at_inferred_velocities"
        return when {
            missing.isNotEmpty() -> fail("METADATA_COVERAGE",detail)
            e.schema<2 || e.layerHashes.isEmpty() -> unknown("METADATA_COVERAGE_NOT_AUDIT",detail)
            changed.isNotEmpty() -> fail("AUDITED_LAYER_REGION",detail)
            else -> pass("AUDITED_SAME_REGION_INFERENCE_NOT_RUNTIME_DYNAMICS",detail)
        }
    }

    fun eligibility(semantic: DrumShadowPlanner.Classification, gates: List<Gate>, ambiguous: Boolean): Eligibility = when {
        semantic==DrumShadowPlanner.Classification.INCOMPATIBLE || gates.any { it.status==Status.FAIL } -> Eligibility.BLOCKED
        ambiguous -> Eligibility.AMBIGUOUS
        semantic !in listOf(DrumShadowPlanner.Classification.EXACT,DrumShadowPlanner.Classification.COMPATIBLE) || gates.any { it.status==Status.UNKNOWN } -> Eligibility.UNKNOWN
        // Metadata-only proofs are intentionally insufficient for runtime activation.
        else -> Eligibility.UNKNOWN
    }

    private fun timeline(style: ParsedStyle, plan: DrumShadowPlanner.Plan, e: DrumShadowPlanner.Evidence): List<OwnerEvent> {
        val out=mutableListOf<OwnerEvent>(); var order=0
        val requests=plan.decisions.map { it.request }.groupBy { listOf(it.section,it.part,it.sourceChannel,it.tick,it.sourceKey) }
        val physical="${e.candidate.sha256}:${e.candidate.bank}:${e.candidate.pc}:${e.candidate.key}"
        for ((name,section) in style.sections.toSortedMap()) for (part in section.parts) {
            val policies=part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            for (event in part.events.sortedBy { it.tick }) {
                val status=event.status and 0xf0
                if (status!=0x90 && status!=0x80) continue
                val dst=policies.filter { it.sourceChannel==event.channel }.map { it.destinationChannel }.distinct().ifEmpty { listOf(event.channel) }
                for (rhythm in dst.filter { it==8 || it==9 }) {
                    val on=status==0x90 && event.velocity>0
                    val matches=requests[listOf(name,part.name,event.channel,event.tick,event.note)].orEmpty().any {
                        it.rhythmChannel==rhythm && it.msb==e.msb && it.lsb==e.lsb && it.rawPc==e.pc && it.sourceKey==e.key }
                    // Other raw notes retain unresolved font/preset/key owners; do not fabricate a live lane.
                    val p=if(on && matches) "$rhythm:$physical" else null
                    out+=OwnerEvent(name,event.tick,order++,"$rhythm:${part.name}:${event.channel}:${event.note}",p,on)
                    check(out.size<=MAX_EVENTS) { "ownership timeline exceeds diagnostic budget" }
                }
            }
        }
        return out
    }

    fun audit(plan: DrumShadowPlanner.Plan, fonts: List<DrumShadowPlanner.Font>, before: DrumShadowPlanner.Snapshot,
        after: DrumShadowPlanner.Snapshot, style: ParsedStyle): Report {
        val start=System.nanoTime(); val stable=generation(before,after)
        val all=plan.decisions.flatMap { d -> d.candidates.map { d to it } }
        val rows=all.groupBy { it.second.evidence }.map { (e,demands) ->
            val b=e.candidate;val font=fonts.firstOrNull { it.sha256==b.sha256 }
            val layers=demands.flatMap { it.second.bundle }.distinctBy { it.raw }
            val coverage=velocity(e,demands.map { it.first.request.velocity }.toSet(),font)
            val normalized=before.normalized.filter { it.sha256==b.sha256 && it.rawBank==b.bank }
            val live=before.production.filter { it.sha256==b.sha256 && it.rawBank==b.bank && it.pc==b.pc && it.verified }
            val managedPath=font?.identity?.let { try { val uri=java.net.URI(it);if(uri.scheme=="file")java.io.File(uri).path else it } catch(_:Exception) { it } }
            val pathHandles=before.raw.lineSequence().filter { it.startsWith("FONT ") }.filter { line ->
                val hex=Regex("source=([0-9a-f]+)").find(line)?.groupValues?.get(1)
                val path=try { hex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()?.toString(Charsets.UTF_8) } catch(_:Exception) { null }
                managedPath!=null && managedPath==path
            }.map { Regex("handle=(\\d+)").find(it)?.groupValues?.get(1) }.filterNotNull().toList()
            val missingSamples=layers.filter { font?.samples?.get(it.sampleId)==null || ((it.sampleType and 0x7fff) in listOf(2,4) && font.samples[it.link]==null) }.map { it.sampleId }
            val invalidSamples=layers.filter { z ->
                val h=font?.samples?.get(z.sampleId); val partner=font?.samples?.get(z.link)
                h!=null && (h.rate<=0 || h.end<=h.start || h.original!=z.original || h.type!=z.sampleType || h.link!=z.link ||
                    ((z.sampleType and 0x7fff) in listOf(2,4) && (partner==null || partner.link!=z.sampleId ||
                        (partner.type and 0x7fff)!=(if((z.sampleType and 0x7fff)==2)4 else 2) || h.rate!=partner.rate ||
                        h.end-h.start!=partner.end-partner.start || h.original!=partner.original)))
            }.map { it.sampleId }
            val available=font?.zones?.any { it.bank==b.bank && it.pc==b.pc }==true
            val resourceDetail="fingerprint=${b.sha256};identity=${font?.identity};bank=${b.bank};rawPC=${b.pc};sourceKey=${b.key};managedBytes=${if(font!=null)Status.PASS else Status.FAIL};presetMetadata=${if(available)Status.PASS else Status.FAIL};eligibleRelations=${layers.size};sampleRefsMissing=$missingSamples;sampleHeadersInvalid=$invalidSamples;managedGeneration=${before.generation};pathCorrelatedHandles=$pathHandles;normalizedHandles=${normalized.map { it.handle }};virtualBanks=${normalized.map { it.virtualBank }};matchingActiveRoutes=${live.map { it.channel }};path_correlation_NOT_loaded_byte_identity;loadedFingerprint=UNKNOWN;perCandidateSampleReadiness=UNKNOWN;${stable.detail}"
            val resource=when {
                stable.status==Status.FAIL -> fail("MANAGED_RESOURCE_OBSERVATION",resourceDetail)
                font==null || !available || layers.isEmpty() || missingSamples.isNotEmpty() || invalidSamples.isNotEmpty() -> fail("MANAGED_METADATA_RESOURCE",resourceDetail)
                else -> unknown("STOP_HANDLE_AND_METADATA_NOT_SAMPLE_READINESS",resourceDetail)
            }
            val pitch=pitch(b,layers); val choke=choke(e,layers,font)
            val owners=ownership(timeline(style,plan,e),b.key!=e.key)
            val potentialOriginalOwners=plan.decisions.filter { it.request.sourceKey==b.key && it.request.rhythmChannel in demands.map { d -> d.first.request.rhythmChannel } }
            val ambiguous=demands.any { "AMBIGUOUS_EVIDENCE_NO_TIE_WINNER" in it.second.failedGates }
            val ambiguity=if(ambiguous) unknown("SEMANTIC_RANK_TIE", "AMBIGUOUS_EVIDENCE_NO_TIE_WINNER;engineering_does_not_choose_winner")
                else unknown("NO_WINNER_POLICY", "selectedWinner=NONE;no_approved_production_policy")
            val gates=listOf(resource,pitch.gate,choke,owners.gate,coverage)
            Candidate(e.evidenceId,e.target?.id ?: "UNKNOWN",e.classification,resource,pitch.gate,choke,owners.gate,coverage,ambiguity,
                eligibility(e.classification,gates,ambiguous),"registry=${e.registryVersion};evidence=${e.provenance};metadata=${e.metadataProvenance};PCM=${e.pcmProvenance};confidence=${e.confidenceLabel};styleSHA256=${plan.key.styleDigest};snapshotSHA256=${DrumShadowPlanner.digest(before.raw.toByteArray())}",
                pitch.layers + listOf("choke=${choke.detail}","ownership=${owners.gate.detail};originalCandidateKeyDemand=${potentialOriginalOwners.size};originalPhysicalLane=UNKNOWN;potential_original_owner_collision_not_proven_from_key_number", "velocity=${coverage.detail}"))
        }
        val digest=DrumShadowPlanner.digest((VERSION+plan.key+fonts.map { it.sha256+it.identity }+before.raw+after.raw+DrumShadowPlanner.styleDigest(style)).toByteArray())
        return Report(digest,rows,System.nanoTime()-start)
    }

    /** Recheck after the worker audit, without rerunning a heavy scan or changing any synth state. */
    fun seal(report: Report, before: DrumShadowPlanner.Snapshot, after: DrumShadowPlanner.Snapshot): Report {
        val stable=generation(before,after)
        val rows=report.rows.map { c ->
            val ready=if(stable.status==Status.FAIL)fail("STOP_POST_AUDIT_RECHECK",c.resourceReady.detail+";"+stable.detail) else c.resourceReady
            val gates=listOf(ready,c.pitchSafe,c.chokeSafe,c.ownershipSafe,c.velocityCoverage)
            c.copy(resourceReady=ready,eligibility=eligibility(c.semantic,gates,c.ambiguity.scope=="SEMANTIC_RANK_TIE"),
                provenance=c.provenance+";postAuditGeneration=${after.generation};postAuditSHA256=${DrumShadowPlanner.digest(after.raw.toByteArray())}")
        }
        return report.copy(rows=rows,cacheDigest=DrumShadowPlanner.digest((report.cacheDigest+after.raw).toByteArray()))
    }

    /** Export receives the planner's bounded writer, so all omissions are accounted for globally. */
    fun export(report: Report, add: (String)->Unit) {
        add("ENGINEERING_PROOF_HEADER version=$VERSION diagnosticOnly=true productionActivation=NONE cacheDigest=${report.cacheDigest} compileNs=${report.compileNanos} noteHooks=0 preparation=STOP_worker;SAFE_FOR_FUTURE_STAGE3_not_assertable_from_metadata_alone")
        add("ENGINEERING_PROOF_SUMMARY ${report.rows.groupingBy { it.eligibility }.eachCount()} selectedWinner=NONE;statuses_are_scope_specific;semantic_never_promoted;runtimePitchOwnershipVelocity=UNKNOWN")
        for(c in report.rows) {
            val gates=listOf("resourceReady" to c.resourceReady,"pitchSafe" to c.pitchSafe,"chokeSafe" to c.chokeSafe,"ownershipSafe" to c.ownershipSafe,"velocityCoverage" to c.velocityCoverage,"ambiguity" to c.ambiguity)
            add("ENGINEERING_PROOF id=${c.id} target=${c.target} semantic=${c.semantic} ${gates.joinToString(" ") { "${it.first}=${it.second.status}[${it.second.scope}]" }} remainingGates=${gates.filter { it.second.status!=Status.PASS }.map { it.first }} runtimeRemaining=[pitchContext,chokeLane,ownerTokens,sampleReadiness,velocityResponse,winnerPolicy] futureStage3Eligibility=${c.eligibility} diagnosticOnly=true")
            add("ENGINEERING_PROVENANCE id=${c.id} ${c.provenance}")
            add("ENGINEERING_RESOURCE id=${c.id} ${c.resourceReady.detail}")
            add("ENGINEERING_PITCH_SCOPE id=${c.id} ${c.pitchSafe.detail}")
            for(detail in c.details)add("ENGINEERING_DETAIL id=${c.id} $detail")
        }
    }
}

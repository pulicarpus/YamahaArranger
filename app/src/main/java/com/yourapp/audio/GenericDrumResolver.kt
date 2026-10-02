package com.yourapp.audio

import java.util.Collections

/** Whole-inventory STOP compiler. A name is a search hint, never a semantic admission. */
object GenericDrumResolver {
    const val VERSION="generic-shadow-v1"
    const val MAX_ZONES=200000
    const val MAX_HINTS=8
    enum class Outcome { EXACT, COMPATIBLE, APPROXIMATION, ABSTAIN }
    data class Semantic(val family:String,val articulation:String)
    data class Hint(val binding:DrumShadowPlanner.Binding,val semantic:Semantic,val samples:List<String>)
    data class Policy(val allowApproximation:Boolean=false)
    /** Only an eventual native resource/lane adapter may supply these observations. No UI claims. */
    data class RuntimeTicket(val binding:DrumShadowPlanner.Binding,val generation:String,val handle:Long,
        val loadedSHA256:String,val samplesReady:Boolean,val pitchNeutral:Boolean,val velocityResponseVerified:Boolean,
        val ownerAdapterVerified:Boolean,val controllerLaneVerified:Boolean,val chokeFamilyClosed:Boolean,
        val chokeSameLane:Boolean,val chokeEngineVerified:Boolean,val provenance:String,
        val rhythmChannel:Int=9,val sourceDigest:String="raw-demand-only")
    data class Candidate(val evidence:DrumShadowPlanner.Evidence,val layers:List<DrumShadowPlanner.Zone>,
        val gates:Map<String,DrumEngineeringProof.Status>,val reasons:List<String>,val ticket:RuntimeTicket?=null) {
        val safe get()=gates.values.all { it==DrumEngineeringProof.Status.PASS }
    }
    data class Row(val request:DrumShadowPlanner.Request,val outcome:Outcome,
        val semanticClaim:DrumShadowPlanner.Classification,val selected:DrumShadowPlanner.Binding?,
        val candidates:List<Candidate>,val hints:List<Hint>,val reasons:List<String>)
    data class Plan(val digest:String,val rows:List<Row>,val fonts:Int,val zones:Int,val candidateKeys:Int,
        val compileNanos:Long,val complete:Boolean=true) {
        fun counts()=Outcome.values().associateWith { o -> rows.count { it.outcome==o } }
    }
    private fun <T> freeze(xs:List<T>):List<T> = Collections.unmodifiableList(xs.toList())
    private fun stable(b:DrumShadowPlanner.Binding)="${b.sha256}:${b.bank.toString().padStart(5,'0')}:${b.pc.toString().padStart(3,'0')}:${b.key.toString().padStart(3,'0')}"

    /** Conservative articulation-specific lexicon; UNKNOWN hints never enter ranking. */
    fun nameHint(z:DrumShadowPlanner.Zone):Semantic? {
        val s=(z.sample+" "+z.instrument).lowercase().replace('_',' ')
        val hat=Regex("hi[ -]?hat|hihat").containsMatchIn(s)
        return when {
            hat -> Semantic("HI_HAT",when {
                Regex("pedal|pedaled|foot").containsMatchIn(s)->"PEDAL_CLOSED"
                Regex("half[ -]?open|splash").containsMatchIn(s)->"PARTIAL_OR_SPLASH"
                Regex("open").containsMatchIn(s)->"OPEN"
                Regex("edge").containsMatchIn(s)->"EDGE"
                Regex("close|closed").containsMatchIn(s)->"CLOSED"
                else->"UNKNOWN"
            })
            Regex("snare|rimshot|rim shot|side.?stick").containsMatchIn(s)->Semantic("SNARE",when {
                Regex("side.?stick|cross.?stick|rim.?click").containsMatchIn(s)->"SIDE_STICK"
                Regex("rim.?shot").containsMatchIn(s)->"RIMSHOT"
                Regex("brush").containsMatchIn(s)->"BRUSH"
                Regex("roll").containsMatchIn(s)->"ROLL"
                else->"HIT"
            })
            Regex("kick|bass.?drum").containsMatchIn(s)->Semantic("KICK","HIT")
            Regex("\\btom\\b").containsMatchIn(s)->Semantic("TOM","HIT")
            Regex("ride").containsMatchIn(s)->Semantic("CYMBAL",if("bell" in s)"RIDE_BELL" else "RIDE")
            Regex("crash").containsMatchIn(s)->Semantic("CYMBAL","CRASH")
            Regex("cow.?bell").containsMatchIn(s)->Semantic("COWBELL","HIT")
            Regex("clap").containsMatchIn(s)->Semantic("CLAP","HIT")
            else->null
        }
    }
    class Index(fonts:List<DrumShadowPlanner.Font>) {
        val fonts=freeze(fonts.distinctBy { it.sha256 })
        val zones=this.fonts.sumOf { it.zones.size }
        val byPreset:Map<Triple<String,Int,Int>,List<DrumShadowPlanner.Zone>>
        private val semantic:Map<Semantic,List<Hint>>
        val candidateKeys:Int
        init {
            require(zones<=MAX_ZONES) {"global zone budget exceeded; no partial resolver"}
            require(this.fonts.all { f -> f.zones.all { it.fontId==f.sha256 } }) {"zone/font fingerprint mismatch"}
            byPreset=Collections.unmodifiableMap(this.fonts.flatMap { it.zones }.groupBy { Triple(it.fontId,it.bank,it.pc) }.mapValues { (_,v)->freeze(v) })
            val hints=mutableListOf<Hint>()
            for(f in this.fonts)for(z in f.zones) {
                val tag=nameHint(z) ?: continue
                // Canonical keys span each relation's actual range/root/fixed-key possibilities.
                // Every zone is indexed; no same-key/bank128/drum-PC/font-name filter.
                val keys=listOfNotNull(z.kl,z.kh,z.root,z.original.takeIf { it in 0..127 },z.ig[46]?.takeIf { it in 0..127 }).distinct().filter { it in z.kl..z.kh }
                for(k in keys)hints+=Hint(DrumShadowPlanner.Binding(f.sha256,z.bank,z.pc,k),tag,listOf(z.sample))
            }
            val unique=hints.groupBy { it.semantic to it.binding }.map { (key,rows)->Hint(key.second,key.first,freeze(rows.flatMap { it.samples }.distinct().sorted())) }
            candidateKeys=unique.size
            semantic=Collections.unmodifiableMap(unique.groupBy { it.semantic }.mapValues { (_,v)->freeze(v.sortedBy { stable(it.binding) }) })
        }
        fun layers(b:DrumShadowPlanner.Binding,v:Int)=byPreset[Triple(b.sha256,b.bank,b.pc)].orEmpty().filter { it.eligible(b.key,v) }
        fun hints(t:DrumSemanticEvidenceRegistry.Target?,v:Int):List<Hint> {
            if(t==null)return emptyList()
            val key=Semantic(t.family,t.technique)
            val eligible=semantic[key].orEmpty().filter { layers(it.binding,v).isNotEmpty() }
            // Bounded evidence display spans fonts; ordering is explicitly never a winner rule.
            return eligible.groupBy { it.binding.sha256 }.values.flatMap { it.take(2) }.take(MAX_HINTS)
        }
    }

    fun compile(requests:List<DrumShadowPlanner.Request>,fonts:List<DrumShadowPlanner.Font>,
        registry:DrumSemanticEvidenceRegistry.Registry, before:DrumShadowPlanner.Snapshot,after:DrumShadowPlanner.Snapshot,
        policy:Policy=Policy(),tickets:List<RuntimeTicket> = emptyList(),sourceDigest:String="raw-demand-only"):Plan {
        val started=System.nanoTime();require(requests.size<=DrumShadowPlanner.MAX_REQUESTS)
        val index=Index(fonts);val stableGeneration=DrumEngineeringProof.generation(before,after)
        val claims=registry.evidence.groupBy { listOf(it.msb,it.lsb,it.pc,it.key) }
        val targets=registry.targets.associateBy { listOf(it.msb,it.lsb,it.rawPc,it.key) }
        require(tickets.map { it.binding to it.rhythmChannel }.distinct().size==tickets.size) {"ambiguous native ticket"}
        val ticketIndex=tickets.associateBy { it.binding to it.rhythmChannel }
        // Evaluate one row per unique source/routing/context/velocity; reuse immutable results for all hits.
        val prepared=mutableMapOf<DrumShadowPlanner.Request,Row>()
        val rows=requests.map { r ->
            val key=r.copy(tick=0,section="",part="")
            prepared.getOrPut(key) {
                val identity=listOf(r.msb,r.lsb,r.rawPc,r.sourceKey);val target=targets[identity]
                val rejected=claims[identity].orEmpty().filter { it.classification==DrumShadowPlanner.Classification.INCOMPATIBLE && r.velocity in it.velocityLow..it.velocityHigh }.map { it.candidate }.toSet()
                val candidates=claims[identity].orEmpty().map { e ->
                    val b=e.candidate;val layers=index.layers(b,r.velocity);val font=index.fonts.firstOrNull { it.sha256==b.sha256 }
                    val ticket=ticketIndex[b to r.rhythmChannel];val statuses=linkedMapOf<String,DrumEngineeringProof.Status>();val reasons=mutableListOf<String>()
                    fun gate(name:String,status:DrumEngineeringProof.Status,why:String) { statuses[name]=status;if(status!=DrumEngineeringProof.Status.PASS)reasons+="$name:$why" }
                    fun required(name:String,available:Boolean?,why:String) = gate(name,when(available){true->DrumEngineeringProof.Status.PASS;false->DrumEngineeringProof.Status.FAIL;null->DrumEngineeringProof.Status.UNKNOWN},why)
                    required("SEMANTIC_AUTHORITY",e.classification in listOf(DrumShadowPlanner.Classification.EXACT,DrumShadowPlanner.Classification.COMPATIBLE) || (e.classification==DrumShadowPlanner.Classification.APPROXIMATION && policy.allowApproximation),"semantic=${e.classification};policy=${policy.allowApproximation}")
                    required("NO_CONTRADICTORY_IDENTITY",b !in rejected,"reviewed_incompatible_evidence_veto")
                    required("RAW_ROUTING",if(!r.routingKnown)false else r.logicalKey?.let { it==r.sourceKey },"CASM_runtime_key_or_override_unproven")
                    required("FINGERPRINT_AND_LAYERS",font!=null && layers.isNotEmpty(),"managed_font_or_eligible_region_missing")
                    val hashes=layers.map { DrumShadowPlanner.digest(it.raw.toByteArray()) }.sorted()
                    required("AUDITED_LAYER_MULTISET",e.schema>=2 && e.layerHashes.isNotEmpty() && hashes==e.layerHashes.sorted(),"audited_layers_mismatch")
                    gate("VELOCITY_REGION",DrumEngineeringProof.velocity(e,setOf(r.velocity),font).status,"metadata_region_or_signature_unproven")
                    gate("STATIC_PITCH",DrumEngineeringProof.pitch(b,layers).gate.status,"root_tuning_modulators_native_pitch_unproven")
                    val headers=font?.samples.orEmpty()
                    required("SAMPLE_PAIRING",layers.isNotEmpty() && layers.all { z ->
                        val h=headers[z.sampleId];val p=headers[z.link]
                        h!=null && h.rate>0 && h.end>h.start && h.type==z.sampleType && h.link==z.link && h.original==z.original &&
                            ((z.sampleType and 0x7fff)==1 || ((z.sampleType and 0x7fff) in listOf(2,4) && p!=null && p.link==z.sampleId &&
                                (p.type and 0x7fff)==(if((z.sampleType and 0x7fff)==2)4 else 2) && h.rate==p.rate && h.end-h.start==p.end-p.start && h.original==p.original))
                    },"missing_or_invalid_sample_header_pair")
                    gate("STABLE_GENERATION",stableGeneration.status,stableGeneration.detail)
                    required("NATIVE_LOADED_RESOURCE",ticket?.let { it.generation==before.generation && it.handle>0 && it.loadedSHA256==b.sha256 && it.samplesReady && it.provenance.isNotBlank() && it.sourceDigest==sourceDigest },"native_per_binding_lane_style_attestation_absent_or_stale")
                    required("RUNTIME_PITCH",ticket?.pitchNeutral,"native_pitch_bend_transpose_context_not_neutral")
                    required("VELOCITY_RESPONSE",ticket?.velocityResponseVerified,"default_modulators_or_lane_velocity_response_unproven")
                    required("OWNER_ADAPTER",ticket?.ownerAdapterVerified,"production_token_adapter_unavailable")
                    required("CONTROLLER_LANE",ticket?.controllerLaneVerified,"controller_mixer_lane_unverified")
                    val hat=target?.family=="HI_HAT"
                    val exclusive=layers.map { it.ig[57] ?: 0 }.toSet()
                    if(hat || exclusive.any { it>0 })required("CHOKE_CLOSED_LANE",ticket?.let { it.chokeFamilyClosed && it.chokeSameLane && it.chokeEngineVerified && (!hat || exclusive.size==1 && exclusive.first()>0) },"complete_family_same_font_preset_lane_and_engine_choke_unproven")
                    else required("CHOKE_CLOSED_LANE",true,"not_exclusive_family")
                    Candidate(e,freeze(layers),Collections.unmodifiableMap(statuses),freeze(reasons),ticket)
                }.sortedWith(compareBy<Candidate> { it.evidence.classification.ordinal }.thenBy { stable(it.evidence.candidate) })
                val eligible=candidates.filter { it.safe }
                val best=eligible.minOfOrNull { it.evidence.classification.ordinal }?.let { n -> eligible.filter { it.evidence.classification.ordinal==n } }.orEmpty()
                val unique=best.map { it.evidence.candidate }.distinct()
                val selected=if(unique.size==1)unique.single() else null
                val claim=candidates.filter { it.evidence.candidate !in rejected }.minByOrNull { it.evidence.classification.ordinal }?.evidence?.classification ?: if(rejected.isNotEmpty())DrumShadowPlanner.Classification.INCOMPATIBLE else DrumShadowPlanner.Classification.UNKNOWN
                val outcome=if(selected==null)Outcome.ABSTAIN else when(best.first().evidence.classification) {
                    DrumShadowPlanner.Classification.EXACT->Outcome.EXACT
                    DrumShadowPlanner.Classification.COMPATIBLE->Outcome.COMPATIBLE
                    DrumShadowPlanner.Classification.APPROXIMATION->Outcome.APPROXIMATION
                    else->Outcome.ABSTAIN
                }
                val reasons=when {
                    unique.size>1->listOf("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER","LEGACY_FALLBACK_UNCHANGED")
                    selected!=null->listOf("UNIQUE_SEMANTIC_RANK_AND_ALL_RUNTIME_GATES_PASS;shadow_only")
                    candidates.count { it.evidence.classification==claim && claim in listOf(DrumShadowPlanner.Classification.EXACT,DrumShadowPlanner.Classification.COMPATIBLE) }>1->listOf("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER","RUNTIME_GATES_NOT_ALL_PASS","LEGACY_FALLBACK_UNCHANGED")
                    target==null->listOf("NO_AUTHORITATIVE_YAMAHA_NOTE_SEMANTICS","LEGACY_FALLBACK_UNCHANGED")
                    else->listOf("RUNTIME_OR_SEMANTIC_GATES_NOT_ALL_PASS","LEGACY_FALLBACK_UNCHANGED")
                }
                Row(key,outcome,claim,selected,freeze(candidates),freeze(index.hints(target,r.velocity)),freeze(reasons))
            }.copy(request=r)
        }
        val digest=DrumShadowPlanner.digest((VERSION+sourceDigest+requests.toString()+fonts.map { it.sha256+it.identity }+registry.sha256+registry.version+registry.evidence+registry.targets+before.raw+after.raw+policy+tickets).toByteArray())
        return Plan(digest,freeze(rows),index.fonts.size,index.zones,index.candidateKeys,System.nanoTime()-started)
    }
}

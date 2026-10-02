package com.yourapp.audio

import java.security.MessageDigest
import java.util.Collections
import com.yourapp.yamahaarranger.style.ParsedStyle

/** Stage 1/2 ONLY: pure data compiler. No synth/MIDI/arranger imports or dispatch method. */
object DrumShadowPlanner {
    const val MAX_REQUESTS = 32768
    const val EXPORT_BYTES = 48 * 1024
    const val MAX_PLAN_LAYER_REFS = 65536
    enum class Classification { EXACT, COMPATIBLE, APPROXIMATION, UNKNOWN, INCOMPATIBLE }
    enum class Action { PASSTHROUGH, SUBSTITUTE, ABSTAIN, LEGACY_ONLY }
    data class Request(val section: String, val part: String, val sourceChannel: Int, val rhythmChannel: Int,
        val msb: Int, val lsb: Int, val rawPc: Int, val sourceKey: Int, val logicalKey: Int?, val velocity: Int,
        val tick: Int, val context: Int, val dynamic: Boolean, val routingKnown: Boolean,
        val scope: String = "RAW_STYLE_DEMAND_NOT_RUNTIME_DISPATCH")
    data class Zone(val fontId: String, val bank: Int, val pc: Int, val preset: String,
        val instrument: String, val sample: String, val sampleId: Int, val kl: Int, val kh: Int,
        val vl: Int, val vh: Int, val original: Int, val root: Int?, val correction: Int,
        val sampleType: Int, val link: Int, val pg: Map<Int,Int>, val ig: Map<Int,Int>, val raw: String) {
        fun eligible(key: Int, velocity: Int) = key in kl..kh && velocity in vl..vh
    }
    data class Sample(val id:Int,val original:Int,val rate:Long,val type:Int,val link:Int,val start:Long,val end:Long)
    data class Font(val sha256: String, val identity: String, val name: String, val zones: List<Zone>, val samples:Map<Int,Sample> = emptyMap())
    data class Binding(val sha256: String, val bank: Int, val pc: Int, val key: Int)
    data class Proof(val pitch: Boolean = false, val choke: Boolean = false, val ownership: Boolean = false,
        val resourceReady: Boolean = false)
    data class Evidence(val msb: Int, val lsb: Int, val pc: Int, val key: Int, val candidate: Binding,
        val classification: Classification, val confidence: Int, val provenance: String, val proof: Proof = Proof(),
        val target:DrumSemanticEvidenceRegistry.Target?=null,val registryVersion:String="manual-unversioned",
        val evidenceId:String="manual",val schema:Int=1,val velocityLow:Int=0,val velocityHigh:Int=127,
        val observedVelocities:List<Int> = emptyList(),val layerHashes:List<String> = emptyList(),
        val confidenceLabel:String="SUPPLIED_UNCALIBRATED",val metadataProvenance:String="NONE",val pcmProvenance:String="NONE")
    data class Policy(val allowApproximation: Boolean = false, val version: Int = 1, val retainLegacy: Boolean = false)
    data class Production(val channel: Int, val inputBank: Int, val inputPc: Int, val sha256: String?,
        val bank: Int, val pc: Int, val verified: Boolean, val generation: Long, val rawBank: Int = bank, val fingerprintScope: String = "CURRENT_MANAGED_BYTES_PATH_CORRELATION_NOT_LOADED_SAMPLE_PROOF")
    data class Normalized(val sha256: String, val rawBank: Int, val virtualBank: Int, val handle: Long? = null)
    data class Snapshot(val production: List<Production>, val normalized: List<Normalized>, val generation: String,
        val raw: String)
    data class CacheKey(val styleDigest: String, val fontDigest: String, val evidenceDigest: String,
        val policy: Policy, val engineGeneration: String, val schema: Int = 2, val registryDigest:String = "NONE")
    data class CandidateDecision(val evidence:Evidence,val bundle:List<Zone>,val passedGates:List<String>,
        val failedGates:List<String>,val proposedAction:Action)
    data class Decision(val request: Request, val production: Production?, val action: Action,
        val classification: Classification, val confidence: Int, val candidate: Binding?,
        val bundle: List<Zone>, val reasons: List<String>, val provenance: String, val crossKey: Boolean,
        val productionKey: Int?, val productionScope: String,val semanticTarget:DrumSemanticEvidenceRegistry.Target?=null,
        val candidates:List<CandidateDecision> = emptyList(),val passedGates:List<String> = emptyList())
    data class Plan(val key: CacheKey, val decisions: List<Decision>, val compileNanos: Long, val storedRows: Int, val storedLayerRefs: Int)
    private fun <T> frozen(xs: List<T>): List<T> = Collections.unmodifiableList(xs.toList())
    private fun frozenMap(xs: Map<Int,Int>): Map<Int,Int> = Collections.unmodifiableMap(xs.toMap())
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun styleDigest(style:ParsedStyle):String = digest(buildString {
        append(style.fileName).append('|').append(style.ppq).append('|').append(style.defaultTempoBpm).append('|').append(style.meter).append('|').append(style.voiceMap.toSortedMap())
        for((name,section) in style.sections.toSortedMap()) {
            append(name).append('|').append(section.lengthTicks)
            for(part in section.parts) {
                append(part.copy(events=emptyList()))
                for(e in part.events) append(e.copy(payload=byteArrayOf()).toString().substringBefore(", payload=")).append('|').append(digest(e.payload))
            }
        }
    }.toByteArray())
    /** Original style identity survives all production coercion/fallback because playback never owns this copy. */
    fun requests(style: ParsedStyle): List<Request> {
        val out=mutableListOf<Request>()
        for ((sectionName,section) in style.sections.toSortedMap()) for (part in section.parts) {
            val policies=part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val sources=part.events.filter { it.isChannelVoice }.map { it.channel }.distinct()
            for (src in sources) {
                val destinations=policies.filter { it.sourceChannel==src }.map { it.destinationChannel }.distinct().ifEmpty { listOf(src) }
                val rhythms=destinations.filter { it==8 || it==9 }
                if(rhythms.isEmpty()) continue
                var msb=part.bankMsb;var lsb=part.bankLsb;var pc=part.program;var context=0;var dynamic=false
                // Stable sort retains within-track event order at equal ticks; header is a fallback, not evidence of runtime setup.
                for (event in part.events.filter { it.channel==src }.sortedBy { it.tick }) {
                    if(event.isControlChange && event.note in listOf(0,32)) {
                        if(event.note==0) msb=event.velocity else lsb=event.velocity
                        context++;dynamic=dynamic || event.tick>0
                    } else if(event.isProgramChange) {pc=event.note;context++;dynamic=dynamic || event.tick>0}
                    else if(event.isNoteOn && (event.status and 0xf0)==0x90 && event.velocity>0) {
                        for(dst in rhythms) {
                            check(out.size<MAX_REQUESTS) { "style demand exceeds bounded shadow plan" }
                            // CASM/overrides/masks are not executed; logical transformed key cannot be asserted from raw demand.
                            out+=Request(sectionName,part.name,src,dst,msb,lsb,pc,event.note,null,event.velocity,event.tick,context,dynamic,
                                destinations.size==1 && msb in 0..127 && lsb in 0..127 && pc in 0..127 && event.note in 0..127 && event.velocity in 1..127)
                        }
                    }
                }
            }
        }
        return frozen(out)
    }
    private val relation=Regex("^Z relation=\\S+ bank=(\\d+) rawPC=(\\d+) displayPC=\\d+ preset='([^']*)' instrument=\\d+:'([^']*)' sample=(\\d+):'([^']*)' keys=(-?\\d+):(-?\\d+) velocities=(\\d+):(\\d+) eligible=(true|false) originalKey=(\\d+) correction=(-?\\d+) overrideRoot=(\\S+) rate=\\d+ type=(\\d+) link=(\\d+) frames=\\d+:\\d+ PG=(.*?) IG=(.*?) classification=UNKNOWN$")
    private fun gens(s:String):Map<Int,Int> = frozenMap(Regex("(\\d+):(-?\\d+)").findAll(s.substringBefore(';')).associate { it.groupValues[1].toInt() to it.groupValues[2].toInt() })
    fun inventory(discovery: Sf2SemanticInventory.Discovery): List<Font> {
        check(discovery.errors.isEmpty()) { discovery.errors.joinToString(";") }
        val result=mutableListOf<Font>();var total=0
        for (source in discovery.sources) {
            val pending=mutableListOf<List<String>>();val samples=mutableMapOf<Int,Sample>()
            val scan=source.open()?.use { input -> Sf2SemanticInventory.scan(input,source.name) { row ->
                if(row.startsWith("S ")) {
                    val h=Regex("^S id=(\\d+) name='[^']*' frames=(\\d+):(\\d+) loops=\\d+:\\d+ rate=(\\d+) originalKey=(\\d+) correction=-?\\d+ link=(\\d+) type=(\\d+)$").matchEntire(row) ?: error("invalid sample header")
                    val v=h.groupValues;samples[v[1].toInt()]=Sample(v[1].toInt(),v[5].toInt(),v[4].toLong(),v[7].toInt(),v[6].toInt(),v[2].toLong(),v[3].toLong())
                }
                if(row.startsWith("Z ")) { val m=relation.matchEntire(row) ?: error("invalid relation")
                    check(++total<=200000) {"global inventory relation limit"}; pending+=m.groupValues + row }
            } } ?: error("cannot open ${source.name}")
            check(scan.complete && scan.sha256!=null) {"incomplete ${source.name}: ${scan.reason}"}
            val id=scan.sha256!!
            val zones=pending.map { v -> Zone(id,v[1].toInt(),v[2].toInt(),v[3],v[4],v[6],v[5].toInt(),v[7].toInt(),v[8].toInt(),v[9].toInt(),v[10].toInt(),
                v[12].toInt(),v[14].toIntOrNull(),v[13].toInt(),v[15].toInt(),v[16].toInt(),gens(v[17]),gens(v[18]),v.last()) }
            result+=Font(id,source.identity,source.name,frozen(zones),Collections.unmodifiableMap(samples.toMap()))
        }
        return frozen(result)
    }
    fun zoneFromMetadata(fontId:String,row:String):Zone {
        val v=relation.matchEntire(row)?.groupValues ?: error("invalid zone relation")
        return Zone(fontId,v[1].toInt(),v[2].toInt(),v[3],v[4],v[6],v[5].toInt(),v[7].toInt(),v[8].toInt(),v[9].toInt(),v[10].toInt(),
            v[12].toInt(),v[14].toIntOrNull(),v[13].toInt(),v[15].toInt(),v[16].toInt(),gens(v[17]),gens(v[18]),row)
    }
    private fun path(identity:String):String = try { val uri=java.net.URI(identity);if(uri.scheme=="file") java.io.File(uri).path else identity } catch(_:Exception){identity}
    private fun decodeHex(s:String):String {if(s=="-")return "";return s.chunked(2).map {it.toInt(16).toByte()}.toByteArray().toString(Charsets.UTF_8)}
    fun snapshot(raw:String, fonts:List<Font>):Snapshot {
        fun sha(hex:String)=fonts.firstOrNull {path(it.identity)==decodeHex(hex)}?.sha256
        val routes=mutableListOf<Production>();val normalized=mutableListOf<Normalized>();var generation="unavailable"
        for(line in raw.lineSequence()) {
            val f=line.split(' ').drop(1).mapNotNull {val p=it.split('=',limit=2);if(p.size==2)p[0] to p[1] else null}.toMap()
            when(line.substringBefore(' ')) {
                "GEN" -> generation=f.getValue("value")
                "LIVE" -> {
                    val id=sha(f.getValue("source"));val bank=f.getValue("bank").toInt();val handle=f["handle"]?.toLong()
                    val rawBank=normalized.firstOrNull {it.sha256==id && it.virtualBank==bank && it.handle==handle}?.rawBank ?: bank
                    routes+=Production(f.getValue("ch").toInt(),f.getValue("inputBank").toInt(),f.getValue("inputPC").toInt(),id,bank,f.getValue("pc").toInt(),f.getValue("verified")=="1",generation.toLong(),rawBank)
                }
                "BANK" -> sha(f.getValue("source"))?.let {normalized+=Normalized(it,f.getValue("raw").toInt(),f.getValue("virtual").toInt(),f["handle"]?.toLong())}
            }
        }
        return Snapshot(frozen(routes),frozen(normalized),generation,raw)
    }
    /** Optional explicit claims. No font names, kit numbers, style names or target-key defaults. */
    fun evidence(text:String):List<Evidence> {
        if(text.isBlank())return emptyList()
        check(text.toByteArray().size<=16*1024) {"evidence exceeds16KiB"}
        return frozen(text.lineSequence().filter {it.isNotBlank() && !it.startsWith('#')}.map {line ->
            val f=line.split('|');require(f.size==11) {"evidence: MSB|LSB|rawPC|targetKey|SHA256|bank|PC|sourceKey|class|confidence|provenance"}
            val n=f.take(4).map {it.toInt()};require(n.all {it in 0..127});require(Regex("[0-9a-f]{64}").matches(f[4]))
            val bank=f[5].toInt();val pc=f[6].toInt();val key=f[7].toInt();val confidence=f[9].toInt()
            require(bank in 0..65535 && pc in 0..127 && key in 0..127 && confidence in 0..100 && f[10].isNotBlank())
            // Imported claims cannot assert engineering proof or resource readiness.
            Evidence(n[0],n[1],n[2],n[3],Binding(f[4],bank,pc,key),Classification.valueOf(f[8]),confidence,f[10])
        }.toList())
    }
    fun cacheKey(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot,
        registry:DrumSemanticEvidenceRegistry.Registry=DrumSemanticEvidenceRegistry.empty()):CacheKey =
        CacheKey(digest(requests.toString().toByteArray()),digest(fonts.map {it.sha256+it.identity}.sorted().joinToString().toByteArray()),
            digest((evidence+registry.evidence).toString().toByteArray()),policy,snapshot.generation+":"+digest(snapshot.raw.toByteArray()),
            registryDigest=registry.version+":"+registry.sha256+":"+registry.analysisSha256+":"+digest(registry.targets.toString().toByteArray()))
    fun compile(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot,
        registry:DrumSemanticEvidenceRegistry.Registry=DrumSemanticEvidenceRegistry.empty()):Plan {
        val start=System.nanoTime();require(requests.size<=MAX_REQUESTS)
        val uniqueFonts=fonts.distinctBy {it.sha256}
        val index=uniqueFonts.flatMap {it.zones}.groupBy {Triple(it.fontId,it.bank,it.pc)}
        val evidenceIndex=(evidence+registry.evidence).groupBy {listOf(it.msb,it.lsb,it.pc,it.key)}
        val demandedKeys=requests.groupBy {it.rhythmChannel}.mapValues {it.value.map {r->r.sourceKey}.toSet()}
        var layerRefs=0
        fun boundedLayers(zones:List<Zone>):List<Zone> {
            layerRefs+=zones.size;check(layerRefs<=MAX_PLAN_LAYER_REFS) {"shadow layer-reference budget exceeded; no partial plan"}
            return frozen(zones)
        }
        val decisions=requests.map {r ->
            val live=snapshot.production.firstOrNull {it.channel==r.rhythmChannel}
            val target=registry.targets.firstOrNull {it.matches(r)}
            val inputMatches=live!=null && live.inputBank==r.msb*128+r.lsb && live.inputPc==r.rawPc
            val observedScope=if(inputMatches) "STOP_SNAPSHOT_MATCHING_NATIVE_INPUT;actual_per_note_UNKNOWN" else "STOP_SNAPSHOT_DIFFERENT_CONTEXT;actual_per_note_UNKNOWN"
            val nativeBundle=live?.sha256?.let {index[Triple(it,live.rawBank,live.pc)]}?.filter {it.eligible(r.sourceKey,r.velocity)}.orEmpty()
            val nativeExactAddress=inputMatches && live!!.verified && live.rawBank==r.msb*128+r.lsb && live.pc==r.rawPc && nativeBundle.isNotEmpty()
            val claims=evidenceIndex[listOf(r.msb,r.lsb,r.rawPc,r.sourceKey)].orEmpty().map {e ->
                // Unreviewed text cannot promote an authoritative target or invent a winner over the bundled audit.
                if(target!=null && e !in registry.evidence)e.copy(classification=Classification.UNKNOWN,confidence=0,
                    confidenceLabel="UNREVIEWED_SUPPLEMENTAL",provenance=e.provenance+";unreviewedClaimClass="+e.classification) else e
            }
            val inRegion=claims.filter {r.velocity in it.velocityLow..it.velocityHigh}
            val rejected=inRegion.filter {it.classification==Classification.INCOMPATIBLE}.map {it.candidate}.toSet()
            val accepted=inRegion.filter {it.candidate !in rejected && (it.classification==Classification.EXACT || it.classification==Classification.COMPATIBLE || (it.classification==Classification.APPROXIMATION && policy.allowApproximation))}
            val best=accepted.minOfOrNull {it.classification.ordinal}?.let {rank->accepted.filter {it.classification.ordinal==rank}.distinct()}.orEmpty()
            val ambiguous=best.size>1
            val evaluations=frozen(claims.map {e ->
                val b=e.candidate;val bundle=index[Triple(b.sha256,b.bank,b.pc)].orEmpty().filter {it.eligible(b.key,r.velocity)}
                val passed=mutableListOf<String>();val failed=mutableListOf<String>()
                fun gate(ok:Boolean,name:String,reason:String) {if(ok)passed+=name else failed+=reason}
                if(target!=null)gate(e in registry.evidence,"REVIEWED_REGISTRY_AUTHORITY","UNREVIEWED_SUPPLEMENTAL_AUTHORITY")
                gate(uniqueFonts.any {it.sha256==b.sha256},"FINGERPRINT_MATCH_CURRENT_MANAGED_BYTES","FINGERPRINT_MISMATCH_OR_FONT_ABSENT")
                gate(bundle.isNotEmpty(),"ELIGIBLE_VELOCITY_LAYERS_PRESENT","MISSING_ZONE_OR_FONT")
                gate(r.velocity in e.velocityLow..e.velocityHigh,"EVIDENCE_VELOCITY_REGION","EVIDENCE_VELOCITY_REGION_MISSING")
                if(e.schema>=2) {
                    val actual=bundle.map {digest(it.raw.toByteArray())}.sorted()
                    gate(e.layerHashes.isNotEmpty() && actual==e.layerHashes.sorted(),"AUDITED_LAYER_SIGNATURES_MATCH","AUDITED_LAYER_MISMATCH")
                    gate(r.velocity in e.observedVelocities,"ISOLATED_PCM_OBSERVED_AT_REQUEST_VELOCITY","REQUEST_VELOCITY_NOT_AUDITIONED")
                }
                val headers=uniqueFonts.firstOrNull {it.sha256==b.sha256}?.samples.orEmpty()
                val badStereo=bundle.any {z ->
                    if((z.sampleType and 0x7fff) !in listOf(2,4)) false else {
                        val sh=headers[z.sampleId];val partner=headers[z.link]
                        sh==null || partner==null || partner.link!=z.sampleId || (partner.type and 0x7fff) != (if((z.sampleType and 0x7fff)==2)4 else 2) ||
                            sh.rate!=partner.rate || sh.end-sh.start!=partner.end-partner.start || sh.original!=partner.original
                    }
                }
                gate(bundle.isNotEmpty() && !badStereo,"STEREO_HEADERS_OR_MONO_METADATA_VALID",if(badStereo)"INVALID_STEREO_SAMPLE_PAIR" else "STEREO_CHECK_NO_LAYERS")
                gate(e in accepted,"SEMANTIC_CLAIM_ACCEPTED",when {
                    e.candidate in rejected || e.classification==Classification.INCOMPATIBLE -> "INCOMPATIBLE"
                    e.classification==Classification.APPROXIMATION && !policy.allowApproximation -> "APPROXIMATION_REQUIRES_EXPLICIT_POLICY"
                    else -> "UNKNOWN_SEMANTIC_IDENTITY_OR_EVIDENCE"
                })
                gate(e.proof.pitch,"PITCH_ROOT_TUNING_MODULATORS_REVIEWED","UNREVIEWED_PITCH_ROOT_TUNING_MODULATORS")
                gate(e.proof.choke,"CHOKE_RELATIONSHIPS_PROVEN","UNSAFE_OR_UNKNOWN_CHOKE_RELATIONSHIPS")
                gate(e.proof.ownership,"NOTE_OWNERSHIP_PROVEN","UNPROVEN_NOTE_OWNERSHIP")
                gate(e.proof.resourceReady,"PRODUCTION_RESOURCE_READY_PROVEN","MISSING_OR_NOT_READY_RESOURCE")
                gate(!(b.key!=r.sourceKey && b.key in demandedKeys[r.rhythmChannel].orEmpty()),"NO_ORIGINAL_KEY_OWNER_COLLISION","MANY_TO_ONE_OWNERSHIP_COLLISION")
                if(ambiguous && e in best)failed+="AMBIGUOUS_EVIDENCE_NO_TIE_WINNER"
                CandidateDecision(e,boundedLayers(bundle),frozen(passed),frozen(failed),if(failed.isEmpty())Action.SUBSTITUTE else Action.ABSTAIN)
            })
            fun decision(action:Action,c:Classification,chosen:CandidateDecision?,bundle:List<Zone>,reasons:List<String>,passed:List<String> = emptyList()) =
                Decision(r,live,action,c,chosen?.evidence?.confidence?:0,chosen?.evidence?.candidate,
                    bundle,frozen(reasons),chosen?.evidence?.provenance?:if(claims.isEmpty())"no_semantic_authority" else "registry_claims_no_selected_winner",
                    chosen?.evidence?.candidate?.key?.let {it!=r.sourceKey}?:false,null,observedScope,target,evaluations,frozen(passed))
            if(!r.routingKnown) return@map decision(Action.ABSTAIN,Classification.UNKNOWN,null,emptyList(),listOf("AMBIGUOUS_ROUTING_OR_ORIGINAL_IDENTITY"))
            val contradictions=inRegion.any {live!=null && it.classification==Classification.INCOMPATIBLE && it.candidate.sha256==live.sha256 && it.candidate.bank==live.rawBank && it.candidate.pc==live.pc && it.candidate.key==r.sourceKey}
            if(nativeExactAddress && !contradictions) return@map decision(Action.PASSTHROUGH,Classification.UNKNOWN,null,boundedLayers(nativeBundle),listOf("EXISTING_NATIVE_ADDRESS_PRESERVED_NOT_IDENTITY_PROOF","LOGICAL_KEY_RUNTIME_UNKNOWN"),listOf("EXISTING_NATIVE_ADDRESS_PRESERVED"))
            if(best.isEmpty()) {
                val reason=when {
                    inRegion.any {it.classification==Classification.INCOMPATIBLE}->"INCOMPATIBLE"
                    inRegion.any {it.classification==Classification.APPROXIMATION}->"APPROXIMATION_REQUIRES_EXPLICIT_POLICY"
                    claims.isNotEmpty() && inRegion.isEmpty()->"EVIDENCE_NOT_APPLICABLE_AT_DEMAND_VELOCITY"
                    else->"UNKNOWN_SEMANTIC_TARGET_OR_EVIDENCE"
                }
                val legacy=policy.retainLegacy && !contradictions && inputMatches && live?.verified==true && nativeBundle.isNotEmpty()
                return@map decision(if(legacy)Action.LEGACY_ONLY else Action.ABSTAIN,Classification.UNKNOWN,null,emptyList(),listOf(reason,"PRODUCTION_LEGACY_ROUTE_UNCHANGED"))
            }
            if(ambiguous) return@map decision(Action.ABSTAIN,best.first().classification,null,emptyList(),listOf("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER","CANDIDATES_ADVISORY_ONLY"))
            val chosen=evaluations.single {it.evidence==best.single()}
            decision(chosen.proposedAction,chosen.evidence.classification,chosen,chosen.bundle,
                if(chosen.failedGates.isEmpty())listOf("PROPOSED_ONLY_NO_DISPATCH_CAPABILITY") else chosen.failedGates,chosen.passedGates)
        }
        // All advisory bindings participate; do not let ambiguity hide a possible future owner collision.
        val targets=decisions.flatMap {d->d.candidates.map {d.request to it.evidence.candidate}}
            .groupBy {listOf(it.first.rhythmChannel,it.second.sha256,it.second.bank,it.second.pc,it.second.key)}
        val checked=decisions.map {d ->
            val candidates=frozen(d.candidates.map {c -> val b=c.evidence.candidate
                val collision=targets[listOf(d.request.rhythmChannel,b.sha256,b.bank,b.pc,b.key)].orEmpty().map {it.first.sourceKey}.distinct().size>1
                if(collision)c.copy(proposedAction=Action.ABSTAIN,failedGates=frozen(c.failedGates+"MANY_TO_ONE_OWNERSHIP_COLLISION")) else c
            })
            val veto=d.candidate?.let {b->candidates.any {it.evidence.candidate==b && "MANY_TO_ONE_OWNERSHIP_COLLISION" in it.failedGates}}==true
            d.copy(candidates=candidates,action=if(veto)Action.ABSTAIN else d.action,reasons=if(veto)frozen(d.reasons+"MANY_TO_ONE_OWNERSHIP_COLLISION") else d.reasons)
        }
        return Plan(cacheKey(requests,fonts,evidence,policy,snapshot,registry),frozen(checked),System.nanoTime()-start,checked.size,layerRefs)
    }
    fun export(plan:Plan,fonts:List<Font>,snapshot:Snapshot,
        registry:DrumSemanticEvidenceRegistry.Registry=DrumSemanticEvidenceRegistry.empty(), engineering:DrumEngineeringProof.Report?=null):String {
        val out=StringBuilder();var bytes=0;var omitted=0;var omittedNotes=0;var omittedZones=0;var omittedMeta=0;var omittedEvals=0
        fun add(s:String):Boolean {
            val safe=s.map {if(it.code<32 || it.code==127)' ' else it}.joinToString("")
            val n=safe.toByteArray().size+1;if(bytes+n>EXPORT_BYTES-1024)return false
            out.append(safe).append('\n');bytes+=n;return true
        }
        fun meta(s:String) {if(!add(s))omittedMeta++}
        meta("YAMAHAARRANGER SHADOW DRUM RESOLVER v2 SHADOW_PASS2 productionDispatch=UNCHANGED runtimeHook=NONE")
        meta("RAW_STYLE_DEMAND plus STOP_NATIVE_SNAPSHOT; ACTUAL_RUNTIME_DISPATCH=UNKNOWN logicalKey=null productionKey=UNKNOWN; no actual per-note proof; no winner")
        meta("REGISTRY version=${registry.version} schema=2 sha256=${registry.sha256} analysisSHA256=${registry.analysisSha256} targets=${registry.targets.size} claims=${registry.evidence.size}; classification=provenance_claim_not_loaded_sample_identity; confidence_tier_not_probability")
        meta("cache=${plan.key} compileNs=${plan.compileNanos} storedRows=${plan.storedRows} storedLayerRefs=${plan.storedLayerRefs} maxLayerRefs=$MAX_PLAN_LAYER_REFS planMemory=bounded_not_heap_measured")
        meta("PERFORMANCE noteOnHooks=0 noteOffHooks=0 addedHotPathAllocations=0 addedMutexes=0; device_p95_p99_xrun=NOT_MEASURED; preparation=STOP_worker_only")
        if(engineering!=null)meta("ENGINEERING_SCOPE static_metadata_and_offline_hypotheses_not_runtime_safety;CANDIDATE_EVAL_retains_Pass2_strict_observed_velocity_gate;ENGINEERING_PROOF_velocityCoverage_adds_separate_same_region_inference;no_Proof_boolean_or_dispatch_changes")
        for(f in fonts)meta("FONT sha256=${f.sha256} name=${f.name} identity=${f.identity} zones=${f.zones.size}")
        for(n in snapshot.normalized)meta("NORMALIZED sha256=${n.sha256} raw=${n.rawBank} virtual=${n.virtualBank} handle=${n.handle}")
        for(l in snapshot.production)meta("CURRENT_STOP_PRODUCTION $l NOT_ACTUAL_PER_NOTE_PROOF")
        for((group,rows) in plan.decisions.groupBy {it.action to it.classification})meta("SUMMARY action=${group.first} class=${group.second} notes=${rows.size}")
        for(t in registry.targets) {
            meta("TARGET $t registryVersion=${registry.version}")
            val rows=plan.decisions.filter {it.semanticTarget?.id==t.id}
            meta("TARGET_SUMMARY id=${t.id} sourceKey=${t.key} notes=${rows.size} velocityDemand=${rows.groupingBy {it.request.velocity}.eachCount().toSortedMap()} actions=${rows.groupingBy {it.action}.eachCount()} classifications=${rows.groupingBy {it.classification}.eachCount()} selectedWinner=NONE")
        }
        if(engineering!=null) {
            for(line in snapshot.raw.lineSequence().filter { it.isNotBlank() })meta("ENGINEERING_STOP_HANDLE_OBSERVATION $line;loaded_fingerprint_or_per_note_sample_identity_NOT_PROVEN")
            DrumEngineeringProof.export(engineering) { meta(it) }
        }
        for(e in registry.evidence)meta("EVIDENCE id=${e.evidenceId} target=${e.target?.id} version=${e.registryVersion} candidate=${e.candidate} class=${e.classification} confidence=${e.confidenceLabel} evidenceVelocity=${e.velocityLow}:${e.velocityHigh} isolatedObservedVelocities=${e.observedVelocities} layerSHA256=${e.layerHashes} provenance=${e.provenance} metadata=${e.metadataProvenance} PCM=${e.pcmProvenance} engineeringProof=${e.proof}")
        val all=plan.decisions.flatMap {d->d.candidates.map {d.request to it}}
        val evals=all.distinctBy {listOf(it.first.rhythmChannel,it.first.velocity,it.second)}
        val emittedLayers=mutableSetOf<String>()
        for((r,c) in evals) {
            val e=c.evidence
            if(!add("CANDIDATE_EVAL target=${e.target?.id} original=${r.msb}:${r.lsb}:${r.rawPc}:key${r.sourceKey} rhythm=${r.rhythmChannel} velocity=${r.velocity} id=${e.evidenceId} version=${e.registryVersion} candidate=${e.candidate} crossKey=${e.candidate.key!=r.sourceKey} class=${e.classification} confidence=${e.confidenceLabel} proposed=${c.proposedAction} passedGates=${c.passedGates} failedGates=${c.failedGates} eligibleLayers=${c.bundle.size}; advisoryOnly=true")){omittedEvals++;continue}
            for(z in c.bundle) {
                val id=digest((z.fontId+z.raw).toByteArray())
                if(emittedLayers.add(id) && !add("LAYER evidence=${e.evidenceId} sf2SHA256=${z.fontId} layerSHA256=${digest(z.raw.toByteArray())} ${z.raw}"))omittedZones++
            }
        }
        // Prioritize semantic demands; aggregate repeated hits so important targets survive the bounded export.
        val groups=plan.decisions.groupBy {it.request.copy(tick=0)}.entries.sortedBy {if(it.value.first().semanticTarget==null)1 else 0}
        for((r,rows) in groups) {
            val d=rows.first()
            if(!add("GROUP original=$r target=${d.semanticTarget?.id ?: "UNKNOWN"} hits=${rows.size} firstTick=${rows.minOf {it.request.tick}} lastTick=${rows.maxOf {it.request.tick}} productionScope=${d.productionScope} productionKey=UNKNOWN shadow=${d.action} class=${d.classification} candidate=${d.candidate} selectedCrossKey=${d.crossKey} candidateCrossKeys=${d.candidates.map {it.evidence.candidate.key!=r.sourceKey}} evidenceIds=${d.candidates.map {it.evidence.evidenceId}} passedGates=${d.passedGates} failedGates=${d.reasons} provenance=${d.provenance}")){omitted++;omittedNotes+=rows.size}
        }
        out.append("END totalRows=${plan.decisions.size} detailUnit=AGGREGATED_RAW_DEMAND groups=${groups.size} omittedRows=$omitted omittedDemandNotes=$omittedNotes omittedZoneRows=$omittedZones omittedCandidateEvaluations=$omittedEvals omittedMetadataRows=$omittedMeta maxBytes=$EXPORT_BYTES; omission != no_zone; production=UNCHANGED\n")
        return out.toString().also {check(it.toByteArray().size<=EXPORT_BYTES)}
    }
    /** Worker-only cache. Key includes all invalidation dimensions; never used by the synth. */
    class Cache {
        private var last:Plan?=null
        @Synchronized fun prepare(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot,styleDigest:String="parsed_demand",registry:DrumSemanticEvidenceRegistry.Registry=DrumSemanticEvidenceRegistry.empty()):Plan {
            val key=cacheKey(requests,fonts,evidence,policy,snapshot,registry).let { it.copy(styleDigest=digest((it.styleDigest+":"+styleDigest).toByteArray())) }
            return last?.takeIf {it.key==key} ?: compile(requests,fonts,evidence,policy,snapshot,registry).copy(key=key).also {last=it}
        }
    }
}

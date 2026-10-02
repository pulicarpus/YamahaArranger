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
        val classification: Classification, val confidence: Int, val provenance: String, val proof: Proof = Proof())
    data class Policy(val allowApproximation: Boolean = false, val version: Int = 1, val retainLegacy: Boolean = false)
    data class Production(val channel: Int, val inputBank: Int, val inputPc: Int, val sha256: String?,
        val bank: Int, val pc: Int, val verified: Boolean, val generation: Long, val rawBank: Int = bank)
    data class Normalized(val sha256: String, val rawBank: Int, val virtualBank: Int, val handle: Long? = null)
    data class Snapshot(val production: List<Production>, val normalized: List<Normalized>, val generation: String,
        val raw: String)
    data class CacheKey(val styleDigest: String, val fontDigest: String, val evidenceDigest: String,
        val policy: Policy, val engineGeneration: String, val schema: Int = 1)
    data class Decision(val request: Request, val production: Production?, val action: Action,
        val classification: Classification, val confidence: Int, val candidate: Binding?,
        val bundle: List<Zone>, val reasons: List<String>, val provenance: String, val crossKey: Boolean,
        val productionKey: Int?, val productionScope: String)
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
                                destinations.size==1 && msb in 0..127 && lsb in 0..127 && pc in 0..127)
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
    fun cacheKey(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot):CacheKey =
        CacheKey(digest(requests.toString().toByteArray()),digest(fonts.map {it.sha256+it.identity}.sorted().joinToString().toByteArray()),digest(evidence.toString().toByteArray()),policy,
            snapshot.generation+":"+digest(snapshot.raw.toByteArray()))
    fun compile(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot):Plan {
        val start=System.nanoTime();require(requests.size<=MAX_REQUESTS)
        val index=fonts.flatMap {it.zones}.groupBy {Triple(it.fontId,it.bank,it.pc)}
        val evidenceIndex=evidence.groupBy {listOf(it.msb,it.lsb,it.pc,it.key)}
        val demandedKeys=requests.groupBy {it.rhythmChannel}.mapValues {it.value.map {r->r.sourceKey}.toSet()}
        var layerRefs=0
        fun boundedLayers(zones:List<Zone>):List<Zone> {
            layerRefs+=zones.size;check(layerRefs<=MAX_PLAN_LAYER_REFS) {"shadow layer-reference budget exceeded; no partial plan"}
            return frozen(zones)
        }
        val decisions=requests.map {r ->
            val live=snapshot.production.firstOrNull {it.channel==r.rhythmChannel}
            val inputMatches=live!=null && live.inputBank==r.msb*128+r.lsb && live.inputPc==r.rawPc
            val observedScope=if(inputMatches) "STOP_SNAPSHOT_MATCHING_NATIVE_INPUT;actual_per_note_UNKNOWN" else "STOP_SNAPSHOT_DIFFERENT_CONTEXT;actual_per_note_UNKNOWN"
            val nativeBundle=live?.sha256?.let {index[Triple(it,live.rawBank,live.pc)]}?.filter {it.eligible(r.sourceKey,r.velocity)}.orEmpty()
            val nativeExactAddress=inputMatches && live!!.verified && live.rawBank==r.msb*128+r.lsb && live.pc==r.rawPc && nativeBundle.isNotEmpty()
            val claims=evidenceIndex[listOf(r.msb,r.lsb,r.rawPc,r.sourceKey)].orEmpty()
            fun decision(action:Action,c:Classification,claim:Evidence?,bundle:List<Zone>,reasons:List<String>) = Decision(r,live,action,c,claim?.confidence?:0,claim?.candidate,boundedLayers(bundle),frozen(reasons),claim?.provenance?:"no_semantic_authority",claim?.candidate?.key?.let {it!=r.sourceKey}?:false,null,observedScope)
            if(!r.routingKnown) return@map decision(Action.ABSTAIN,Classification.UNKNOWN,null,emptyList(),listOf("AMBIGUOUS_ROUTING_OR_ORIGINAL_IDENTITY"))
            val contradictions=claims.any {live!=null && it.classification==Classification.INCOMPATIBLE && it.candidate.sha256==live?.sha256 && it.candidate.bank==live.rawBank && it.candidate.pc==live.pc && it.candidate.key==r.sourceKey}
            if(nativeExactAddress && !contradictions) return@map decision(Action.PASSTHROUGH,Classification.UNKNOWN,null,nativeBundle,listOf("EXISTING_NATIVE_ADDRESS_PRESERVED_NOT_IDENTITY_PROOF","LOGICAL_KEY_RUNTIME_UNKNOWN"))
            val rejected=claims.filter {it.classification==Classification.INCOMPATIBLE}.map {it.candidate}.toSet()
            val accepted=claims.filter {it.candidate !in rejected && (it.classification==Classification.EXACT || it.classification==Classification.COMPATIBLE || (it.classification==Classification.APPROXIMATION && policy.allowApproximation))}
            if(accepted.isEmpty()) {
                val reason=when {claims.any {it.classification==Classification.INCOMPATIBLE}->"INCOMPATIBLE";claims.any {it.classification==Classification.APPROXIMATION}->"APPROXIMATION_REQUIRES_EXPLICIT_POLICY";else->"UNKNOWN_SEMANTIC_TARGET_OR_EVIDENCE"}
                val legacy=policy.retainLegacy && !contradictions && inputMatches && live?.verified==true && nativeBundle.isNotEmpty()
                return@map decision(if(legacy) Action.LEGACY_ONLY else Action.ABSTAIN,Classification.UNKNOWN,null,emptyList(),listOf(reason,"PRODUCTION_LEGACY_ROUTE_UNCHANGED"))
            }
            val rank=accepted.minOf {it.classification.ordinal};val best=accepted.filter {it.classification.ordinal==rank}.distinct()
            if(best.size!=1) return@map decision(Action.ABSTAIN,Classification.UNKNOWN,null,emptyList(),listOf("AMBIGUOUS_EVIDENCE_NO_TIE_WINNER"))
            val e=best.single();val b=e.candidate;val bundle=index[Triple(b.sha256,b.bank,b.pc)].orEmpty().filter {it.eligible(b.key,r.velocity)}
            val failures=mutableListOf<String>()
            if(bundle.isEmpty())failures+="MISSING_ZONE_OR_FONT"
            val sampleHeaders=fonts.firstOrNull {it.sha256==b.sha256}?.samples.orEmpty()
            if(bundle.any {z ->
                val sh=sampleHeaders[z.sampleId]
                if((z.sampleType and 0x7fff) !in listOf(2,4)) false else {
                    val partner=sampleHeaders[z.link]
                    sh==null || partner==null || partner.link!=z.sampleId || (partner.type and 0x7fff) != (if((z.sampleType and 0x7fff)==2)4 else 2) ||
                        sh.rate!=partner.rate || sh.end-sh.start!=partner.end-partner.start || sh.original!=partner.original
                }
            }) failures+="INVALID_STEREO_SAMPLE_PAIR"
            if(!e.proof.pitch)failures+="UNREVIEWED_PITCH_ROOT_TUNING_MODULATORS"
            if(!e.proof.choke)failures+="UNSAFE_OR_UNKNOWN_CHOKE_RELATIONSHIPS"
            if(!e.proof.ownership)failures+="UNPROVEN_NOTE_OWNERSHIP"
            if(!e.proof.resourceReady)failures+="MISSING_OR_NOT_READY_RESOURCE"
            // Future route tokens are not implemented. Imports cannot bypass these proofs.
            if((b.key!=r.sourceKey && b.key in demandedKeys[r.rhythmChannel].orEmpty()))failures+="MANY_TO_ONE_OWNERSHIP_COLLISION"
            if(failures.isNotEmpty()) decision(Action.ABSTAIN,e.classification,e,bundle,failures)
            else decision(Action.SUBSTITUTE,e.classification,e,bundle,listOf("PROPOSED_ONLY_NO_DISPATCH_CAPABILITY"))
        }
        // Detect collisions between proposed candidates as well as an original source key.
        val targets=decisions.filter {it.candidate!=null}.groupBy {listOf(it.request.rhythmChannel,it.candidate!!.sha256,it.candidate.bank,it.candidate.pc,it.candidate.key)}
        val checked=decisions.map {d -> val b=d.candidate
            if(b!=null && targets[listOf(d.request.rhythmChannel,b.sha256,b.bank,b.pc,b.key)].orEmpty().map {it.request.sourceKey}.distinct().size>1)
                d.copy(action=Action.ABSTAIN,reasons=frozen(d.reasons+"MANY_TO_ONE_OWNERSHIP_COLLISION")) else d }
        return Plan(cacheKey(requests,fonts,evidence,policy,snapshot),frozen(checked),System.nanoTime()-start,checked.size,layerRefs)
    }
    fun export(plan:Plan,fonts:List<Font>,snapshot:Snapshot):String {
        val out=StringBuilder();var bytes=0;var omitted=0;var omittedZones=0
        fun add(s:String):Boolean {val safe=s.replace('\r',' ').replace('\n',' ');val n=safe.toByteArray().size+1;if(bytes+n>EXPORT_BYTES-1024)return false;out.append(safe).append('\n');bytes+=n;return true}
        add("YAMAHAARRANGER SHADOW DRUM RESOLVER v1 STAGE1_2 productionDispatch=UNCHANGED runtimeHook=NONE")
        add("scope=RAW_STYLE_DEMAND plus STOP_NATIVE_SNAPSHOT; source/logical/synth keys separate; no claimed actual per-note sample voice; no winner")
        add("cache=${plan.key} compileNs=${plan.compileNanos} storedRows=${plan.storedRows} storedLayerRefs=${plan.storedLayerRefs} maxLayerRefs=$MAX_PLAN_LAYER_REFS planMemory=bounded_not_heap_measured")
        add("PERFORMANCE noteOnHooks=0 noteOffHooks=0 addedHotPathAllocations=0 addedMutexes=0; device_p95_p99_xrun=NOT_MEASURED; preparation=STOP_worker_only")
        for(f in fonts)add("FONT sha256=${f.sha256} name=${f.name} identity=${f.identity} zones=${f.zones.size}")
        for(n in snapshot.normalized)add("NORMALIZED sha256=${n.sha256} raw=${n.rawBank} virtual=${n.virtualBank} handle=${n.handle}")
        for(l in snapshot.production)add("CURRENT_STOP_PRODUCTION $l")
        for((group,rows) in plan.decisions.groupBy {it.action to it.classification})add("SUMMARY action=${group.first} class=${group.second} notes=${rows.size}")
        for((id,d) in plan.decisions.withIndex()) {
            if(!add("ROW id=$id original=${d.request} production=${d.production} productionKey=UNKNOWN productionScope=${d.productionScope} shadow=${d.action} class=${d.classification} confidence=${d.confidence} candidate=${d.candidate} crossKey=${d.crossKey} provenance=${d.provenance} failedGates=${d.reasons} eligibleLayers=${d.bundle.size}")){omitted++;continue}
            for(z in d.bundle) if(!add("BUNDLE row=$id sha256=${z.fontId} ${z.raw}"))omittedZones++
        }
        out.append("END totalRows=${plan.decisions.size} omittedRows=$omitted omittedZoneRows=$omittedZones maxBytes=$EXPORT_BYTES; omission != no_zone; production=UNCHANGED\n")
        return out.toString().also {check(it.toByteArray().size<=EXPORT_BYTES)}
    }
    /** Worker-only cache. Key includes all invalidation dimensions; never used by the synth. */
    class Cache {
        private var last:Plan?=null
                @Synchronized fun prepare(requests:List<Request>,fonts:List<Font>,evidence:List<Evidence>,policy:Policy,snapshot:Snapshot,styleDigest:String="parsed_demand"):Plan {
            val key=cacheKey(requests,fonts,evidence,policy,snapshot).let { it.copy(styleDigest=digest((it.styleDigest+":"+styleDigest).toByteArray())) }
            return last?.takeIf {it.key==key} ?: compile(requests,fonts,evidence,policy,snapshot).copy(key=key).also {last=it}
        }
    }
}

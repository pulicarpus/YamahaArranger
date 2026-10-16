package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.*
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Single bounded non-audio worker. Overflow is reported, never blocks sequencing. */
object DrumKitShadowRuntime {
    private const val CATALOG_SHA="bec1368f783f847e1b3d433dd46a085968aa1118a9d19b4161bb34c08db4bff1"
    @Volatile var enabled=true
        private set
    private data class Context(val hash:String,val format:String,val profiles:Map<String,List<DrumKitShadowResolver.Hit>>,val resolver:DrumKitShadowResolver.Resolver)
    @Volatile private var context:Context?=null
    val active:Boolean get()=enabled && context!=null
    private val dropped=AtomicLong();private val submitted=AtomicLong();private val completed=AtomicLong()
    private val rows=linkedMapOf<String,String>();private val timings=ArrayDeque<Long>()
    private var agreements=0L;private var comparisons=0L;private var historyOmitted=0L
    private val worker=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(256),{ r->Thread(r,"R22-shadow-metadata").apply { isDaemon=true } },{ _,_->dropped.incrementAndGet() })
    fun setEnabled(value:Boolean){enabled=value}
    fun prepare(style:ParsedStyle,bytes:ByteArray,input:InputStream) {
        context=null
        val resolver=DrumKitShadowResolver.Resolver(DrumKitShadowResolver.load(input,CATALOG_SHA))
        val profiles=linkedMapOf<String,List<DrumKitShadowResolver.Hit>>()
        for((name,section) in style.sections)for(ch in 4..15){
            val bins=linkedMapOf<Pair<Int,Int>,Int>();var ambiguous=false
            for(part in section.parts)for(e in part.events){
                if(!e.isNoteOn || e.velocity<=0)continue
                val destinations=part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }.filter { it.sourceChannel==e.channel }.map { it.destinationChannel }.distinct()
                if(destinations.size>1 && ch in destinations){ambiguous=true;continue}
                val dst=destinations.singleOrNull()?:e.channel
                if(dst==ch){val k=e.note to e.velocity;bins[k]=(bins[k]?:0)+1}
            }
            profiles["$name:$ch"]=if(ambiguous)emptyList() else bins.toList().sortedWith(compareBy({it.first.first},{it.first.second})).map { DrumKitShadowResolver.Hit(it.first.first,it.first.second,it.second) }
        }
        context=Context(DrumKitShadowResolver.sha256(bytes),style.dialectIdentity.dialect.name,profiles,resolver)
        synchronized(rows){rows.clear();timings.clear();agreements=0;comparisons=0;historyOmitted=0};submitted.set(0);completed.set(0);dropped.set(0)
    }
    fun resolveAB(section:StyleSectionModel,ch:Int,msb:Int?,lsb:Int?,pc:Int?,source:String):DrumKitShadowResolver.Candidate? {
        val c=context?:return null
        return c.resolver.evaluate(DrumKitShadowResolver.Request(c.hash,c.format,section.name,ch,msb,lsb,pc,source),c.profiles["${section.name}:$ch"].orEmpty()).ideal
    }
    fun observe(section:StyleSectionModel,ch:Int,msb:Int?,lsb:Int?,pc:Int?,source:String,actual:String){
        if(!enabled)return
        val c=context?:return
        val request=DrumKitShadowResolver.Request(c.hash,c.format,section.name,ch,msb,lsb,pc,source)
        val eventSequence=submitted.incrementAndGet()
        worker.execute {
            try {
                val start=System.nanoTime();val hits=c.profiles["${section.name}:$ch"].orEmpty();val d=c.resolver.evaluate(request,hits);val elapsed=System.nanoTime()-start
                val a=actual.split('\t');val actualKit=if(a.size==5)c.resolver.kits.firstOrNull { it.pdta==a[1] && it.bank.toString()==a[2] && it.pc.toString()==a[3] }else null
                val ac=actualKit?.let { DrumKitShadowResolver.coverage(it,hits) }
                val agree=actualKit!=null && d.ideal!=null && actualKit.id==d.ideal.kit.id
                fun candidate(k:DrumKitShadowResolver.Kit,cov:DrumKitShadowResolver.Coverage)="${k.font}:${k.bank}:${k.pc}:'${k.name}' id=${k.id} cov=${cov.covered}/${cov.total} missingKeys=${cov.missingKeys} missingVel=${cov.missingVelocities} stackMax=${cov.stackMax} zonesMax=${cov.zonesMax} negAtt=${cov.negativeZones} velocityOverlap=${cov.velocityOverlap} attenuationRawCb=${cov.attenuationMinCb}..${cov.attenuationMaxCb} reverbRaw=${cov.reverb} chorusRaw=${cov.chorus} fxZones=${cov.fxZones} exclusive=${cov.exclusiveClass}"
                val row=buildString {
                    appendLine("DRUM_KIT_SHADOW eventSequence=$eventSequence drumSelectionGeneration=${a.firstOrNull()} style=${c.hash} fmt=${c.format} section='${section.name}' ch=$ch src=$source req=$msb:$lsb:$pc ns=${d.namespace}")
                    appendLine("actual=${if(actualKit!=null && ac!=null)candidate(actualKit,ac)else "UNOBSERVED fingerprint_or_identity_not_verified"} actualReason=${a.getOrNull(4)} actualEvidence=cached_native_selection_not_independent_engine_binding")
                    appendLine("shadow=${d.ideal?.let { candidate(it.kit,it.coverage) }?:"unresolved"} shadowReason=${d.reason}")
                    d.alternatives.forEachIndexed { i,alt->appendLine("alt${i+1}=${candidate(alt.kit,alt.coverage)} reason=${alt.reason}") }
                    appendLine("agree=${if(actualKit==null)"UNVERIFIED" else if(agree)"1" else "0"} evalUs=${elapsed/1000} thread=${Thread.currentThread().name} profile=raw_section_key_velocity_not_CASM_transformed audioPolicy=legacy catalogUse=OFF_FOR_PLAYBACK")
                }
                synchronized(rows){if(context===c){rows[eventSequence.toString()]=row;if(rows.size>1024){rows.remove(rows.keys.first());historyOmitted++};if(actualKit!=null){comparisons++;if(agree)agreements++};timings.addLast(elapsed);if(timings.size>4096)timings.removeFirst()}}
            }catch(e:Exception){synchronized(rows){rows["error"]="DRUM_KIT_SHADOW status=ERROR type=${e.javaClass.simpleName}"}}
            finally{if(context===c)completed.incrementAndGet()}
        }
    }
    fun report():String=synchronized(rows){val times=timings.sorted();val p99=if(times.isEmpty())null else times[((times.size-1)*99)/100]/1000
        "=== DRUM KIT SHADOW R2-2 ===\nenabled=$enabled catalogUse=OFF_FOR_PLAYBACK submitted=${submitted.get()} completed=${completed.get()} dropped=${dropped.get()} historyOmitted=$historyOmitted agreement=$agreements/$comparisons p99Us=$p99 formatEvidence=baseline_detector_SFF2_UNVERIFIED no_PCM_or_loudness_claim\n"+rows.values.joinToString("\n")}
}

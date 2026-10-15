package com.yourapp.yamahaarranger.arranger

import java.security.MessageDigest
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Pure, metadata-only R2-2. No synth/transport/MIDI dependency. */
object DrumKitShadowResolver {
    data class Hit(val key:Int,val velocity:Int,val count:Int=1)
    data class Request(val styleHash:String,val format:String,val section:String,val channel:Int,
        val msb:Int?,val lsb:Int?,val pc:Int?,val source:String,val customIdentity:String?=null)
    data class Zone(val kl:Int,val kh:Int,val vl:Int,val vh:Int,val sample:Int,val att:Int,val rev:Int,val cho:Int,val exclusive:Int) {
        fun matches(h:Hit)=h.key in kl..kh && h.velocity in vl..vh
    }
    data class Kit(val id:String,val font:String,val pdta:String,val bank:Int,val pc:Int,val nameHex:String,val namespace:String,val zones:List<Zone>) {
        val byKey:Array<List<Zone>> = Array(128) { key->zones.filter { key in it.kl..it.kh } }
        val negativeZoneCount=zones.count { it.att<0 }
        val exclusiveClass:String = run {
            val hats=listOf(42,44,46).map { key->byKey[key].map { it.exclusive }.toSet() }
            if(hats.any { it.isEmpty() })"UNVERIFIED_MISSING_HAT" else if(hats.flatten().toSet().size==1 && hats.first().single()>0)"STATIC_SHARED_CLASS_NOT_RUNTIME_PROOF" else "RISK_NO_COMMON_EXCLUSIVE_CLASS"
        }
        val name:String get()=String(nameHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),Charsets.ISO_8859_1).substringBefore('\u0000')
    }
    data class Coverage(val covered:Int,val total:Int,val missingKeys:List<Int>,val missingVelocities:List<String>,
        val stackMax:Int,val zonesMax:Int,val negativeZones:Int,val velocityOverlap:Boolean,val fxZones:Int,val exclusiveClass:String,
        val attenuationMinCb:Int?,val attenuationMaxCb:Int?,val reverb:List<Int>,val chorus:List<Int>)
    data class Candidate(val kit:Kit,val coverage:Coverage,val reason:String)
    data class Decision(val namespace:String,val ideal:Candidate?,val alternatives:List<Candidate>,val reason:String)
    fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun load(input:InputStream,expectedSha:String):List<Kit> {
        val raw=GZIPInputStream(input).use { it.readBytes() };require(raw.size<=8*1024*1024 && sha256(raw)==expectedSha)
        val lines=String(raw,Charsets.UTF_8).lineSequence().iterator();require(lines.next()=="R22_KIT_IDENTITY\t1\tsf2-list-pdta-payload-v2")
        val kits=mutableListOf<Kit>();var fields:List<String>?=null;var zones=mutableListOf<Zone>()
        fun finish(){fields?.let { f ->require(f.size==8);kits+=Kit(f[1],f[2],f[3],f[4].toInt(),f[5].toInt(),f[6],f[7],zones.toList()) }}
        for(line in lines){if(line.isEmpty())continue;val f=line.split('\t');when(f[0]) {
            "K"->{finish();fields=f;zones=mutableListOf()}
            "Z"->{require(fields!=null && f.size==10);val v=f.drop(1).map { it.toInt() };require(v[0] in 0..127 && v[1] in v[0]..127 && v[2] in 0..127 && v[3] in v[2]..127);zones+=Zone(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8])}
            else->error("unknown catalog row")
        }};finish();require(kits.map { it.id }.distinct().size==kits.size);return kits.toList()
    }
    fun coverage(kit:Kit,hits:List<Hit>):Coverage {
        require(hits.all { it.key in 0..127 && it.velocity in 1..127 && it.count>0 })
        var covered=0;var stack=0;var maxZones=0;val missing=sortedSetOf<Int>();val missingV=sortedSetOf<String>()
        val relevant=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Zone,Boolean>())
        for(h in hits){val zs=kit.byKey[h.key].filter { h.velocity in it.vl..it.vh };if(zs.isEmpty()){missing+=h.key;missingV+="${h.key}:${h.velocity}"}else covered+=h.count
            maxZones=maxOf(maxZones,zs.size);stack=maxOf(stack,zs.groupingBy { it.sample }.eachCount().values.maxOrNull()?:0);relevant.addAll(zs)}
        return Coverage(covered,hits.sumOf { it.count },missing.toList(),missingV.toList(),stack,maxZones,kit.negativeZoneCount,
            maxZones>1,relevant.count { it.rev!=0 || it.cho!=0 },kit.exclusiveClass,
            relevant.minOfOrNull { it.att },relevant.maxOfOrNull { it.att },relevant.map { it.rev }.distinct().sorted(),relevant.map { it.cho }.distinct().sorted())
    }
    class Resolver(val kits:List<Kit>) {
        private val kitsByPc=kits.groupBy { it.pc }
        private val kitsById=kits.associateBy { it.id }
        private val cache=object:LinkedHashMap<Pair<Request,List<Hit>>,Decision>(128,0.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<Pair<Request,List<Hit>>,Decision>)=size>2048}
        @Synchronized fun evaluate(r:Request,hits:List<Hit>):Decision {
            val sorted=hits.sortedWith(compareBy({it.key},{it.velocity},{it.count}));val key=r to sorted;cache[key]?.let { return it }
            val ns=when {r.customIdentity!=null->"CUSTOM";(r.source=="NAME_GUESS" || r.source.startsWith("OVERRIDE")) || r.pc !in 0..127 || r.lsb!=0->"UNKNOWN";r.msb==127->"YAMAHA_XG";r.msb in listOf(120,128)->"GS";r.msb==0 && r.channel==9->"GM";r.msb==126->"CUSTOM";else->"UNKNOWN"}
            val candidates=if(ns=="UNKNOWN" || (ns=="CUSTOM" && r.customIdentity==null) || sorted.isEmpty())emptyList() else (if(ns=="CUSTOM")listOfNotNull(kitsById[r.customIdentity]) else (kitsByPc[r.pc].orEmpty()+if(ns=="YAMAHA_XG" && r.pc!=0)kitsByPc[0].orEmpty()else emptyList())).filter { k ->
                if(ns=="CUSTOM")k.id==r.customIdentity else if(ns=="YAMAHA_XG")k.namespace in listOf("YAMAHA_XG","GS") && (k.pc==r.pc || k.namespace=="YAMAHA_XG" && k.pc==0)
                else k.namespace=="GS" && k.pc==r.pc
            }.map { k ->Candidate(k,coverage(k,sorted),when{ns=="CUSTOM"->"explicit_custom_identity";ns=="YAMAHA_XG" && k.namespace=="GS"->"gs_map_risk_alternative_only";ns=="YAMAHA_XG" && k.pc!=r.pc->"pc_fallback";ns=="YAMAHA_XG"->"xg_exact";else->"gs_gm_exact"}) }
            val ordered=candidates.sortedWith(compareBy<Candidate>({if(it.coverage.covered==it.coverage.total)0 else 1},{if(it.reason=="gs_map_risk_alternative_only")2 else if(it.reason=="pc_fallback")1 else 0},
                {if(it.kit.font=="COLOMBO")0 else if(it.kit.font=="TYROS4")1 else 2},{it.coverage.stackMax},{it.coverage.negativeZones},{it.coverage.fxZones},{it.kit.id}))
            val ideal=ordered.firstOrNull { it.coverage.covered==it.coverage.total && it.reason!="gs_map_risk_alternative_only" }
            return Decision(ns,ideal,ordered.filter { it!=ideal }.take(3),ideal?.reason?:"unresolved").also { cache[key]=it }
        }
    }
}

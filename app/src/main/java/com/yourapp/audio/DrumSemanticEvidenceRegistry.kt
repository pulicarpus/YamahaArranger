package com.yourapp.audio

import java.util.Collections

/** Generic, file-backed semantic claims. No synth/arranger access or embedded note mappings. */
object DrumSemanticEvidenceRegistry {
    const val MAX_BYTES=32*1024
    const val SCHEMA=2
    data class Target(val id:String,val manufacturer:String,val protocol:String,val msb:Int,val lsb:Int,
        val rawPc:Int,val key:Int,val label:String,val family:String,val technique:String,
        val reference:String,val referenceVersion:String) {
        fun matches(r:DrumShadowPlanner.Request)=msb==r.msb && lsb==r.lsb && rawPc==r.rawPc && key==r.sourceKey
    }
    data class Registry(val version:String,val sha256:String,val analysisSha256:String,
        val targets:List<Target>,val evidence:List<DrumShadowPlanner.Evidence>)
    private fun <T> freeze(xs:List<T>):List<T> = Collections.unmodifiableList(xs.toList())
    fun empty()=Registry("NONE",DrumShadowPlanner.digest(byteArrayOf()),"NONE",emptyList(),emptyList())
    /** Read on the explicit STOP worker only. Missing/corrupt resources fail closed, never silently disappear. */
    fun bundled():Registry {
        val input=DrumSemanticEvidenceRegistry::class.java.getResourceAsStream("/drum_shadow_audit770_v1.tsv")
            ?: error("Bundled shadow evidence unavailable")
        return input.use {
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(4096)
            while(out.size()<=MAX_BYTES) {val n=it.read(buf,0,minOf(buf.size,MAX_BYTES+1-out.size()));if(n<0)break;out.write(buf,0,n)}
            parse(out.toByteArray().toString(Charsets.UTF_8))
        }
    }
    fun parse(text:String):Registry {
        require(text.toByteArray().size<=MAX_BYTES) {"semantic registry exceeds 32 KiB"}
        val lines=text.lineSequence().filter {it.isNotBlank() && !it.startsWith('#')}.map {it.split('|')}.toList()
        require(lines.isNotEmpty());val header=lines.first()
        require(header.size==4 && header[0]=="DRUM_SHADOW_EVIDENCE" && header[1].toInt()==SCHEMA && header[2].isNotBlank()) {"unsupported semantic registry"}
        fun sha(s:String):String {require(Regex("[0-9a-f]{64}").matches(s));return s}
        fun number(s:String,max:Int=127):Int = s.toInt().also {require(it in 0..max)}
        val analysis=sha(header[3]);val version=header[2]
        val targets=lines.drop(1).filter {it[0]=="TARGET"}.map {f ->
            require(f.size==13 && f.drop(1).all {it.isNotBlank()}) {"invalid semantic target"}
            Target(f[1],f[2],f[3],number(f[4]),number(f[5]),number(f[6]),number(f[7]),f[8],f[9],f[10],f[11],f[12])
        }
        require(targets.map {it.id}.distinct().size==targets.size)
        require(targets.map {listOf(it.msb,it.lsb,it.rawPc,it.key)}.distinct().size==targets.size) {"ambiguous semantic target identity"}
        require(lines.drop(1).all {it[0] in listOf("TARGET","CANDIDATE")}) {"unknown registry record"}
        val claims=lines.drop(1).filter {it[0]=="CANDIDATE"}.map {f ->
            require(f.size==16 && f.drop(1).all {it.isNotBlank()}) {"invalid semantic candidate"}
            val target=targets.singleOrNull {it.id==f[2]} ?: error("candidate target absent")
            val binding=DrumShadowPlanner.Binding(sha(f[3]),number(f[4],65535),number(f[5]),number(f[6]))
            val lo=number(f[7]);val hi=number(f[8]);require(lo<=hi)
            val observed=freeze(f[11].split(',').map {number(it)}.distinct().sorted());require(observed.all {it in lo..hi})
            val layers=freeze(f[12].split(',').map {sha(it)}.sorted());require(layers.isNotEmpty())
            // Classification is a provenance claim, never inferred from names/coverage/PCM. Engineering proof remains UNKNOWN.
            DrumShadowPlanner.Evidence(target.msb,target.lsb,target.rawPc,target.key,binding,
                DrumShadowPlanner.Classification.valueOf(f[9]),0,f[13],target=target,registryVersion=version,
                evidenceId=f[1],schema=SCHEMA,velocityLow=lo,velocityHigh=hi,observedVelocities=observed,
                layerHashes=layers,confidenceLabel=f[10],metadataProvenance=f[14],pcmProvenance=f[15])
        }
        require(claims.map {it.evidenceId}.distinct().size==claims.size) {"duplicate evidence id"}
        return Registry(version,DrumShadowPlanner.digest(text.toByteArray()),analysis,freeze(targets),freeze(claims))
    }
}

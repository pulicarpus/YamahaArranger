package com.yourapp.audio

import com.yourapp.yamahaarranger.style.*
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

/** Entire four-font metadata and original style through unchanged native parser. No PCM inference. */
class GenericDrumGlobalReplayTest {
    private fun fonts():List<DrumShadowPlanner.Font> {
        val out=mutableListOf<DrumShadowPlanner.Font>()
        ZipInputStream(javaClass.getResourceAsStream("/global_drum_metadata.zip")!!).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                val text=zip.readBytes().toString(Charsets.UTF_8);val lines=text.lineSequence()
                val header=lines.first { it.startsWith("SF2 ") };val footer=lines.first { it.startsWith("END ") }
                val sha=Regex("sha256=([a-f0-9]{64})").find(footer)!!.groupValues[1]
                assertTrue(footer.contains("complete=true"))
                val zones=lines.filter { it.startsWith("Z ") }.map { DrumShadowPlanner.zoneFromMetadata(sha,it) }.toList()
                val samples=lines.filter { it.startsWith("S ") }.associate { row ->
                    val f=Regex("id=(\\d+).*frames=(\\d+):(\\d+).*rate=(\\d+) originalKey=(\\d+).*link=(\\d+) type=(\\d+)").find(row)!!.groupValues
                    f[1].toInt() to DrumShadowPlanner.Sample(f[1].toInt(),f[5].toInt(),f[4].toLong(),f[7].toInt(),f[6].toInt(),f[2].toLong(),f[3].toLong())
                }
                out+=DrumShadowPlanner.Font(sha,entry.name,header,zones,samples)
            }
        }
        return out
    }
    private fun style():ParsedStyle {
        val sections=linkedMapOf<String,StyleSectionModel>();var ppq=480;var name="";var length=0
        var parts=mutableListOf<StylePartModel>();var partName="";var events=mutableListOf<StyleNoteEvent>();var policies=mutableListOf<CasmPolicyModel>()
        fun part() {
            if(partName.isEmpty())return
            var msb=0;var lsb=0;var pc=-1
            for(e in events.filter { it.tick==0 })when {
                e.isControlChange && e.note==0->msb=e.velocity
                e.isControlChange && e.note==32->lsb=e.velocity
                e.isProgramChange->pc=e.note
            }
            parts+=StylePartModel(partName,events.toList(),casm=policies.firstOrNull(),casmPolicies=policies.toList(),program=pc,bankMsb=msb,bankLsb=lsb)
            partName="";events=mutableListOf();policies=mutableListOf()
        }
        fun section() { part();if(name.isNotEmpty())sections[name]=StyleSectionModel(name,length,parts.toList());parts=mutableListOf() }
        javaClass.getResourceAsStream("/drum_style_native_replay.tsv")!!.bufferedReader().useLines { rows -> rows.forEach { row ->
            val f=row.split('\t');when(f[0]) {
                "PPQ"->ppq=f[1].toInt()
                "S"->{section();name=f[1];length=f[2].toInt()}
                "P"->{part();partName=f[1]}
                "C"->policies+=CasmPolicyModel(f[1].toInt(),f[2].toInt(),f[3],f[4].toInt(),f[5].toInt(),f[6].toInt(),f[7].toInt(),f[8].toInt(),f[9].toInt(),f[10].toInt(),f[11].toInt(),f[12]=="1",f[13].toLong(),f[14].toInt(),f[15].toInt())
                "E"->{val status=f[2].toInt();val velocity=if((status and 0xf0) in listOf(0xc0,0xd0))0 else f[4].toInt()
                    events+=StyleNoteEvent(f[1].toInt(),(status and 0xf0)==0x90 && velocity>0,f[3].toInt(),velocity,if(status in 0x80..0xef)status and 15 else 0,status,f[5].toInt(),f.getOrElse(6){""}.chunked(2).map { it.toInt(16).toByte() }.toByteArray())}
            }
        } };section();return ParsedStyle("original_style_native_fixture",ppq,sections)
    }
    @Test fun completeGlobalIndexAndAllOriginalDemandResolveOrAbstainWithoutLoss() {
        val fs=fonts();assertEquals(4,fs.size);assertEquals(66944,fs.sumOf { it.zones.size })
        val s=style();val demands=DrumShadowPlanner.requests(s)
        assertEquals(1054,demands.size)
        val snap=DrumShadowPlanner.Snapshot(emptyList(),emptyList(),"37","GEN value=37\n")
        val reg=DrumSemanticEvidenceRegistry.bundled()
        val plan=GenericDrumResolver.compile(demands,fs,reg,snap,snap,sourceDigest=DrumShadowPlanner.styleDigest(s))
        assertEquals(demands.size,plan.rows.size);assertTrue(plan.complete)
        assertTrue(plan.rows.all { it.selected==null && it.outcome==GenericDrumResolver.Outcome.ABSTAIN })
        assertTrue(plan.rows.filter { it.request.sourceKey==16 }.all { it.semanticClaim==DrumShadowPlanner.Classification.UNKNOWN })
        val claimCounts=plan.rows.groupingBy { it.semanticClaim }.eachCount()
        assertEquals(95,claimCounts[DrumShadowPlanner.Classification.COMPATIBLE])
        assertEquals(959,claimCounts[DrumShadowPlanner.Classification.UNKNOWN])
        println("GLOBAL_REPLAY fonts=${fs.size} zones=${plan.zones} candidateKeys=${plan.candidateKeys} fullDemand=${demands.size} rawKeys=${demands.map { it.sourceKey }.distinct().sorted()} semanticClaims=$claimCounts outcomes=${plan.counts()} compileNs=${plan.compileNanos}")
        println("GLOBAL_REPLAY_CONTEXTS ${plan.rows.groupBy { it.request.section to it.request.rhythmChannel }.mapValues { it.value.size }}")
        val legacy=GenericDrumShadowRehearsal.replay(s,plan,snap,false)
        val proposed=GenericDrumShadowRehearsal.replay(s,plan,snap,true)
        assertEquals(1054,legacy.noteOns);assertEquals(0,legacy.rejectedCapacity);assertEquals(0,legacy.finalOwners)
        assertEquals(legacy.traceSHA256,proposed.traceSHA256);assertEquals(0,proposed.selectedCandidateOns)
        println("GLOBAL_REPLAY_OWNER_CONTRACT legacy=$legacy proposed=$proposed projectionTraceIdentical=true actualRuntimeScope=UNPROVEN")
        val report=GenericDrumShadowExport.export(plan,rehearsal="legacy=$legacy proposed=$proposed projectionTraceIdentical=true;RAW_SOURCE_OWNER_CONTRACT_NOT_PRODUCTION_SCHEDULER")
        assertTrue(report.toByteArray().size<=DrumShadowPlanner.EXPORT_BYTES);assertTrue(report.contains("fullDemand=${demands.size}"));assertTrue(report.contains("productionDispatch=UNCHANGED"))
        // Known pedal hints must come from full metadata and cross-key, never inferred to be a winner.
        val pedal=plan.rows.filter { it.request.sourceKey==21 && it.candidates.isNotEmpty() }
        assertTrue(pedal.isNotEmpty());assertTrue(pedal.any { r -> r.hints.any { it.binding.key==44 } })
        java.io.File(System.getProperty("user.dir"),"build/generic-drum-global-replay.txt").apply { parentFile.mkdirs();writeText(report) }
    }
}

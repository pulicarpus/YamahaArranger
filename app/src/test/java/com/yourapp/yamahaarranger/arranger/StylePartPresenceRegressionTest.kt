package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.*
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Calls the production scheduler/transformer/audio boundary, never a copied drum predicate. */
class StylePartPresenceRegressionTest {
    private class Run : AutoCloseable {
        val audio = mock(AudioEngineManager::class.java)
        val midi = mock(MidiInputManager::class.java)
        val scope = CoroutineScope(SupervisorJob())
        val seq = StyleSequencer(audio, midi, scope)
        fun play(section: StyleSectionModel, root: Int = 0, quality: ChordQuality = ChordQuality.MAJOR) {
            seq.currentChord = DetectedChord(root, root, quality)
            val done = CountDownLatch(1)
            seq.playSeamless(section, 1_000_000_000, 1) { done.countDown() }
            assertTrue("production scheduler completes ${section.name}", done.await(10, TimeUnit.SECONDS))
        }
        fun calls(name: String) = mockingDetails(audio).invocations.filter { it.method.name == name }.map { it.arguments.toList() }
        override fun close() { scope.cancel() }
    }
    private fun part(ch: Int, voice: String, mask: Long = -1): StylePartModel {
        val p = CasmPolicyModel(ch, ch, voice, 0, 0, 0, 0, 11, 0, 127, 1, false, mask)
        return StylePartModel(voice, listOf(StyleNoteEvent(0,true,60,42,ch),StyleNoteEvent(1,false,60,0,ch)),
            p, program=24,bankMsb=8,bankLsb=16,volume=90,expression=110)
    }
    @Test fun melodicDrPrefixesKeepDeclaredBankAndRealChordTransform() {
        for (voice in listOf("DrawbarOrgan", "DriveGtr", "DreamPad", "PercOrgan")) Run().use { r ->
            r.play(StyleSectionModel("MainA", 2, listOf(part(12,voice))),5)
            assertEquals(65,r.calls("noteOnStyleChannel").single()[1])
            assertEquals(8*128+16,r.calls("setChannelProgram").single()[2])
            assertEquals(65,(r.calls("noteOffStyleChannel") + r.calls("noteOffChannel")).single()[1])
        }
    }
    @Test fun bothNativeRhythmDestinationsStayUntransposedEvenWithOpaqueKitNames() {
        Run().use { r ->
            r.play(StyleSectionModel("MainA",2,listOf(part(8,"Kit A"),part(9,"Kit B"))),5)
            assertEquals(setOf(8,9),r.calls("noteOnStyleChannel").map { it[0] }.toSet())
            assertTrue(r.calls("noteOnStyleChannel").all { it[1]==60 && it[2]==42/127f })
            assertTrue(r.calls("setChannelProgram").all { it[2]==128 })
        }
    }
    @Test fun everyPermittedRoleIncludingPhrase2ReachesProductionAudioBoundary() {
        val voices=listOf("Drums","Perc.","Bass","Piano","A.Guitar","Strings1","Flute","Warm Pad")
        val section=StyleSectionModel("MainD",2,(8..15).map { part(it,voices[it-8]) })
        Run().use { r ->
            r.seq.setVoiceMap(emptyMap());r.play(section)
            assertEquals((8..15).toSet(),r.calls("noteOnStyleChannel").map { it[0] }.toSet())
            assertEquals(8,r.calls("noteOnStyleChannel").size)
            assertTrue(r.calls("setChannelProgram").all { it[0] in 8..15 })
            val before=mockingDetails(r.audio).invocations.size
            val report=r.seq.partPresenceReport(ParsedStyle("all",480,mapOf(section.name to section)))
            for(ch in 8..15) assertTrue(report.lines().single { it.startsWith("PART ") && it.contains(" ch=$ch ") }.contains("beforeTransform=1 afterTransform=1 bridgeCalls=1"))
            assertEquals(before,mockingDetails(r.audio).invocations.size)
        }
    }
    @Test fun missingCasmAndIntentionalMaskRemainDistinctLossesWithoutOpeningEitherGate() {
        val missing=part(12,"Guitar").copy(casm=null,casmPolicies=emptyList())
        val section=StyleSectionModel("MainA",2,listOf(missing,part(13,"Strings",0)))
        Run().use { r ->
            r.play(section);assertTrue(r.calls("noteOnStyleChannel").isEmpty())
            val report=r.seq.partPresenceReport(ParsedStyle("rejected",480,mapOf("MainA" to section)))
            assertTrue(report.lines().single { it.startsWith("PART ") && it.contains(" ch=12 ") }.contains("missingCASMRejected=1 maskOrRangeRejected=0"))
            assertTrue(report.lines().single { it.startsWith("PART ") && it.contains(" ch=13 ") }.contains("missingCASMRejected=0 maskOrRangeRejected=1"))
        }
    }
    @Test fun originalNativeParsedStyleAllSectionsAdmitAllExpectedExistingParts() {
        val style=nativeFixture()
        val reports=StringBuilder()
        for(quality in listOf(ChordQuality.MAJOR,ChordQuality.MINOR)) for(section in style.sections.values) Run().use { r ->
            r.seq.setVoiceMap(emptyMap());r.play(section,quality=quality)
            val counts=r.calls("noteOnStyleChannel").groupingBy { it[0] as Int }.eachCount()
            val expected=section.parts.flatMap { part -> part.casmPolicies.ifEmpty { listOfNotNull(part.casm) } }.map { it.destinationChannel }.toSet()
            assertEquals("${section.name} $quality",expected,counts.keys)
            assertFalse("Phrase2 absent in this fixture",counts.containsKey(15))
            if(section.name=="MainD" && quality==ChordQuality.MAJOR)
                assertEquals(mapOf(8 to 117,9 to 96,10 to 19,11 to 70,12 to 168,13 to 3,14 to 2),counts)
            if(section.name=="MainC" && quality==ChordQuality.MAJOR)
                assertEquals(mapOf(8 to 72,9 to 56,10 to 19,11 to 54,12 to 71,13 to 19,14 to 2),counts)
            reports.appendLine("REPLAY ${section.name} $quality audioBoundary=$counts")
            reports.appendLine(r.seq.partPresenceReport(style))
        }
        println(reports.lines().filter { it.startsWith("REPLAY ") }.joinToString("\n"))
        java.io.File("build/accompaniment-presence-replay.txt").apply { parentFile.mkdirs();writeText(reports.toString()) }
    }
    @Test fun exportIsByteBoundedAndReportsOmittedSourceRows() {
        val sections=(0..500).associate { "Section $it" to StyleSectionModel("Section $it",2,listOf(part(12,"Guitar"))) }
        val report=StylePartPresence().report(ParsedStyle("many",480,sections))
        assertTrue(report.toByteArray().size<=16*1024)
        assertEquals(8,report.lines().count { it.startsWith("PART ") })
        assertTrue(Regex("exportOmittedRows=([1-9][0-9]*)").containsMatchIn(report))
    }
    /** Decode archived native-parser data only. No CASM/routing decisions are duplicated here. */
    private fun nativeFixture(): ParsedStyle {
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
            parts+=StylePartModel(partName,events.toList(),policies.firstOrNull(),policies.toList(),pc,msb,lsb)
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
        } };section();return ParsedStyle("Love Song native parser fixture",ppq,sections)
    }
}

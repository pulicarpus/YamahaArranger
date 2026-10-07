package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.style.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SffDialectBoundaryTest {
    private val repository=mock(StyleRepository::class.java,CALLS_REAL_METHODS)
    private val attach=StyleRepository::class.java.getDeclaredMethod(
        "withDialectMetadata",ParsedStyle::class.java,Int::class.javaPrimitiveType).apply { isAccessible=true }
    private fun source(name:String="unchanged.bin"):ParsedStyle {
        val events=listOf(StyleNoteEvent(0,true,60,100,4),StyleNoteEvent(1,false,60,0,4))
        return ParsedStyle(name,1920,mapOf("MainD" to StyleSectionModel("MainD",2,
            listOf(StylePartModel("source4",events)))),mapOf(4 to "voice"),120)
    }
    private fun tagged(style:ParsedStyle,code:Int):ParsedStyle = attach.invoke(repository,style,code) as ParsedStyle

    @Test fun verifiedNativeIdentityPropagatesWithoutChangingMusicalObjects() {
        val original=source();val result=tagged(original,1)
        assertEquals(StyleDialect.SFF1,result.dialectIdentity.dialect)
        assertEquals(StyleDialectEvidence.SMF_TICK0_MARKER_SFF1,result.dialectIdentity.evidence)
        assertSame(result.dialectIdentity,result.sections.getValue("MainD").dialectIdentity)
        assertSame(original.sections.getValue("MainD").parts,result.sections.getValue("MainD").parts)
        assertSame(original.meter,result.meter);assertSame(original.voiceMap,result.voiceMap)
        assertEquals(original.fileName,result.fileName);assertEquals(original.ppq,result.ppq)
        assertEquals(original.defaultTempoBpm,result.defaultTempoBpm)
        assertEquals(original.sections.getValue("MainD").lengthTicks,result.sections.getValue("MainD").lengthTicks)
        assertEquals(StyleDialect.UNKNOWN,original.dialectIdentity.dialect)
        verifyNoInteractions(repository)
    }
    @Test fun insufficientOrReservedEvidenceFailsClosed() {
        for(code in listOf(0,2,-1,99)) {
            val result=tagged(source("SFF1.sty"),code)
            assertEquals(StyleDialectIdentity(),result.dialectIdentity)
            assertEquals(StyleDialectIdentity(),result.sections.getValue("MainD").dialectIdentity)
        }
    }
    @Test fun extensionNeverDeterminesIdentity() {
        for(name in listOf("plain.mid","pretend.sff2","SFF1.sty","folderSFF1/unknown.bin")) {
            assertEquals(StyleDialect.SFF1,tagged(source(name),1).dialectIdentity.dialect)
            assertEquals(StyleDialect.UNKNOWN,tagged(source(name),0).dialectIdentity.dialect)
        }
    }
    @Test fun metadataDoesNotLeakBetweenLoads() {
        val first=tagged(source(),1);val unknown=tagged(source(),0);val last=tagged(source(),1)
        assertEquals(StyleDialect.SFF1,first.dialectIdentity.dialect)
        assertEquals(StyleDialect.UNKNOWN,unknown.dialectIdentity.dialect)
        assertEquals(StyleDialect.SFF1,last.dialectIdentity.dialect)
        assertSame(first.dialectIdentity,first.sections.getValue("MainD").dialectIdentity)
        assertSame(unknown.dialectIdentity,unknown.sections.getValue("MainD").dialectIdentity)
    }
    @Test fun sff2IsRepresentableButNotFalselyCertified() {
        assertTrue(StyleDialect.values().contains(StyleDialect.SFF2))
        assertEquals("UNVERIFIED_NEEDS_FIXTURE",StyleDialectIdentity.SFF2_DETECTION_STATUS)
        assertEquals(StyleDialect.UNKNOWN,StyleDialectIdentity.fromNativeCode(2).dialect)
    }
}

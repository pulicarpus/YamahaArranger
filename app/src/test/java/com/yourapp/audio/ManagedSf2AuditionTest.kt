package com.yourapp.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class ManagedSf2AuditionTest {
    private val raw get() = Sf2SemanticInventoryTest.fixture()
    private fun source(bytes: ByteArray = raw) = Sf2SemanticInventory.Source("managed:test", "test.sf2") { ByteArrayInputStream(bytes) }
    private fun discovery(bytes: ByteArray = raw) = Sf2SemanticInventory.Discovery(listOf(source(bytes)))
    private fun request(bytes: ByteArray = raw) = ManagedSf2Audition.Request(ManagedSf2Audition.fonts(discovery(bytes)).single(),128,36,62,42)
    private val wav = "RIFF".toByteArray() + ByteArray(40)
    private fun refused(req: ManagedSf2Audition.Request, sources: Sf2SemanticInventory.Discovery = discovery(), allowed: () -> Boolean = {true}) {
        val dir = kotlin.io.path.createTempDirectory("audition-test").toFile();var calls=0
        try {
            try { ManagedSf2Audition.render(req,sources,dir,allowed) { _,_ -> calls++;ManagedSf2Audition.NativeResult(wav,"actual") };fail("Expected rejection") }
            catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
            assertEquals(0,calls);assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun crossKeyAuditionVerifiesSnapshotAndIncludesFullMetadataSidecar() {
        val dir=kotlin.io.path.createTempDirectory("audition-test").toFile();val req=request();var calls=0
        try {
            val result=ManagedSf2Audition.render(req,discovery(),dir,{true}) { f,r ->
                calls++;assertTrue(f.isFile);assertArrayEquals(raw,f.readBytes());assertEquals(req,r)
                ManagedSf2Audition.NativeResult(wav,"actual fontHandle=55 bank=128 rawPC=36 verified_before_and_after_note=true")
            }
            assertEquals(1,calls);assertArrayEquals(wav,result.wav);assertTrue(dir.listFiles()!!.isEmpty())
            for (text in listOf(req.font.sha256,"sourceKey=62 velocity=42","sample=0:'Pedal Hi-Hat'","51:2","52:-7","overrideRoot=62","mods=258:8:0:0:0","classification=UNKNOWN","eligibleLayers=1","actual fontHandle=55","wavSHA256="))
                assertTrue(text,result.sidecar.contains(text))
        } finally { dir.deleteRecursively() }
    }
    @Test fun changedFingerprintNeverCallsNative() = refused(request(),discovery(raw+byteArrayOf(1)))
    @Test fun unmanagedSourceNeverCallsNative() = refused(request(),Sf2SemanticInventory.Discovery(emptyList()))
    @Test fun absentBankPresetOrSourceKeyNeverCallsNative() {
        val r=request();refused(r.copy(bank=127));refused(r.copy(rawPc=1));refused(r.copy(sourceKey=21))
    }
    @Test fun validVelocityOutsideEligibleLayerNeverCallsNative() {
        val bytes=raw;val pos=bytes.toString(Charsets.ISO_8859_1).indexOf("igen")+8+2*4+2
        bytes[pos]=50;bytes[pos+1]=127
        refused(request(bytes),discovery(bytes))
    }
    @Test fun invalidVelocityOrParametersNeverCallNative() {
        val r=request();refused(r.copy(velocity=0));refused(r.copy(velocity=128));refused(r.copy(sourceKey=128));refused(r.copy(bank=-1));refused(r.copy(rawPc=128))
    }
    @Test fun malformedAndIncompleteInventoryNeverCallsNative() = refused(request(),discovery(byteArrayOf(1,2)))
    @Test fun playbackStartingDuringVerificationRejectsBeforeNativeAndCleansSnapshot() {
        var checks=0;refused(request(),allowed={++checks==1});assertEquals(2,checks)
    }
    @Test fun nativeFailureStillCleansPrivateSnapshot() {
        val dir=kotlin.io.path.createTempDirectory("audition-test").toFile()
        try {
            try { ManagedSf2Audition.render(request(),discovery(),dir,{true}) { _,_ -> error("native failure") };fail() } catch (_: IllegalStateException) {}
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun allCoeligibleLayersAreRetainedRatherThanOnlyCompactLeads() {
        val bytes=raw;val pos=bytes.toString(Charsets.ISO_8859_1).indexOf("igen")+8+6*4+2
        bytes[pos]=62;bytes[pos+1]=62
        val dir=kotlin.io.path.createTempDirectory("audition-test").toFile()
        try {
            val result=ManagedSf2Audition.render(request(bytes),discovery(bytes),dir,{true}) { _,_ -> ManagedSf2Audition.NativeResult(wav,"actual") }
            assertTrue(result.sidecar.contains("eligibleLayers=2"));assertTrue(result.sidecar.contains("sample=1:'Hi-Hat Edge'"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun wavAndSidecarAreExportedAsOneBundle() {
        val out=ByteArrayOutputStream();ManagedSf2Audition.Result(wav,"evidence").zip(out,"probe")
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while(true) { val e=z.nextEntry?:break;entries[e.name]=z.readBytes() } }
        assertEquals(setOf("probe.wav","probe.txt"),entries.keys);assertArrayEquals(wav,entries["probe.wav"]);assertEquals("evidence",entries["probe.txt"]!!.toString(Charsets.UTF_8))
    }
    @Test fun discoveryFailureIsNotSilentlySkipped() {
        try { ManagedSf2Audition.fonts(Sf2SemanticInventory.Discovery(emptyList(),listOf("unreadable")));fail() } catch (_: IllegalStateException) {}
    }
}

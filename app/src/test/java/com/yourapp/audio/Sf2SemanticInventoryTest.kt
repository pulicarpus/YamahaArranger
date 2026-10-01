package com.yourapp.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

/** Real RIFF/pdta fixtures exercise the production file scanner, never fake sound identity. */
class Sf2SemanticInventoryTest {
    companion object {
        private fun w(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
        private fun d(v: Int) = w(v) + w(v ushr 16)
        private fun name(s: String, n: Int) = s.toByteArray(Charsets.US_ASCII).copyOf(n)
        private fun chunk(id: String, bytes: ByteArray) = name(id,4) + d(bytes.size) + bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        private fun gens(vararg pairs: Pair<Int, Int>) = pairs.fold(byteArrayOf()) { a, (op,v) -> a + w(op) + w(v) }
        private fun ph(s: String, pc: Int, bank: Int, bag: Int) = name(s,20) + w(pc) + w(bank) + w(bag) + ByteArray(12)
        private fun inst(s: String, bag: Int) = name(s,20) + w(bag)
        private fun sh(s: String, type: Int = 1) = name(s,20) + d(0) + d(32) + d(0) + d(32) + d(44100) + byteArrayOf(60,(-3).toByte()) + w(0) + w(type)
        private fun mod(amount: Int) = w(258) + w(8) + w(amount) + w(0) + w(0)
        fun fixture(): ByteArray {
            val pg = gens(51 to 2, 43 to (30 or (70 shl 8)), 41 to 0, 41 to 1) + ByteArray(4)
            val ig = gens(52 to -7,
                43 to (62 or (62 shl 8)), 44 to (1 or (127 shl 8)), 53 to 0, 58 to 62, 56 to 23,
                43 to (63 or (63 shl 8)), 53 to 1,
                43 to (38 or (38 shl 8)), 53 to 2,
                43 to (10 or (20 shl 8)), 53 to 0) + ByteArray(4)
            val pdta = chunk("phdr",ph("Opaque Kit",36,128,0) + ph("Other",73,0,2) + ph("EOP",0,0,3)) +
                chunk("pbag",w(0)+w(0)+w(2)+w(0)+w(3)+w(0)+w(4)+w(0)) +
                chunk("pmod",ByteArray(10)) + chunk("pgen",pg) +
                chunk("inst",inst("Percussion",0)+inst("Other Instrument",4)+inst("EOI",5)) +
                chunk("ibag",w(0)+w(0)+w(1)+w(1)+w(6)+w(2)+w(8)+w(2)+w(10)+w(2)+w(12)+w(2)) +
                chunk("imod",mod(100)+mod(0)+ByteArray(10)) + chunk("igen",ig) +
                chunk("shdr",sh("Pedal Hi-Hat") + sh("Hi-Hat Edge") + sh("Snare 4 PD") + sh("EOS"))
            val payload = name("sfbk",4) + chunk("LIST",name("sdta",4)+chunk("smpl",ByteArray(64))) + chunk("LIST",name("pdta",4)+pdta)
            return name("RIFF",4) + d(payload.size) + payload
        }
    }
    @Test fun allPresetsAndOffKeySemanticLeadsAreScannedWithoutDeclaringIdentity() {
        val scan = Sf2SemanticInventory.scan(ByteArrayInputStream(fixture()), "test.sf2")
        assertTrue(scan.reason,scan.complete); assertEquals(2,scan.presets); assertEquals(2,scan.instruments); assertEquals(3,scan.samples)
        assertEquals(4,scan.relations)
        val pedal = scan.candidates.first { it.target == 21 && it.row.contains("sourceKeys=62:62") }
        assertTrue(pedal.row.contains("sameKey=false")); assertTrue(pedal.row.contains("nameHint=true"))
        assertTrue(pedal.row.contains("classification=UNKNOWN"))
        assertTrue(scan.candidates.any { it.target == 16 && it.row.contains("sourceKeys=63:63") })
        assertTrue(scan.candidates.any { it.target == 31 && it.row.contains("sourceKeys=38:38") })
        assertTrue(scan.candidates.any { it.row.contains("bank=0 rawPC=73") })
    }
    @Test fun rootPitchRangesAndGlobalModulatorOverrideArePreserved() {
        val scan = Sf2SemanticInventory.scan(ByteArrayInputStream(fixture()), "test.sf2")
        val row = scan.candidates.first { it.target == 21 && it.row.contains("sourceKeys=62:62") }.row
        assertTrue(row.contains("keys=62:62 velocities=1:127"))
        assertTrue(row.contains("originalKey=60 correction=-3 overrideRoot=62"))
        assertTrue(row.contains("51:2")); assertTrue(row.contains("52:-7")); assertTrue(row.contains("56:23"))
        assertTrue(row.contains("mods=258:8:0:0:0")); assertFalse(row.contains("mods=258:8:0:0:100"))
    }
    @Test fun sha256IncludesSampleChunkAndTrailingBytesWithoutInputSkip() {
        val raw = fixture() + byteArrayOf(5,7,9)
        var skipped = false
        val input = object : ByteArrayInputStream(raw) {
            override fun skip(n: Long): Long { skipped = true; error("hash must read all bytes") }
        }
        val scan = Sf2SemanticInventory.scan(input,"test")
        assertTrue(scan.complete); assertFalse(skipped); assertEquals(raw.size.toLong(),scan.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) },scan.sha256)
    }
    @Test fun unreadableAndMalformedFontsDoNotPreventOtherFilesBeingScanned() {
        var opened = 0; var closed = 0
        val sources = listOf(
            Sf2SemanticInventory.Source("bad","bad.sf2") { null },
            Sf2SemanticInventory.Source("broken","broken.sf2") { ByteArrayInputStream(byteArrayOf(1,2,3)) },
            Sf2SemanticInventory.Source("good","good.sf2") {
                opened++; object : ByteArrayInputStream(fixture()) { override fun close() { closed++; super.close() } }
            })
        val out = ByteArrayOutputStream(); val summary = Sf2SemanticInventory.export(Sf2SemanticInventory.Discovery(sources),out,false)
        assertEquals(3,summary.files); assertEquals(1,summary.valid); assertEquals(2,summary.failed)
        assertEquals(1,opened); assertEquals(1,closed)
        assertTrue(out.toString("UTF-8").contains("cannot_open")); assertTrue(out.toString("UTF-8").contains("sha256="))
    }
    @Test fun compactIsUtf8BoundedWithExplicitOmissionsAndFairFontRows() {
        val discovery = Sf2SemanticInventory.Discovery((0 until 40).map { n ->
            Sf2SemanticInventory.Source("id$n","日本語_$n.sf2") { ByteArrayInputStream(fixture()) }
        })
        val out = ByteArrayOutputStream(); val result = Sf2SemanticInventory.export(discovery,out,false)
        assertEquals(40,result.valid); assertTrue(out.size() <= Sf2SemanticInventory.COMPACT_BYTES)
        val text = out.toString("UTF-8"); assertTrue(text.contains("omitted=")); assertTrue(text.contains("filesScanned=40"))
        assertTrue(text.contains("FILE id=39")); assertEquals(out.size(),result.compactBytes)
        assertFalse(text.contains("classification=EXACT")); assertTrue(text.contains("UNKNOWN_not_permission"))
    }
    @Test fun fullZipRetainsEveryPresetInstrumentBagSampleAndCompactIndex() {
        val out = ByteArrayOutputStream()
        Sf2SemanticInventory.export(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("id","test") { ByteArrayInputStream(fixture()) })),out,true)
        val entries = linkedMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("font_1.txt","semantic_compact.txt"),entries.keys)
        val text = entries.getValue("font_1.txt")
        assertEquals(2,text.lineSequence().count { it.startsWith("P ") }); assertEquals(2,text.lineSequence().count { it.startsWith("I ") })
        assertEquals(5,text.lineSequence().count { it.startsWith("IB ") }); assertEquals(3,text.lineSequence().count { it.startsWith("S ") })
        assertEquals(4,text.lineSequence().count { it.startsWith("Z ") }); assertTrue(text.contains("END sha256="))
        assertTrue(text.contains("displayPC=37")); assertTrue(text.contains("displayPC=74"))
    }
    @Test fun discoveryIncludesNestedUppercaseFontsAndNeverCreatesMissingRoot() {
        val root = kotlin.io.path.createTempDirectory("sf2-test").toFile()
        try {
            File(root,"a.sf2").writeBytes(fixture()); File(root,"nested").mkdir()
            File(root,"nested/B.SF2").writeBytes(fixture()); File(root,"ignore.wav").writeBytes(byteArrayOf())
            val discovery = Sf2SemanticInventory.discover(root)
            assertEquals(listOf("a.sf2","nested/B.SF2"),discovery.sources.map { it.name }); assertTrue(discovery.errors.isEmpty())
            val missing = File(root,"missing"); assertFalse(missing.exists())
            assertTrue(Sf2SemanticInventory.discover(missing).errors.isNotEmpty()); assertFalse(missing.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun malformedTablePointersFailAsUnknownAndDoNotExportPartialCandidates() {
        val raw = fixture(); val index = raw.toString(Charsets.ISO_8859_1).indexOf("pbag")
        raw[index+8] = 0xff.toByte(); raw[index+9] = 0x7f.toByte()
        val scan = Sf2SemanticInventory.scan(ByteArrayInputStream(raw),"bad")
        assertFalse(scan.complete); assertTrue(scan.candidates.isEmpty()); assertNotNull(scan.sha256)
    }
    @Test fun emptyDiscoveryIsExplicitAndHasNoFabricatedCandidate() {
        val out = ByteArrayOutputStream()
        val summary = Sf2SemanticInventory.export(Sf2SemanticInventory.Discovery(emptyList(),listOf("managed_folder_unavailable")),out,false)
        assertEquals(0,summary.files); val text=out.toString("UTF-8")
        assertTrue(text.contains("discoveryErrors=1")); assertTrue(text.contains("managed_folder_unavailable")); assertFalse(text.contains("classification=COMPATIBLE"))
    }
}

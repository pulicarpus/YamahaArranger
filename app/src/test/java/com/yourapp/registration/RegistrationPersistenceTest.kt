package com.yourapp.yamahaarranger.registration

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import org.junit.Assert.*
import org.junit.Test

class RegistrationPersistenceTest {
    private fun bank(name: String = "Bank"): RegistrationBank = RegistrationBank("test-bank", name,
        OutputMode.values().map { mode -> RegistrationSlot("Slot $mode",mode,List(4) { i -> PartSetup(
            if(i%2==0) Sf2VoiceRef("真实.sf2",if(i==0) 65535 else 1025,27,"Strings") else null,
            if(i%2==1) ExternalVoice(0,112,5,"E343 Strings") else null,true,100) },
            ReleaseSetup(127,false),96,2,123L) } + List(5) { null })
    private fun manifest(b: RegistrationBank) = RegistrationManifest(banks=listOf(BankHeader(b.id,b.name,0)),activeBankId=b.id,
        freeze=FreezeFlags(),externalVoices=listOf(ExternalVoiceEntry("ext1",ExternalVoice(0,112,5,"E343"))))
    private fun dir(): Path = Files.createTempDirectory("reg-test-")
    private fun primary(d: Path) = d.resolve("bank-test-bank.json")
    private fun fail(block: () -> Unit) { try { block() } catch(_: Exception) { return }; throw AssertionError("Expected failure") }
    @Suppress("UNCHECKED_CAST") private fun mutated(raw: String, change: (MutableMap<String,Any?>) -> Unit): String {
        val e=RegistrationCodec.Json.parse(raw) as MutableMap<String,Any?>
        change(e["payload"] as MutableMap<String,Any?>)
        e["crc32"]="%08x".format(CRC32().apply { update(RegistrationCodec.Json.write(e["payload"]).toByteArray(Charsets.UTF_8)) }.value)
        return RegistrationCodec.Json.write(e)
    }
    @Test fun P1_roundTripManifestAndAllThreeModes() = runBlocking {
        val b=bank(); assertEquals(b,RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(b)).value)
        val m=manifest(b); assertEquals(m,RegistrationCodec.decodeManifest(RegistrationCodec.encodeManifest(m)).value)
        val store=AtomicBankStore(dir()); store.saveBank(b); store.saveManifest(m)
        assertEquals(b,store.readBank(b.id).value); assertEquals(m,store.readManifest().value)
    }
    @Test fun P2_loadClampsEveryBoundedParameter() {
        val b=bank("x".repeat(30)); val s=b.slots[0]!!
        val bad=b.copy(slots=listOf(s.copy(name="n".repeat(30),parts=List(4) { PartSetup(Sf2VoiceRef("a",65535,999,"n"),ExternalVoice(-1,999,999,"e"),true,-40) },release=ReleaseSetup(999,true),tempoBpm=999,transpose=-999))+List(7){null})
        val loaded=RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(bad)).value
        assertEquals(24,loaded.name.length); val v=loaded.slots[0]!!
        assertEquals(24,v.name.length); assertEquals(280,v.tempoBpm); assertEquals(-12,v.transpose); assertEquals(127,v.release.knobValue)
        assertEquals(0,v.parts[0].volume); assertEquals(65535,v.parts[0].sf2!!.bank); assertEquals(127,v.parts[0].sf2!!.program)
        assertEquals(ExternalVoice(0,127,127,"e"),v.parts[0].external)
        val low=s.copy(tempoBpm=-1,transpose=50,release=ReleaseSetup(-1,false),parts=s.parts.map{it.copy(volume=999)})
        val l=RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(b.copy(slots=listOf(low)+List(7){null}))).value.slots[0]!!
        assertEquals(20,l.tempoBpm); assertEquals(12,l.transpose); assertEquals(0,l.release.knobValue); assertEquals(127,l.parts[0].volume)
    }
    @Test fun P3_mixedVoiceStructuresAreRejected() {
        val raw=mutated(RegistrationCodec.encodeBank(bank())) { p ->
            @Suppress("UNCHECKED_CAST") val parts=(p["slots"] as List<Map<String,Any?>>)[0]["parts"] as List<MutableMap<String,Any?>>
            parts[0]["external"]=parts[0]["sf2"]
        }
        fail { RegistrationCodec.decodeBank(raw) }
        // Numeric coincidence cannot prove provenance; only explicit independent fields are accepted.
    }
    @Test fun P4_sessionSoundFontIdIsNotInSchema() {
        val raw=RegistrationCodec.encodeBank(bank()); assertFalse(raw.contains("soundFontId"))
        val injected=mutated(raw) { it["soundFontId"]=999L }; fail { RegistrationCodec.decodeBank(injected) }
    }
    @Test fun P5_crashAfterTmpKeepsOldBankAndCleansTmp() = runBlocking {
        val d=dir(); val b=bank(); AtomicBankStore(d).saveBank(b)
        val crashing=AtomicBankStore(d) { stage,_ -> if(stage==WriteStage.TMP_FORCED) error("Simulated process loss") }
        fail { runBlocking { crashing.saveBank(bank("New")) } }
        assertTrue(Files.exists(d.resolve("bank-test-bank.json.tmp")))
        assertEquals(b,AtomicBankStore(d).readBank(b.id).value)
        assertFalse(Files.exists(d.resolve("bank-test-bank.json.tmp")))
    }
    @Test fun P6_crashAfterMoveLoadsNewOrBackup() = runBlocking {
        val d=dir(); AtomicBankStore(d).saveBank(bank())
        val crashing=AtomicBankStore(d) { stage,_ -> if(stage==WriteStage.MOVED) error("Simulated process loss") }
        fail { runBlocking { crashing.saveBank(bank("New")) } }
        val read=AtomicBankStore(d).readBank("test-bank"); assertEquals(BankReadStatus.VALID,read.status); assertEquals("New",read.value!!.name)
        assertEquals("Bank",RegistrationCodec.decodeBank(Files.readString(d.resolve("bank-test-bank.json.bak"))).value.name)
    }
    @Test fun P7_crcCorruptionUsesBackupWithoutAutomaticRewrite() = runBlocking {
        val d=dir(); val st=AtomicBankStore(d); st.saveBank(bank()); st.saveBank(bank("New"))
        val corrupt=Files.readString(primary(d)).replace("\"New\"","\"Bad\""); Files.writeString(primary(d),corrupt)
        val recovered=st.readBank("test-bank"); assertEquals(BankReadStatus.RECOVERED_BACKUP,recovered.status); assertEquals("Bank",recovered.value!!.name)
        assertFalse(Files.exists(primary(d))); assertEquals(corrupt,Files.list(d.resolve("quarantine")).use { Files.readString(it.findFirst().get()) })
        st.saveBank(bank("Saved")); assertEquals("Saved",st.readBank("test-bank").value!!.name)
    }
    @Test fun P8_bothCorruptAreDamagedReadonlyAndRetained() = runBlocking {
        val d=dir(); val st=AtomicBankStore(d); st.saveBank(bank()); st.saveBank(bank("New"))
        Files.writeString(primary(d),"bad-primary"); Files.writeString(d.resolve("bank-test-bank.json.bak"),"bad-backup")
        val r=st.readBank("test-bank"); assertEquals(BankReadStatus.DAMAGED,r.status); assertTrue(r.readOnly); assertNull(r.value)
        val contents=Files.list(d.resolve("quarantine")).use { it.map(Files::readString).toArray().toSet() }; assertEquals(setOf("bad-primary","bad-backup"),contents)
        fail { runBlocking { AtomicBankStore(d).saveBank(bank("Overwrite")) } }
    }
    @Test fun P9_manifestRebuiltAndOrphanRecoveredWithoutDiskWrite() = runBlocking {
        val d=dir(); val st=AtomicBankStore(d); st.saveBank(bank()); Files.writeString(d.resolve("manifest.json"),"bad")
        val r=st.readManifest(); assertEquals(BankReadStatus.REBUILT,r.status); assertEquals("test-bank",r.value!!.activeBankId); assertFalse(Files.exists(d.resolve("manifest.json")))
        st.saveManifest(manifest(bank())); val orphan=bank().copy(id="orphan"); st.saveBank(orphan)
        assertEquals(setOf("test-bank","orphan"),st.readManifest().value!!.banks.map{it.id}.toSet())
        assertEquals("Recovered bank",st.readManifest().value!!.banks.first{it.id=="orphan"}.name)
    }
    @Test fun P10_newerSchemaIsReadonlyAndNeverRewritten() = runBlocking {
        val d=dir(); Files.createDirectories(d); val raw=RegistrationCodec.encodeBank(bank()).replace("\"schema\":2","\"schema\":3")
        Files.writeString(primary(d),raw); val st=AtomicBankStore(d); assertEquals(BankReadStatus.NEWER_SCHEMA,st.readBank("test-bank").status)
        fail { runBlocking { st.saveBank(bank()) } }; assertEquals(raw,Files.readString(primary(d)))
        val m=RegistrationCodec.encodeManifest(manifest(bank())).replace("\"schema\":2","\"schema\":3")
        Files.writeString(d.resolve("manifest.json"),m); assertEquals(BankReadStatus.NEWER_SCHEMA,st.readManifest().status)
        fail { runBlocking { st.saveManifest(manifest(bank())) } }; assertEquals(m,Files.readString(d.resolve("manifest.json")))
    }
    @Test fun P11_slotSaveChangesOnlyOnePrimaryBank() = runBlocking {
        val d=dir(); val st=AtomicBankStore(d); val b=bank(); st.saveBank(b); st.saveBank(b.copy(id="other")); st.saveManifest(manifest(b))
        val files=Files.list(d).use { it.filter{p->p.toString().endsWith(".json")}.toArray().map{it as Path} }
        val before=files.associateWith{Files.readAllBytes(it).toList()}
        val slots=b.slots.toMutableList(); slots[7]=b.slots[0]!!.copy(name="Changed"); st.saveBank(b.copy(slots=slots))
        assertEquals(listOf(primary(d)),files.filter{before[it]!=Files.readAllBytes(it).toList()})
        assertEquals(b.slots[0],st.readBank(b.id).value!!.slots[0])
    }
    @Test fun P12_undoSnapshotUsesSameAtomicProtocol() = runBlocking {
        val d=dir(); val st=AtomicBankStore(d); val original=bank(); st.saveBank(original); st.saveBank(bank("Overwrite")); st.saveBank(original)
        assertEquals(original,st.readBank(original.id).value); assertEquals(3L,st.readBank(original.id).writeSeq)
        assertEquals("Overwrite",RegistrationCodec.decodeBank(Files.readString(d.resolve("bank-test-bank.json.bak"))).value.name)
    }
    @Test fun readbackFailureRestoresBackupAndReportsFailure() = runBlocking {
        val d=dir(); AtomicBankStore(d).saveBank(bank())
        val st=AtomicBankStore(d) { stage,p -> if(stage==WriteStage.BEFORE_VERIFY) Files.writeString(p,"broken-after-move") }
        fail { runBlocking { st.saveBank(bank("New")) } }
        assertEquals("Bank",AtomicBankStore(d).readBank("test-bank").value!!.name)
        assertEquals("broken-after-move",Files.list(d.resolve("quarantine")).use{Files.readString(it.findFirst().get())})
    }
    @Test fun concurrentStoresShareOneSerialWriter() = runBlocking {
        val d=dir(); val stores=listOf(AtomicBankStore(d),AtomicBankStore(d))
        (0 until 20).map { i -> launch { stores[i%2].saveBank(bank("Write$i")) } }.joinAll()
        val r=AtomicBankStore(d).readBank("test-bank")
        assertEquals(BankReadStatus.VALID,r.status); assertEquals(20L,r.writeSeq)
        assertEquals(19L,RegistrationCodec.decodeBank(Files.readString(d.resolve("bank-test-bank.json.bak"))).writeSeq)
    }
    @Test fun filenameMismatchNeverOverwritesWrongBank() = runBlocking {
        val d=dir(); val wrong=RegistrationCodec.encodeBank(bank().copy(id="wrong"))
        Files.writeString(primary(d),wrong)
        assertEquals(BankReadStatus.DAMAGED,AtomicBankStore(d).readBank("test-bank").status)
        fail { runBlocking { AtomicBankStore(d).saveBank(bank()) } }
        assertEquals(wrong,Files.list(d.resolve("quarantine")).use { Files.readString(it.findFirst().get()) })
    }
    @Test fun crcUsesCanonicalPayloadNotOriginalObjectOrder() {
        val raw=RegistrationCodec.encodeBank(bank())
        @Suppress("UNCHECKED_CAST") val e=RegistrationCodec.Json.parse(raw) as Map<String,Any?>
        val alternate=e.entries.reversed().joinToString(",\n", "{\n", "\n}") { RegistrationCodec.Json.write(it.key)+" : "+RegistrationCodec.Json.write(it.value) }
        assertEquals(bank(),RegistrationCodec.decodeBank(alternate).value)
        fail { RegistrationCodec.decodeBank(raw+" trailing") }
        fail { RegistrationCodec.decodeBank(raw.replace("65535","65534")) }
    }
    @Test fun malformedShapeAndUnsafeIdRejected() {
        fail { RegistrationCodec.decodeBank("{\"schema\":2,\"schema\":2}") }
        fail { RegistrationCodec.encodeBank(bank().copy(id="../escape")) }
        fail { RegistrationCodec.encodeBank(bank().copy(slots=emptyList())) }
        val b=bank(); fail { RegistrationCodec.encodeBank(b.copy(slots=listOf(b.slots[0]!!.copy(parts=emptyList()))+List(7){null})) }
        fail { RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(b).replace("\"schema\":2","\"schema\":1")) }
    }
}

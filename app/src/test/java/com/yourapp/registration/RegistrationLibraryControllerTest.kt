package com.yourapp.yamahaarranger.registration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RegistrationLibraryControllerTest {
    private var clock=100L
    private fun snapshot()=RecallSnapshot(List(4) { PartSetup(Sf2VoiceRef("real.sf2",1025,24,"Guitar"),ExternalVoice(0,112,5,"User voice"),it!=1,70+it) },ReleaseSetup(127,false),135,3)
    private suspend fun library(store: AtomicBankStore): RegistrationLibraryController {
        val c=RegistrationLibraryController(store,{clock},{500})
        c.load();assertTrue(c.createBank("Test"));return c
    }
    @Test fun s1HoldAndArmRequired()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s1")))
        var calls=0
        assertFalse(c.arm(599,false));assertFalse(c.saveSlot(0,"A",{false}) { calls++;snapshot() })
        assertEquals(0,calls);assertTrue(c.arm(600,false));assertTrue(c.saveSlot(0,"A",{false}) { calls++;snapshot() });assertEquals(1,calls)
    }
    @Test fun s2FiveSecondTimeout()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s2")))
        c.arm(600,false);clock+=5000;c.tick()
        assertFalse(c.saveSlot(0,"A",{false}) { error("snapshot forbidden") })
    }
    @Test fun s3OverwriteRequiresSecondTapWithinTwoSeconds()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s3")))
        c.arm(600,false);c.saveSlot(0,"A",{false}) { snapshot() }
        c.arm(600,false);assertFalse(c.saveSlot(0,"B",{false}) { error("no snapshot") })
        clock+=2001;assertFalse(c.saveSlot(0,"B",{false}) { error("expired confirmation") })
        clock+=1;assertTrue(c.saveSlot(0,"B",{false}) { snapshot() });assertEquals("B",c.state.value.bank!!.slots[0]!!.name)
    }
    @Test fun s4UndoSaveAndTenSecondDeadline()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s4")))
        c.arm(600,false);c.saveSlot(0,"A",{false}) { snapshot() };assertTrue(c.undoSave(false));assertNull(c.state.value.bank!!.slots[0])
        c.arm(600,false);c.saveSlot(0,"A",{false}) { snapshot() };clock+=10000;assertFalse(c.undoSave(false))
    }
    @Test fun s5ActualSnapshotAndModeLayers()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s5")))
        for(mode in OutputMode.values()) {
            val i=mode.ordinal;c.options(mode,true,true);c.arm(600,false)
            val actual=snapshot().copy(tempoBpm=200,parts=snapshot().parts.mapIndexed { j,p->p.copy(enabled=j==3,volume=42+j) })
            assertTrue(c.saveSlot(i,"A",{false}) { actual })
            val slot=c.state.value.bank!!.slots[i]!!
            assertEquals(200,slot.tempoBpm);assertEquals(3,slot.transpose);assertEquals(ReleaseSetup(127,false),slot.release)
            assertTrue(slot.parts[3].enabled);assertEquals(45,slot.parts[3].volume)
            assertEquals(mode!=OutputMode.EXTERNAL,slot.parts[0].sf2!=null)
            assertEquals(mode!=OutputMode.INTERNAL,slot.parts[0].external!=null)
        }
    }
    @Test fun s6SaveHasNoPlaybackDependency()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-s6")))
        c.arm(600,false);c.saveSlot(0,"A",{false}) { snapshot() }
        assertNull(c.state.value.bank!!.slots[0]!!.parts[0].sf2)
        assertEquals(OutputMode.EXTERNAL,c.state.value.bank!!.slots[0]!!.outputMode)
    }
    @Test fun busyRejectsBeforeSnapshotAndArm()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-busy")))
        assertFalse(c.arm(600,true));c.arm(600,false)
        assertFalse(c.saveSlot(0,"A",{true}) { error("busy snapshot") });assertTrue(c.state.value.message.startsWith("WAIT"))
    }
    @Test fun busyAppearingDuringSnapshotRejectsWrite()=runBlocking {
        val c=library(AtomicBankStore(Files.createTempDirectory("reg-busy-race")));var busy=false
        c.arm(600,false);assertFalse(c.saveSlot(0,"A",{busy}) { busy=true;snapshot() });assertNull(c.state.value.bank!!.slots[0])
    }
    @Test fun restartLoadAndRecallExternalWithoutSf2()=runBlocking {
        val dir=Files.createTempDirectory("reg-restart");val c=library(AtomicBankStore(dir))
        c.arm(600,false);c.saveSlot(7,"Stored",{false}) { snapshot() }
        val restarted=RegistrationLibraryController(AtomicBankStore(dir));restarted.load()
        val target=restarted.state.value.bank!!.slots[7]!!
        val plan=RecallPlanner.plan(snapshot(),target,FreezeFlags(),emptyList(),true)
        assertEquals("Stored",target.name);assertEquals(4,plan.steps.filterIsInstance<RecallStep.External>().size)
        assertTrue(plan.steps.none { it is RecallStep.Sf2 })
    }
    @Test fun bankSelectionEmptyAndFreezePersist()=runBlocking {
        val dir=Files.createTempDirectory("reg-freeze");val c=library(AtomicBankStore(dir));val first=c.state.value.bank!!.id
        c.createBank("Second");assertNull(c.state.value.bank!!.slots[0]);assertTrue(c.selectBank(first))
        val flags=FreezeFlags(true,true,true,true,true,true);assertTrue(c.freeze(flags))
        val reopened=RegistrationLibraryController(AtomicBankStore(dir));reopened.load()
        assertEquals(first,reopened.state.value.bank!!.id);assertEquals(flags,reopened.state.value.manifest.freeze)
        val target=RegistrationSlot("Frozen",OutputMode.BOTH,snapshot().parts.map { it.copy(enabled=false,volume=1) },ReleaseSetup(0,true),20,-12,0)
        assertTrue(RecallPlanner.plan(snapshot(),target,flags,emptyList(),true).steps.isEmpty())
    }
    @Test fun backupRecoveryAndDamagedReadOnly()=runBlocking {
        val dir=Files.createTempDirectory("reg-recover");val c=library(AtomicBankStore(dir));val id=c.state.value.bank!!.id
        c.arm(600,false);c.saveSlot(0,"A",{false}) { snapshot() }
        Files.writeString(dir.resolve("bank-$id.json"),"corrupt")
        val recovered=RegistrationLibraryController(AtomicBankStore(dir));recovered.load()
        assertTrue(recovered.state.value.message.contains("RECOVERED_BACKUP"));assertFalse(Files.exists(dir.resolve("bank-$id.json")))
        Files.writeString(dir.resolve("bank-$id.json.bak"),"broken backup")
        val damaged=RegistrationLibraryController(AtomicBankStore(dir));damaged.load()
        assertTrue(id in damaged.state.value.readOnly);assertFalse(damaged.arm(600,false))
    }
    @Test fun failedSaveLeavesMemoryAndDiskUnchanged()=runBlocking {
        val dir=Files.createTempDirectory("reg-fail");val c=library(AtomicBankStore(dir))
        val id=c.state.value.bank!!.id;val path=dir.resolve("bank-$id.json");val bytes=Files.readAllBytes(path)
        val failing=RegistrationLibraryController(AtomicBankStore(dir) { stage,_ -> if(stage==WriteStage.TMP_FORCED) error("injected storage failure") },{clock})
        failing.load();failing.arm(600,false);assertFalse(failing.saveSlot(0,"A",{false}) { snapshot() })
        assertNull(failing.state.value.bank!!.slots[0]);assertArrayEquals(bytes,Files.readAllBytes(path));assertTrue(failing.state.value.message.startsWith("SAVE FAILED"))
    }
    @Test fun externalListIsExplicitValidatedAndRestored()=runBlocking {
        val dir=Files.createTempDirectory("reg-ext");val c=library(AtomicBankStore(dir))
        assertTrue(c.addExternal(ExternalVoice(127,112,126,"User")))
        try { c.addExternal(ExternalVoice(128,0,0,"Bad"));fail("invalid MSB") } catch(_:IllegalArgumentException) {}
        val next=RegistrationLibraryController(AtomicBankStore(dir));next.load()
        assertEquals(ExternalVoice(127,112,126,"User"),next.state.value.manifest.externalVoices.single().voice)
    }
    @Test fun saveExistingBankChangesOnlyBankFile()=runBlocking {
        val dir=Files.createTempDirectory("reg-single");val c=library(AtomicBankStore(dir))
        val manifest=Files.readAllBytes(dir.resolve("manifest.json"))
        c.arm(600,false);c.saveSlot(2,"A",{false}) { snapshot() }
        assertArrayEquals(manifest,Files.readAllBytes(dir.resolve("manifest.json")))
    }
    @Test fun defaultSourceAndFutureSchemaArePersistentAndReadOnly()=runBlocking {
        val dir=Files.createTempDirectory("reg-future");val c=library(AtomicBankStore(dir))
        assertTrue(c.defaultMode(OutputMode.INTERNAL));assertEquals(OutputMode.INTERNAL,c.state.value.mode)
        val restarted=RegistrationLibraryController(AtomicBankStore(dir));restarted.load()
        assertEquals(OutputMode.INTERNAL,restarted.state.value.mode)
        val id=restarted.state.value.bank!!.id
        val path=dir.resolve("bank-$id.json");val original=Files.readString(path)
        Files.writeString(path,original.replace("\"schema\":2","\"schema\":999"))
        val future=RegistrationLibraryController(AtomicBankStore(dir));future.load()
        assertTrue(id in future.state.value.readOnly);assertFalse(future.arm(600,false))
        assertTrue(Files.readString(path).contains("\"schema\":999"))
    }

    @Test fun externalInputRejectsOutOfRangeAndNeverGuessesNumbers() {
        assertEquals(ExternalVoice(0,112,5,"User"),externalVoiceInput("0","112","5"," User "))
        for(value in listOf("-1","128","invalid","1.5","")) {
            assertNull(externalVoiceInput(value,"0","0","Name"))
            assertNull(externalVoiceInput("0",value,"0","Name"))
            assertNull(externalVoiceInput("0","0",value,"Name"))
        }
        assertNull(externalVoiceInput("0","0","0"," "))
        assertEquals(127,externalVoiceInput("127","127","127","Name")!!.program)
    }

}

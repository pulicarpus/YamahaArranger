package com.yourapp.yamahaarranger.registration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Owns files and SAVE only. No MIDI, audio, style or recall dependencies. */
data class RegistrationLibraryState(
    val manifest: RegistrationManifest = RegistrationManifest(banks=emptyList(),activeBankId="",freeze=FreezeFlags(),externalVoices=emptyList()),
    val banks: List<RegistrationBank> = emptyList(), val readOnly: Set<String> = emptySet(),
    val manifestReadOnly: Boolean = false, val loaded: Boolean = false, val saving: Boolean = false,
    val save: RegistrationSaveState = RegistrationSaveState(), val undoUntil: Long = 0,
    val mode: OutputMode = OutputMode.EXTERNAL, val saveTempo: Boolean = false,
    val saveTranspose: Boolean = false, val message: String = "LOADING REG…"
) {
    val bank get() = banks.firstOrNull { it.id==manifest.activeBankId }
}
class RegistrationLibraryController(private val store: AtomicBankStore,
    private val now: () -> Long = { System.nanoTime()/1_000_000 },
    private val epoch: () -> Long = { System.currentTimeMillis() }) {
    private val lock=Mutex()
    private val mutable=MutableStateFlow(RegistrationLibraryState())
    val state: StateFlow<RegistrationLibraryState> = mutable
    private var undo: RegistrationBank? = null
    private fun message(text: String) { mutable.value=mutable.value.copy(message=text) }
    suspend fun load() = lock.withLock {
        try {
            val result=store.readManifest()
            val manifest=result.value ?: mutable.value.manifest
            val banks=mutableListOf<RegistrationBank>(); val readonly=mutableSetOf<String>();val notices=mutableListOf<String>()
            for(header in manifest.banks.sortedBy { it.order }) {
                val bank=store.readBank(header.id)
                banks+=bank.value ?: RegistrationBank(header.id,header.name,List(8) { null })
                if(bank.readOnly || bank.value==null) readonly+=header.id
                if(bank.status!=BankReadStatus.VALID) notices+="${header.name}: ${bank.status}"
            }
            if(result.status !in listOf(BankReadStatus.VALID,BankReadStatus.MISSING)) notices+="MANIFEST ${result.status}"
            val active=manifest.activeBankId.takeIf { id -> banks.any { it.id==id } } ?: banks.firstOrNull()?.id.orEmpty()
            mutable.value=RegistrationLibraryState(manifest=manifest.copy(activeBankId=active),banks=banks,
                readOnly=readonly,manifestReadOnly=result.readOnly,loaded=true,mode=manifest.defaultOutputMode,
                message=notices.joinToString("; ").ifEmpty { if(banks.isEmpty()) "CREATE A BANK" else "READY" })
        } catch(e: Exception) { mutable.value=mutable.value.copy(loaded=true,manifestReadOnly=true,message="LOAD FAILED: ${e.message}") }
    }
    fun options(mode: OutputMode, tempo: Boolean, transpose: Boolean) {
        if(!state.value.saving) mutable.value=state.value.copy(mode=mode,saveTempo=tempo,saveTranspose=transpose)
    }
    fun arm(heldMs: Long, busy: Boolean): Boolean {
        val s=state.value
        if(busy || s.saving) { message("WAIT: RECALLING / SAVING");return false }
        if(!s.loaded || s.manifestReadOnly || s.bank==null || s.bank!!.id in s.readOnly) { message("BANK READ-ONLY OR EMPTY");return false }
        val armed=s.save.arm(now(),heldMs)
        mutable.value=s.copy(save=armed,message=if(armed.armed(now())) "TAP SLOT TO SAVE" else s.message)
        return armed.armed(now())
    }
    fun tick() {
        val s=state.value;val t=now()
        if(s.save.armedUntil!=0L && !s.save.armed(t)) mutable.value=s.copy(save=RegistrationSaveState(),message="SAVE DISARMED")
        if(state.value.undoUntil!=0L && t>=state.value.undoUntil) { undo=null;mutable.value=state.value.copy(undoUntil=0) }
    }
    fun disarm() { mutable.value=state.value.copy(save=RegistrationSaveState()) }
    /** Snapshot callback runs on the caller's UI dispatcher, once, after the busy check. */
    suspend fun saveSlot(index: Int, name: String, busy: () -> Boolean,
        snapshot: suspend () -> RecallSnapshot): Boolean = lock.withLock {
        require(index in 0..7)
        val s=state.value
        if(busy() || s.saving) { message("WAIT: RECALLING / SAVING");return@withLock false }
        val bank=s.bank ?: return@withLock false
        if(s.manifestReadOnly || bank.id in s.readOnly) { message("BANK READ-ONLY");return@withLock false }
        val tap=s.save.tap(now(),index,bank.slots[index]!=null)
        mutable.value=s.copy(save=tap.first,message=if(!tap.second && tap.first.armed(now())) "TAP AGAIN TO OVERWRITE" else "SAVE DISARMED")
        if(!tap.second) return@withLock false
        mutable.value=state.value.copy(saving=true)
        try {
            val actual=snapshot()
            check(!busy()) { "WAIT: RECALLING" }
            val parts=actual.parts.map { p -> p.copy(sf2=if(s.mode==OutputMode.EXTERNAL) null else p.sf2,
                external=if(s.mode==OutputMode.INTERNAL) null else p.external) }
            val slot=RegistrationSlot(name.trim().take(24).ifEmpty { "REG ${index+1}" },s.mode,parts,actual.release,
                if(s.saveTempo) actual.tempoBpm else null,if(s.saveTranspose) actual.transpose else null,epoch())
            val updated=bank.copy(slots=bank.slots.mapIndexed { i,old -> if(i==index) slot else old })
            store.saveBank(updated)
            undo=bank
            mutable.value=state.value.copy(banks=s.banks.map { if(it.id==bank.id) updated else it },undoUntil=now()+10000,message="SAVED ${index+1}")
            true
        } catch(e: Exception) { message("SAVE FAILED: ${e.message}");false }
        finally { mutable.value=state.value.copy(saving=false,save=RegistrationSaveState()) }
    }
    suspend fun undoSave(busy: Boolean): Boolean = lock.withLock {
        val previous=undo
        if(busy || state.value.saving || previous==null || now()>=state.value.undoUntil) { message("UNDO UNAVAILABLE");return@withLock false }
        mutable.value=state.value.copy(saving=true)
        try {
            store.saveBank(previous)
            mutable.value=state.value.copy(banks=state.value.banks.map { if(it.id==previous.id) previous else it },undoUntil=0,message="SAVE UNDONE")
            undo=null;true
        } catch(e: Exception) { message("UNDO FAILED: ${e.message}");false }
        finally { mutable.value=state.value.copy(saving=false) }
    }
    private suspend fun manifest(value: RegistrationManifest): Boolean {
        if(state.value.manifestReadOnly) { message("MANIFEST READ-ONLY");return false }
        return try { store.saveManifest(value);mutable.value=state.value.copy(manifest=value,message="READY");true }
        catch(e: Exception) { message("PREFERENCES SAVE FAILED: ${e.message}");false }
    }
    suspend fun createBank(name: String) = lock.withLock {
        if(state.value.manifestReadOnly) { message("MANIFEST READ-ONLY");return@withLock false }
        val bank=RegistrationBank(UUID.randomUUID().toString(),name.trim().take(24).ifEmpty { "New bank" },List(8) { null })
        try {
            store.saveBank(bank) // manifest comes second; orphan bank remains recoverable on failure
            val m=state.value.manifest.copy(banks=state.value.manifest.banks+BankHeader(bank.id,bank.name,state.value.banks.size),activeBankId=bank.id)
            if(!manifest(m)) return@withLock false
            mutable.value=state.value.copy(banks=state.value.banks+bank,save=RegistrationSaveState());true
        } catch(e: Exception) { message("CREATE FAILED: ${e.message}");false }
    }
    suspend fun selectBank(id: String) = lock.withLock {
        if(state.value.banks.none { it.id==id }) return@withLock false
        disarm();manifest(state.value.manifest.copy(activeBankId=id))
    }
    suspend fun freeze(flags: FreezeFlags) = lock.withLock { manifest(state.value.manifest.copy(freeze=flags)) }
    suspend fun defaultMode(mode: OutputMode) = lock.withLock {
        val ok=manifest(state.value.manifest.copy(defaultOutputMode=mode))
        if(ok) mutable.value=state.value.copy(mode=mode)
        ok
    }
    suspend fun addExternal(voice: ExternalVoice) = lock.withLock {
        require(voice.msb in 0..127 && voice.lsb in 0..127 && voice.program in 0..127 && voice.label.isNotBlank())
        manifest(state.value.manifest.copy(externalVoices=state.value.manifest.externalVoices+
            ExternalVoiceEntry(UUID.randomUUID().toString(),voice.copy(label=voice.label.take(24)))))
    }
}

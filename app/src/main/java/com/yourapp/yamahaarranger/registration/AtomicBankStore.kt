package com.yourapp.yamahaarranger.registration

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BankReadStatus { VALID, RECOVERED_BACKUP, REBUILT, MISSING, DAMAGED, NEWER_SCHEMA }
data class BankReadResult<T>(val status: BankReadStatus, val value: T?, val writeSeq: Long = 0) {
    val readOnly: Boolean get() = status == BankReadStatus.DAMAGED || status == BankReadStatus.NEWER_SCHEMA
}
internal enum class WriteStage { TMP_FORCED, MOVED, BEFORE_VERIFY }

/** Dormant component: caller supplies the registration directory; nothing is wired to runtime.
 * All disk access runs on IO with one shared serial lock per directory, including recovery.
 * Unsupported atomic rename fails closed; no non-atomic replacement fallback.
 */
class AtomicBankStore internal constructor(directory: Path, private val fault: (WriteStage, Path) -> Unit) {
    constructor(directory: Path) : this(directory, { _, _ -> })
    private val root = directory.toAbsolutePath().normalize()
    private val lock = locks.computeIfAbsent(root) { Mutex() }
    companion object { private val locks = ConcurrentHashMap<Path, Mutex>() }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
    private fun bankPath(id: String): Path { RegistrationCodec.validId(id); return root.resolve("bank-$id.json") }
    private fun backup(p: Path) = p.resolveSibling(p.fileName.toString()+".bak")
    private fun tmp(p: Path) = p.resolveSibling(p.fileName.toString()+".tmp")
    private fun cleanupTmp(p: Path) {
        Files.deleteIfExists(tmp(p))
        Files.deleteIfExists(tmp(backup(p)))
    }
    private fun text(p: Path): String {
        require(Files.size(p) <= 4_000_000) { "File too large" }
        return String(Files.readAllBytes(p), Charsets.UTF_8)
    }
    private fun quarantined(p: Path): Boolean {
        val dir=root.resolve("quarantine")
        return Files.isDirectory(dir) && Files.list(dir).use { stream -> stream.anyMatch { it.fileName.toString().startsWith(p.fileName.toString()+".") } }
    }
    private fun quarantine(p: Path) {
        if(!Files.exists(p)) return
        val q=Files.createDirectories(root.resolve("quarantine")).resolve(p.fileName.toString()+"."+UUID.randomUUID())
        Files.move(p,q,ATOMIC_MOVE)
    }
    private fun <T> read(p: Path, decode: (String) -> RegistrationEnvelope<T>): BankReadResult<T> {
        val bak=backup(p)
        if(Files.exists(p)) {
            try { val e=decode(text(p)); cleanupTmp(p); return BankReadResult(BankReadStatus.VALID,e.value,e.writeSeq) }
            catch(e: FutureSchemaException) { return BankReadResult(BankReadStatus.NEWER_SCHEMA,null) }
            catch(_: IllegalArgumentException) { /* Try backup without replacing primary. */ }
            catch(_: IllegalStateException) { /* Strict codec type mismatch. */ }
        }
        if(Files.exists(bak)) {
            try { val e=decode(text(bak)); quarantine(p); cleanupTmp(p); return BankReadResult(BankReadStatus.RECOVERED_BACKUP,e.value,e.writeSeq) }
            catch(e: FutureSchemaException) { return BankReadResult(BankReadStatus.NEWER_SCHEMA,null) }
            catch(_: IllegalArgumentException) { }
            catch(_: IllegalStateException) { }
        }
        val damaged=Files.exists(p)||Files.exists(bak)||quarantined(p)
        quarantine(p); quarantine(bak)
        cleanupTmp(p)
        return BankReadResult(if(damaged) BankReadStatus.DAMAGED else BankReadStatus.MISSING,null)
    }
    private fun <T> write(p: Path, value: T, decode: (String) -> RegistrationEnvelope<T>, encode: (T,Long,Long) -> String, rebuildManifest: Boolean = false) {
        Files.createDirectories(root)
        val previous=read(p,decode)
        check(!previous.readOnly || (rebuildManifest && previous.status == BankReadStatus.DAMAGED)) { "Read-only ${previous.status}: ${p.fileName}" }
        val seq=Math.addExact(previous.writeSeq,1)
        val serialized=encode(value,seq,System.currentTimeMillis())
        decode(serialized) // Structural validation before touching disk.
        force(tmp(p),serialized.toByteArray(Charsets.UTF_8))
        fault(WriteStage.TMP_FORCED,p)
        if(Files.exists(p)) {
            // read() already validated it; copy, never move the valid primary.
            force(tmp(backup(p)),Files.readAllBytes(p))
            Files.move(tmp(backup(p)),backup(p),ATOMIC_MOVE,REPLACE_EXISTING)
        }
        Files.move(tmp(p),p,ATOMIC_MOVE,REPLACE_EXISTING)
        fault(WriteStage.MOVED,p)
        try {
            fault(WriteStage.BEFORE_VERIFY,p)
            val actual=decode(text(p))
            check(actual.writeSeq==seq && text(p)==serialized) { "Read-back mismatch" }
        } catch(e: Exception) {
            quarantine(p)
            val bak=backup(p)
            if(Files.exists(bak)) {
                decode(text(bak))
                force(tmp(p),Files.readAllBytes(bak))
                Files.move(tmp(p),p,ATOMIC_MOVE,REPLACE_EXISTING)
            }
            throw IllegalStateException("SAVE FAILED: ${p.fileName}",e)
        }
    }
    private fun force(p: Path, bytes: ByteArray) {
        FileChannel.open(p,CREATE,TRUNCATE_EXISTING,WRITE).use { channel ->
            val b=ByteBuffer.wrap(bytes); while(b.hasRemaining()) channel.write(b); channel.force(true)
        }
    }
    suspend fun readBank(id: String): BankReadResult<RegistrationBank> = io { readBankNow(id) }
    private fun readBankNow(id: String): BankReadResult<RegistrationBank> = read(bankPath(id)) { raw ->
        RegistrationCodec.decodeBank(raw).also { require(it.value.id==id) { "Bank filename/ID mismatch" } }
    }
    suspend fun saveBank(bank: RegistrationBank) = io {
        write(bankPath(bank.id),bank,{ raw -> RegistrationCodec.decodeBank(raw).also { require(it.value.id == bank.id) } },RegistrationCodec::encodeBank)
    }
    suspend fun saveManifest(manifest: RegistrationManifest) = io {
        require(manifest.banks.all { readBankNow(it.id).value != null }) { "Save banks before manifest" }
        write(root.resolve("manifest.json"),manifest,RegistrationCodec::decodeManifest,RegistrationCodec::encodeManifest, rebuildManifest = true)
    }
    suspend fun readManifest(): BankReadResult<RegistrationManifest> = io {
        val m=read(root.resolve("manifest.json"),RegistrationCodec::decodeManifest)
        if(m.status==BankReadStatus.NEWER_SCHEMA) return@io m
        val banks=inventory()
        if(m.value==null) {
            if(banks.isEmpty() && m.status==BankReadStatus.MISSING) return@io m
            return@io BankReadResult(BankReadStatus.REBUILT,RegistrationManifest(banks=banks,activeBankId=banks.firstOrNull()?.id.orEmpty(),freeze=FreezeFlags(),externalVoices=emptyList()))
        }
        val orphan=banks.filter { b -> m.value.banks.none { it.id==b.id } }
        if(orphan.isEmpty()) m else m.copy(value=m.value.copy(banks=m.value.banks+orphan.mapIndexed { i,b -> b.copy(order=m.value.banks.size+i) }))
    }
    private fun inventory(): List<BankHeader> {
        if(!Files.isDirectory(root)) return emptyList()
        val ids=Files.list(root).use { paths -> paths.map { it.fileName.toString() }
            .filter { it.startsWith("bank-") && (it.endsWith(".json")||it.endsWith(".json.bak")) }
            .map { it.removePrefix("bank-").removeSuffix(".bak").removeSuffix(".json") }.distinct().sorted().toArray().map { it as String } }
        return ids.mapNotNull { id -> readBankNow(id).value?.let { BankHeader(it.id,"Recovered bank",0) } }.mapIndexed { i,b -> b.copy(order=i) }
    }
}

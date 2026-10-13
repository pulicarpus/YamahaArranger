package com.yourapp.yamahaarranger.registration

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

enum class VoiceResult { APPLIED, SUPERSEDED, FAILED, TIMEOUT }
enum class RecallCompletion { COMPLETED, PARTIAL, SUPERSEDED, FAILED }
data class StepResult(val step: RecallStep, val result: VoiceResult)
data class RecallReport(val generation: Long, val completion: RecallCompletion, val results: List<StepResult>, val issues: List<RecallIssue>)
interface RegistrationPort {
    suspend fun snapshot(): RecallSnapshot
    suspend fun catalog(): List<Sf2VoiceRef>
    fun midiOutActive(): Boolean
    suspend fun applySf2(part: Part, voice: Sf2VoiceRef, isCurrent: () -> Boolean): VoiceResult
    /** Timeout cannot cancel a running JNI call: drain it before another snapshot or SF2 step. */
    suspend fun awaitSf2Idle()
    suspend fun apply(step: RecallStep, isCurrent: () -> Boolean): VoiceResult
}

/** One serial consumer; no engine, MIDI or style dependencies. No automatic recall on construction. */
class RegistrationCoordinator(scope: CoroutineScope, private val port: RegistrationPort,
    private val nanoTime: () -> Long = System::nanoTime) {
    private data class Request(val generation: Long, val slot: RegistrationSlot, val freeze: FreezeFlags,
        val completion: CompletableDeferred<RecallReport>)
    private val generation=AtomicLong()
    private val busyState=MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState
    private var lastKey: String?=null
    private var lastTime=Long.MIN_VALUE
    private var lastResult: Deferred<RecallReport>?=null
    private fun superseded(r: Request) { r.completion.complete(RecallReport(r.generation,RecallCompletion.SUPERSEDED,emptyList(),emptyList())) }
    private val requests=Channel<Request>(Channel.CONFLATED,onUndeliveredElement=::superseded)
    private val worker=scope.launch {
        try {
            for(r in requests) {
                val results=mutableListOf<StepResult>(); val issues=mutableListOf<RecallIssue>()
                var completion=RecallCompletion.COMPLETED
                try {
                    port.awaitSf2Idle()
                    val snapshot=port.snapshot()
                    val catalog=if(r.slot.outputMode==OutputMode.EXTERNAL || r.freeze.voices) emptyList() else port.catalog()
                    val plan=RecallPlanner.plan(snapshot,r.slot,r.freeze,catalog,port.midiOutActive())
                    issues.addAll(plan.issues)
                    for(step in plan.steps) {
                        if(r.generation != generation.get()) { completion=RecallCompletion.SUPERSEDED; break }
                        val valid={ r.generation==generation.get() && workerActive() }
                        val result=if(step is RecallStep.Sf2) {
                            if(port.snapshot().voiceRequests[step.part.channel] != snapshot.voiceRequests[step.part.channel]) VoiceResult.SUPERSEDED
                            else port.applySf2(step.part,step.voice,valid)
                        } else port.apply(step,valid)
                        results.add(StepResult(step,result))
                        if(step is RecallStep.Sf2) withContext(NonCancellable) { port.awaitSf2Idle() }
                        yield()
                    }
                    if(r.generation != generation.get()) completion=RecallCompletion.SUPERSEDED
                    else if(issues.isNotEmpty() || results.any { it.result != VoiceResult.APPLIED }) completion=RecallCompletion.PARTIAL
                } catch(_: Exception) { completion=if(r.generation==generation.get()) RecallCompletion.FAILED else RecallCompletion.SUPERSEDED }
                finally {
                    try { withContext(NonCancellable) { port.awaitSf2Idle() } }
                    catch(_: Exception) { completion=RecallCompletion.FAILED }
                    r.completion.complete(RecallReport(r.generation,completion,results,issues))
                    synchronized(this@RegistrationCoordinator) { if(r.generation==generation.get()) busyState.value=false }
                }
            }
        } finally { requests.cancel(); busyState.value=false }
    }
    private fun workerActive(): Boolean = worker.isActive
    @Synchronized fun recall(key: String, slot: RegistrationSlot, freeze: FreezeFlags = FreezeFlags()): Deferred<RecallReport> {
        val now=nanoTime()
        if(key==lastKey && now-lastTime in 0 until 150_000_000L) return lastResult!!
        val done=CompletableDeferred<RecallReport>(); val r=Request(generation.incrementAndGet(),slot.copy(parts=slot.parts.toList()),freeze,done)
        busyState.value=true; lastKey=key; lastTime=now; lastResult=done
        if(!requests.trySend(r).isSuccess) { superseded(r); busyState.value=false }
        return done
    }
    @Synchronized fun cancel() { generation.incrementAndGet(); requests.cancel(); worker.cancel() }
}

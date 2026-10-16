package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import com.yourapp.yamahaarranger.style.StylePartModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicLong

class StyleSequencer(private val audioEngine: AudioEngineManager, private val midiInputManager: MidiInputManager, private val scope: CoroutineScope) {
    private var playbackJob:Job?=null
    private var r23LastJob:Job?=null
    fun setDrumABMode(resolved:Boolean):Boolean = lifecycle {
        if(playbackJob?.isActive==true || r23LastJob?.isCompleted==false)return@lifecycle false
        if(!audioEngine.setDrumABMode(resolved))return@lifecycle false
        appliedChannelStates.remove(8);appliedChannelStates.remove(9);lastAppliedSection=""
        true
    }
    // Diagnostic admission only; clock/scheduler/transport bodies are unchanged.
    fun setDrumPunch(mode:Int):Boolean = lifecycle {
        if(playbackJob?.isActive==true || r23LastJob?.isCompleted==false)return@lifecycle false
        audioEngine.setDrumPunch(mode)
    }
    private fun applyDrumAB(section:StyleSectionModel,ch:Int,msb:Int?,lsb:Int?,pc:Int,source:String) {
        val kit=DrumKitShadowRuntime.resolveAB(section,ch,msb,lsb,pc,source)?.kit
        // An unresolved request invalidates the prior binding; no implicit Prime fallback.
        audioEngine.bindDrumAB(ch,kit?.pdta.orEmpty(),kit?.bank?:-1,kit?.pc?:-1,section.name)
    }
    // Musical position is integrated at each tempo change, never re-scaled from
    // the playback anchor. This monitor protects clock arithmetic only: no
    // native/MIDI calls, callbacks, delay or lifecycle monitor acquisition.
    private val musicalClockLock = Any()
    private data class TempoSegment(val nanos: Long, val quarters: Double, val bpm: Int)
    private class TempoClock(val anchor: Long, bpm: Int) {
        val segments = arrayListOf(TempoSegment(anchor, 0.0, bpm))
    }
    private var tempoClock: TempoClock? = null
    private var tempoRevision = 0L
    @Volatile var tempoBpm:Int=120
        set(value) {
            synchronized(musicalClockLock) {
                val now = System.nanoTime()
                val clock = ensureTempoClock()
                val bpm = value.coerceIn(20, 280)
                if (masterClockStartedAtNanos != 0L && clock.segments.last().bpm != bpm) {
                    val previous = clock.segments.last()
                    val quarters = previous.quarters +
                        (now - previous.nanos).coerceAtLeast(0L) * previous.bpm / 60_000_000_000.0
                    clock.segments.add(TempoSegment(now, quarters, bpm))
                    tempoRevision++
                }
                field = value
            }
            // A suspended worker recalculates its event and transition deadline.
            wakeMusicalClock()
        }

    private fun ensureTempoClock(): TempoClock {
        val anchor = masterClockStartedAtNanos
        val existing = tempoClock
        if (existing != null && existing.anchor == anchor) return existing
        return TempoClock(anchor, tempoBpm.coerceIn(20, 280)).also { tempoClock = it }
    }

    private fun tickDeadlineNanos(tick: Long, ppq: Int,
        clock: TempoClock = synchronized(musicalClockLock) { ensureTempoClock() }): Long = synchronized(musicalClockLock) {
        val resolution = ppq.coerceAtLeast(1)
        val quarters = tick.toDouble() / resolution
        // Retain the original mapping for already admitted/past ticks as well
        // as the latest segment for future ticks (including pending NextBar).
        var lo = 0
        var hi = clock.segments.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (clock.segments[mid].quarters <= quarters) lo = mid else hi = mid - 1
        }
        val segment = clock.segments[lo]
        segment.nanos + ((tick - segment.quarters * resolution) *
            (60_000_000_000.0 / (segment.bpm.toDouble() * resolution))).toLong()
    }
    var currentChord:DetectedChord?=null
        set(value){
            f12Timing.record(7,f12Timing.begin(),0,0,true,tick=value?.rootNote?:-1)
            val old=field
            field=value
            when {
                old==null&&value!=null -> {
                    // The first detected chord must immediately retarget notes that
                    // started while the arranger was still in the no-chord state.
                    handleChordChange(value,old)
                    com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 FIRST CHORD: " + value.rootNote + " " + value.quality)
                }
                old!=null&&value!=null&&old!=value -> handleChordChange(value,old)
                old!=null&&value==null -> handleNoChord()
            }
        }
    private var loopCount=0
    @Volatile private var barClockStartedAtNanos: Long = 0L
    private var voiceMap:Map<Int,String> = emptyMap()
    private var lastAppliedSection=""
    private var lockedChannels:Set<Int> = emptySet()
    private val channelOverrides = mutableMapOf<Int, StyleChannelOverride>()
    
    private data class AppliedChannelState(
        val program: Int, val bank: Int, val volume: Int, val pan: Int,
        val expression: Int, val reverbSend: Int, val chorusSend: Int
    )
    private val appliedChannelStates = mutableMapOf<Int, AppliedChannelState>()

    private data class MixerState(
        var volume: Int = 127,
        var pan: Int = 64,
        var expression: Int = 127,
        var reverbSend: Int = 40,
        var chorusSend: Int = 0
    )
    private val mixerStates = Array(16) { MixerState() }
    private var styleBusTrim = 127
    fun setStyleBusTrim(value: Int) {
        styleBusTrim = value.coerceIn(0,127)
        for (channel in 8..15) {
            val state = mixerStates[channel]
            val effective = effectiveExpression(channel, state.expression)
            audioEngine.setChannelExpression(channel, effective)
            appliedChannelStates[channel]?.let { appliedChannelStates[channel] = it.copy(expression = effective) }
        }
    }
    private fun effectiveExpression(channel: Int, source: Int): Int =
        styleTrim(styleTrim(source, channelOverrides[channel]?.expression ?: 127), if(channel in 8..15) styleBusTrim else 127)

    private data class ActiveTransposedNote(
        val sourceChannel:Int,
        val sourceNote:Int,
        var destinationChannel:Int,
        var outputNote:Int,
        val velocity:Int,
        var policy:CasmPolicyModel,
        val policies:List<CasmPolicyModel>,
        val sourcePart:String, val sourceSection:String, val sourceBank:Int,
        val sourceHeaderPC:Int, val sourceTick:Long, val onId:Long
    )
    private val partPresence = StylePartPresence()
    fun partPresenceReport(style:com.yourapp.yamahaarranger.style.ParsedStyle):String = partPresence.report(style)
    private val chordTrace = ChordChangeDiagnostic()
    fun armChordDiagnostic() { audioEngine.armChordDiagnostic(); chordTrace.arm(); f12Timing.arm() }
    fun stopChordDiagnostic() { f12Timing.stop(); chordTrace.stop(); audioEngine.stopChordDiagnostic() }
    fun chordDiagnosticReport(): String = chordTrace.report()
    fun compactChordDiagnosticReport(): String = f12Timing.report()+chordTrace.compactReport()

    // One monitor for owner identity + complete NOTE_OFF/NOTE_ON lifecycle.
    // Tick waiting and the whole chord-change loop remain outside this lock.
    private val noteLifecycleLock=Any()
    // F12_TIMING_BEGIN
    private val f12Timing=F12Timing()
    private inline fun <T> lifecycle(block:()->T):T {
        val requested=f12Timing.begin()
        return synchronized(noteLifecycleLock) {
            val acquired=if(requested!=0L) System.nanoTime() else 0L
            try { block() } finally { f12Timing.finish(0,requested,acquired) }
        }
    }
    // F12_TIMING_END
    private val activeTransposedNotes=mutableMapOf<String,ActiveTransposedNote>()
    private fun activeNoteSnapshot()=lifecycle { activeTransposedNotes.values.toList() }
    private fun activeNoteCount()=lifecycle { activeTransposedNotes.size }

    // Long-running diagnostic recorder for String/CASM lifecycle.
    private val traceSequence = AtomicLong(0L)
    private val stringTrace = ArrayDeque<String>()
    private val stringTraceLock = Any()
    private val stringTraceMaxEntries = 12000
    @Volatile private var stringTraceEnabled = false

    private fun stringTrace(message:String){
        if(!stringTraceEnabled)return
        val n=traceSequence.incrementAndGet()
        val line="#${n.toString().padStart(5,'0')} t=${System.currentTimeMillis()} $message"
        synchronized(stringTraceLock){
            if(stringTrace.size>=stringTraceMaxEntries)stringTrace.removeFirst()
            stringTrace.addLast(line)
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 $line")
    }

    fun setStringTraceEnabled(enabled:Boolean){
        stringTraceEnabled=enabled
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 STRING TRACE ${if(enabled) "ON" else "OFF"}")
    }

    fun clearStringTrace(){
        synchronized(stringTraceLock){stringTrace.clear()}
        traceSequence.set(0L)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 STRING TRACE CLEARED")
    }

    fun dumpStringTrace(){
        val snapshot=synchronized(stringTraceLock){stringTrace.toList()}
        val fullText = buildString {
            appendLine("========== YAMAHA ARRANGER FULL STRING/CASM TRACE entries=${snapshot.size} active=${activeNoteCount()} chord=${currentChord?.rootNote}/${currentChord?.quality} ==========")
            snapshot.forEach { appendLine(it) }
            appendLine("========== TRACE END ==========")
        }
        com.yourapp.yamahaarranger.ui.DebugLog.setLongText(fullText)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 FULL TRACE READY: ${snapshot.size} entries — tap SEND LOG to send the complete trace")
        com.yourapp.yamahaarranger.ui.DebugLog.add("========== STRING TRACE BEGIN entries=${snapshot.size} active=${activeNoteCount()} chord=${currentChord?.rootNote}/${currentChord?.quality} ==========")
        snapshot.chunked(80).forEachIndexed{index,chunk->
            com.yourapp.yamahaarranger.ui.DebugLog.add("🔬 TRACE CHUNK ${index+1}/${(snapshot.size+79)/80}")
            chunk.forEach{com.yourapp.yamahaarranger.ui.DebugLog.add(it)}
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add("========== STRING TRACE END entries=${snapshot.size} active=${activeNoteCount()} ==========")
    }


    fun setLockedChannels(channels:Set<Int>){lockedChannels=channels;com.yourapp.yamahaarranger.ui.DebugLog.add("🔒 Locked channels updated: $channels")}
    fun setChannelOverride(channel:Int, override:StyleChannelOverride){com.yourapp.yamahaarranger.audio.PcmSessionControls.observeMute(channel,override.muted);channelOverrides[channel]=override; appliedChannelStates.remove(channel);com.yourapp.yamahaarranger.ui.DebugLog.add("🎚 STYLE CH$channel: vol=${override.volume} prog=${override.program ?: "AUTO"} bank=${override.bank ?: "AUTO"} tr=${override.transpose} mute=${override.muted}")}
    fun channelOverride(channel:Int):StyleChannelOverride = channelOverrides[channel] ?: StyleChannelOverride()
    fun setChannelVolume(channel:Int, volume:Int){
        if(channel !in 4..15) return
        val v=volume.coerceIn(0,127)
        val old=channelOverride(channel)
        setChannelOverride(channel, old.copy(volume=v))
        applyEffectiveMixer(channel)
    }
    fun setChannelMute(channel:Int, muted:Boolean){
        if(channel !in 4..15) return
        val old=channelOverride(channel)
        setChannelOverride(channel, old.copy(muted=muted))
        applyEffectiveMixer(channel)
    }
    fun setChannelMixer(channel:Int, volume:Int, pan:Int?, expression:Int, reverbSend:Int?, chorusSend:Int?){
        if(channel !in 4..15) return
        val old=channelOverride(channel)
        val v=volume.coerceIn(0,127)
        val p=pan?.coerceIn(0,127)
        val e=expression.coerceIn(0,127)
        val r=reverbSend?.coerceIn(0,127)
        val ch=chorusSend?.coerceIn(0,127)
        val state = mixerStates[channel]
        val next=old.copy(volume=v,
            pan=if(p != null && p != (old.pan ?: state.pan)) p else old.pan,
            expression=e,
            reverbSend=if(r != null && r != (old.reverbSend ?: state.reverbSend)) r else old.reverbSend,
            chorusSend=if(ch != null && ch != (old.chorusSend ?: state.chorusSend)) ch else old.chorusSend)
        setChannelOverride(channel,next)
        applyEffectiveMixer(channel)
    }
    fun setChannelProgramOverride(channel:Int, program:Int, bank:Int){
        val old=channelOverride(channel)
        setChannelOverride(channel, old.copy(program=program.coerceIn(0,127), bank=bank.coerceIn(0,16383)))
    }
    fun setVoiceMap(vm:Map<Int,String>){partPresence.reset();voiceMap=vm;lastAppliedSection="";com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 Legacy VoiceMap received: ${vm.size}; CASM policy takes precedence")}
    private data class PendingSection(
        val section: StyleSectionModel,
        val ppq: Int,
        val loopLimit: Int,
        val onComplete: (() -> Unit)?,
        val onStart: (() -> Unit)? = null
    )

    private data class PendingTransition @JvmOverloads constructor(
        val sections: List<PendingSection>,
        val startTick: Long,
        val nextBarBoundary: Boolean = false
    )

    private val pendingSectionQueue = java.util.ArrayDeque<PendingSection>()
    // Broadcast: playback and a Brain quantization wait must both wake.
    private val musicalClockRevision = MutableStateFlow(0L)
    private fun wakeMusicalClock() {
        // A Main.immediate/Unconfined continuation may resume inline. Emit
        // after releasing the clock monitor, never under that monitor.
        musicalClockRevision.update { it + 1L }
    }
    private suspend fun waitForClockChange(revision: Long, waitMs: Long) {
        withTimeoutOrNull(waitMs) { musicalClockRevision.first { it != revision } }
    }
    @Volatile private var pendingTransition: PendingTransition? = null
    @Volatile private var masterClockStartedAtNanos: Long = 0L
    @Volatile private var masterTimelineTick: Long = 0L

    /**
     * Seamless section request.
     *
     * While Style is running, a new section is queued instead of cancelling
     * the current playback coroutine. The same musical clock therefore spans
     * Main -> Fill -> Main without an all-notes-off or clock reset.
     */
    /**
     * Queue a musical transition without stopping the current section.
     *
     * The transition starts on the next bar of the SAME master clock.
     * The current Main is allowed to finish exactly at that bar boundary,
     * then Fill and the target Main are consumed consecutively.
     */
    fun queueSeamlessTransition(
        sections: List<Pair<StyleSectionModel, Int>>,
        ppq: Int,
        numerator: Int,
        denominator: Int,
        onFinalSectionStarted: (() -> Unit)? = null
    ) {
        if (sections.isEmpty()) return
        if (playbackJob?.isActive != true) {
            val first = sections.first()
            startPlayback(first.first, ppq, first.second, null, seamless = true)
            if (sections.size > 1) {
                pendingTransition = PendingTransition(
                    sections.drop(1).map { PendingSection(it.first, ppq, it.second, null) },
                    startTick = first.first.lengthTicks.toLong().coerceAtLeast(0L)
                )
            }
            return
        }

        val currentTick = currentMasterTick(ppq)

        // Immediate/phase-continuous mode: the button press becomes the
        // transition point on the SAME master clock. The fill is not restarted
        // from tick 0; it is phase-aligned to the beat where the request lands.
        val queue = sections.mapIndexed { index, item ->
            val onStart = if (index == sections.lastIndex) onFinalSectionStarted else null
            PendingSection(item.first, ppq, item.second, null, onStart)
        }
        pendingTransition = PendingTransition(queue, currentTick)
        pendingSectionQueue.clear()
        wakeMusicalClock()

        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "🎼 TRANSITION QUEUED: " +
                queue.joinToString(" → ") +
                " at current masterTick=$currentTick (phase continuous)"
        )
    }

    // Admit a known musical boundary now, rather than racing the worker
    // with a separate Brain timer at that boundary. Existing cleanup/ownership
    // and natural successor handling remain the outer loop's responsibility.
    fun queueScheduledTransition(
        sections: List<Pair<StyleSectionModel, Int>>, ppq: Int, startTick: Long,
        onFirstSectionStarted: (() -> Unit)? = null,
        onFinalSectionStarted: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        if (sections.isEmpty() || startTick < 0L || playbackJob?.isActive != true) return false
        val queue = sections.mapIndexed { index, item ->
            PendingSection(item.first, ppq, item.second,
                if (index == sections.lastIndex) onComplete else null,
                { if (index == 0) onFirstSectionStarted?.invoke()
                  if (index == sections.lastIndex) onFinalSectionStarted?.invoke() })
        }
        pendingSectionQueue.clear()
        pendingTransition = PendingTransition(queue, startTick, nextBarBoundary = true)
        wakeMusicalClock()
        return true
    }

    // Narrow NextBar request: outside the unresolved first-beat window.
    // Existing Immediate/AutoFill and first-beat request paths remain unchanged.
    fun queueNextBarMain(section: StyleSectionModel, ppq: Int, numerator: Int,
        denominator: Int, onStarted: (() -> Unit)? = null): Boolean {
        if (playbackJob?.isActive != true || ppq <= 0) return false
        val beatTicks = ppq.toLong() * 4L / denominator.coerceAtLeast(1)
        val barTicks = beatTicks * numerator.coerceAtLeast(1)
        if (beatTicks <= 0L || barTicks <= 0L) return false
        val tick = currentMasterTick(ppq)
        if (tick % barTicks < beatTicks) return false
        pendingSectionQueue.clear()
        pendingTransition = PendingTransition(
            listOf(PendingSection(section, ppq, -1, null, onStarted)),
            (tick / barTicks + 1L) * barTicks, nextBarBoundary = true)
        wakeMusicalClock()
        return true
    }

    private suspend fun waitForNextBarOrEvent(absoluteTick: Long, ppq: Int, clock: TempoClock): Long? {
        while (true) {
            val revision: Long
            val boundaryTick: Long?
            val targetNanos: Long
            val boundaryNanos: Long?
            val now: Long
            synchronized(musicalClockLock) {
                revision = musicalClockRevision.value
                boundaryTick = pendingTransition?.startTick
                targetNanos = tickDeadlineNanos(absoluteTick, ppq, clock)
                boundaryNanos = boundaryTick?.let { tickDeadlineNanos(it, ppq, clock) }
                now = System.nanoTime()
            }
            if (boundaryNanos != null && now >= boundaryNanos) return boundaryTick
            if (now >= targetNanos) return null
            val wakeAt = if (boundaryNanos == null) targetNanos else minOf(targetNanos, boundaryNanos)
            val waitMs = (wakeAt - now) / 1_000_000L
            if (waitMs > 0L) waitForClockChange(revision, waitMs) else {
                // Preserve baseline's sub-millisecond event dispatch tolerance.
                // Future bar boundaries still require a suspended wait.
                if (boundaryNanos != null && boundaryNanos <= targetNanos) delay(1L)
                else return null
            }
        }
    }

    private fun currentMasterTick(ppq: Int): Long = synchronized(musicalClockLock) {
        if (masterClockStartedAtNanos == 0L || ppq <= 0) return@synchronized masterTimelineTick
        val segment = ensureTempoClock().segments.last()
        val quarters = segment.quarters +
            (System.nanoTime() - segment.nanos).coerceAtLeast(0L) * segment.bpm / 60_000_000_000.0
        (quarters * ppq).toLong().coerceAtLeast(masterTimelineTick)
    }

    /** Queue sections after the currently playing section finishes naturally. */
    fun queueAfterCurrentSection(
        sections: List<Pair<StyleSectionModel, Int>>,
        ppq: Int,
        onFinalSectionStarted: (() -> Unit)? = null
    ) {
        if (sections.isEmpty()) return
        pendingTransition = null
        pendingSectionQueue.clear()
        sections.forEachIndexed { index, (section, loopLimit) ->
            val onStart = if (index == sections.lastIndex) onFinalSectionStarted else null
            pendingSectionQueue.addLast(PendingSection(section, ppq, loopLimit, null, onStart))
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "🎼 QUEUE AFTER CURRENT: " +
                sections.joinToString(" → ") { it.first.name } +
                " (active section is preserved)"
        )
    }

    fun playSeamless(section: StyleSectionModel, ppq: Int, loopLimit: Int = -1, onComplete: (() -> Unit)? = null) {
        if (playbackJob?.isActive == true) {
            pendingTransition = null
            pendingSectionQueue.clear()
            pendingSectionQueue.addLast(PendingSection(section, ppq, loopLimit, onComplete))
            com.yourapp.yamahaarranger.ui.DebugLog.add(
                "🎼 QUEUE seamless " + section.name + " (no cancel/no allNotesOff)"
            )
            return
        }
        startPlayback(section, ppq, loopLimit, onComplete, seamless = true)
    }

    private fun startPlayback(
        section: StyleSectionModel,
        ppq: Int,
        loopLimit: Int,
        onComplete: (() -> Unit)?,
        seamless: Boolean
    ) {
        if (!seamless) clearStringTrace()
        pendingSectionQueue.clear()
        pendingTransition = null
        synchronized(musicalClockLock) {
            masterClockStartedAtNanos = System.nanoTime()
            tempoClock = null
            ensureTempoClock()
        }
        masterTimelineTick = 0L
        barClockStartedAtNanos = masterClockStartedAtNanos
        stringTrace("TRACE_SESSION section=" + section.name + " ppq=" + ppq + " loopLimit=" + loopLimit + " seamless=" + seamless)
        com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 SECTION " + section.name + ": continuous master clock")
        loopCount = 0
        if (lastAppliedSection != section.name) {
            applyVoicesFromCasm(section)
            lastAppliedSection = section.name
        }
        val noteCount = section.parts.sumOf { part -> part.events.count { isNoteEvent(it) } }
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "▶ PLAY " + section.name + ": parts=" + section.parts.size + ", events=" + section.parts.sumOf { it.events.size } + ", noteEvents=" + noteCount + ", loopLimit=" + loopLimit + ", seamless=" + seamless
        )

        playbackJob = scope.launch(Dispatchers.Default) {
            var active = PendingSection(section, ppq, loopLimit, onComplete)
            active.onStart?.invoke()
            var remainingLoops = loopLimit
            while (true) {
                val result = playOnce(active.section, active.ppq, masterTimelineTick, 0L)
                masterTimelineTick = result.endTick
                barClockStartedAtNanos = masterClockStartedAtNanos

                if (result.interruptedByTransition) {
                    val transition = pendingTransition
                    pendingTransition = null
                    if (transition != null) {
                        // Release only notes owned by the outgoing section. This
                        // is deliberately NOT allNotesOff(): keyboard voices and
                        // unrelated channels remain untouched.
                        val outgoing = activeNoteSnapshot()
                        outgoing.forEach { releaseActive(it) }
                        if (outgoing.isNotEmpty()) {
                            com.yourapp.yamahaarranger.ui.DebugLog.add(
                                "🎼 TRANSITION: released " + outgoing.size + " outgoing style notes (no global allNotesOff)"
                            )
                        }

                        active = transition.sections.first()
                        active.onStart?.invoke()
                        remainingLoops = active.loopLimit
                        // The transition start is an absolute tick. Every section
                        // in the sequence is placed immediately after the previous
                        // one; no clock reset and no global note-off.
                        val rest = transition.sections.drop(1)
                        pendingSectionQueue.clear()
                        rest.forEach { pendingSectionQueue.addLast(it) }
                        loopCount = 0
                        if (lastAppliedSection != active.section.name) {
                            applyVoicesFromCasm(active.section)
                            lastAppliedSection = active.section.name
                        }
                        com.yourapp.yamahaarranger.ui.DebugLog.add(
                            "🎼 MASTER CLOCK → " + active.section.name + " at tick=" + masterTimelineTick
                        )
                        continue
                    }
                }

                if (remainingLoops > 0) remainingLoops--

                if (remainingLoops == 0) active.onComplete?.invoke()

                val queued = if (pendingSectionQueue.isEmpty()) null else pendingSectionQueue.removeFirst()
                if (queued != null) {
                    active = queued
                    active.onStart?.invoke()
                    remainingLoops = queued.loopLimit
                    loopCount = 0
                    if (lastAppliedSection != active.section.name) {
                        applyVoicesFromCasm(active.section)
                        lastAppliedSection = active.section.name
                    }
                    com.yourapp.yamahaarranger.ui.DebugLog.add(
                        "🎼 MASTER CLOCK → " + active.section.name + " at tick=" + masterTimelineTick
                    )
                    continue
                }

                if (remainingLoops == 0) break
            }
            playbackJob = null
        }
    }

    fun play(section:StyleSectionModel,ppq:Int,loopLimit:Int=-1,onComplete:(()->Unit)?=null){
        stop()
        clearStringTrace()
        startPlayback(section, ppq, loopLimit, onComplete, seamless = false)
    }

    fun stop(){
        pendingSectionQueue.clear()
        pendingTransition = null
        r23LastJob=playbackJob ?: r23LastJob
        playbackJob?.cancel()
        playbackJob=null
        masterClockStartedAtNanos=0L
        masterTimelineTick=0L
        barClockStartedAtNanos=0L
        if(stringTraceEnabled && synchronized(stringTraceLock){stringTrace.isNotEmpty()}) dumpStringTrace()
        lifecycle {
            audioEngine.allNotesOff()
            midiInputManager.allNotesOff()
            activeTransposedNotes.clear()
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add("⏹ STOP")
    }

    fun queueNextSection(section:StyleSectionModel,ppq:Int)=playSeamless(section,ppq)

    // Capture a musical target at request time, not a wall-time delay. The
    // existing <=25ms admission window is preserved for the Brain caller.
    fun nextBarTargetTick(ppq: Int, numerator: Int = 4, denominator: Int = 4): Long? {
        if (masterClockStartedAtNanos == 0L || ppq <= 0) return null
        val beatTicks = ppq.toLong() * 4L / denominator.coerceAtLeast(1)
        val barTicks = beatTicks * numerator.coerceAtLeast(1)
        if (barTicks <= 0L) return null
        val tick = currentMasterTick(ppq)
        val barTick = tick / barTicks * barTicks
        val sinceBarNanos = System.nanoTime() - tickDeadlineNanos(barTick, ppq)
        val nextBarTick = if (sinceBarNanos in 0L..999_999L) barTick else barTick + barTicks
        val wait = (tickDeadlineNanos(nextBarTick, ppq) - System.nanoTime()) / 1_000_000L
        return if (wait <= 25L) null else nextBarTick
    }

    fun millisToNextBar(ppq: Int, numerator: Int = 4, denominator: Int = 4): Long {
        val tick = nextBarTargetTick(ppq, numerator, denominator) ?: return 0L
        return ((tickDeadlineNanos(tick, ppq) - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
    }

    fun clockTempoRevision(): Long = synchronized(musicalClockLock) { tempoRevision }

    suspend fun awaitMusicalTick(tick: Long, ppq: Int, requestTempoRevision: Long) {
        val clock = synchronized(musicalClockLock) { ensureTempoClock() }
        while (true) {
            val revision = musicalClockRevision.value
            val remaining = tickDeadlineNanos(tick, ppq, clock) - System.nanoTime()
            if (remaining <= 0L) return
            // Preserve baseline sub-ms command admission for unchanged tempo.
            // After a real tempo change, reach the recalculated musical target
            // before posting the command. Same-BPM writes are only wake-ups.
            val waitMs = if (clockTempoRevision() == requestTempoRevision)
                remaining / 1_000_000L else (remaining - 1L) / 1_000_000L + 1L
            if (waitMs <= 0L) return
            waitForClockChange(revision, waitMs)
        }
    }

    private fun handleNoChord(){
        val captureId=chordTrace.begin("previous", "NONE", activeNoteCount())
        if(captureId!=0L) { f12Timing.chord(captureId); val markAt=f12Timing.begin(); audioEngine.markChordDiagnostic(captureId); f12Timing.finish(5,markAt,marker=true) }
        stringTrace("NO_CHORD active="+activeNoteCount())
        val snapshot=activeNoteSnapshot()
        snapshot.forEach{releaseActive(it)}
        if(snapshot.isNotEmpty())com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 NO CHORD: released ${snapshot.size} held style notes")
    }

    private fun handleChordChange(newChord:DetectedChord,oldChord:DetectedChord?){
        val captureId=chordTrace.begin("${oldChord?.rootNote}/${oldChord?.quality}", "${newChord.rootNote}/${newChord.quality}",activeNoteCount())
        if(captureId!=0L) { f12Timing.chord(captureId); val markAt=f12Timing.begin(); audioEngine.markChordDiagnostic(captureId); f12Timing.finish(5,markAt,marker=true) }
        stringTrace("CHORD_CHANGE new="+newChord.rootNote+"/"+newChord.quality+" active="+activeNoteCount())
        if(activeNoteCount()==0){
            stringTrace("CHORD_CHANGE no-active-notes")
            return
        }
        val snapshot=activeNoteSnapshot()
        snapshot.forEach { retargetActive(it,newChord) }
    }

    private fun retargetActive(active:ActiveTransposedNote,newChord:DetectedChord):Unit=lifecycle {
        val eventId=if(chordTrace.active()) StyleAudioPathDiagnostic.nextId() else 0L
        val sourceKey="${active.sourceChannel}:${active.sourceNote}"
        if(activeTransposedNotes[sourceKey] !== active) {
            chordEvent(active,eventId,"STALE_OWNER_SKIP",-1)
            return@lifecycle
        }
        chordEvent(active,eventId,"ACTIVE",active.outputNote)
        stringTrace("ACTIVE src"+active.sourceChannel+":"+active.sourceNote+" dst="+active.destinationChannel+" note="+active.outputNote+" NTR="+(active.policy.ntr and 0x7f)+" NTT="+(active.policy.ntt and 0x7f)+" RTR="+(active.policy.rtr and 0x7f))
        val selectedPolicy=selectPolicy(active.policies,active.sourceNote,newChord)
        if(selectedPolicy==null){
            stringTrace("POLICY_MISS src"+active.sourceChannel+":"+active.sourceNote+" chord="+newChord.rootNote+"/"+newChord.quality+" -> RELEASE")
            chordEvent(active,eventId,"POLICY_MISS_RELEASE",-1)
            releaseActive(active,eventId)
            com.yourapp.yamahaarranger.ui.DebugLog.add(
                "🔇 CASM CHORD MUTE src${active.sourceChannel}:${active.sourceNote} chord=${newChord.rootNote}/${newChord.quality}"
            )
            return@lifecycle
        }
        chordTrace.record { "RETARGET_SELECT id=$eventId chordId=${chordTrace.chordId()} src=${active.sourceChannel} original=${active.sourceNote} oldDst=${active.destinationChannel} newDst=${selectedPolicy.destinationChannel} oldVoice='${active.policy.voiceName}' voice='${selectedPolicy.voiceName}' NTR=${selectedPolicy.ntr and 0x7f} NTT=${selectedPolicy.ntt and 0x7f} RTR=${selectedPolicy.rtr and 0x7f} locked=${selectedPolicy.destinationChannel in lockedChannels} muted=${channelOverrides[selectedPolicy.destinationChannel]?.muted} reserved=${selectedPolicy.destinationChannel in 0..3} decision=existing_policy_selected" }
        if(selectedPolicy.destinationChannel!=active.destinationChannel){
            chordOff(active,eventId,1,"DESTINATION_CHANGE_OFF")
            midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
            active.destinationChannel=selectedPolicy.destinationChannel
        }
        active.policy=selectedPolicy
        if(selectedPolicy.destinationChannel==13 || active.destinationChannel==13){
            stringTrace("RETARGET src"+active.sourceChannel+":"+active.sourceNote+" chord="+newChord.rootNote+"/"+newChord.quality+" dst="+selectedPolicy.destinationChannel+" NTR="+(selectedPolicy.ntr and 0x7f)+" NTT="+(selectedPolicy.ntt and 0x7f)+" RTR="+(selectedPolicy.rtr and 0x7f)+" range="+selectedPolicy.sourceNoteLow+"-"+selectedPolicy.sourceNoteHigh+" mask="+selectedPolicy.chordMuteMask)
        }
        com.yourapp.yamahaarranger.ui.DebugLog.add(
            "🎼 CASM RETARGET src${active.sourceChannel}:${active.sourceNote} chord=${newChord.rootNote}/${newChord.quality} → dst${selectedPolicy.destinationChannel} NTR=${selectedPolicy.ntr and 0x7f} NTT=${selectedPolicy.ntt and 0x7f} RTR=${selectedPolicy.rtr and 0x7f}"
        )
        val rtr=active.policy.rtr and 0x7f
        when(rtr){
            0->{chordEvent(active,eventId,"RTR_STOP",-1);releaseActive(active,eventId);com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR STOP src${active.sourceChannel}:${active.sourceNote}")}
            1->updateHeldPitch(active,newChord,rootOnly=false,retrigger=false,eventId=eventId)
            2->updateHeldPitch(active,newChord,rootOnly=true,retrigger=false,eventId=eventId)
            3->updateHeldPitch(active,newChord,rootOnly=false,retrigger=true,eventId=eventId)
            4->updateHeldPitch(active,newChord,rootOnly=true,retrigger=true,eventId=eventId)
            5->{chordEvent(active,eventId,"RTR_DEFERRED_NO_SEND",active.outputNote);com.yourapp.yamahaarranger.ui.DebugLog.add("ℹ RTR NOTE GENERATOR src${active.sourceChannel}:${active.sourceNote}: deferred")}
            else->updateHeldPitch(active,newChord,rootOnly=false,retrigger=true,eventId=eventId)
        }
    }

    private fun updateHeldPitch(active:ActiveTransposedNote,chord:DetectedChord,rootOnly:Boolean,retrigger:Boolean,eventId:Long){
        val target=if(rootOnly) rootPitchForHeld(active,chord) else CasmNoteTransformer.transform(active.sourceNote,chord,active.policy)
        chordEvent(active,eventId,if(target==null) "TRANSFORM_NULL_NO_SEND" else if(target==active.outputNote) "UNCHANGED_NO_SEND" else "REPLACE",target?:-1)
        if(target==null||target==active.outputNote)return
        if(!retrigger)com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR PITCH SHIFT src${active.sourceChannel}:${active.sourceNote} ${active.outputNote}→$target (note replacement)")
        else com.yourapp.yamahaarranger.ui.DebugLog.add("🎹 RTR RETRIGGER src${active.sourceChannel}:${active.sourceNote} ${active.outputNote}→$target")
        chordOff(active,eventId,1,"REPLACEMENT_OFF")
        midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
        if(chordTrace.active()) audioEngine.noteOnStyleChannel(active.destinationChannel,target,active.velocity/127f,
            active.sourceChannel,active.sourceNote,active.sourceBank,active.sourceTick,eventId,false,chordTrace.chordId(),1)
        else audioEngine.noteOnChannel(active.destinationChannel,target,active.velocity/127f)
        midiInputManager.sendNoteOn(active.destinationChannel,target,active.velocity)
        active.outputNote=target
    }

    private fun chordEvent(active:ActiveTransposedNote,id:Long,stage:String,target:Int) {
        chordTrace.record { "RETARGET id=$id chordId=${chordTrace.chordId()} onId=${active.onId} section='${active.sourceSection}' part='${active.sourcePart}' src=${active.sourceChannel} original=${active.sourceNote} dst=${active.destinationChannel} oldOutput=${active.outputNote} output=$target velocity=${active.velocity} sourceBank=${active.sourceBank} sourceHeaderPC=${active.sourceHeaderPC} originTick=${active.sourceTick} voice='${active.policy.voiceName}' NTR=${active.policy.ntr and 0x7f} NTT=${active.policy.ntt and 0x7f} RTR=${active.policy.rtr and 0x7f} stage=$stage method=${if(stage=="REPLACE") "OFF_ON" else "decision_or_off"} ${if(stage=="ACTIVE") "" else chordOwnerEvidence(active.destinationChannel,active.outputNote,target,active.onId)}" }
    }
    // Read-only snapshot of the existing source ledger, never a voice count or
    // a deduplication/gate. Concurrent mutation makes evidence unknown.
    private fun chordOwnerEvidence(dst:Int,old:Int,target:Int,selfId:Long):String {
        if(dst!=11) return ""
        val snapshot=try { activeNoteSnapshot() } catch (_: RuntimeException) {
            return "oldPeers=-1 targetPeers=-1 owners='CONCURRENT_UNKNOWN'"
        }
        val peers=snapshot.filter { it.destinationChannel==dst && it.onId!=selfId }
        val targetPeers=peers.filter { it.outputNote==target }
        return "oldPeers=${peers.count { it.outputNote==old }} targetPeers=${targetPeers.size} owners='${targetPeers.take(4).joinToString(",") { "${it.sourceChannel}:${it.sourceNote}:${it.onId}" }}'"
    }
    private fun chordOff(active:ActiveTransposedNote,id:Long,operation:Int,stage:String) {
        chordEvent(active,id,stage,active.outputNote)
        if(chordTrace.active()) audioEngine.noteOffStyleChannel(active.destinationChannel,active.outputNote,
            active.sourceChannel,active.sourceNote,active.sourceBank,active.sourceTick,id,chordTrace.chordId(),operation)
        else audioEngine.noteOffChannel(active.destinationChannel,active.outputNote)
    }

    private fun rootPitchForHeld(active:ActiveTransposedNote,chord:DetectedChord):Int{
        val oldOctave=active.outputNote/12
        return (oldOctave*12+chord.rootNote.coerceIn(0,11)).coerceIn(0,127)
    }

    private fun releaseActive(active:ActiveTransposedNote,eventId:Long=active.onId):Unit=lifecycle {
        val key="${active.sourceChannel}:${active.sourceNote}"
        if(activeTransposedNotes[key] !== active) {
            chordEvent(active,eventId,"STALE_OWNER_SKIP",-1)
            return@lifecycle
        }
        chordOff(active,eventId,1,"RELEASE_OFF")
        midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
        activeTransposedNotes.remove(key)
        Unit
    }

    private fun endScheduledNote(sourceChannel:Int,sourceNote:Int):Boolean=lifecycle {
        val active=activeTransposedNotes.remove("$sourceChannel:$sourceNote") ?: return@lifecycle false
        if(active.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEOFF src"+active.sourceChannel+":"+active.sourceNote+" dst13 note="+active.outputNote)
        chordOff(active,active.onId,0,"SCHEDULED_OFF")
        midiInputManager.sendNoteOff(active.destinationChannel,active.outputNote)
        true
    }

    private fun applyVoicesFromCasm(section:StyleSectionModel) {
        val f12Requested=f12Timing.begin()
        f12Timing.section(section.name)
        val startedAtNanos = System.nanoTime()
        com.yourapp.yamahaarranger.ui.DebugLog.add("⏱ CASM VOICE APPLY START section=${section.name}")
        val explicitByDestination = linkedMapOf<Int, StylePartModel>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in 0..3 || destination in lockedChannels) return@forEach
            if (part.program in 0..127 && !explicitByDestination.containsKey(destination)) explicitByDestination[destination] = part
        }
        val applied = mutableSetOf<Int>()
        section.parts.forEach { part ->
            val policies = part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
            val c = policies.firstOrNull() ?: return@forEach
            val destination = c.destinationChannel
            if (destination in lockedChannels || destination in applied) return@forEach
            val drum = isDrumDestination(destination, c.voiceName)
            val override = channelOverrides[destination]
            val explicit = explicitByDestination[destination]
            val sourcePart = explicit ?: part
            val prog = override?.program ?: explicit?.program?.takeIf { it in 0..127 } ?: guessProgramFromVoiceName(c.voiceName)
            if (prog !in 0..127) return@forEach
            val state = mixerStates[destination]
            sourcePart.events.filter { it.tick == 0 && it.isControlChange }.forEach { e ->
                when (e.note) {
                    7 -> state.volume = e.velocity
                    10 -> state.pan = e.velocity
                    11 -> state.expression = e.velocity
                    91 -> state.reverbSend = e.velocity
                    93 -> state.chorusSend = e.velocity
                }
            }
            // R22_RAW_SETUP_BEGIN
            val r22SourceChannel=sourcePart.casmPolicies.ifEmpty { listOfNotNull(sourcePart.casm) }.filter { it.destinationChannel==destination }.map { it.sourceChannel }.distinct().singleOrNull()
            val r22Msb=sourcePart.events.lastOrNull { it.channel==r22SourceChannel && it.tick==0 && it.isControlChange && it.note==0 }?.velocity
            val r22Lsb=sourcePart.events.lastOrNull { it.channel==r22SourceChannel && it.tick==0 && it.isControlChange && it.note==32 }?.velocity
            val r22Source=if(override?.program!=null) "OVERRIDE_WITHOUT_VERIFIED_KIT_IDENTITY" else if(explicit!=null) "SINT_OR_TICK0_SETUP" else "NAME_GUESS"
            // R22_RAW_SETUP_END
            // R22_OBSERVE_MUTED_BEGIN
            if(drum && override?.muted==true && DrumKitShadowRuntime.active) DrumKitShadowRuntime.observe(section,destination,r22Msb,r22Lsb,prog,r22Source+"_MUTED_NO_PRESET_APPLIED",audioEngine.drumChannelSelection(destination))
            // R22_OBSERVE_MUTED_END
            if (override?.muted == true) { applied += destination; return@forEach }
            val styleBank = sourcePart.bankMsb.coerceIn(0,127) * 128 + sourcePart.bankLsb.coerceIn(0,127)
            val audioBank = if (drum) 128 else styleBank
            val midiMsb = if (drum) 127 else sourcePart.bankMsb.coerceIn(0,127)
            val midiLsb = if (drum) 0 else sourcePart.bankLsb.coerceIn(0,127)
            val stringVoice = c.voiceName.lowercase().let {
                it.contains("string") || it.contains("strg") || it.contains("strings")
            }
            val volume = if (override?.muted == true) 0 else styleTrim(state.volume, override?.volume ?: 127)
            val pan = override?.pan ?: state.pan
            val expression = effectiveExpression(destination, state.expression)
            val reverb = override?.reverbSend ?: state.reverbSend
            val chorus = override?.chorusSend ?: state.chorusSend
            val nativeState = AppliedChannelState(prog, audioBank, volume.coerceIn(0,127), pan.coerceIn(0,127),
                expression.coerceIn(0,127), reverb.coerceIn(0,127), chorus.coerceIn(0,127))
            // String parts are sensitive to stale native BASSMIDI channel state:
            // the CASM layer may legitimately keep the same logical voice while
            // another section/Program Change has touched the destination channel.
            // Re-apply the native preset for explicit string destinations so the
            // soundfont state is authoritative at every section activation.
            val forceNativePreset = stringVoice && destination in 13..14
            if(drum && audioEngine.drumABResolved) applyDrumAB(section,destination,r22Msb,r22Lsb,prog,r22Source)
            if (forceNativePreset || appliedChannelStates[destination] != nativeState) {
                chordTrace.record { "SECTION_PRESET section='${section.name}' part='${sourcePart.name}' dst=$destination requestBank=$audioBank requestPC=$prog voice='${c.voiceName}'" }
                if(!drum || !audioEngine.drumABResolved) audioEngine.setChannelProgram(destination, prog, audioBank, c.voiceName)
                audioEngine.setChannelMixer(destination, nativeState.volume, nativeState.pan, nativeState.expression,
                    nativeState.reverbSend, nativeState.chorusSend)
                appliedChannelStates[destination] = nativeState
                if (forceNativePreset) {
                    com.yourapp.yamahaarranger.ui.DebugLog.add(
                        "🎻 STRING AUDIO PRESET REAPPLY dst$destination pc=$prog bank=$audioBank"
                    )
                }
            } else {
                com.yourapp.yamahaarranger.ui.DebugLog.add("🎚 CASM AUDIO CACHE HIT dst$destination pc=$prog bank=$audioBank")
            }
            // R22_OBSERVE_SETUP_BEGIN
            if(drum && DrumKitShadowRuntime.active) DrumKitShadowRuntime.observe(section,destination,r22Msb,r22Lsb,prog,r22Source,audioEngine.drumChannelSelection(destination))
            // R22_OBSERVE_SETUP_END
            midiInputManager.sendProgramChange(destination, prog, midiMsb, midiLsb)
            applied += destination
            val source = if (override?.program != null) "STYLE OVERRIDE" else if (explicit != null) "actual MIDI setup" else "fallback name"
            com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 dst$destination: ${c.voiceName} → PC=$prog MIDIbank=$midiMsb:$midiLsb SFbank=$audioBank mix=$volume/$pan/$expression/$reverb/$chorus ($source)")
        }
        f12Timing.finish(1,f12Requested)
        val durationUs = (System.nanoTime() - startedAtNanos) / 1_000L
        com.yourapp.yamahaarranger.ui.DebugLog.add("⏱ CASM VOICE APPLY END section=${section.name} duration_us=$durationUs")
    }

    private fun applyStyleController(destinationChannel:Int, event:StyleNoteEvent) {
        if (destinationChannel !in 4..15) return
        if ((event.status and 0xF0) != 0xB0) return
        val state = mixerStates[destinationChannel]
        when (event.note) {
            7 -> state.volume = event.velocity.coerceIn(0,127)
            10 -> state.pan = event.velocity.coerceIn(0,127)
            11 -> state.expression = event.velocity.coerceIn(0,127)
            91 -> state.reverbSend = event.velocity.coerceIn(0,127)
            93 -> state.chorusSend = event.velocity.coerceIn(0,127)
            else -> return
        }
        val ov = channelOverrides[destinationChannel]
        // An expression event must not resend cached raw CC7 over the effective
        // CC7 trim/mute already installed; CC11 retains the style envelope.
        if (event.note == 11) {
            val effective = effectiveExpression(destinationChannel, state.expression)
            audioEngine.setChannelExpression(destinationChannel, effective)
            appliedChannelStates[destinationChannel]?.let { appliedChannelStates[destinationChannel] = it.copy(expression = effective) }
            return
        }
        applyEffectiveMixer(destinationChannel)
    }

    // A trim never raises the source CC value or destroys intentional zeroes.
    private fun styleTrim(source: Int, trim: Int): Int =
        (source.coerceIn(0,127) * trim.coerceIn(0,127) + 63) / 127

    private fun applyEffectiveMixer(channel: Int) {
        if (channel !in 4..15) return
        val state = mixerStates[channel]
        val ov = channelOverrides[channel]
        audioEngine.setChannelMixer(channel,
            if (ov?.muted == true) 0 else styleTrim(state.volume, ov?.volume ?: 127),
            ov?.pan ?: state.pan, effectiveExpression(channel, state.expression),
            ov?.reverbSend ?: state.reverbSend, ov?.chorusSend ?: state.chorusSend)
        // This cache describes installed state, not only the previous section's
        // tick-zero snapshot. Otherwise equal section headers skip a needed
        // restore after CC automation changed the live synth in between.
        appliedChannelStates[channel]?.let { appliedChannelStates[channel] = it.copy(
            volume = if (ov?.muted == true) 0 else styleTrim(state.volume, ov?.volume ?: 127),
            pan = ov?.pan ?: state.pan, expression = effectiveExpression(channel,state.expression),
            reverbSend = ov?.reverbSend ?: state.reverbSend, chorusSend = ov?.chorusSend ?: state.chorusSend) }
    }

    private fun guessProgramFromVoiceName(name:String):Int{val n=name.lowercase();val numeric=Regex("(?:^|\\D)(\\d{1,3})\\s*$").find(n)?.groupValues?.getOrNull(1)?.toIntOrNull();if(numeric!=null&&numeric in 0..127)return numeric;return when{n.contains("piano")->0;n.contains("e.piano")||n.contains("ep")->4;n.contains("organ")->16;n.contains("accordion")->21;n.contains("guitar")||n.contains("gtr")->24;n.contains("bass")->33;n.contains("violin")->40;n.contains("cello")->42;n.contains("strg")||n.contains("str")->48;n.contains("choir")->52;n.contains("trumpet")->56;n.contains("trombone")->57;n.contains("brass")->61;n.contains("sax")->65;n.contains("oboe")->68;n.contains("clarinet")->71;n.contains("flute")->73;isDrumVoice(n)->0;n.contains("pad")->89;else->-1}}
    // Match the native role contract: Rhythm1/2 are 8/9, destinations
    // 10..15 are melodic. Auxiliary legacy destinations may use voice hints.
    private fun isDrumDestination(destination:Int, voiceName:String):Boolean =
        destination == 8 || destination == 9 || (destination !in 10..15 && isDrumVoice(voiceName))
    private fun isDrumVoice(name:String)=name.lowercase().trim().let{
        it.contains("crash") || it.contains("cymbal") || it.contains("perc") ||
        it.contains("add-dr") || it.contains("drum") || it.contains("kit") ||
        it == "dr" || it.startsWith("dr.") || it.startsWith("dr_") ||
        it.startsWith("dr ") || it.startsWith("dr-")
    }
    private fun yamahaChordType(chord:DetectedChord):Int = when(chord.quality){
        ChordQuality.MAJOR -> 0
        ChordQuality.SIX -> 1
        ChordQuality.MAJ7 -> 2
        ChordQuality.ADD9 -> 4
        ChordQuality.SIX9 -> 6
        ChordQuality.AUG -> 7
        ChordQuality.MINOR -> 8
        ChordQuality.MIN6 -> 9
        ChordQuality.MIN7 -> 10
        ChordQuality.MIN7_FLAT5 -> 11
        ChordQuality.MIN7_11 -> 14
        ChordQuality.DIM -> 17
        ChordQuality.DIM7 -> 18
        ChordQuality.DOM7 -> 19
        ChordQuality.DOM7_SUS4 -> 20
        ChordQuality.DOM7_FLAT5 -> 21
        ChordQuality.SUS4 -> 32
        ChordQuality.SUS2 -> 33
        ChordQuality.POWER5 -> 31
    }

    private fun policyMatchesChord(policy:CasmPolicyModel,chord:DetectedChord):Boolean {
        return policyScore(policy,0,chord)!=null
    }

    /**
     * Resolve one CASM rule for the current source note/chord.
     *
     * Important semantics:
     * - A present Chord Mute/Play mask is a hard selector.
     * - SourceChordType/SourceChordRoot describe the recorded source pattern;
     *   they are metadata, not the currently played chord.
     * - Source-note range is a hard compatibility gate. An out-of-range rule
     *   must never win merely because its metadata happens to score higher.
     * - Among compatible rules, narrower source ranges are more specific.
     * - Source-channel compatibility is a later tie-breaker.
     */
    private fun policyScore(
        policy:CasmPolicyModel,
        eventNote:Int,
        chord:DetectedChord?,
        sourceChannel:Int?=null
    ):Int?{
        var score=0

        // 1) Chord mute/play mask is the strongest selector.
        if(chord!=null && policy.chordMuteMask>=0L){
            val type=yamahaChordType(chord)
            if(type !in 0..33) return null
            if(((policy.chordMuteMask ushr type) and 1L)==0L) return null
            score+=1_000_000
        }

        // 2) Source note range is a hard compatibility gate.
        val low=policy.sourceNoteLow.coerceIn(0,127)
        val high=policy.sourceNoteHigh.coerceIn(low,127)
        if(eventNote !in low..high) return null

        // Narrower matching ranges are more specific than broad ranges.
        val width=high-low
        score+=10_000 + (127-width)*100

        // 3) Prefer source-channel compatibility after range specificity.
        if(sourceChannel!=null && policy.sourceChannel==sourceChannel) score+=10

        // 4) Source-root/type are metadata specificity only. They must not
        // be compared to the currently played chord root/type.
        if(policy.sourceChordRoot in 0..11) score+=1
        if(policy.sourceChordType in 0..34) score+=1

        return score
    }

    private fun selectPolicy(
        policies:List<CasmPolicyModel>,
        eventNote:Int,
        chord:DetectedChord?,
        sourceChannel:Int?=null
    ):CasmPolicyModel?{
        if(policies.isEmpty())return null

        // Every candidate must pass policyScore, including the source-note
        // range gate. This prevents an out-of-range policy from becoming a
        // fallback solely because it has more metadata.
        return policies.mapNotNull{policy->
            policyScore(policy,eventNote,chord,sourceChannel)?.let{score->policy to score}
        }.maxWithOrNull(
            compareBy<Pair<CasmPolicyModel,Int>>{it.second}
                .thenByDescending{it.first.sourceNoteLow}
                .thenBy{it.first.sourceNoteHigh}
                .thenBy{it.first.sourceChannel}
                .thenBy{it.first.destinationChannel}
        )?.first
    }

    private fun selectPolicy(
        part:com.yourapp.yamahaarranger.style.StylePartModel,
        eventNote:Int,
        chord:DetectedChord?
    ):CasmPolicyModel?{
        val policies=part.casmPolicies.ifEmpty{listOfNotNull(part.casm)}
        val sourceChannel=part.events.firstOrNull()?.channel
        return selectPolicy(policies,eventNote,chord,sourceChannel)
    }
    private fun sourceChordTypeFor(chord:DetectedChord):Int=when(chord.quality){ChordQuality.MINOR,ChordQuality.MIN6,ChordQuality.MIN7->10;else->2}
    private fun isNoteEvent(event:StyleNoteEvent):Boolean{val hi=event.status and 0xF0;return hi==0x90||hi==0x80}

    private fun isRhythmSource(channel:Int):Boolean = channel == 8 || channel == 9

    private fun hasOnlyMelodicDeclarations(part:StylePartModel, sourceChannel:Int):Boolean {
        val declarations=part.casmPolicies.ifEmpty { listOfNotNull(part.casm) }
        return declarations.isNotEmpty() && declarations.all {
            it.sourceChannel == sourceChannel && it.destinationChannel in 10..15
        }
    }

    // LoveSong Main D contains a Yamaha MegaVoice articulation source named
    // "StrumFX" (src7). A normal GM/SF2 player cannot reproduce its Yamaha
    // articulation mapping; sending those high trigger notes as ordinary
    // guitar notes creates the audible "tinut-tinut" artifact. Keep the real
    // guitar source (src5) but suppress this articulation-only source.
    private fun isUnsupportedArticulation(policy:CasmPolicyModel?):Boolean =
        policy?.voiceName?.lowercase()?.contains("strumfx") == true

    private data class PlayOnceResult(
        val endTick: Long,
        val interruptedByTransition: Boolean
    )

    private suspend fun playOnce(section:StyleSectionModel,ppq:Int,startAbsoluteTick:Long,phaseStartTick:Long): PlayOnceResult {
        loopCount++
        if(section.lengthTicks<=0) return PlayOnceResult(startAbsoluteTick, false)

        data class Scheduled(
            val tick:Int,
            val event:StyleNoteEvent,
            val part:com.yourapp.yamahaarranger.style.StylePartModel
        )

        val sectionLength = section.lengthTicks.coerceAtLeast(1).toLong()
        val phase = phaseStartTick.mod(sectionLength)
        val merged=section.parts
            .flatMap{part->
                part.events.map{e->
                    val raw = e.tick.toLong().coerceAtLeast(0L)
                    val relative = (raw - phase + sectionLength) % sectionLength
                    Scheduled(relative.toInt(), e, part)
                }
            }
            .sortedWith(compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()})
        if(merged.isEmpty()) return PlayOnceResult(startAbsoluteTick + sectionLength, false)

        // Retain this iteration's time domain if STOP/START replaces the
        // current anchor. This is clock ownership, not stale-session rejection.
        val clock = synchronized(musicalClockLock) { ensureTempoClock() }
        var interruptedByTransition = false
        var lastProcessedTick = startAbsoluteTick

        // Yamaha styles can issue bank-select + program changes inside a
        // section (T547 does this on the A.Guitar part). Keep a bank state per
        // source part so a later Program Change does not silently fall back to
        // bank 0 and replace the intended SF2 preset.
        val dynamicBankBySource = mutableMapOf<Int, Int>()
        section.parts.forEach { part ->
            val msb = part.bankMsb.coerceIn(0, 127)
            val lsb = part.bankLsb.coerceIn(0, 127)
            val sourceChannel = part.casmPolicies.firstOrNull()?.sourceChannel
                ?: part.casm?.sourceChannel
                ?: part.events.firstOrNull()?.channel
            if (sourceChannel != null) dynamicBankBySource[sourceChannel] = msb * 128 + lsb
        }

        val audioPath = StyleAudioPathDiagnostic(section.name, partPresence)
        StyleAudioPathDiagnostic.inventory(section)
        for(s in merged){
            val absoluteTick=startAbsoluteTick+s.tick.toLong()
            val transition = pendingTransition
            if (transition != null && absoluteTick >= transition.startTick &&
                (!transition.nextBarBoundary || System.nanoTime() >=
                    tickDeadlineNanos(transition.startTick, ppq, clock))) {
                interruptedByTransition = true
                lastProcessedTick = transition.startTick
                break
            }
            lastProcessedTick = absoluteTick
            val boundary = waitForNextBarOrEvent(absoluteTick, ppq, clock)
            if (boundary != null) {
                interruptedByTransition = true
                lastProcessedTick = boundary
                break
            }
            // A transition may have arrived while the event was suspended.
            // Revalidate before dispatching an event of the outgoing section.
            val afterWaitTransition = pendingTransition
            if (afterWaitTransition != null && absoluteTick >= afterWaitTransition.startTick &&
                (!afterWaitTransition.nextBarBoundary || System.nanoTime() >=
                    tickDeadlineNanos(afterWaitTransition.startTick, ppq, clock))) {
                interruptedByTransition = true
                lastProcessedTick = afterWaitTransition.startTick
                break
            }
            val targetNanos = tickDeadlineNanos(absoluteTick, ppq, clock)

            if(f12Timing.begin()!=0L) { val now=System.nanoTime(); f12Timing.record(2,now,0,((now-targetNanos)/1000).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),tick=absoluteTick.toInt()) }
            val chord=currentChord
            val isNote = isNoteEvent(s.event)
            val policy = if (isNote) {
                selectPolicy(s.part, s.event.note, chord)
            } else {
                s.part.casmPolicies.firstOrNull() ?: s.part.casm
            }

            // Tick-0 setup is applied once when the section becomes active.
            if (!isNote && s.tick == 0) continue

            if (!isNote) {
                val destination = policy?.destinationChannel ?: s.event.channel
                if (destination !in 0..3 && destination !in lockedChannels) {
                    if (s.event.isControlChange) {
                        // CC0/32 are part of Yamaha's 14-bit bank selection.
                        // They are consumed here and applied together with the
                        // following Program Change instead of being discarded.
                        when (s.event.note) {
                            0 -> {
                                val oldBank = dynamicBankBySource[s.event.channel] ?: 0
                                dynamicBankBySource[s.event.channel] =
                                    s.event.velocity.coerceIn(0, 127) * 128 + (oldBank and 0x7f)
                            }
                            32 -> {
                                val oldBank = dynamicBankBySource[s.event.channel] ?: 0
                                dynamicBankBySource[s.event.channel] =
                                    ((oldBank ushr 7) and 0x7f) * 128 + s.event.velocity.coerceIn(0, 127)
                            }
                            else -> applyStyleController(destination, s.event)
                        }
                        if(s.event.note==0 || s.event.note==32) chordTrace.record { "SOURCE_BANK chordId=${chordTrace.chordId()} src=${s.event.channel} dst=$destination controller=${s.event.note} value=${s.event.velocity} bank=${dynamicBankBySource[s.event.channel]} tick=$absoluteTick nativeSend=0_until_PROGRAM" }
                    } else if (s.event.isProgramChange) {
                        val drum = isDrumDestination(destination, policy?.voiceName.orEmpty())
                        val styleBank = dynamicBankBySource[s.event.channel]
                            ?: ((s.part.bankMsb.coerceIn(0, 127) * 128) +
                                s.part.bankLsb.coerceIn(0, 127))
                        val bank = if (drum) 128 else styleBank
                        val msb = if (drum) 127 else (bank ushr 7).coerceIn(0, 127)
                        val lsb = if (drum) 0 else (bank and 0x7f)
                        val program = s.event.note.coerceIn(0, 127)
                        chordTrace.record { "DYNAMIC_PROGRAM chordId=${chordTrace.chordId()} section='${section.name}' part='${s.part.name}' src=${s.event.channel} dst=$destination requestBank=$bank requestPC=$program tick=$absoluteTick" }
                        if(drum && audioEngine.drumABResolved) applyDrumAB(section,destination,
                            (styleBank ushr 7).takeIf { s.part.events.any { e->e.channel==s.event.channel && e.tick<=s.tick && e.isControlChange && e.note==0 } },
                            (styleBank and 127).takeIf { s.part.events.any { e->e.channel==s.event.channel && e.tick<=s.tick && e.isControlChange && e.note==32 } },program,"DYNAMIC_PC")
                        else audioEngine.setChannelProgram(destination, program, bank)
                        // R22_OBSERVE_DYNAMIC_BEGIN
                        if(drum && DrumKitShadowRuntime.active) DrumKitShadowRuntime.observe(section,destination,
                            (styleBank ushr 7).takeIf { s.part.events.any { e->e.channel==s.event.channel && e.tick<=s.tick && e.isControlChange && e.note==0 } },
                            (styleBank and 127).takeIf { s.part.events.any { e->e.channel==s.event.channel && e.tick<=s.tick && e.isControlChange && e.note==32 } },program,"DYNAMIC_PC",audioEngine.drumChannelSelection(destination))
                        // R22_OBSERVE_DYNAMIC_END
                        midiInputManager.sendProgramChange(destination, program, msb, lsb)
                        com.yourapp.yamahaarranger.ui.DebugLog.add(
                            "DYNAMIC PC src" + s.event.channel + " -> dst" + destination +
                                " PC=" + program + " bank=" + msb + ":" + lsb + " SFbank=" + bank
                        )
                    }
                }
                continue
            }

            lifecycle {
                if(f12Timing.begin()!=0L) { val now=System.nanoTime(); f12Timing.record(6,now,0,((now-targetNanos)/1000).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),tick=absoluteTick.toInt()) }
                val diagChannel = policy?.destinationChannel ?: s.part.casmPolicies.ifEmpty { listOfNotNull(s.part.casm) }.map { it.destinationChannel }.distinct().singleOrNull() ?: s.event.channel
                val diagId = if (s.event.isNoteOn) audioPath.observe(diagChannel) else 0L
                val diagBank = dynamicBankBySource[s.event.channel] ?: (s.part.bankMsb * 128 + s.part.bankLsb)
                fun diagnostic(stage: String, output: Int = -1, detail: String = ""): Boolean {
                    chordTrace.record { "SCHEDULED id=$diagId chordId=${chordTrace.chordId()} section='${section.name}' part='${s.part.name}' partIndex=${section.parts.indexOf(s.part)} src=${s.event.channel} original=${s.event.note} output=$output dst=$diagChannel velocity=${s.event.velocity} sourceBank=$diagBank sourceHeaderPC=${s.part.program} tick=$absoluteTick lagUs=${(System.nanoTime()-targetNanos)/1000} voice='${policy?.voiceName}' stage=$stage ${if(stage=="FORWARD") chordOwnerEvidence(diagChannel,-1,output,diagId) else ""} $detail" }
                    return audioPath.event(
                    diagId, diagChannel, s.event.channel, s.event.note, output, s.event.velocity,
                    diagBank, absoluteTick, policy?.voiceName ?: "UNKNOWN", stage, detail)
                }
                if(policy==null&&chord!=null&&s.event.isNoteOn&&
                    (!isRhythmSource(s.event.channel)||hasOnlyMelodicDeclarations(s.part,s.event.channel))) {
                    val candidates=s.part.casmPolicies.ifEmpty { listOfNotNull(s.part.casm) }
                    if (candidates.isEmpty()) partPresence.missingPolicy(diagChannel)
                    val rules=candidates.map { "${it.sourceNoteLow}-${it.sourceNoteHigh}:mask=${it.chordMuteMask.toString(16)}" }
                    diagnostic("DROP_NO_POLICY_WITH_CHORD", detail="candidateDst=${candidates.map { it.destinationChannel }.distinct()} chord=${chord.rootNote}/${chord.quality} yamahaType=${yamahaChordType(chord)} rules=$rules evidence=policy_rejection_not_missing_part")
                    return@lifecycle
                }
                if(s.event.isNoteOn&&isUnsupportedArticulation(policy)){
                    diagnostic("DROP_UNSUPPORTED_ARTICULATION")
                    com.yourapp.yamahaarranger.ui.DebugLog.add("🔇 SUPPRESS " + (policy?.voiceName ?: "unknown") + " src" + s.event.channel + ":" + s.event.note + " (MegaVoice articulation unsupported by SF2)")
                    return@lifecycle
                }

                if(!s.event.isNoteOn){
                    if(!endScheduledNote(s.event.channel,s.event.note)) {
                        diagnostic("OFF_NO_ACTIVE_LEDGER", detail = "NOTE_OFF_FORWARDED=0 oneShotDrumMayBeNormal=1")
                    }
                    return@lifecycle
                }

                val sourceChannel=s.event.channel
                val destinationChannel=policy?.destinationChannel?:sourceChannel
                if(destinationChannel in 0..3){
                    diagnostic("DROP_RESERVED_KEYBOARD")
                    com.yourapp.yamahaarranger.ui.DebugLog.add("  · style event src"+sourceChannel+":"+s.event.note+": SKIP dst"+destinationChannel+" (reserved for keyboard voices)")
                    return@lifecycle
                }
                if(destinationChannel in lockedChannels) { diagnostic("DROP_LOCKED"); return@lifecycle }
                val channelOverride=channelOverrides[destinationChannel]
                if(channelOverride?.muted==true) { diagnostic("DROP_MUTED"); return@lifecycle }

                val key=sourceChannel.toString()+":"+s.event.note
                val previousActive=activeTransposedNotes[key]
                if(previousActive!=null){
                    if(previousActive.destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING REPLACE src"+previousActive.sourceChannel+":"+previousActive.sourceNote+" oldNote="+previousActive.outputNote)
                    chordOff(previousActive,previousActive.onId,0,"SCHEDULED_REPLACE_OFF")
                    midiInputManager.sendNoteOff(previousActive.destinationChannel,previousActive.outputNote)
                    activeTransposedNotes.remove(key)
                }

                partPresence.beforeTransform(destinationChannel)
                val isDrumPart=isDrumDestination(destinationChannel, policy?.voiceName.orEmpty())
                val transformed=if(policy!=null&&!isDrumPart){
                    chord?.let{CasmNoteTransformer.transform(s.event.note,it,policy)}?:s.event.note.coerceIn(0,127)
                }else s.event.note.coerceIn(0,127)
                if (transformed == null) { diagnostic("DROP_TRANSFORM_NULL"); return@lifecycle }
                val note=(transformed+(channelOverride?.transpose?:0)).coerceIn(0,127)
                val velocity=s.event.velocity.coerceIn(1,127)

                if(policy!=null&&!isDrumPart){
                    val policyList=s.part.casmPolicies.ifEmpty{listOfNotNull(s.part.casm)}
                    activeTransposedNotes[key]=ActiveTransposedNote(sourceChannel,s.event.note,destinationChannel,note,velocity,policy,policyList,s.part.name,section.name,diagBank,s.part.program,absoluteTick,diagId)
                    if(destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎻 STRING NOTEON src"+sourceChannel+":"+s.event.note+" dst13 note="+note+" NTR="+(policy.ntr and 0x7f)+" NTT="+(policy.ntt and 0x7f)+" RTR="+(policy.rtr and 0x7f))
                    if(destinationChannel==13)com.yourapp.yamahaarranger.ui.DebugLog.add("🎼 CASM SELECT src"+sourceChannel+":"+s.event.note+" chord="+chord?.rootNote+"/"+chord?.quality+" → dst"+destinationChannel+" NTR="+(policy.ntr and 0x7f)+" NTT="+(policy.ntt and 0x7f)+" SRC="+policy.sourceChordRoot+"/"+policy.sourceChordType+" range="+policy.sourceNoteLow+"-"+policy.sourceNoteHigh+" RTR="+(policy.rtr and 0x7f))
                }

                val sampled = diagnostic("FORWARD", note,
                    "transformAsMelody=${!isDrumPart} nativeRhythmChannel=${destinationChannel == 8 || destinationChannel == 9} transpose=${channelOverride?.transpose ?: 0}")
                audioEngine.noteOnStyleChannel(destinationChannel,note,velocity/127f,
                    sourceChannel,s.event.note,diagBank,absoluteTick,diagId,sampled,chordTrace.chordId())
                midiInputManager.sendNoteOn(destinationChannel,note,velocity)
            }
        }
        audioPath.finish()
        val naturalEnd = startAbsoluteTick + section.lengthTicks.coerceAtLeast(0).toLong()
        return if (interruptedByTransition) {
            PlayOnceResult(lastProcessedTick, true)
        } else {
            PlayOnceResult(naturalEnd, false)
        }
    }

    private fun ticksToMillis(ticks:Int,ppq:Int,bpm:Int):Long=if(ppq<=0||bpm<=0)0 else((ticks*(60000.0/bpm))/ppq).toLong().coerceAtLeast(0)
}

package com.yourapp.yamahaarranger.arranger

/** Explicit finite observer; never chooses CASM policies or changes MIDI. */
internal class ChordChangeDiagnostic(private val clock: () -> Long = System::nanoTime) {
    private val rows = ArrayList<String>()
    private var deadline = 0L
    private var armed = false
    private var dropped = 0
    private var lastChord = 0L
    private var windowUntil = 0L
    private val recentPiano = ArrayDeque<Pair<Long,String>>()
    @Synchronized fun arm() {
        rows.clear(); dropped = 0; lastChord = 0; windowUntil = 0L
        recentPiano.clear()
        deadline = clock() + 60_000_000_000L; armed = true
    }
    @Synchronized fun stop() { armed = false; recentPiano.clear() }
    @Synchronized fun active(): Boolean = armed && clock() < deadline
    @Synchronized fun chordId(): Long = if (active()) lastChord else 0L
    @Synchronized fun begin(old: String, new: String, activeNotes: Int): Long {
        if (!active()) return 0L
        lastChord = StyleAudioPathDiagnostic.nextId()
        val now=clock()
        windowUntil = now + 750_000_000L
        recentPiano.filter { now-it.first in 0..250_000_000L }.forEach { retain(it.second + " contextId=$lastChord") }
        recentPiano.clear()
        record { "CHORD_CHANGE chordId=$lastChord old=$old new=$new activeNotes=$activeNotes" }
        return lastChord
    }
    @Synchronized fun record(message: () -> String) {
        if (!active()) return
        val text = message()
        val stage=text.substringAfter("stage=", "").substringBefore(' ')
        val scheduledOff = stage=="SCHEDULED_OFF" || stage=="SCHEDULED_REPLACE_OFF"
        val retarget = text.startsWith("RETARGET_SELECT ") || (text.startsWith("RETARGET ") && !scheduledOff)
        val pianoContext = (text.startsWith("SCHEDULED ") || scheduledOff) && text.contains("dst=11 ")
        val context = clock() < windowUntil && pianoContext
        val setup = clock() < windowUntil && (text.startsWith("SOURCE_BANK ") || text.startsWith("DYNAMIC_PROGRAM ") || text.startsWith("SECTION_PRESET "))
        if (stage=="ACTIVE") return
        val now=clock()
        val row="wallMs=${System.currentTimeMillis()} monoNs=$now ${text}"
        if (!text.startsWith("CHORD_CHANGE ") && !retarget && !context && !setup) {
            if(pianoContext) {
                while(recentPiano.isNotEmpty() && now-recentPiano.first().first>250_000_000L) recentPiano.removeFirst()
                if(recentPiano.size==32) recentPiano.removeFirst()
                recentPiano.addLast(now to row)
            }
            return
        }
        retain(row + " contextId=$lastChord")
    }
    private fun retain(row:String) {
        if (rows.size >= 2048) { dropped++; return }
        rows.add("STYLE order=${rows.size + 1} $row")
    }
    @Synchronized fun compactReport(): String {
        fun rank(row:String):Int {
            if(row.contains("CHORD_CHANGE ")) return 0
            val piano=row.contains("dst=11 ") || row.contains("oldDst=11 ") || row.contains("newDst=11 ")
            val decision=row.contains("RETARGET ") && !row.contains("stage=SCHEDULED_") && !row.contains("stage=REPLACEMENT_OFF") && !row.contains("stage=DESTINATION_CHANGE_OFF") && !row.contains("stage=RELEASE_OFF")
            if(piano && decision) return 1
            if(piano && !row.contains("RETARGET_SELECT ") && (row.contains("SCHEDULED ") || row.contains("stage=SCHEDULED_"))) return 2
            if(decision) return 3
            return 4
        }
        val priority=rows.sortedBy(::rank)
        val fields = Regex("([A-Za-z][A-Za-z0-9]*)=(?:'([^']*)'|([^ ]+))")
        val compact = priority.map { row ->
            val values = fields.findAll(row).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            val kind = when { row.contains("CHORD_CHANGE ") -> "CHORD_CHANGE"; row.contains("RETARGET_SELECT ") -> "RETARGET_SELECT"; row.contains("RETARGET ") -> "RETARGET"; else -> "CONTEXT" }
            val keys = listOf("order","wallMs","id","chordId","contextId","onId","old","new","activeNotes","section","part","partIndex","src","original","dst","oldDst","newDst","oldOutput","output","velocity","originTick","tick","lagUs","sourceBank","sourceHeaderPC","voice","NTR","NTT","RTR","stage","method","oldPeers","targetPeers","owners","locked","muted","reserved","requestBank","requestPC","controller","value","bank")
            "$kind " + keys.mapNotNull { key -> values[key]?.let { "$key='${it.take(64).replace("\n", " ").replace("\r", " ")}'" } }.joinToString(" ")
        }
        val header = "=== CHORD STYLE COMPACT v2 ===\nrows=${rows.size} captureDropped=$dropped active=${active()} maxBytes=16384 windowBeforeMs=250 windowAfterMs=750\nPriority: CHORD_CHANGE, ch11 decisions, ch11 normal context, other decisions, auxiliary rows. Join native id/chordId; contextId attributes pre-window rows. onId/originTick=held source identity, tick=normal event, lagUs=send-decision lateness. oldPeers/targetPeers/owners=other source-ledger entries at that output pitch, not BASS voices. sourceHeaderPC is provenance; values capped at64 characters.\n"
        return ChordReportBounds.lines(header, compact, 16 * 1024)
    }
    @Synchronized fun report(): String = buildString {
        appendLine("=== CHORD STYLE CAPTURE ===")
        appendLine("rows=${rows.size} cap=2048 dropped=$dropped active=${active()} timedOut=${deadline != 0L && clock() >= deadline} durationLimitMs=60000 channelNumbers=zero_based")
        appendLine("Source headerPC/bank are provenance; native requestBank/PC and livePreset are authoritative at send. originTick/onId refer to the held note's original scheduled event.")
        rows.forEach { appendLine(it) }
    }
}

/** A hard UTF-8 byte bound, retaining whole records and reporting omissions. */
internal object ChordReportBounds {
    const val FILE_BYTES = 48 * 1024
    fun capture(header:String,style:String,native:String,limit:Int=FILE_BYTES):String {
        val all=(style+"\n"+native).lineSequence().toList()
        fun fields(line:String)=Regex("(\\w+)=(-?\\d+)").findAll(line).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val brackets=all.filter { it.startsWith("F12_STYLE_ROW") && fields(it)["kind"]==5L }
        val markers=all.filter { it.startsWith("F12_NATIVE_ROW") && fields(it)["kind"]==7L }
        val pairs=brackets.mapNotNull { b -> val k=fields(b);markers.firstOrNull { fields(it)["chordId"]==k["chordId"] }?.let { n ->
            val f=fields(n);val raw=(f.getValue("atUs")-k.getValue("atUs")) and 0xffffffffL
            val upper=if(raw>=0x80000000L)raw-0x100000000L else raw
            "F12_CLOCK_PAIR chordId=${k["chordId"]} offsetLowerUs=${upper-k.getValue("holdUs")-2} offsetUpperUs=${upper+2} uncertaintyUs=${k.getValue("holdUs")+4} convention=native_minus_STYLE modulo32=1 driftUnverified=1"
        } }
        val ended=all.any { it.startsWith("F12_STYLE ") && it.contains("active=false") } && all.any { it.startsWith("F12_NATIVE ") && it.contains("active=0") }
        val priorityLost=all.filter { it.startsWith("F12_STYLE ") || it.startsWith("F12_NATIVE ") }.any { (fields(it)["priorityDrop"]?:1L)>0 }
        val focused=all.any { it.startsWith("F12_STYLE_ROW pool=focus") } && all.any { it.startsWith("F12_NATIVE_ROW pool=focus") }
        val unique=brackets.map { fields(it)["chordId"] }.distinct().size==brackets.size && markers.map { fields(it)["chordId"] }.distinct().size==markers.size
        val ready=pairs.isNotEmpty() && pairs.size==brackets.size && pairs.size==markers.size && unique && ended && !priorityLost && focused
        val reason=if(ready)"matched_bracket_native_marker_focus_rows;coverage_not_complete_audio_cause_unknown" else "matchedPairs=${pairs.size},ended=$ended,priorityLost=$priorityLost,uniqueIds=$unique,focusBoth=$focused"
        val status="F12_CORRELATION ${if(ready) "CORRELATION_READY" else "CORRELATION_INCOMPLETE"} reason=$reason"
        val ordered=all.filter { it.startsWith("F12_") }.sortedBy { when {
            it.contains("pool=priority") -> 0
            !it.contains("_ROW") -> 1
            it.contains("pool=focus") -> 2
            else -> 3
        } }+all.filterNot { it.startsWith("F12_") }
        val result=boundedLines(header,listOf(status)+pairs+ordered,limit)
        return if(ready && ordered.filter { it.startsWith("F12_") }.any { !result.contains(it+"\n") })
            result.replace(status,"F12_CORRELATION CORRELATION_INCOMPLETE reason=timing_export_truncated") else result
    }
    fun lines(header:String,rows:List<String>,limit:Int=FILE_BYTES):String =
        if(rows.any { it.startsWith("F12_") })capture(header,rows.joinToString("\n"),"",limit) else boundedLines(header,rows,limit)
    private fun boundedLines(header: String, rows: List<String>, limit: Int): String {
        require(limit >= 256)
        val out = StringBuilder()
        var bytes = 0
        for (line in header.lineSequence()) {
            val text = line + "\n"
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size + 128 <= limit) { out.append(text); bytes += size }
            else { out.append("headerTruncated=1\n"); bytes += 18; break }
        }
        var omitted = 0
        for (row in rows) {
            val line = row + "\n"
            val size = line.toByteArray(Charsets.UTF_8).size
            if (bytes + size + 128 > limit) { omitted++; continue }
            out.append(line); bytes += size
        }
        out.append("exportOmittedRows=$omitted maxFileBytes=$limit; captureDropped is separate.\n")
        return out.toString()
    }
}

// F12_TIMING_BEGIN
/** Bounded observer. Integer microseconds wrap modulo 2^32; compare unsigned deltas. */
internal class F12Timing(private val clock: () -> Long = System::nanoTime) {
    private val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writers = java.util.concurrent.atomic.AtomicInteger(0)
    private val used = java.util.concurrent.atomic.AtomicInteger(0)
    private val dropped = java.util.concurrent.atomic.AtomicInteger(0)
    private val overwritten = java.util.concurrent.atomic.AtomicInteger(0)
    private val rows = java.util.concurrent.atomic.AtomicIntegerArray(64 * 8)
    private val priority = java.util.concurrent.atomic.AtomicIntegerArray(64 * 8)
    private val slow = java.util.concurrent.atomic.AtomicIntegerArray(32 * 8)
    private val priorityUsed = java.util.concurrent.atomic.AtomicInteger(0)
    private val priorityDrop = java.util.concurrent.atomic.AtomicInteger(0)
    private val slowUsed = java.util.concurrent.atomic.AtomicIntegerArray(8)
    private val slowDrop = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var focusAt = 0L
    private val count = java.util.concurrent.atomic.AtomicIntegerArray(8)
    private val maxWait = java.util.concurrent.atomic.AtomicIntegerArray(8)
    private val maxHold = java.util.concurrent.atomic.AtomicIntegerArray(8)
    @Volatile private var started = 0L
    @Volatile private var deadline = 0L
    @Volatile private var chord = 0
    @Volatile private var section = 0
    fun begin(): Long = if (enabled.get()) clock() else 0L
    fun stop() { enabled.set(false); while (writers.get() != 0) Thread.yield() }
    fun arm() { stop(); used.set(0); dropped.set(0); overwritten.set(0); chord=0; section=0; focusAt=0; priorityUsed.set(0); priorityDrop.set(0); slowDrop.set(0)
        for(i in 0 until priority.length())priority.set(i,0)
        for(i in 0 until slow.length())slow.set(i,0)
        for(i in 0..7)slowUsed.set(i,0)
        for (i in 0 until rows.length()) rows.set(i,0)
        for (i in 0..7) { count.set(i,0); maxWait.set(i,0); maxHold.set(i,0) }
        started=clock(); deadline=started+60_000_000_000L; enabled.set(true)
    }
    private fun maximum(array: java.util.concurrent.atomic.AtomicIntegerArray,k:Int,v:Int) {
        var old=array.get(k); while(old<v && !array.compareAndSet(k,old,v)) old=array.get(k)
    }
    fun record(kind:Int,at:Long,wait:Int,hold:Int,marker:Boolean=false,tick:Int=-1) {
        if(at==0L || !enabled.get()) return
        writers.incrementAndGet()
        try { if(!enabled.get() || at<started || at>deadline)return
            if(kind==7 && focusAt==0L)focusAt=at
            count.incrementAndGet(kind); maximum(maxWait,kind,wait); maximum(maxHold,kind,hold)
            if(marker || wait>=2000 || hold>=10000) {
                if(kind in 3..5 || kind==7) {
                    val n=priorityUsed.getAndIncrement()
                    if(n<64)writeRow(priority,n*8,n+1,kind,at,wait,hold,tick) else priorityDrop.incrementAndGet()
                    return
                }
                if(focusAt!=0L && at>=focusAt && at-focusAt<=1_000_000_000L) {
                    val n=slowUsed.getAndIncrement(kind)
                    if(n<4)writeRow(slow,(kind*4+n)*8,n+1,kind,at,wait,hold,tick) else slowDrop.incrementAndGet()
                }
                val n=used.getAndIncrement()
                val p=(n%64)*8;val old=rows.get(p)
                if(old==-1 || !rows.compareAndSet(p,old,-1)){dropped.incrementAndGet();return}
                if(n>=64)overwritten.incrementAndGet(); rows.set(p+1,kind);rows.set(p+2,(at/1000).toInt());rows.set(p+3,wait);rows.set(p+4,hold);rows.set(p+5,chord);rows.set(p+6,section);rows.set(p+7,tick);rows.set(p,n+1)
            }
        } finally { writers.decrementAndGet() }
    }
    fun finish(kind:Int,request:Long,acquired:Long=request,marker:Boolean=false,tick:Int=-1) {
        if(request!=0L)record(kind,request,((acquired-request)/1000).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),((clock()-acquired)/1000).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),marker)
    }
    fun chord(id:Long){chord=id.toInt();if(focusAt==0L)focusAt=begin();record(3,begin(),0,0,true)}
    fun section(name:String){section=name.hashCode();record(4,begin(),0,0,true)}
    private fun writeRow(a:java.util.concurrent.atomic.AtomicIntegerArray,p:Int,n:Int,k:Int,at:Long,w:Int,h:Int,tick:Int) {
        a.set(p+1,k);a.set(p+2,(at/1000).toInt());a.set(p+3,w);a.set(p+4,h);a.set(p+5,chord);a.set(p+6,section);a.set(p+7,tick);a.set(p,n)
    }
    fun report():String=buildString {
        appendLine("F12_STYLE clock=System.nanoTime_us_mod32 cap=64 dropped=${dropped.get()} overwritten=${overwritten.get()} active=${enabled.get()} priorityCap=64 priorityDrop=${priorityDrop.get()} slowCap=32 slowDrop=${slowDrop.get()} priorityOverwrite=0 slowOverwrite=0 focus=firstChord_plus1s rowPayloadBytes=5120")
        for(k in 0..7)appendLine("F12_STYLE_SUM kind=$k count=${count.get(k)} maxWaitUs=${maxWait.get(k)} maxHoldUs=${maxHold.get(k)}")
        fun emit(a:java.util.concurrent.atomic.AtomicIntegerArray,tag:String) {
            for(n in 0 until a.length()/8){val p=n*8;if(a.get(p)>0)appendLine("F12_STYLE_ROW pool=$tag order=${a.get(p)} kind=${a.get(p+1)} atUs=${a.get(p+2).toLong() and 0xffffffffL} waitUs=${a.get(p+3)} holdUs=${a.get(p+4)} chordId=${a.get(p+5)} sectionHash=${a.get(p+6)} tick=${a.get(p+7)}")}
        }
        emit(priority,"priority");emit(slow,"focus");emit(rows,"recent")
    }
}
// F12_TIMING_END

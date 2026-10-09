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
    fun lines(header: String, rows: List<String>, limit: Int = FILE_BYTES): String {
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
    private val count = java.util.concurrent.atomic.AtomicIntegerArray(8)
    private val maxWait = java.util.concurrent.atomic.AtomicIntegerArray(8)
    private val maxHold = java.util.concurrent.atomic.AtomicIntegerArray(8)
    @Volatile private var started = 0L
    @Volatile private var deadline = 0L
    @Volatile private var chord = 0
    @Volatile private var section = 0
    fun begin(): Long = if (enabled.get()) clock() else 0L
    fun stop() { enabled.set(false); while (writers.get() != 0) Thread.yield() }
    fun arm() { stop(); used.set(0); dropped.set(0); overwritten.set(0); chord=0; section=0
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
            count.incrementAndGet(kind); maximum(maxWait,kind,wait); maximum(maxHold,kind,hold)
            if(marker || wait>=2000 || hold>=10000) {
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
    fun chord(id:Long){chord=id.toInt();record(3,begin(),0,0,true)}
    fun section(name:String){section=name.hashCode();record(4,begin(),0,0,true)}
    fun report():String=buildString {
        appendLine("F12_STYLE clock=System.nanoTime_us_mod32 cap=64 dropped=${dropped.get()} overwritten=${overwritten.get()} active=${enabled.get()}")
        for(k in 0..7)appendLine("F12_STYLE_SUM kind=$k count=${count.get(k)} maxWaitUs=${maxWait.get(k)} maxHoldUs=${maxHold.get(k)}")
        for(n in 0..63){val p=n*8;if(rows.get(p)>0)appendLine("F12_STYLE_ROW order=${rows.get(p)} kind=${rows.get(p+1)} atUs=${rows.get(p+2).toLong() and 0xffffffffL} waitUs=${rows.get(p+3)} holdUs=${rows.get(p+4)} chordId=${rows.get(p+5)} sectionHash=${rows.get(p+6)} tick=${rows.get(p+7)}")}
    }
}
// F12_TIMING_END

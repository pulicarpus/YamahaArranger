package com.yourapp.yamahaarranger.arranger

/** Explicit finite observer; never chooses CASM policies or changes MIDI. */
internal class ChordChangeDiagnostic(private val clock: () -> Long = System::nanoTime) {
    private val rows = ArrayList<String>()
    private var deadline = 0L
    private var armed = false
    private var dropped = 0
    private var lastChord = 0L
    private var windowUntil = 0L
    @Synchronized fun arm() {
        rows.clear(); dropped = 0; lastChord = 0; windowUntil = 0L
        deadline = clock() + 60_000_000_000L; armed = true
    }
    @Synchronized fun stop() { armed = false }
    @Synchronized fun active(): Boolean = armed && clock() < deadline
    @Synchronized fun chordId(): Long = if (active()) lastChord else 0L
    @Synchronized fun begin(old: String, new: String, activeNotes: Int): Long {
        if (!active()) return 0L
        lastChord = StyleAudioPathDiagnostic.nextId()
        windowUntil = clock() + 750_000_000L
        record { "CHORD_CHANGE chordId=$lastChord old=$old new=$new activeNotes=$activeNotes" }
        return lastChord
    }
    @Synchronized fun record(message: () -> String) {
        if (!active()) return
        val text = message()
        val scheduledOff = text.endsWith("stage=SCHEDULED_OFF") || text.endsWith("stage=SCHEDULED_REPLACE_OFF")
        val retarget = text.startsWith("RETARGET_SELECT ") || (text.startsWith("RETARGET ") && !scheduledOff)
        val context = clock() < windowUntil && (text.startsWith("SCHEDULED ") || scheduledOff) && text.contains("dst=11 ")
        val setup = clock() < windowUntil && (text.startsWith("SOURCE_BANK ") || text.startsWith("DYNAMIC_PROGRAM ") || text.startsWith("SECTION_PRESET "))
        if (!text.startsWith("CHORD_CHANGE ") && !retarget && !context && !setup) return
        if (text.endsWith("stage=ACTIVE")) return
        if (rows.size >= 2048) { dropped++; return }
        rows.add("STYLE order=${rows.size + 1} wallMs=${System.currentTimeMillis()} monoNs=${clock()} ${text}")
    }
    @Synchronized fun compactReport(): String {
        val priority = rows.filter { it.contains("CHORD_CHANGE ") } +
            rows.filter { !it.contains("CHORD_CHANGE ") && (it.contains("dst=11 ") || it.contains("oldDst=11 ") || it.contains("newDst=11 ")) } +
            rows.filter { !it.contains("CHORD_CHANGE ") && !it.contains("dst=11 ") && !it.contains("oldDst=11 ") && !it.contains("newDst=11 ") }
        val fields = Regex("([A-Za-z][A-Za-z0-9]*)=(?:'([^']*)'|([^ ]+))")
        val compact = priority.map { row ->
            val values = fields.findAll(row).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
            val kind = when { row.contains("CHORD_CHANGE ") -> "CHORD_CHANGE"; row.contains("RETARGET_SELECT ") -> "RETARGET_SELECT"; row.contains("RETARGET ") -> "RETARGET"; else -> "CONTEXT" }
            val keys = listOf("order","wallMs","id","chordId","onId","old","new","activeNotes","section","part","src","original","dst","oldDst","newDst","oldOutput","output","velocity","sourceBank","sourceHeaderPC","voice","NTR","NTT","RTR","stage","locked","muted","reserved","requestBank","requestPC","controller","value","bank")
            "$kind " + keys.mapNotNull { key -> values[key]?.let { "$key='${it.take(64).replace("\n", " ").replace("\r", " ")}'" } }.joinToString(" ")
        }
        val header = "=== CHORD STYLE COMPACT ===\nrows=${rows.size} captureDropped=$dropped active=${active()} maxBytes=16384 windowAfterMs=750 ch11_priority\norder/wallMs preserve chronology; CHORD_CHANGE first, then ch11, then other parts. Join native id/chordId. sourceHeaderPC is provenance. Values capped at64 characters.\n"
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

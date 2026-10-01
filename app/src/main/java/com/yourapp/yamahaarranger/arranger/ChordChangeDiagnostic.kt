package com.yourapp.yamahaarranger.arranger

/** Explicit finite observer; never chooses CASM policies or changes MIDI. */
internal class ChordChangeDiagnostic(private val clock: () -> Long = System::nanoTime) {
    private val rows = ArrayList<String>()
    private var deadline = 0L
    private var armed = false
    private var dropped = 0
    private var lastChord = 0L
    @Synchronized fun arm() {
        rows.clear(); dropped = 0; lastChord = 0
        deadline = clock() + 60_000_000_000L; armed = true
    }
    @Synchronized fun stop() { armed = false }
    @Synchronized fun active(): Boolean = armed && clock() < deadline
    @Synchronized fun chordId(): Long = if (active()) lastChord else 0L
    @Synchronized fun begin(old: String, new: String, activeNotes: Int): Long {
        if (!active()) return 0L
        lastChord = StyleAudioPathDiagnostic.nextId()
        record { "CHORD_CHANGE chordId=$lastChord old=$old new=$new activeNotes=$activeNotes" }
        return lastChord
    }
    @Synchronized fun record(message: () -> String) {
        if (!active()) return
        if (rows.size >= 2048) { dropped++; return }
        rows.add("STYLE order=${rows.size + 1} wallMs=${System.currentTimeMillis()} monoNs=${clock()} ${message()}")
    }
    @Synchronized fun report(): String = buildString {
        appendLine("=== CHORD STYLE CAPTURE ===")
        appendLine("rows=${rows.size} cap=2048 dropped=$dropped active=${active()} timedOut=${deadline != 0L && clock() >= deadline} durationLimitMs=60000 channelNumbers=zero_based")
        appendLine("Source headerPC/bank are provenance; native requestBank/PC and livePreset are authoritative at send. originTick/onId refer to the held note's original scheduled event.")
        rows.forEach { appendLine(it) }
    }
}
